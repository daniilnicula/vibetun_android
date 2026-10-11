# GoreBox Android

Android-клиент GoreBox с интерфейсом в палитре и визуальном стиле Windows-версии: [GoreSense/GoreBox · Windows](https://github.com/GoreSense/GoreBox/tree/Windows). Приложение использует тот же sing-box-код из дерева Windows-версии, собранный в Android `libbox.aar`, и Android `VpnService`/TUN для маршрутизации трафика.

## Реализация

- Kotlin + Jetpack Compose, локальное хранение профилей, импорт ссылок и JSON, экран маршрутов, светлая/тёмная тема.
- sing-box `1.9.7-neko-1` собирается через gomobile для Android; исходники ядра лежат в `core/sing-box/` и `core/sing-quic/`. AmneziaWG запускается отдельным встроенным userspace-движком `amneziawg-go` через тот же Android `VpnService`.
- `GoreBoxVpnService` реализует `PlatformInterface`: создаёт TUN через `VpnService.Builder`, защищает исходящие сокеты через `VpnService.protect()`, следит за сменой сети и передаёт lifecycle ядру. Для TUN используется gVisor-stack, чтобы не привязывать системные сокеты Android к виртуальному IPv6-адресу.
- Режим выбранных приложений использует `include_package` sing-box и `Builder.addAllowedApplication()`. Сам GoreBox исключается из списка VPN-приложений, чтобы служба могла устанавливать прокси-соединения.
- Quick Settings-плитка GoreBox: короткое нажатие переключает VPN; если ещё нет профиля или разрешения Android, открывает приложение для настройки. Удержание плитки открывает GoreBox.
- При первом запуске пользователь получает рекомендацию снять ограничения батареи. Приложение не меняет системные настройки без разрешения пользователя.
- Профили и учётные данные хранятся локально в приватном хранилище приложения.

## Поддержка профилей

Связка Android-конвертера и текущей версии ядра подключает:

- VLESS (TLS/Reality, WebSocket, gRPC, HTTP/HTTPUpgrade);
- VMess;
- Trojan;
- Shadowsocks;
- SOCKS4/5;
- HTTP/HTTPS;
- Hysteria и Hysteria 2;
- TUIC;
- WireGuard (ссылки и стандартные конфиги `.conf`);
- AmneziaWG 1.x/2.x/3.x `.conf` с параметрами обфускации Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5 и дополнительными настройками AmneziaWG 3.x;
- SSH (аутентификация по паролю);
- полный sing-box JSON (если содержит валидный `outbounds`; при необходимости GoreBox добавляет TUN inbound).

Импортёр может сохранить и другие форматы Windows-версии, но приложение **не запускает заведомо неподдерживаемые профили**: AnyTLS отсутствует в vendored sing-box `1.9.7-neko-1`, ShadowsocksR не реализован в ядре, а MTProto рассчитан на Telegram и не является общим TUN-протоколом. Для них при подключении показывается причина отказа.

## Собрать APK

Нужны JDK 17, Android SDK API 35 и Android NDK `26.2.11394342`. Сборка AWG-runtime требует Go 1.25.x, а gomobile-ядро sing-box закреплено на Go 1.23.x. Сначала соберите нативную библиотеку AmneziaWG с Go 1.25.x, затем переключите `PATH` на Go 1.23.x для libbox и соберите приложение:

```bash
./scripts/build-amneziawg-android.sh
./scripts/build-libbox-android.sh
./gradlew testDebugUnitTest assembleDebug
```

Debug APK появится в `app/build/outputs/apk/debug/app-debug.apk`. Скрипт устанавливает `gomobile`/`gobind` из Go-модуля; Android SDK и NDK должны быть установлены и лицензии приняты. Полученный `app/libs/libbox.aar` игнорируется Git — в репозитории находятся исходники и воспроизводимая сборка, а не локальный бинарный артефакт.

GitHub Actions workflow `.github/workflows/android.yml` собирает ядро, запускает unit-тесты и публикует APK как artifact `GoreBox-debug-apk`. Для этой рабочей ветки workflow также помещает сжатый установочный файл `deliverables/GoreBox-debug-apk.zip` (debug-подпись) в ветку; распакуйте архив, чтобы получить `GoreBox-debug.apk`.

## Важные ограничения Android

- Per-app proxy реализован посредством `VpnService`/TUN. В Android нет публичного системного API для назначения обычного HTTP/SOCKS proxy произвольным приложениям по отдельности.
- Для запуска нужно один раз подтвердить системный запрос VPN. Сеть, DNS, профиль, серверная доступность, ограничения прошивки и выбранные приложения влияют на фактическое подключение.
- Android/Go-сборка и unit-тесты проверяют интеграцию и преобразование профилей; перед выпуском на широкую аудиторию APK следует проверить на реальных устройствах и серверах для каждого протокола. Сборка сама по себе не заменяет такое end-to-end тестирование.
- Долговременная работа в фоне зависит от разрешений Android и политики производителя. На отдельных прошивках вручную включите автозапуск или режим батареи «Без ограничений».
