using System.Text.Json.Serialization;

namespace GoreBox.Models;

/// <summary>Что делать при закрытии окна по кнопке-крестику.</summary>
public enum CloseBehavior
{
    /// <summary>Спрашивать: скрыть в трей или выйти из приложения.</summary>
    Ask,
    /// <summary>Всегда сворачивать в трей.</summary>
    HideToTray,
    /// <summary>Всегда закрывать приложение полностью.</summary>
    Exit,
}

/// <summary>Правило маршрутизации для доменов/адресов.</summary>
public sealed class RouteRule
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    /// <summary>Что ввёл пользователь: "youtube", "vk.com", "1.2.3.0/24", "regex:^ads\." и т.п.</summary>
    public string Pattern { get; set; } = "";

    public MatchKind Kind { get; set; } = MatchKind.Domain;

    public RouteAction Action { get; set; } = RouteAction.Proxy;

    public bool Enabled { get; set; } = true;

    public string? Note { get; set; }

    [JsonIgnore]
    public string ActionLabel => Action switch
    {
        RouteAction.Proxy => "PROXY",
        RouteAction.Direct => "DIRECT",
        _ => "BLOCK"
    };

    [JsonIgnore]
    public string KindLabel => Kind switch
    {
        MatchKind.Domain => "домен",
        MatchKind.Ip => "IP / подсеть",
        MatchKind.Keyword => "ключ",
        MatchKind.Regex => "regex",
        _ => "—"
    };

    public RouteRule Clone() => new()
    {
        Id = Guid.NewGuid().ToString("N"),
        Pattern = Pattern,
        Kind = Kind,
        Action = Action,
        Enabled = Enabled,
        Note = Note
    };
}

/// <summary>Правило для приложения.</summary>
public sealed class AppRule
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");

    public string Name { get; set; } = "";

    /// <summary>Полный путь к .exe (может быть пустым, если добавлено по имени процесса).</summary>
    public string Path { get; set; } = "";

    /// <summary>Имя процесса без расширения (browser, telegram, ...).</summary>
    public string ProcessName { get; set; } = "";

    public RouteAction Action { get; set; } = RouteAction.Proxy;

    public bool Enabled { get; set; } = true;

    [JsonIgnore]
    public string DisplayPath => string.IsNullOrWhiteSpace(Path) ? ProcessName : Path;

    [JsonIgnore]
    public string ActionLabel => Action switch
    {
        RouteAction.Proxy => "PROXY",
        RouteAction.Direct => "DIRECT",
        _ => "BLOCK"
    };

    public AppRule Clone() => new()
    {
        Id = Guid.NewGuid().ToString("N"),
        Name = Name,
        Path = Path,
        ProcessName = ProcessName,
        Action = Action,
        Enabled = Enabled
    };
}

/// <summary>
/// Набор правил: домены/адреса + приложения.
/// Хранится отдельным файлом в %AppData%\GoreBox\routes\&lt;имя&gt;.json, чтобы можно было делиться.
/// </summary>
public sealed class RouteSet
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "Новый набор";
    public string? Description { get; set; }
    public bool BuiltIn { get; set; }
    public List<RouteRule> Rules { get; set; } = new();
    public List<AppRule> Apps { get; set; } = new();
    public DateTime UpdatedAt { get; set; } = DateTime.UtcNow;

    /// <summary>Имя .json-файла на диске (для встроенных — имя ресурса).</summary>
    [JsonIgnore]
    public string FileName { get; set; } = "";

    [JsonIgnore]
    public string RulesSummary => $"{Rules.Count} правил · {Apps.Count} приложений";
}

/// <summary>Профиль маршрутизации: локальный вход, системный прокси, TUN, набор правил.</summary>
public sealed class RouteProfile
{
    public string Id { get; set; } = Guid.NewGuid().ToString("N");
    public string Name { get; set; } = "По умолчанию";

    public string ListenAddress { get; set; } = "127.0.0.1";
    public int ListenPort { get; set; } = 2080;
    public ListenMode Mode { get; set; } = ListenMode.Mixed;

    public bool UseSystemProxy { get; set; } = true;

    public bool TunEnabled { get; set; }
    public string TunStack { get; set; } = "system";   // system | gvisor | mixed
    public int TunMtu { get; set; } = 9000;
    public bool TunStrictRoute { get; set; }
    public bool SniffTraffic { get; set; } = true;

    public string DnsMode { get; set; } = "system";    // system | proxy | custom
    public string DnsServer { get; set; } = "1.1.1.1";

    /// <summary>Id набора правил (<see cref="RouteSet"/>), который применяется в этом профиле.</summary>
    public string? RouteSetId { get; set; }

    public bool BlockAds { get; set; }

    /// <summary>Локальные и служебные адреса всегда напрямую.</summary>
    public bool DirectPrivate { get; set; } = true;

    [JsonIgnore]
    public string TargetLabel => $"{ListenAddress}:{ListenPort} · " + Mode switch
    {
        ListenMode.Mixed => "mixed",
        ListenMode.Socks => "socks",
        _ => "http"
    };

    public RouteProfile Clone() => (RouteProfile)MemberwiseClone();
}

/// <summary>Настройки приложения.</summary>
public sealed class AppSettings
{
    public ThemeMode Theme { get; set; } = ThemeMode.Dark;

    /// <summary>Акцентный цвет интерфейса в формате #AARRGGBB; null — цвет темы по умолчанию.</summary>
    public string? AccentColor { get; set; }

    /// <summary>Панели (браузеры) и SSH-подключения (терминалы) из выпадающих списков навигации.</summary>
    public List<PanelEntry> Panels { get; set; } = new();
    public List<SshEntry> SshItems { get; set; } = new();

    /// <summary>Какая панель / какое SSH-подключение открыто сейчас.</summary>
    public string? SelectedPanelId { get; set; }
    public string? SelectedSshId { get; set; }

    /// <summary>Раскрыты ли выпадающие списки «Панели» и «SSH» в навигации.</summary>
    public bool NavPanelsExpanded { get; set; }
    public bool NavSshExpanded { get; set; }

    /// <summary>Помнить состояние: при старте поднимать тот же профиль и то же состояние тумблера.</summary>
    public bool RememberState { get; set; } = true;

    public string? ActiveProfileId { get; set; }
    public string? ActiveRouteProfileId { get; set; }
    public bool ProxyWasActive { get; set; }

    public bool RunAtStartup { get; set; }
    public bool StartMinimized { get; set; }

    /// <summary>Что делать при закрытии окна по крестику.</summary>
    public CloseBehavior CloseBehavior { get; set; } = CloseBehavior.Ask;
    public bool TrayHintShown { get; set; }

    /// <summary>Путь к выбранному ядру (sing-box). Пусто — используется скачанное приложением.</summary>
    public string? CorePath { get; set; }
    public CoreFlavor CoreFlavor { get; set; } = CoreFlavor.SingBox;
    public string? CoreVersion { get; set; }
    public bool AutoUpdateCore { get; set; } = true;

    /// <summary>Точный замер задержки через ядро (запускает ядро на время теста).</summary>
    public bool PreciseLatencyTest { get; set; } = true;

    public bool CheckUpdatesOnStart { get; set; } = true;
    public DateTime? LastUpdateCheck { get; set; }

    public double WindowWidth { get; set; } = 1040;
    public double WindowHeight { get; set; } = 720;
    public double? WindowLeft { get; set; }
    public double? WindowTop { get; set; }
    public string? WindowState { get; set; }

    public string LastTab { get; set; } = "profiles";

    /// <summary>
    /// Какая из двух вкладок в левой карточке открыта: «proxy» или «dpi».
    /// Это переключатель режима, а не навигация по разделам — поэтому хранится отдельно.
    /// </summary>
    public string ConnectionTab { get; set; } = "proxy";

    /// <summary>
    /// Настройки обхода DPI (стратегии, порт, системный прокси).
    /// Возвращаем экземпляр всегда: в старом settings.json поля может не быть,
    /// а обращений к настройкам много — проверять везде неудобно.
    /// </summary>
    public DpiBypassSettings Dpi
    {
        get => _dpi ??= new DpiBypassSettings();
        set => _dpi = value ?? new DpiBypassSettings();
    }

    private DpiBypassSettings? _dpi;

    /// <summary>Порядок вкладок в левом меню (ключи: profiles, routing, settings, panel, ssh).</summary>
    public List<string> NavOrder { get; set; } = new();

    /// <summary>Высота панели логов на вкладке «Профили» в пикселях.</summary>
    public double ProfilesLogHeight { get; set; } = 210;
}

/// <summary>Информация об установленном ядре.</summary>
public sealed class CoreInfo
{
    public string Path { get; set; } = "";
    public string Version { get; set; } = "";
    public bool Exists { get; set; }
    public bool SupportsAmnezia { get; set; }
    public bool SupportsEndpoints { get; set; }
    public bool SupportsModernRules { get; set; }
    public bool IsExtended { get; set; }
    public int VersionMajor { get; set; }
    public int VersionMinor { get; set; }
    public int VersionPatch { get; set; }

    /// <summary>
    /// Результат живой проверки ядра (<c>sing-box check</c> на тестовом эндпоинте WireGuard).
    /// <c>null</c> — проверку не запускали или она не дала однозначного ответа.
    /// </summary>
    public bool? AmneziaProbed { get; set; }

    /// <summary>
    /// Понимает ли ядро параметры AmneziaWG 3.1 (<c>random_trailers</c>, <c>disable_cookies</c>).
    /// Библиотека wireguard-go в расширенной сборке их поддерживает, а вот в конфиг sing-box
    /// они пока не проброшены — поэтому проверяем так же живо, как и сам блок <c>amnezia</c>.
    /// Без <c>random_trailers</c> хендшейк с сервером, который их отправляет, не проходит:
    /// пакет ответа длиннее ожидаемого и отбрасывается как нераспознанный.
    /// </summary>
    public bool SupportsAwgTrailers { get; set; }

    /// <summary>Результат живой проверки <c>random_trailers</c>; <c>null</c> — не проверяли.</summary>
    public bool? AwgTrailersProbed { get; set; }

    /// <summary>
    /// Ядро понимает отдельный эндпоинт <c>type: "awg"</c> с плоскими параметрами обфускации —
    /// это форк Amnezia (hoaxisr/amnezia-box). У него своя схема конфига, и только он на сегодня
    /// передаёт <c>random_trailers</c>/<c>disable_cookies</c> из файла конфигурации.
    /// </summary>
    public bool SupportsAwgEndpoint { get; set; }

    /// <summary>
    /// Ядро понимает параметры обфускации, лежащие плоско в самом эндпоинте <c>wireguard</c>
    /// (без обёртки <c>amnezia</c>) — так устроен sing-box-lx, собранный с тегом <c>with_awg</c>.
    /// Это единственная схема с готовыми сборками под Windows, которая передаёт AWG 3.1 целиком.
    /// </summary>
    public bool SupportsAwgFlat { get; set; }

    /// <summary>Хоть одна из схем AmneziaWG поддерживается ядром.</summary>
    [JsonIgnore]
    public bool SupportsAnyAwg => SupportsAmnezia || SupportsAwgEndpoint || SupportsAwgFlat;

    /// <summary>
    /// Ядро собрано из исходников nekobox (nekobox_core): печатает баннер «NekoBox:» и умеет
    /// gRPC-режим <c>nekobox --token --port</c> (libcore.proto). Официальные релизы sing-box
    /// и посторонние форки такого режима не имеют — для них остаётся запуск <c>run -c</c>.
    /// </summary>
    public bool SupportsNekoRpc { get; set; }

    /// <summary>
    /// Почему тестовый эндпоинт WireGuard не собрался (например, сборка ядра без gvisor —
    /// тогда WireGuard-эндпоинт не работает вообще, а не только AmneziaWG).
    /// </summary>
    public string? EndpointError { get; set; }

    [JsonIgnore]
    public string VersionShort => string.IsNullOrWhiteSpace(Version) ? "не найдено" : Version;

    /// <summary>Короткое имя версии без префикса «sing-box version».</summary>
    [JsonIgnore]
    public string VersionNumber
    {
        get
        {
            var v = Version ?? "";
            var index = v.IndexOf("version", StringComparison.OrdinalIgnoreCase);
            if (index >= 0) v = v[(index + "version".Length)..];
            return v.Trim().Split('\n')[0].Trim();
        }
    }

    /// <summary>Вариант сборки ядра, которому соответствует этот файл.</summary>
    public CoreFlavor Flavor() => IsExtended ? CoreFlavor.SingBoxExtended : CoreFlavor.SingBox;

    public static CoreInfo Empty => new();
}
