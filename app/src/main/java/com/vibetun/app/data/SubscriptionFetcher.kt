package com.vibetun.app.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Одноразовая загрузка подписки по URL (3x-ui /sub/<id> и совместимые).
 * 3x-ui отдаёт base64 списка share-ссылок — раскодируем, если получилось.
 *
 * [allowInsecure] отключает проверку TLS-сертификата: нужно для подписок на голом IP
 * с самоподписанным сертификатом (проверено: без этого fetch падает на верификации).
 * Опция осознанно опасная — включается галочкой с предупреждением в диалоге.
 */
object SubscriptionFetcher {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000

    fun fetch(url: String, allowInsecure: Boolean): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "v2rayNG/1.7.13")
        if (connection is HttpsURLConnection && allowInsecure) {
            connection.sslSocketFactory = trustingSslContext().socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        val raw = try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("Сервер подписки вернул HTTP $code.")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        if (raw.isEmpty()) throw IOException("Подписка вернула пустой ответ.")
        val text = raw.toString(Charsets.UTF_8)
        // 3x-ui: base64(строки ссылок). Если декодировалось в ссылки — отдаём распакованное.
        val decoded = runCatching {
            val cleaned = text.trim().replace('-', '+').replace('_', '/')
            val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
            String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8)
        }.getOrNull()
        return if (decoded != null && (decoded.contains("://") || decoded.contains("[Interface]"))) decoded else text
    }

    private fun trustingSslContext(): SSLContext {
        val trustAll = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }
    }
}
