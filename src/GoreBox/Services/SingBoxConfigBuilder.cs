using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using GoreBox.Models;
using GoreBox.Utils;

namespace GoreBox.Services;

public sealed class ConfigBuildResult
{
    public string Json { get; set; } = "";
    public int ClashApiPort { get; set; }
    public List<string> Warnings { get; } = new();

    /// <summary>
    /// Проблемы, с которыми запускать прокси нельзя: соединение «поднимется», но работать не будет
    /// (например, AmneziaWG-профиль на ядре без обфускации).
    /// </summary>
    public List<string> Errors { get; } = new();
}

/// <summary>
/// Сборка конфигурации sing-box: локальный вход, TUN, outbound из профиля,
/// правила маршрутизации (домены/IP) и правила приложений.
/// Формат правил/эндпоинтов подбирается под версию установленного ядра.
/// </summary>
public static class SingBoxConfigBuilder
{
    private const string ProxyTag = "proxy";
    private const string DirectTag = "direct";
    private const string BlockTag = "block";
    private const string InboundTag = "proxy-in";
    private const string TunTag = "tun-in";

    public static ConfigBuildResult Build(
        ProxyProfile profile,
        RouteProfile route,
        RouteSet? routeSet,
        AppSettings settings,
        CoreInfo core,
        int clashApiPort,
        string logFile)
    {
        var result = new ConfigBuildResult { ClashApiPort = clashApiPort };
        _ = result;
        var modern = core.SupportsModernRules;
        var modernDns = core.VersionMajor > 1 || core.VersionMinor >= 12 || !core.Exists;

        _ = logFile; // логи ядра перехватываются процессом и дублируются в файл самим приложением

        // MTU туннеля WireGuard/AmneziaWG: нужен и для самого эндпоинта, и для входа TUN поверх него
        var tunnelNode = IsTunnelProfile(profile) ? profile.Node : null;
        var tunnelMtu = tunnelNode is null
            ? 0
            : EffectiveMtu(tunnelNode,
                tunnelNode.HasAwgParams ||
                profile.Protocol.Equals(ProtocolIds.AmneziaWg, StringComparison.OrdinalIgnoreCase));

        var root = new JsonObject
        {
            ["log"] = new JsonObject
            {
                ["level"] = "info",
                ["timestamp"] = true
            }
        };

        // ---------------------------------------------------------------- DNS
        var dns = BuildDns(route, profile, modernDns, out var resolverTag, out var profileDnsInTunnel);
        root["dns"] = dns;

        // ----------------------------------------------------------- inbounds
        var inbounds = new JsonArray();
        var mixedInbound = new JsonObject
        {
            ["type"] = route.Mode switch
            {
                ListenMode.Socks => "socks",
                ListenMode.Http => "http",
                _ => "mixed"
            },
            ["tag"] = InboundTag,
            ["listen"] = route.ListenAddress,
            ["listen_port"] = route.ListenPort
        };
        if (route.Mode == ListenMode.Socks) mixedInbound["version"] = "5";
        if (!modern) mixedInbound["sniff"] = route.SniffTraffic;
        inbounds.Add(mixedInbound);

        if (route.TunEnabled)
        {
            // Поверх туннеля WireGuard пакеты больше его MTU не проходят: ядро режет их на фрагменты,
            // и потеря одного фрагмента рвёт весь пакет (внешне — «подключено, но ничего не грузит»).
            var tunMtu = route.TunMtu;
            if (tunnelMtu > 0) tunMtu = Math.Min(tunMtu, tunnelMtu);

            var tun = new JsonObject
            {
                ["type"] = "tun",
                ["tag"] = TunTag,
                // Имя адаптера — уникальное на каждый запуск (идея из nekobox: CalculateInterfaceName
                // подбирает свободное имя). Стандартный подбор «tun0/tun1/…» обманывается гонкой:
                // при быстрой смене режимов старый wintun-адаптер ещё удаляется драйвером (секунды),
                // в net.Interfaces() его уже нет, ядро выбирает его же имя и падает с
                // «create adapter: Cannot create a file when that file already exists |
                // open existing adapter: Element not found». Случайный суффикс делает коллизию
                // невозможной: новое имя никогда не занято, удаление старого идёт параллельно.
                // Суффикс обновляет и CoreService перед каждой повторной попыткой запуска.
                ["interface_name"] = "GoreBox-" + Guid.NewGuid().ToString("N")[..8],
                ["address"] = new JsonArray("172.19.0.1/30", "fdfe:dcba:9876::1/126"),
                ["mtu"] = tunMtu,
                ["auto_route"] = true,
                ["strict_route"] = route.TunStrictRoute,
                ["stack"] = route.TunStack
            };
            if (!modern) tun["sniff"] = route.SniffTraffic;
            inbounds.Add(tun);
        }
        root["inbounds"] = inbounds;

        // ----------------------------------------------------------- outbounds
        var outbounds = new JsonArray();
        var endpoints = new JsonArray();

        if (profile.RawJson is { Length: > 0 })
        {
            var raw = ExtractOutboundFromRawConfig(profile.RawJson, out var warn);
            if (raw is null)
            {
                if (warn is not null) result.Warnings.Add(warn);
                throw new NotSupportedException(warn ?? "Не удалось получить outbound из JSON-профиля");
            }
            raw["tag"] = ProxyTag;
            outbounds.Add(raw);
        }
        else
        {
            switch (profile.Protocol.ToLowerInvariant())
            {
                case ProtocolIds.Wireguard:
                case ProtocolIds.AmneziaWg:
                    var (wg, wgWarn, wgError) = BuildWireGuard(profile.Node, profile.Protocol, modern, core);
                    if (wgWarn is not null) result.Warnings.Add(wgWarn);
                    if (wgError is not null) result.Errors.Add(wgError);
                    if (modern) endpoints.Add(wg);
                    else outbounds.Add(wg);
                    break;

                case ProtocolIds.MtProto:
                    throw new NotSupportedException(
                        "MTProto — прокси для Telegram. Ядро sing-box его не поддерживает: используйте «Открыть в Telegram», " +
                        "чтобы применить прокси в самом клиенте Telegram.");

                default:
                    outbounds.Add(BuildOutbound(profile));
                    break;
            }
        }

        outbounds.Add(new JsonObject { ["type"] = "direct", ["tag"] = DirectTag });
        if (!modern)
            outbounds.Add(new JsonObject { ["type"] = "block", ["tag"] = BlockTag });

        root["outbounds"] = outbounds;
        if (endpoints.Count > 0) root["endpoints"] = endpoints;

        // --------------------------------------------------------------- route
        var allowProcessPath = !core.Exists || core.VersionMinor >= 12;
        var tunnelCidrs = IsTunnelProfile(profile)
            ? TunnelOnlyCidrs(profile, profileDnsInTunnel)
            : new List<string>();

        root["route"] = BuildRoute(route, routeSet, modern, allowProcessPath,
            modernDns ? resolverTag : null, tunnelCidrs);

        // -------------------------------------------------------- experimental
        var experimental = new JsonObject
        {
            ["clash_api"] = new JsonObject
            {
                ["external_controller"] = $"127.0.0.1:{clashApiPort}"
            },
            ["cache_file"] = new JsonObject
            {
                ["enabled"] = true,
                ["path"] = AppPaths.CacheFile
            }
        };
        root["experimental"] = experimental;

        result.Json = root.ToJsonString(new JsonSerializerOptions
        {
            WriteIndented = true,
            TypeInfoResolver = new System.Text.Json.Serialization.Metadata.DefaultJsonTypeInfoResolver(),
            Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping
        });
        return result;
    }

    // ------------------------------------------------------------------- DNS
    private static JsonNode BuildDns(RouteProfile route, ProxyProfile profile, bool modernDns,
        out string resolverTag, out bool tunnelledProfileDns)
    {
        // DNS из конфига WireGuard/AmneziaWG (Dns = 10.8.0.1, ...) — это то, чем пользуется официальный
        // клиент Amnezia: запросы идут через туннель. Если режим «системный», для таких профилей
        // берём DNS профиля через туннель (иначе запросы уходят провайдеру: утечка + медленно).
        // Такие адреса существуют только внутри туннеля — см. TunnelOnlyCidrs().
        var profileDns = ProfileDnsServers(profile);
        tunnelledProfileDns = profileDns.Count > 0 && route.DnsMode is "system" or "profile";

        // Для профилей WireGuard/AmneziaWG «системный» DNS — это DNS провайдера мимо туннеля:
        // утечка, а в сетях с DPI ещё и неработающая резолвиция. Если своего DNS в .conf нет,
        // берём защищённый DNS через туннель.
        var tunnelDnsFallback = IsTunnelProfile(profile) && !tunnelledProfileDns &&
                                route.DnsMode is "system" or "profile";

        var useProxyDns = route.DnsMode == "proxy" || tunnelledProfileDns || tunnelDnsFallback;
        var custom = !tunnelledProfileDns && route.DnsMode == "custom" && !string.IsNullOrWhiteSpace(route.DnsServer);
        var remoteServer = custom ? route.DnsServer! : "1.1.1.1";
        var tunnelDnsServer = tunnelledProfileDns ? profileDns[0] : "1.1.1.1";

        if (modernDns)
        {
            var servers = new JsonArray();

            if (useProxyDns)
            {
                // DNS профиля — обычный UDP:53 внутри туннеля (как в клиенте Amnezia),
                // иначе защищённый DNS по TLS
                servers.Add(tunnelledProfileDns
                    ? new JsonObject
                    {
                        ["type"] = "udp",
                        ["tag"] = "dns-remote",
                        ["server"] = tunnelDnsServer,
                        ["detour"] = ProxyTag
                    }
                    : new JsonObject
                    {
                        ["type"] = "tls",
                        ["tag"] = "dns-remote",
                        ["server"] = "1.1.1.1",
                        ["detour"] = ProxyTag
                    });
                resolverTag = "dns-remote";
            }
            else if (custom)
            {
                var type = remoteServer.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ? "https"
                    : remoteServer.StartsWith("tls://", StringComparison.OrdinalIgnoreCase) ? "tls"
                    : remoteServer.StartsWith("quic://", StringComparison.OrdinalIgnoreCase) ? "quic"
                    : remoteServer.StartsWith("h3://", StringComparison.OrdinalIgnoreCase) ? "h3"
                    : "udp";

                var server = remoteServer;
                foreach (var prefix in new[] { "https://", "tls://", "quic://", "h3://", "udp://" })
                    if (server.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) { server = server[prefix.Length..]; break; }

                // detour не задаём: в новом формате DNS-серверов (sing-box >= 1.12) сервер без detour
                // сам диалится напрямую. Явный detour на «пустой» direct-outbound ядро считает
                // ошибкой конфигурации («detour to an empty direct outbound makes no sense»).
                var node = new JsonObject
                {
                    ["type"] = type,
                    ["tag"] = "dns-custom",
                    ["server"] = server
                };
                if (type == "https") node["path"] = "/dns-query";
                servers.Add(node);
                resolverTag = "dns-custom";
            }
            else if (route.TunEnabled)
            {
                // под TUN системный резолвер уводит запросы в туннель — используем внешний напрямую.
                // detour не задаём: в новом формате DNS-серверов (sing-box >= 1.12) сервер без detour
                // диалится напрямую сам (route.auto_detect_interface выводит его на физический
                // интерфейс в обход TUN). Явный detour на «пустой» direct-outbound — fatальная
                // ошибка ядра («detour to an empty direct outbound makes no sense»), из-за которой
                // ядро падало при старте TUN.
                servers.Add(new JsonObject
                {
                    ["type"] = "udp",
                    ["tag"] = "dns-direct",
                    ["server"] = "1.1.1.1"
                });
                resolverTag = "dns-direct";
            }
            else
            {
                resolverTag = "dns-local";
            }

            servers.Add(new JsonObject { ["type"] = "local", ["tag"] = "dns-local" });

            return new JsonObject
            {
                ["servers"] = servers,
                ["final"] = resolverTag,
                ["strategy"] = "prefer_ipv4"
            };
        }

        // legacy-формат DNS (sing-box <= 1.11)
        var legacyServers = new JsonArray();
        if (useProxyDns)
        {
            legacyServers.Add(new JsonObject
            {
                ["tag"] = "dns-remote",
                ["address"] = tunnelledProfileDns ? "udp://" + tunnelDnsServer : "tls://1.1.1.1",
                ["detour"] = ProxyTag
            });
            resolverTag = "dns-remote";
        }
        else if (custom)
        {
            legacyServers.Add(new JsonObject
            {
                ["tag"] = "dns-custom",
                ["address"] = remoteServer.Contains("://") ? remoteServer : "udp://" + remoteServer
                // detour не задаём: сервер без detour в любом формате DNS диалится напрямую.
                // Явный detour на «пустой» direct-outbound свежие ядра (sing-box >= 1.12 и форки
                // вроде sing-box-extended) отвергают: «detour to an empty direct outbound makes
                // no sense» — ядро падает на старте.
            });
            resolverTag = "dns-custom";
        }
        else if (route.TunEnabled)
        {
            legacyServers.Add(new JsonObject
            {
                ["tag"] = "dns-direct",
                ["address"] = "udp://1.1.1.1"
                // detour не задаём — см. комментарий у dns-custom выше
            });
            resolverTag = "dns-direct";
        }
        else
        {
            resolverTag = "dns-local";
        }

        legacyServers.Add(new JsonObject { ["tag"] = "dns-local", ["address"] = "local" });   // без detour: см. выше
        return new JsonObject
        {
            ["servers"] = legacyServers,
            ["final"] = resolverTag,
            ["strategy"] = "prefer_ipv4"
        };
    }

    // ---------------------------------------------------------------- outbound
    private static JsonObject BuildOutbound(ProxyProfile profile)
    {
        var n = profile.Node;
        var o = new JsonObject
        {
            ["tag"] = ProxyTag,
            ["server"] = n.Server,
            ["server_port"] = n.Port
        };

        switch (profile.Protocol.ToLowerInvariant())
        {
            case ProtocolIds.Vless:
                o["type"] = "vless";
                o["uuid"] = n.Uuid ?? "";
                if (!string.IsNullOrWhiteSpace(n.Flow)) o["flow"] = n.Flow;
                if (!string.IsNullOrWhiteSpace(n.PacketEncoding)) o["packet_encoding"] = n.PacketEncoding;
                AddTls(o, n);
                AddTransport(o, n);
                break;

            case ProtocolIds.Vmess:
                o["type"] = "vmess";
                o["uuid"] = n.Uuid ?? "";
                o["security"] = n.Extra.TryGetValue("security", out var scy) && scy.Length > 0 ? scy : "auto";
                o["alter_id"] = Text.ToInt(n.Extra.TryGetValue("alterId", out var aid) ? aid : "0");
                AddTls(o, n);
                AddTransport(o, n);
                break;

            case ProtocolIds.Trojan:
                o["type"] = "trojan";
                o["password"] = n.Password ?? "";
                AddTls(o, n, force: true);
                AddTransport(o, n);
                break;

            case ProtocolIds.Shadowsocks:
                o["type"] = "shadowsocks";
                o["method"] = n.Method ?? "aes-256-gcm";
                o["password"] = n.Password ?? "";
                break;

            case ProtocolIds.Socks:
                o["type"] = "socks";
                o["version"] = "5";
                if (!string.IsNullOrWhiteSpace(n.Username)) o["username"] = n.Username;
                if (!string.IsNullOrWhiteSpace(n.Password)) o["password"] = n.Password;
                break;

            case ProtocolIds.Http:
                o["type"] = "http";
                if (!string.IsNullOrWhiteSpace(n.Username)) o["username"] = n.Username;
                if (!string.IsNullOrWhiteSpace(n.Password)) o["password"] = n.Password;
                if (n.Tls || n.Port == 443) AddTls(o, n);
                break;

            case ProtocolIds.Hysteria2:
                o["type"] = "hysteria2";
                o["password"] = n.Password ?? "";
                if (n.UpMbps is > 0) o["up_mbps"] = n.UpMbps;
                if (n.DownMbps is > 0) o["down_mbps"] = n.DownMbps;
                if (!string.IsNullOrWhiteSpace(n.Obfs))
                {
                    o["obfs"] = new JsonObject
                    {
                        ["type"] = n.Obfs!.Contains("salamander") ? "salamander" : n.Obfs,
                        ["password"] = n.ObfsPassword ?? ""
                    };
                }
                if (n.Extra.TryGetValue("mport", out var mport) && mport.Length > 0)
                {
                    var ports = new JsonArray();
                    foreach (var p2 in mport.Split(',', StringSplitOptions.RemoveEmptyEntries)) ports.Add(p2);
                    o["server_ports"] = ports;
                }
                if (n.Extra.TryGetValue("hop_interval", out var hop) && hop.Length > 0)
                    o["hop_interval"] = hop.Contains('s') || hop.Contains('m') ? hop : hop + "s";
                AddTls(o, n, force: true);
                break;

            case ProtocolIds.Hysteria:
                o["type"] = "hysteria";
                o["auth_str"] = n.Password ?? "";
                o["up_mbps"] = n.UpMbps ?? 100;
                o["down_mbps"] = n.DownMbps ?? 100;
                if (!string.IsNullOrWhiteSpace(n.Obfs)) o["obfs"] = n.Obfs;
                AddTls(o, n, force: true);
                break;

            case ProtocolIds.Tuic:
                o["type"] = "tuic";
                o["uuid"] = n.Uuid ?? "";
                o["password"] = n.Password ?? "";
                if (!string.IsNullOrWhiteSpace(n.CongestionControl)) o["congestion_control"] = n.CongestionControl;
                if (!string.IsNullOrWhiteSpace(n.UdpRelayMode)) o["udp_relay_mode"] = n.UdpRelayMode;
                AddTls(o, n, force: true, defaultAlpn: "h3");
                break;

            case ProtocolIds.AnyTls:
                o["type"] = "anytls";
                o["password"] = n.Password ?? "";
                AddTls(o, n, force: true);
                break;

            case ProtocolIds.Ssh:
                o["type"] = "ssh";
                o["user"] = n.Username ?? "root";
                if (!string.IsNullOrWhiteSpace(n.Password)) o["password"] = n.Password;
                if (!string.IsNullOrWhiteSpace(n.SshPrivateKeyPath)) o["private_key_path"] = n.SshPrivateKeyPath;
                break;

            case ProtocolIds.ShadowsocksR:
                throw new NotSupportedException("SSR (ShadowsocksR) не поддерживается ядром sing-box. Используйте Shadowsocks или другой протокол.");

            case ProtocolIds.Tunnel:
                // Сырой JSON обрабатывается в Build() до этого switch'а; сюда попадаем только без него.
                throw new NotSupportedException(
                    "Tunnel требует JSON ядра: вставьте конфиг в поле «JSON ядра» внизу редактора профиля.");

            default:
                throw new NotSupportedException($"Протокол «{profile.Protocol}» не поддерживается ядром");
        }

        return o;
    }

    private static void AddTls(JsonObject o, ProxyNode n, bool force = false, string? defaultAlpn = null)
    {
        if (!n.Tls && !n.Reality && !force) return;

        var tls = new JsonObject { ["enabled"] = true };
        if (!string.IsNullOrWhiteSpace(n.Sni)) tls["server_name"] = n.Sni;
        if (n.Insecure) tls["insecure"] = true;

        var alpn = !string.IsNullOrWhiteSpace(n.Alpn)
            ? n.Alpn!.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            : defaultAlpn is null ? null : new[] { defaultAlpn };
        if (alpn is { Length: > 0 })
        {
            var arr = new JsonArray();
            foreach (var a in alpn) arr.Add(a);
            tls["alpn"] = arr;
        }

        if (n.Reality)
        {
            tls["utls"] = new JsonObject
            {
                ["enabled"] = true,
                ["fingerprint"] = string.IsNullOrWhiteSpace(n.Fingerprint) ? "chrome" : n.Fingerprint
            };
            var reality = new JsonObject { ["enabled"] = true };
            if (!string.IsNullOrWhiteSpace(n.PublicKey)) reality["public_key"] = ToUrlSafeBase64(n.PublicKey!);
            if (!string.IsNullOrWhiteSpace(n.ShortId)) reality["short_id"] = n.ShortId;
            tls["reality"] = reality;
        }
        else if (!string.IsNullOrWhiteSpace(n.Fingerprint))
        {
            tls["utls"] = new JsonObject { ["enabled"] = true, ["fingerprint"] = n.Fingerprint };
        }

        o["tls"] = tls;
    }

    /// <summary>
    /// REALITY-ключи ядро принимает в base64url без паддинга: приводим любой base64 к этому виду.
    /// </summary>
    private static string ToUrlSafeBase64(string value)
    {
        var v = value.Trim();
        var converted = v.Replace('+', '-').Replace('/', '_').TrimEnd('=');
        return converted;
    }

    private static void AddTransport(JsonObject o, ProxyNode n)
    {
        var network = (n.Network ?? "tcp").ToLowerInvariant();
        switch (network)
        {
            case "ws":
            case "websocket":
            {
                var tr = new JsonObject { ["type"] = "ws" };
                if (!string.IsNullOrWhiteSpace(n.Path)) tr["path"] = n.Path;
                if (!string.IsNullOrWhiteSpace(n.Host))
                    tr["headers"] = new JsonObject { ["Host"] = n.Host };
                o["transport"] = tr;
                break;
            }
            case "grpc":
            {
                var tr = new JsonObject { ["type"] = "grpc" };
                if (!string.IsNullOrWhiteSpace(n.ServiceName)) tr["service_name"] = n.ServiceName;
                o["transport"] = tr;
                break;
            }
            case "http":
            case "h2":
            case "httpupgrade":
            {
                var tr = new JsonObject { ["type"] = network == "httpupgrade" ? "httpupgrade" : "http" };
                if (!string.IsNullOrWhiteSpace(n.Path)) tr["path"] = n.Path;
                if (!string.IsNullOrWhiteSpace(n.Host)) tr["host"] = new JsonArray(n.Host!);
                o["transport"] = tr;
                break;
            }
            case "quic":
                o["transport"] = new JsonObject { ["type"] = "quic" };
                break;
            case "xhttp":
            case "splithttp":
            {
                var tr = new JsonObject { ["type"] = "xhttp" };
                if (!string.IsNullOrWhiteSpace(n.Path)) tr["path"] = n.Path;
                if (!string.IsNullOrWhiteSpace(n.Host)) tr["host"] = n.Host;
                o["transport"] = tr;
                break;
            }
        }
    }

    // -------------------------------------------------------------- wireguard
    private static (JsonObject node, string? warning, string? error) BuildWireGuard(
        ProxyNode n, string protocol, bool modern, CoreInfo core)
    {
        string? warning = null;
        string? error = null;
        var isAwg = protocol.Equals(ProtocolIds.AmneziaWg, StringComparison.OrdinalIgnoreCase) || n.HasAwgParams;

        // Ядро декодирует ключи строго как стандартный base64 (с «+», «/» и паддингом):
        // url-safe-ключ или ключ с переносами строк роняет эндпоинт ещё до попытки подключения.
        var privateKey = Text.NormalizeKey(n.WgPrivateKey);
        var peerPublicKey = Text.NormalizeKey(n.WgPeerPublicKey);
        var preSharedKey = Text.NormalizeKey(n.WgPresharedKey);

        if (string.IsNullOrWhiteSpace(privateKey))
            error = "Не задан приватный ключ клиента (PrivateKey) — туннель WireGuard не поднимется.";
        else if (string.IsNullOrWhiteSpace(peerPublicKey))
            error = "Не задан публичный ключ сервера (PublicKey) — туннель WireGuard не поднимется.";
        else if (string.IsNullOrWhiteSpace(n.Server))
            error = "Не задан адрес сервера (Endpoint).";

        // random_trailers/disable_cookies (AWG 3.1) умеют передавать две схемы: отдельный
        // эндпоинт type:"awg" у форка Amnezia (hoaxisr/amnezia-box, только Linux) и плоские поля
        // в эндпоинте wireguard у форка sing-box-lx (есть сборки под Windows). Без них сервер
        // AWG 3.1 отбрасывает ответы на хендшейк, и туннель поднимается без трафика.
        if (isAwg && modern && core.SupportsAwgEndpoint)
            return BuildAwgEndpoint(n, privateKey, peerPublicKey, preSharedKey, warning, error);

        var addresses = AddressArray(n);
        if (string.IsNullOrWhiteSpace(n.WgAddress))
            warning = AddWarning(warning,
                "В профиле нет адреса клиента (Address) — подставлен 10.0.0.2/32. Если сервер выдаёт другой адрес, трафик не пойдёт.");

        var allowedIps = AllowedIpsArray(n);

        var peer = new JsonObject
        {
            ["address"] = n.Server,
            ["port"] = n.Port > 0 ? n.Port : 51820,
            ["public_key"] = peerPublicKey,
            ["allowed_ips"] = allowedIps
        };
        if (!string.IsNullOrWhiteSpace(preSharedKey)) peer["pre_shared_key"] = preSharedKey;
        if (n.WgKeepalive is > 0) peer["persistent_keepalive_interval"] = n.WgKeepalive;
        if (!string.IsNullOrWhiteSpace(n.WgReserved))
        {
            var arr = new JsonArray();
            var meaningful = false;
            foreach (var part in n.WgReserved!.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            {
                if (!int.TryParse(part, out var b)) continue;
                arr.Add(b);
                if (b != 0) meaningful = true;
            }

            // Поле reserved есть у пира в официальном sing-box и в sing-box-lx, а вот
            // sing-box-extended и форк Amnezia его из своих WireGuardPeer/AwgPeerOptions убрали —
            // пишем только туда, где оно существует, чтобы не рисковать запуском.
            var reservedEmitted = arr.Count > 0 && (!core.IsExtended || core.SupportsAwgFlat);
            if (reservedEmitted) peer["reserved"] = arr;

            // Если поля в схеме ядра нет, значение молча отбросится. Предупреждаем,
            // иначе получится «подключено, но не работает».
            if (meaningful && !reservedEmitted)
            {
                warning = AddWarning(warning,
                    "Ядро не передаёт reserved-байты WireGuard (" + n.WgReserved!.Trim() +
                    ") — значение проигнорировано. Если сервер их требует, трафик не пойдёт.");
            }
        }

        var node = new JsonObject
        {
            ["type"] = "wireguard",
            ["tag"] = ProxyTag,
            ["address"] = addresses,
            ["private_key"] = privateKey,
            ["mtu"] = EffectiveMtu(n, isAwg),
            ["peers"] = new JsonArray(peer)
        };

        if (!isAwg) return (node, warning, error);

        if (!core.SupportsAmnezia)
        {
            // Подключаться бессмысленно: сервер с обфускацией обычный WireGuard-хендшейк не примет,
            // поэтому вместо «подключено, но ничего не грузит» честно останавливаем запуск.
            var reason = !modern
                ? "Ядро старше 1.11 не умеет WireGuard-эндпоинты и не поддерживает обфускацию AmneziaWG."
                : core.EndpointError is { Length: > 0 } endpointError
                    ? "Ядро не смогло собрать эндпоинт WireGuard: " + endpointError
                    : "Текущее ядро (официальный sing-box) не поддерживает обфускацию AmneziaWG: " +
                      "параметры Jc/Jmin/Jmax, S1–S4, H1–H4, I1–I5 будут отброшены, и сервер не примет хендшейк.";

            error = error is null
                ? reason + " Нужна расширенная сборка ядра — Настройки → Ядро → «Расширенная (AmneziaWG)»."
                : error + " " + reason;
            return (node, warning, error);
        }

        // Расширенная сборка принимает обфускацию вложенным блоком "amnezia":
        // https://github.com/shtorm-7/sing-box-extended → option/wireguard.go (WireGuardAmnezia)
        var amnezia = new JsonObject();
        if (n.AwgJc is not null) amnezia["jc"] = n.AwgJc;
        if (n.AwgJmin is not null) amnezia["jmin"] = n.AwgJmin;
        if (n.AwgJmax is not null) amnezia["jmax"] = n.AwgJmax;
        if (n.AwgS1 is not null) amnezia["s1"] = n.AwgS1;
        if (n.AwgS2 is not null) amnezia["s2"] = n.AwgS2;
        if (n.AwgS3 is not null) amnezia["s3"] = n.AwgS3;
        if (n.AwgS4 is not null) amnezia["s4"] = n.AwgS4;
        // H1..H4 и интервальные поля принимают как число, так и диапазон ("100-200")
        AddRange(amnezia, "h1", n.AwgH1); AddRange(amnezia, "h2", n.AwgH2);
        AddRange(amnezia, "h3", n.AwgH3); AddRange(amnezia, "h4", n.AwgH4);
        if (!string.IsNullOrWhiteSpace(n.AwgI1)) amnezia["i1"] = n.AwgI1!.Trim();
        if (!string.IsNullOrWhiteSpace(n.AwgI2)) amnezia["i2"] = n.AwgI2!.Trim();
        if (!string.IsNullOrWhiteSpace(n.AwgI3)) amnezia["i3"] = n.AwgI3!.Trim();
        if (!string.IsNullOrWhiteSpace(n.AwgI4)) amnezia["i4"] = n.AwgI4!.Trim();
        if (!string.IsNullOrWhiteSpace(n.AwgI5)) amnezia["i5"] = n.AwgI5!.Trim();

        var headerKey = Text.NormalizeKey(n.AwgHeaderProtectionKey);
        if (headerKey.Length > 0) amnezia["header_protection_key"] = headerKey;

        AddRange(amnezia, "content_padding_addition", n.AwgContentPadding);
        AddRange(amnezia, "rekey_after_time", n.AwgRekeyAfterTime);
        AddRange(amnezia, "rekey_timeout", n.AwgRekeyTimeout);
        AddRange(amnezia, "reject_after_time", n.AwgRejectAfterTime);
        AddRange(amnezia, "keepalive_timeout", n.AwgKeepaliveTimeout);
        AddRange(amnezia, "max_handshake_attempts", n.AwgMaxHandshakeAttempts);

        var missing = n.MissingAwgParams;
        if (missing.Count > 0)
        {
            warning = AddWarning(warning,
                "В профиле нет параметров AmneziaWG: " + string.Join(", ", missing) +
                ". Если сервер их использует, хендшейк не пройдёт — сверьтесь с .conf из панели.");
        }

        // AmneziaWG 3.1: random_trailers и disable_cookies. Библиотека wireguard-go внутри
        // расширенной сборки их понимает (ключи UAPI есть), а вот в конфиг sing-box они проброшены
        // не во всех версиях — поэтому спрашиваем у ядра (ProbeAwgTrailersAsync) и пишем только
        // туда, где поле действительно существует.
        //
        // Это не косметика. random_trailers симметричен: сторона со включённым флагом дописывает
        // к пакетам хендшейка хвост случайной длины, а сторона с выключенным считает такой пакет
        // «неизвестным типом» и отбрасывает (wireguard-go, device/receive.go:
        // «size == expected || randomTrailers && size > expected»). То есть если на сервере флаг
        // включён, а ядро клиента его передать не может, ответы на хендшейк молча теряются —
        // туннель «подключён», а трафика нет.
        if (n.AwgRandomTrailers || n.AwgDisableCookies)
        {
            if (core.SupportsAwgTrailers)
            {
                amnezia["random_trailers"] = n.AwgRandomTrailers;
                amnezia["disable_cookies"] = n.AwgDisableCookies;
            }
            else if (n.AwgRandomTrailers)
            {
                warning = AddWarning(warning,
                    "На сервере включены случайные трейлеры AmneziaWG 3.1 (RandomTrailers), а это ядро " +
                    "передать их не умеет: без них ответы на хендшейк отбрасываются как пакеты неизвестного " +
                    "типа, и трафик не пойдёт. Установите ядро с AWG 3.1 — Настройки → Ядро → «Расширенная " +
                    $"(AmneziaWG)» → «Скачать» (github.com/{CoreInstaller.AwgRepo}), — либо выключите " +
                    "RandomTrailers на сервере (в панели/3x-ui) и пересохраните профиль.");
            }
            else
            {
                warning = AddWarning(warning,
                    "DisableCookies (AmneziaWG 3.1) этим ядром не передаётся. На работу клиента это не " +
                    "влияет: флаг отключает ответы cookie на стороне сервера.");
            }
        }

        if (amnezia.Count == 0)
        {
            error = "Профиль помечен как AmneziaWG, но параметров обфускации в нём нет: " +
                    "подключиться к AWG-серверу без Jc/S1–S4/H1–H4 нельзя. Вставьте исходный .conf в редакторе профиля.";
            return (node, warning, error);
        }

        if (headerKey.Length > 0)
        {
            var paddingError = HeaderPaddingError(n);
            if (paddingError is not null) error = error is null ? paddingError : error + " " + paddingError;
        }

        // Куда положить параметры: sing-box-lx держит их плоско в самом эндпоинте (как awg-quick
        // и .conf), sing-box-extended — вложенным блоком "amnezia".
        if (core.SupportsAwgFlat)
        {
            foreach (var key in amnezia.Select(kv => kv.Key).ToList())
            {
                var value = amnezia[key];
                amnezia.Remove(key);      // JsonNode нельзя перенести, не отцепив от прежнего родителя
                node[key] = value;
            }
        }
        else
        {
            node["amnezia"] = amnezia;
        }

        // Расширенная сборка shtorm-7 (форк wireguard-go) умеет отключать «паузы» устройства:
        // без них первый пакет после простоя не ждёт возобновления обработки.
        // Поле есть только у неё — пишем лишь при вложенной схеме.
        if (core.SupportsAmnezia && !core.SupportsAwgEndpoint && !core.SupportsAwgFlat)
            node["disable_pauses"] = true;

        return (node, warning, error);
    }

    /// <summary>Добавить предупреждение к уже накопленным.</summary>
    private static string AddWarning(string? existing, string note) =>
        existing is null ? note : existing + " " + note;

    /// <summary>Адреса клиента из .conf; без них подставляем заглушку — ядро требует непустой список.</summary>
    private static JsonArray AddressArray(ProxyNode n)
    {
        var addresses = new JsonArray();
        foreach (var a in (n.WgAddress ?? "10.0.0.2/32")
                     .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            addresses.Add(a);
        if (addresses.Count == 0) addresses.Add("10.0.0.2/32");
        return addresses;
    }

    /// <summary>AllowedIPs берём из .conf, иначе — весь трафик.</summary>
    private static JsonArray AllowedIpsArray(ProxyNode n)
    {
        var allowedIps = new JsonArray();
        foreach (var item in (n.WgAllowedIps ?? "")
                     .Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            allowedIps.Add(item);

        if (allowedIps.Count == 0)
        {
            allowedIps.Add("0.0.0.0/0");
            allowedIps.Add("::/0");
        }

        return allowedIps;
    }

    /// <summary>
    /// Эндпоинт <c>type: "awg"</c> форка Amnezia (hoaxisr/amnezia-box): параметры обфускации лежат
    /// прямо в эндпоинте, без вложенного блока. Числовые интервалы (H1–H4, таймеры) у этого ядра
    /// объявлены строками — JSON-число оно не примет, поэтому всё отдаём текстом.
    /// </summary>
    private static (JsonObject node, string? warning, string? error) BuildAwgEndpoint(
        ProxyNode n, string privateKey, string peerPublicKey, string preSharedKey,
        string? warning, string? error)
    {
        if (string.IsNullOrWhiteSpace(n.WgAddress))
            warning = AddWarning(warning,
                "В профиле нет адреса клиента (Address) — подставлен 10.0.0.2/32. Если сервер выдаёт другой адрес, трафик не пойдёт.");

        var peer = new JsonObject
        {
            ["address"] = n.Server,
            ["port"] = n.Port > 0 ? n.Port : 51820,
            ["public_key"] = peerPublicKey,
            ["allowed_ips"] = AllowedIpsArray(n)
        };
        // у этого ядра поле называется preshared_key, а не pre_shared_key
        if (!string.IsNullOrWhiteSpace(preSharedKey)) peer["preshared_key"] = preSharedKey;
        if (n.WgKeepalive is > 0) peer["persistent_keepalive_interval"] = n.WgKeepalive;

        var node = new JsonObject
        {
            ["type"] = "awg",
            ["tag"] = ProxyTag,
            ["useIntegratedTun"] = false,
            ["address"] = AddressArray(n),
            ["private_key"] = privateKey,
            ["mtu"] = EffectiveMtu(n, true),
            ["peers"] = new JsonArray(peer)
        };

        if (n.AwgJc is > 0) node["jc"] = n.AwgJc.Value;
        if (n.AwgJmin is > 0) node["jmin"] = n.AwgJmin.Value;
        if (n.AwgJmax is > 0) node["jmax"] = n.AwgJmax.Value;
        if (n.AwgS1 is > 0) node["s1"] = n.AwgS1.Value;
        if (n.AwgS2 is > 0) node["s2"] = n.AwgS2.Value;
        if (n.AwgS3 is > 0) node["s3"] = n.AwgS3.Value;
        if (n.AwgS4 is > 0) node["s4"] = n.AwgS4.Value;

        AddText(node, "h1", n.AwgH1); AddText(node, "h2", n.AwgH2);
        AddText(node, "h3", n.AwgH3); AddText(node, "h4", n.AwgH4);
        AddText(node, "i1", n.AwgI1); AddText(node, "i2", n.AwgI2);
        AddText(node, "i3", n.AwgI3); AddText(node, "i4", n.AwgI4); AddText(node, "i5", n.AwgI5);

        var headerKey = Text.NormalizeKey(n.AwgHeaderProtectionKey);
        if (headerKey.Length > 0)
        {
            node["header_protection_key"] = headerKey;

            // ядро отказывается стартовать: при шифровании заголовка мусорные вставки
            // должны вмещать сам заголовок
            var paddingError = HeaderPaddingError(n);
            if (paddingError is not null) error = error is null ? paddingError : error + " " + paddingError;
        }

        AddText(node, "content_padding_addition", n.AwgContentPadding);
        AddText(node, "rekey_after_time", n.AwgRekeyAfterTime);
        AddText(node, "rekey_timeout", n.AwgRekeyTimeout);
        AddText(node, "reject_after_time", n.AwgRejectAfterTime);
        AddText(node, "keepalive_timeout", n.AwgKeepaliveTimeout);
        AddText(node, "max_handshake_attempts", n.AwgMaxHandshakeAttempts);

        // Флаги AWG 3.1 пишем только когда они включены: выключено — это значение по умолчанию.
        if (n.AwgRandomTrailers) node["random_trailers"] = true;
        if (n.AwgDisableCookies) node["disable_cookies"] = true;

        var missing = n.MissingAwgParams;
        if (missing.Count > 0)
            warning = AddWarning(warning,
                "В профиле нет параметров AmneziaWG: " + string.Join(", ", missing) +
                ". Если сервер их использует, хендшейк не пройдёт — сверьтесь с .conf из панели.");

        if (HasReservedBytes(n))
            warning = AddWarning(warning,
                "Ядро не передаёт reserved-байты WireGuard (" + n.WgReserved!.Trim() +
                ") — значение проигнорировано. Если сервер их требует, трафик не пойдёт.");

        return (node, warning, error);
    }

    /// <summary>
    /// При шифровании заголовка (AWG 3.0) мусорные вставки S1–S4 должны вмещать сам заголовок:
    /// оба ядра с AWG 3.x требуют не меньше 12 байт и отказываются стартовать иначе.
    /// </summary>
    private static string? HeaderPaddingError(ProxyNode n)
    {
        foreach (var (name, value) in new[] { ("S1", n.AwgS1), ("S2", n.AwgS2), ("S3", n.AwgS3), ("S4", n.AwgS4) })
        {
            if (value is null || value.Value < 12)
                return $"С параметром HeaderProtectionKey значение {name} должно быть не меньше 12, а в профиле " +
                       (value is null ? "оно не задано" : "оно " + value.Value) +
                       ". Ядро не запустит туннель — сверьтесь с .conf из панели.";
        }

        return null;
    }

    /// <summary>Есть ли в reserved ненулевые байты (нули провайдеры не используют).</summary>
    private static bool HasReservedBytes(ProxyNode n)
    {
        if (string.IsNullOrWhiteSpace(n.WgReserved)) return false;
        foreach (var part in n.WgReserved!.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
            if (int.TryParse(part, out var b) && b != 0) return true;
        return false;
    }

    /// <summary>
    /// Строковое поле ядра amnezia-box: «100», «100-200» или произвольный текст (I1–I5).
    /// Интервал приводим к виду «меньшее-большее» — иначе устройство откажется его разобрать.
    /// </summary>
    private static void AddText(JsonObject target, string key, string? value)
    {
        var text = RangeText(value);
        if (text is not null) target[key] = text;
    }

    private static string? RangeText(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        var v = value.Trim();

        if (v.Contains('-') && !v.StartsWith('-'))
        {
            var parts = v.Split('-', 2);
            if (parts.Length == 2
                && long.TryParse(parts[0].Trim(), out var from)
                && long.TryParse(parts[1].Trim(), out var to))
            {
                if (from > to) (from, to) = (to, from);
                return from == to ? from.ToString() : from + "-" + to;
            }
        }

        return v;
    }

    /// <summary>
    /// MTU интерфейса WireGuard. Обфускация AmneziaWG добавляет к пакетам байты
    /// (мусорные вставки S1–S4 и content padding), поэтому для AWG оставляем запас —
    /// иначе пакеты уходят во фрагментацию и туннель «подключен, но ничего не грузит».
    /// </summary>
    private static int EffectiveMtu(ProxyNode n, bool isAwg)
    {
        // Значения по умолчанию: 1408 у sing-box и 1380 у клиента Amnezia — запас на обфускацию
        var mtu = n.WgMtu is > 0 ? n.WgMtu!.Value : (isAwg ? 1380 : 1408);
        if (mtu > 1420) mtu = 1420;

        if (isAwg)
        {
            mtu -= RangeUpperBound(n.AwgContentPadding);
            if (mtu > 1380) mtu = 1380;          // значение по умолчанию в клиентах Amnezia
        }

        return mtu < 576 ? 1280 : mtu;
    }

    /// <summary>Верхняя граница интервала («50-100» → 100, «50» → 50).</summary>
    private static int RangeUpperBound(string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return 0;
        var parts = value.Trim().Split('-');
        var text = parts.Length > 1 ? parts[^1] : parts[0];
        return int.TryParse(text.Trim(), out var number) && number > 0 ? Math.Min(number, 256) : 0;
    }

    // ------------------------------------------------------------------- route
    private static JsonNode BuildRoute(RouteProfile route, RouteSet? set, bool modern, bool allowProcessPath,
        string? domainResolver, List<string>? tunnelCidrs = null)
    {
        var rules = new JsonArray();

        if (modern && route.SniffTraffic)
            rules.Add(new JsonObject { ["action"] = "sniff" });

        if (modern && route.TunEnabled)
            rules.Add(new JsonObject { ["protocol"] = "dns", ["action"] = "hijack-dns" });

        // Адреса, которые существуют только внутри туннеля WireGuard/AmneziaWG (адрес интерфейса
        // и DNS из .conf, обычно 10.x.x.x). Их обязательно отправляем в туннель ДО правила
        // «приватные адреса — напрямую»: иначе DNS-запрос к 10.8.0.1 уходит провайдеру,
        // домены не резолвятся и получается «подключено, но ничего не грузит».
        if (tunnelCidrs is { Count: > 0 })
        {
            var tunnelRule = new JsonObject();
            var tunnelArray = new JsonArray();
            foreach (var cidr in tunnelCidrs) tunnelArray.Add(cidr);
            tunnelRule["ip_cidr"] = tunnelArray;
            rules.Add(Action(modern, RouteAction.Proxy, tunnelRule));
        }

        // локальные сети и служебные адреса — всегда напрямую
        if (route.DirectPrivate)
        {
            rules.Add(Action(modern, RouteAction.Direct, new JsonObject { ["ip_is_private"] = true }));
            rules.Add(Action(modern, RouteAction.Direct, new JsonObject { ["ip_cidr"] = new JsonArray("127.0.0.0/8", "::1/128") }));
        }

        var userRules = set?.Rules.Where(r => r.Enabled && !string.IsNullOrWhiteSpace(r.Pattern)).ToList() ?? new();
        foreach (var rule in BuildJsonRules(userRules, modern))
            rules.Add(rule);

        var appRules = set?.Apps.Where(a => a.Enabled && (!string.IsNullOrWhiteSpace(a.ProcessName) || !string.IsNullOrWhiteSpace(a.Path))).ToList()
                       ?? new List<AppRule>();
        foreach (var app in appRules)
        {
            // Поля одного правила в sing-box объединяются по И: если и process_name, и process_path
            // лежат в одном объекте, правило умрёт при первом же обновлении программы (путь
            // содержит версию, напр. WindowsApps\..._4.54.8517.0_x64__...). Разбиваем на два
            // отдельных правила — матчит хоть имя процесса, хоть путь.
            if (!string.IsNullOrWhiteSpace(app.ProcessName))
                rules.Add(Action(modern, app.Action,
                    new JsonObject { ["process_name"] = new JsonArray(NormalizeProcessName(app.ProcessName)) }));
            var path = app.Path;
            if (allowProcessPath && !string.IsNullOrWhiteSpace(path))
                rules.Add(Action(modern, app.Action, new JsonObject { ["process_path"] = new JsonArray(path) }));
        }

        var routeNode = new JsonObject
        {
            ["rules"] = rules,
            ["final"] = ProxyTag,
            ["auto_detect_interface"] = true
        };

        // с sing-box 1.12 для доменов в серверах обязателен явный резолвер
        if (!string.IsNullOrWhiteSpace(domainResolver))
            routeNode["default_domain_resolver"] = domainResolver;

        return routeNode;
    }

    /// <summary>DNS-серверы, заданные в конфиге профиля (WireGuard/AmneziaWG).</summary>
    private static List<string> ProfileDnsServers(ProxyProfile? profile)
    {
        var result = new List<string>();
        var raw = profile?.Node?.WgDns;
        if (string.IsNullOrWhiteSpace(raw)) return result;

        foreach (var item in raw!.Split(new[] { ',', ';', ' ' }, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            // отбрасываем всё, что не похоже на адрес сервера
            var value = item;
            foreach (var prefix in new[] { "udp://", "tcp://", "tls://", "https://", "quic://", "h3://" })
                if (value.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) { value = value[prefix.Length..]; break; }

            if (value.Length is > 0 and < 64 && !value.Contains('/')) result.Add(value);
        }

        return result;
    }

    /// <summary>
    /// Профиль, поднимающий собственный сетевой туннель (WireGuard/AmneziaWG), а не просто прокси.
    /// У таких профилей свои адреса и свой DNS живут внутри туннеля — это меняет правила маршрутизации.
    /// Профили из «сырого» JSON sing-box не трогаем: там пользователь описал всё сам.
    /// </summary>
    private static bool IsTunnelProfile(ProxyProfile profile) =>
        profile.RawJson is not { Length: > 0 } &&
        profile.Protocol.ToLowerInvariant() is ProtocolIds.Wireguard or ProtocolIds.AmneziaWg;

    /// <summary>
    /// Сети, доступные только внутри туннеля WireGuard/AmneziaWG: подсеть адреса интерфейса
    /// (<c>Address = 10.8.1.5/24</c> → <c>10.8.1.0/24</c>) и DNS-серверы из .conf, если они
    /// используются через туннель. Без явного правила на прокси их перехватывает правило
    /// «приватные адреса — напрямую», и туннель остаётся без DNS: домены не резолвятся,
    /// страницы не открываются, хотя ядро при этом работает.
    /// </summary>
    private static List<string> TunnelOnlyCidrs(ProxyProfile? profile, bool profileDnsInTunnel)
    {
        var result = new List<string>();
        var node = profile?.Node;
        if (node is null) return result;

        foreach (var raw in (node.WgAddress ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            var network = NetworkOf(raw);
            if (network is not null) result.Add(network);
        }

        if (profileDnsInTunnel)
        {
            foreach (var dns in ProfileDnsServers(profile))
            {
                var network = NetworkOf(dns);
                if (network is not null) result.Add(network);
            }
        }

        return result.Distinct().ToList();
    }

    /// <summary>«10.8.1.5/24» → «10.8.1.0/24», «10.8.0.1» → «10.8.0.1/32». Мусор отбрасываем.</summary>
    private static string? NetworkOf(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;

        var parts = value.Trim().Split('/', 2);
        var host = parts[0].Trim('[', ']');
        if (!IPAddress.TryParse(host, out var address)) return null;

        var bytes = address.GetAddressBytes();
        var maxBits = bytes.Length * 8;
        var bits = maxBits;
        if (parts.Length > 1 && int.TryParse(parts[1].Trim(), out var parsed) && parsed >= 0)
            bits = Math.Min(parsed, maxBits);

        // «адрес» вида 0.0.0.0/0 — не подсеть интерфейса, а весь интернет: правило на прокси
        // для него перекрыло бы доступ к локальной сети
        if (bits == 0 || (maxBits == 32 && bits < 8)) return null;

        var fullBytes = bits / 8;
        for (var i = fullBytes; i < bytes.Length; i++) bytes[i] = 0;
        if (bits % 8 != 0 && fullBytes < bytes.Length)
            bytes[fullBytes] &= (byte)(0xFF << (8 - bits % 8));

        return new IPAddress(bytes) + "/" + bits;
    }

    /// <summary>
    /// Числовые поля AmneziaWG: ядро принимает либо число («100»), либо диапазон («100-200»).
    /// Отдаём ровно то, что было в .conf, но приводим диапазон к виду «меньшее-большее»:
    /// тип Range у ядра требует from &lt;= to и иначе роняет весь запуск с «invalid range».
    /// H1–H4 бывают больше int.MaxValue, поэтому числа держим как long.
    /// </summary>
    private static void AddRange(JsonObject target, string key, string? value)
    {
        if (string.IsNullOrWhiteSpace(value)) return;
        var v = value.Trim();

        if (v.Contains('-') && !v.StartsWith('-'))
        {
            var parts = v.Split('-', 2);
            if (parts.Length == 2
                && long.TryParse(parts[0].Trim(), out var from)
                && long.TryParse(parts[1].Trim(), out var to))
            {
                if (from > to) (from, to) = (to, from);
                if (from == to) target[key] = from;
                else target[key] = $"{from}-{to}";
                return;
            }

            target[key] = v;
            return;
        }

        if (long.TryParse(v, out var number)) target[key] = number;
        else target[key] = v;
    }

    /// <summary>Ядру нужно имя процесса с расширением (chrome.exe).</summary>
    private static string NormalizeProcessName(string name)
    {
        var value = name.Trim();
        if (value.Length == 0) return value;
        if (value.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) return value;
        if (value.Contains('.')) return value;            // уже полное имя файла
        return value + ".exe";
    }

    private static JsonNode Action(bool modern, RouteAction action, JsonObject match)
    {
        if (modern)
        {
            switch (action)
            {
                case RouteAction.Block:
                    match["action"] = "reject";
                    break;
                default:
                    match["action"] = "route";
                    match["outbound"] = action == RouteAction.Direct ? DirectTag : ProxyTag;
                    break;
            }
        }
        else
        {
            match["outbound"] = action switch
            {
                RouteAction.Direct => DirectTag,
                RouteAction.Block => BlockTag,
                _ => ProxyTag
            };
        }
        return match;
    }

    /// <summary>Преобразование пользовательских правил в правила sing-box с группировкой по типу и действию.</summary>
    private static List<JsonNode> BuildJsonRules(List<RouteRule> rules, bool modern)
    {
        var groups = new List<(RouteAction Action, string Type, List<string> Values)>();

        foreach (var rule in rules)
        {
            var (type, value) = Normalize(rule);
            if (value.Length == 0) continue;

            var group = groups.FirstOrDefault(g => g.Action == rule.Action && g.Type == type);
            if (group.Values is null)
            {
                group = (rule.Action, type, new List<string>());
                groups.Add(group);
            }
            if (!group.Values.Contains(value)) group.Values.Add(value);
        }

        var result = new List<JsonNode>();
        foreach (var g in groups)
        {
            var arr = new JsonArray();
            foreach (var v in g.Values) arr.Add(v);
            var match = new JsonObject { [g.Type] = arr };
            result.Add(Action(modern, g.Action, match));
        }
        return result;
    }

    /// <summary>Пользовательский ввод → (поле sing-box, значение).</summary>
    public static (string type, string value) Normalize(RouteRule rule)
    {
        var raw = rule.Pattern.Trim();

        switch (rule.Kind)
        {
            case MatchKind.Regex:
                return ("domain_regex", raw.StartsWith("regex:", StringComparison.OrdinalIgnoreCase) ? raw[6..].Trim() : raw);

            case MatchKind.Ip:
            {
                var ip = raw.Trim('[', ']');
                if (!ip.Contains('/'))
                    ip += ip.Contains(':') ? "/128" : "/32";
                return ("ip_cidr", ip);
            }

            case MatchKind.Keyword:
                return ("domain_keyword", raw.TrimStart('*', '.').ToLowerInvariant());

            default:
            {
                var d = raw.ToLowerInvariant();
                if (d.StartsWith("*.")) return ("domain_suffix", d[2..]);
                if (d.Contains('*'))
                {
                    var escaped = System.Text.RegularExpressions.Regex.Escape(d).Replace("\\*", ".*");
                    return ("domain_regex", "^" + escaped + "$");
                }
                if (!d.Contains('.'))
                    return ("domain_keyword", d);           // "youtube", "vk" и т.п.
                return ("domain_suffix", d.TrimStart('.'));
            }
        }
    }

    // ---------------------------------------------- raw JSON (профиль "tunnel")
    private static JsonObject? ExtractOutboundFromRawConfig(string rawJson, out string? warning)
    {
        warning = null;
        try
        {
            var node = JsonNode.Parse(rawJson);
            if (node is not JsonObject obj) { warning = "JSON-профиль должен быть объектом"; return null; }

            if (obj["outbounds"] is JsonArray outbounds)
            {
                foreach (var candidate in outbounds.OfType<JsonObject>())
                {
                    var type = candidate["type"]?.GetValue<string>() ?? "";
                    if (type is "direct" or "block" or "dns" or "selector" or "urltest") continue;
                    return (JsonObject)candidate.DeepClone();
                }
            }

            if (obj["endpoints"] is JsonArray endpoints)
            {
                var first = endpoints.OfType<JsonObject>().FirstOrDefault();
                if (first is not null) return (JsonObject)first.DeepClone();
            }

            if (obj["type"] is not null) return (JsonObject)obj.DeepClone();

            warning = "В JSON-конфиге не найден outbound для прокси";
            return null;
        }
        catch (Exception ex)
        {
            warning = "Ошибка разбора JSON: " + ex.Message;
            return null;
        }
    }
}
