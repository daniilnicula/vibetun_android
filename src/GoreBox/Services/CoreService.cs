using System.Diagnostics;
using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using GoreBox.Models;
using GoreBox.Utils;

namespace GoreBox.Services;

/// <summary>Управление процессом ядра sing-box: поиск, проверка, запуск, остановка, логи.</summary>
public sealed partial class CoreService : IDisposable
{
    private readonly object _sync = new();
    private readonly List<string> _recentLog = new();
    private Process? _process;
    private NekoCoreRpcClient? _rpc;
    private int _clashPort;
    private readonly SemaphoreSlim _stopGate = new(1, 1);   // остановки выполняются по одной

    public event Action<string>? LogReceived;
    public event Action<ConnectionState>? StateChanged;

    public ConnectionState State { get; private set; } = ConnectionState.Stopped;
    public CoreInfo Info { get; private set; } = CoreInfo.Empty;
    public int ClashApiPort => _clashPort;
    public bool IsRunning => State is ConnectionState.Running or ConnectionState.Starting;
    public IReadOnlyList<string> RecentLog => _recentLog;

    [GeneratedRegex(@"(?<maj>\d+)\.(?<min>\d+)\.(?<pat>\d+)")]
    private static partial Regex VersionRegex();

    /// <summary>Значение «interface_name» TUN-адаптера, которое выставляет SingBoxConfigBuilder (GoreBox-*).</summary>
    [GeneratedRegex("\"interface_name\"\\s*:\\s*\"(?<name>GoreBox-[^\"]*)\"")]
    private static partial Regex TunAdapterNameRegex();

    /// <summary>
    /// Build-теги, которых нет в официальном sing-box: по ним узнаём форк
    /// (shtorm-7/sing-box-extended и подобные) даже если в строке версии нет слова «extended»
    /// — например, когда ядро собрано пользователем вручную.
    /// </summary>
    private static readonly string[] ExtendedTagMarkers =
    {
        "with_manager", "with_admin_panel", "with_masque", "with_mtproxy", "with_sudoku",
        "with_snell", "with_trusttunnel", "with_call", "with_awg"
    };

    // Ключи из examples/wireguard расширенной сборки: нужны только для проверки ядра, трафик никуда не идёт.
    private const string ProbePrivateKey = "QGg8AFRn6qKfTB7cT3FWH1WGx3np+OKzlNuQUrqIBmI=";
    private const string ProbePublicKey = "3nk7jdnkcL95Fc/z+GCiH7jOovEKhFkLIGPT+U/uLEQ=";

    private string? _probedPath;

    // ------------------------------------------------------------------ detect
    public async Task<CoreInfo> DetectAsync(string? overridePath = null, CoreFlavor? preferFlavor = null)
    {
        var candidates = new List<string>();
        if (!string.IsNullOrWhiteSpace(overridePath)) candidates.Add(overridePath!);

        // Сначала — ядро выбранного варианта, затем второе (встроенные распаковываются в CoreDir)
        if (preferFlavor == CoreFlavor.SingBoxExtended)
        {
            candidates.Add(AppPaths.CoreExtendedBinary);
            candidates.Add(AppPaths.CoreBinary);
        }
        else if (preferFlavor == CoreFlavor.SingBox)
        {
            candidates.Add(AppPaths.CoreBinary);
            candidates.Add(AppPaths.CoreExtendedBinary);
        }
        else
        {
            candidates.Add(AppPaths.CoreBinary);
            candidates.Add(AppPaths.CoreExtendedBinary);
        }

        var local = Path.Combine(AppContext.BaseDirectory, "sing-box.exe");
        candidates.Add(local);

        foreach (var dir in new[] { AppPaths.CoreDir, AppPaths.DataRoot })
        {
            try
            {
                if (Directory.Exists(dir))
                    candidates.AddRange(Directory.EnumerateFiles(dir, "sing-box*.exe", SearchOption.AllDirectories));
            }
            catch { /* ignore */ }
        }

        foreach (var path in candidates.Distinct())
        {
            if (string.IsNullOrWhiteSpace(path) || !File.Exists(path)) continue;
            var info = await ReadVersionAsync(path);
            if (info is not null)
            {
                Info = info;
                return info;
            }
        }

        Info = CoreInfo.Empty;
        return Info;
    }

    private async Task<CoreInfo?> ReadVersionAsync(string path)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = path,
                Arguments = "version",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var p = Process.Start(psi);
            if (p is null) return null;
            var output = await p.StandardOutput.ReadToEndAsync();
            output += await p.StandardError.ReadToEndAsync();
            await p.WaitForExitAsync();

            if (string.IsNullOrWhiteSpace(output)) return null;

            var match = VersionRegex().Match(output);
            var info = new CoreInfo
            {
                Path = path,
                Version = output.Split('\n').FirstOrDefault(l => l.Contains("sing-box version"))?.Trim() ?? output.Trim(),
                Exists = true,
                IsExtended = LooksExtended(output, path)
            };
            if (match.Success)
            {
                info.VersionMajor = int.Parse(match.Groups["maj"].Value);
                info.VersionMinor = int.Parse(match.Groups["min"].Value);
                info.VersionPatch = int.Parse(match.Groups["pat"].Value);
            }

            info.SupportsModernRules = info.VersionMajor > 1 ||
                                       (info.VersionMajor == 1 && info.VersionMinor >= 11);
            info.SupportsEndpoints = info.SupportsModernRules;
            // Баннер nekobox_core («sing-box: … NekoBox: …» печатается до любых подкоманд):
            // такое ядро умеет gRPC-режим «nekobox --token --port».
            info.SupportsNekoRpc = output.Contains("NekoBox:", StringComparison.Ordinal);
            // Обфускацию AmneziaWG понимают только расширенные сборки (блок "amnezia" в эндпоинте).
            // Это предварительная оценка по строке версии и build-тегам; точный ответ даёт
            // ProbeAmneziaSupportAsync(), который вызывается перед запуском AmneziaWG-профиля.
            info.SupportsAmnezia = info.IsExtended;
            info.SupportsAwgTrailers = false;
            info.SupportsAwgEndpoint = false;
            info.AmneziaProbed = null;
            info.AwgTrailersProbed = null;
            info.EndpointError = null;
            return info;
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Похоже ли ядро на расширенный форк: sing-box-extended (shtorm-7), sing-box-lx (в версии
    /// видно «-lx.NN»), форк Amnezia (hoaxisr/amnezia-box, в версии видно «-awgm.NN») и подобные.
    /// </summary>
    private static bool LooksExtended(string output, string path)
    {
        var fileName = Path.GetFileName(path);
        if (fileName.Contains("extended", StringComparison.OrdinalIgnoreCase) ||
            fileName.Contains("amnezia", StringComparison.OrdinalIgnoreCase) ||
            fileName.Contains("-lx", StringComparison.OrdinalIgnoreCase) ||
            fileName.Contains("awg", StringComparison.OrdinalIgnoreCase)) return true;

        var lines = output.Split('\n');
        var version = lines.FirstOrDefault(l => l.Contains("sing-box version", StringComparison.OrdinalIgnoreCase)) ?? "";
        if (version.Contains("extended", StringComparison.OrdinalIgnoreCase) ||
            version.Contains("amnezia", StringComparison.OrdinalIgnoreCase) ||
            version.Contains("-lx", StringComparison.OrdinalIgnoreCase) ||
            version.Contains("awg", StringComparison.OrdinalIgnoreCase)) return true;

        var tags = lines.FirstOrDefault(l => l.StartsWith("Tags:", StringComparison.OrdinalIgnoreCase)) ?? "";
        return tags.Length > 0 && ExtendedTagMarkers.Any(t => tags.Contains(t, StringComparison.OrdinalIgnoreCase));
    }

    // ------------------------------------------------------- проверка AmneziaWG
    /// <summary>
    /// Живая проверка: понимает ли установленное ядро блок <c>amnezia</c> у эндпоинта WireGuard.
    ///
    /// sing-box молча игнорирует неизвестные поля, поэтому «конфиг прошёл check» НЕ означает поддержку —
    /// спрашиваем наоборот: кладём в <c>jc</c> заведомо неподходящее значение. Знакомое поле ядро
    /// попытается разобрать и вернёт ошибку разбора, незнакомое — пропустит, и check пройдёт.
    ///
    /// Возвращает <c>null</c>, если однозначный вывод сделать не удалось (тогда остаёмся на эвристике
    /// по строке версии).
    /// </summary>
    public async Task<bool?> ProbeAmneziaSupportAsync(bool force = false)
    {
        if (!Info.Exists) return null;
        if (!force && _probedPath == Info.Path && Info.AmneziaProbed is not null) return Info.AmneziaProbed;

        _probedPath = Info.Path;
        Info.AmneziaProbed = null;
        Info.AwgTrailersProbed = null;
        Info.SupportsAwgTrailers = false;
        Info.SupportsAwgEndpoint = false;
        Info.SupportsAwgFlat = false;

        // Эндпоинты появились в sing-box 1.11; в более старых ядрах AmneziaWG не бывает в принципе.
        if (!Info.SupportsEndpoints)
        {
            Info.SupportsAmnezia = false;
            Info.AmneziaProbed = false;
            Info.EndpointError = "Ядро старше 1.11: эндпоинты WireGuard/AmneziaWG не поддерживаются.";
            return false;
        }

        // Аварийный переключатель: если автоопределение схемы ошиблось, её можно задать файлом.
        var forced = SchemaOverride();
        if (forced is not null)
        {
            Info.SupportsAmnezia = forced is not "endpoint";
            Info.SupportsAwgFlat = forced == "flat";
            Info.SupportsAwgEndpoint = forced == "endpoint";
            Info.SupportsAwgTrailers = forced is "flat" or "endpoint";
            Info.IsExtended = true;
            Info.AmneziaProbed = true;
            Info.AwgTrailersProbed = true;
            Info.EndpointError = null;
            AppendLog($"Проверка ядра: схема AmneziaWG задана вручную — {forced} (awg-schema.txt).");
            return true;
        }

        // 1) sing-box-lx: параметры обфускации лежат плоско в самом эндпоинте wireguard.
        //    Это единственная схема с готовыми Windows-сборками, передающая AWG 3.1 целиком,
        //    поэтому пробуем её первой.
        if (await ProbeAwgFlatAsync()) return true;

        // 2) Форк Amnezia (hoaxisr/amnezia-box): отдельный эндпоинт type:"awg" с плоскими
        //    параметрами обфускации — он тоже передаёт random_trailers/disable_cookies,
        //    но готовые сборки у него только под Linux. У других ядер такого типа эндпоинта
        //    нет, проверка не пройдёт, и мы пойдём дальше — к вложенному блоку amnezia.
        if (await ProbeAwgEndpointAsync()) return true;

        // 3) базовый эндпоинт wireguard — работает ли WireGuard в этом ядре вообще
        var baseline = await RunCheckAsync(ProbeConfig(null));
        if (!baseline.Ok)
        {
            // Однозначного вывода об обфускации здесь нет (ядро могло не собрать эндпоинт по своей
            // причине) — запоминаем диагноз и оставляем оценку по строке версии.
            Info.EndpointError = FirstLine(baseline.Message);
            AppendLog("Проверка ядра: тестовый эндпоинт WireGuard не собирается — " + Info.EndpointError);
            return null;
        }

        Info.EndpointError = null;

        // 4) тот же эндпоинт + amnezia.jc с неверным типом
        var probe = await RunCheckAsync(ProbeConfig("\"amnezia\":{\"jc\":[1,2]}"));
        if (probe.Ok)
        {
            // поле не знакомо ядру — обфускации нет
            Info.SupportsAmnezia = false;
            Info.AmneziaProbed = false;
            Info.AwgTrailersProbed = false;
            AppendLog("Проверка ядра: ни эндпоинт type=\"awg\", ни блок amnezia не поддерживаются " +
                      "(похоже на официальный sing-box).");
            return false;
        }

        var message = probe.Message ?? "";
        var unknownField = message.Contains("unknown field", StringComparison.OrdinalIgnoreCase);
        var aboutAmnezia = message.Contains("amnezia", StringComparison.OrdinalIgnoreCase);

        if (unknownField || !aboutAmnezia)
        {
            // ответ неоднозначен (ядро строго проверяет поля или упало по другой причине)
            AppendLog("Проверка ядра: не удалось однозначно определить поддержку AmneziaWG — " + FirstLine(message));
            if (Info.SupportsAmnezia) await ProbeNestedAwg31Async();
            return null;
        }

        Info.SupportsAmnezia = true;
        Info.IsExtended = true;
        Info.AmneziaProbed = true;
        AppendLog("Проверка ядра: AmneziaWG поддерживается (вложенный блок amnezia).");
        await ProbeNestedAwg31Async();
        return true;
    }

    /// <summary>
    /// Ручное переопределение схемы AmneziaWG через файл <c>%LocalAppData%\GoreBox\awg-schema.txt</c>
    /// (одно слово: <c>flat</c> — параметры прямо в эндпоинте wireguard, <c>nested</c> — вложенный блок
    /// amnezia, <c>endpoint</c> — отдельный эндпоинт type="awg"). Нужно на случай, если автоопределение
    /// ошиблось: тогда ядро будет считаться поддерживающим выбранную схему без проверок, а неверный
    /// выбор проявится как понятная ошибка <c>sing-box check</c> при запуске.
    /// </summary>
    private static string? SchemaOverride()
    {
        try
        {
            var file = Path.Combine(AppPaths.DataRoot, "awg-schema.txt");
            if (!File.Exists(file)) return null;

            return File.ReadAllText(file).Trim().ToLowerInvariant() switch
            {
                "flat" => "flat",
                "nested" => "nested",
                "endpoint" => "endpoint",
                _ => null
            };
        }
        catch
        {
            return null;
        }
    }

    /// <summary>
    /// Проверка плоской схемы AmneziaWG (sing-box-lx): параметры обфускации лежат прямо в
    /// эндпоинте <c>wireguard</c>, без обёртки <c>amnezia</c>.
    ///
    /// Два шага. Первый — заведомо неверный тип у <c>random_trailers</c>: ядро, которое знает
    /// поле, упадёт на разборе, а незнающее либо молча пропустит его (check пройдёт), либо
    /// ответит «unknown field». По тексту ошибки ориентироваться нельзя — Go не всегда пишет
    /// в ней имя поля, поэтому решает второй шаг: корректный набор AWG-полей проходит только
    /// там, где они есть в схеме и где устройство собрано с тегом <c>with_awg</c>. Без тега
    /// sing-box-lx отвечает явной ошибкой «awg support not built», а не тихим откатом к
    /// обычному WireGuard — именно этот случай и надо отсечь.
    /// </summary>
    private async Task<bool> ProbeAwgFlatAsync()
    {
        var typeProbe = await RunCheckAsync(ProbeConfig("\"random_trailers\":\"probe\""));
        if (typeProbe.Ok) return false;   // поле неизвестно и проигнорировано — это не sing-box-lx

        var buildProbe = await RunCheckAsync(ProbeConfig("\"jc\":3,\"s1\":44,\"random_trailers\":true"));
        if (!buildProbe.Ok)
        {
            AppendLog("Проверка ядра: плоская схема AmneziaWG не подтвердилась — " + FirstLine(buildProbe.Message));
            return false;
        }

        Info.SupportsAwgFlat = true;
        Info.SupportsAwgTrailers = true;
        Info.SupportsAmnezia = true;
        Info.IsExtended = true;
        Info.AmneziaProbed = true;
        Info.AwgTrailersProbed = true;
        Info.EndpointError = null;
        AppendLog("Проверка ядра: плоская схема AmneziaWG (sing-box-lx) с поддержкой AWG 3.1.");
        return true;
    }

    /// <summary>
    /// Проверка эндпоинта <c>type: "awg"</c> — схемы форка Amnezia. Проверка положительная:
    /// тестовый конфиг корректен целиком, поэтому «check прошёл» и означает, что ядро знает
    /// этот тип эндпоинта. Следом тем же приёмом (заведомо неверный тип у знакомого поля)
    /// выясняем, есть ли у него флаги AmneziaWG 3.1.
    /// </summary>
    private async Task<bool> ProbeAwgEndpointAsync()
    {
        var flat = await RunCheckAsync(AwgEndpointProbeConfig(null));
        if (!flat.Ok) return false;

        Info.SupportsAwgEndpoint = true;
        Info.SupportsAmnezia = true;
        Info.IsExtended = true;
        Info.AmneziaProbed = true;
        Info.EndpointError = null;

        var flags = await RunCheckAsync(AwgEndpointProbeConfig("\"random_trailers\":\"probe\""));
        var message = flags.Message ?? "";
        Info.SupportsAwgTrailers = !flags.Ok
                                   && message.Contains("random_trailers", StringComparison.OrdinalIgnoreCase)
                                   && !message.Contains("unknown field", StringComparison.OrdinalIgnoreCase);
        Info.AwgTrailersProbed = Info.SupportsAwgTrailers;

        AppendLog(Info.SupportsAwgTrailers
            ? "Проверка ядра: эндпоинт type=\"awg\" с AmneziaWG 3.1 (random_trailers, disable_cookies)."
            : "Проверка ядра: эндпоинт type=\"awg\" есть, но флаги AmneziaWG 3.1 — нет (" + FirstLine(message) + ").");
        return true;
    }

    /// <summary>
    /// AmneziaWG 3.1 при вложенной схеме (блок <c>amnezia</c> у эндпоинта wireguard).
    ///
    /// Библиотека wireguard-go в расширенной сборке эти ключи принимает, но в конфиг sing-box
    /// они проброшены не во всех версиях. Это не косметика: random_trailers симметричен —
    /// сторона с включённым флагом дописывает к пакетам хендшейка хвост случайной длины,
    /// а сторона с выключенным отбрасывает такой пакет как нераспознанный
    /// (wireguard-go, device/receive.go). Туннель при этом «подключён», а трафика нет.
    /// </summary>
    private async Task ProbeNestedAwg31Async()
    {
        var probe = await RunCheckAsync(ProbeConfig("\"amnezia\":{\"random_trailers\":\"probe\"}"));
        if (probe.Ok)
        {
            Info.AwgTrailersProbed = false;
            AppendLog("Проверка ядра: random_trailers/disable_cookies (AmneziaWG 3.1) не поддерживаются.");
            return;
        }

        var message = probe.Message ?? "";
        var supported = message.Contains("random_trailers", StringComparison.OrdinalIgnoreCase)
                        && !message.Contains("unknown field", StringComparison.OrdinalIgnoreCase);

        Info.SupportsAwgTrailers = supported;
        Info.AwgTrailersProbed = supported;
        AppendLog(supported
            ? "Проверка ядра: AmneziaWG 3.1 (random_trailers) поддерживается."
            : "Проверка ядра: поддержка random_trailers не подтвердилась — " + FirstLine(message));
    }

    /// <summary>
    /// Тестовый конфиг с эндпоинтом <c>type:"awg"</c> (форк Amnezia). Значения рабочие, трафик никуда
    /// не идёт: порт 1 на 127.0.0.1, а <c>check</c> только собирает устройство и закрывает его.
    /// </summary>
    private string AwgEndpointProbeConfig(string? extraEndpointFields)
    {
        var modernDns = Info.VersionMajor > 1 || Info.VersionMinor >= 12;
        var dns = modernDns
            ? "{\"servers\":[{\"type\":\"local\",\"tag\":\"dns-local\"}],\"final\":\"dns-local\"}"
            : "{\"servers\":[{\"tag\":\"dns-local\",\"address\":\"local\"}],\"final\":\"dns-local\"}";

        return "{\"log\":{\"level\":\"fatal\"}," +
               "\"dns\":" + dns + "," +
               "\"endpoints\":[{" +
               "\"type\":\"awg\"," +
               "\"tag\":\"probe\"," +
               "\"useIntegratedTun\":false," +
               "\"address\":[\"10.255.255.1/32\"]," +
               "\"private_key\":\"" + ProbePrivateKey + "\"," +
               "\"mtu\":1280," +
               "\"jc\":3,\"s1\":44," +
               "\"peers\":[{" +
               "\"address\":\"127.0.0.1\",\"port\":1," +
               "\"public_key\":\"" + ProbePublicKey + "\"," +
               "\"allowed_ips\":[\"0.0.0.0/0\"]" +
               "}]" +
               (string.IsNullOrEmpty(extraEndpointFields) ? "" : "," + extraEndpointFields) +
               "}]," +
               "\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]," +
               "\"route\":{\"final\":\"direct\",\"default_domain_resolver\":\"dns-local\"}}";
    }

    /// <summary>
    /// Тестовый конфиг для <c>sing-box check</c>: эндпоинт WireGuard в петлю, без запуска и без прав.
    /// Формат блока DNS зависит от версии ядра (в 1.12 он сменился).
    /// </summary>
    private string ProbeConfig(string? extraEndpointFields)
    {
        var modernDns = Info.VersionMajor > 1 || Info.VersionMinor >= 12;
        var dns = modernDns
            ? "{\"servers\":[{\"type\":\"local\",\"tag\":\"dns-local\"}],\"final\":\"dns-local\"}"
            : "{\"servers\":[{\"tag\":\"dns-local\",\"address\":\"local\"}],\"final\":\"dns-local\"}";

        return "{\"log\":{\"level\":\"fatal\"}," +
               "\"dns\":" + dns + "," +
               "\"endpoints\":[{" +
               "\"type\":\"wireguard\"," +
               "\"tag\":\"probe\"," +
               "\"address\":[\"10.255.255.1/32\"]," +
               "\"private_key\":\"" + ProbePrivateKey + "\"," +
               "\"mtu\":1280," +
               "\"peers\":[{" +
               "\"address\":\"127.0.0.1\",\"port\":1," +
               "\"public_key\":\"" + ProbePublicKey + "\"," +
               "\"allowed_ips\":[\"0.0.0.0/0\"]" +
               "}]" +
               (string.IsNullOrEmpty(extraEndpointFields) ? "" : "," + extraEndpointFields) +
               "}]," +
               "\"outbounds\":[{\"type\":\"direct\",\"tag\":\"direct\"}]," +
               "\"route\":{\"final\":\"direct\",\"default_domain_resolver\":\"dns-local\"}}";
    }

    private static string FirstLine(string? text)
    {
        if (string.IsNullOrWhiteSpace(text)) return "";
        var line = text.Split('\n').FirstOrDefault(l => !string.IsNullOrWhiteSpace(l)) ?? "";
        line = line.Trim();
        return line.Length > 400 ? line[..400] + "…" : line;
    }

    // ------------------------------------------------------------------ config
    public async Task<(bool Ok, string Message)> ValidateAsync(string configJson) =>
        await RunCheckAsync(configJson, "config.check.json");

    /// <summary>
    /// Запуск <c>sing-box check</c> на отдельном временном файле. Ничего не стартует и прав не требует:
    /// эндпоинт WireGuard при проверке собирается в пользовательском стеке (gvisor), а не в системе.
    /// </summary>
    private async Task<(bool Ok, string Message)> RunCheckAsync(string configJson, string fileName = "config.probe.json",
        int timeoutMs = 20000)
    {
        if (!Info.Exists) return (false, "Ядро не найдено");

        AppPaths.EnsureCreated();
        var probe = Path.Combine(AppPaths.RuntimeDir, fileName);
        try
        {
            await File.WriteAllTextAsync(probe, configJson, new UTF8Encoding(false));

            var psi = new ProcessStartInfo
            {
                FileName = Info.Path,
                Arguments = $"check -c \"{probe}\" -D \"{AppPaths.RuntimeDir}\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            using var p = Process.Start(psi);
            if (p is null) return (false, "Не удалось запустить ядро");

            var stdoutTask = p.StandardOutput.ReadToEndAsync();
            var stderrTask = p.StandardError.ReadToEndAsync();

            using var cts = new CancellationTokenSource(timeoutMs);
            try
            {
                await p.WaitForExitAsync(cts.Token);
            }
            catch (OperationCanceledException)
            {
                try { p.Kill(entireProcessTree: true); } catch { /* ignore */ }
                // потоки вывода не ждём бесконечно: их может держать потомок ядра, который уже не выйдет
                try { await Task.WhenAny(Task.WhenAll(stdoutTask, stderrTask), Task.Delay(2000)); } catch { /* ignore */ }
                return (false, "Проверка ядра прервана по таймауту");
            }

            var stdout = await stdoutTask;
            var stderr = await stderrTask;

            if (p.ExitCode == 0) return (true, "OK");

            var message = string.IsNullOrWhiteSpace(stderr) ? stdout : stderr + "\n" + stdout;
            return (false, message.Trim());
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
        finally
        {
            try { if (fileName != "config.check.json" && File.Exists(probe)) File.Delete(probe); } catch { /* ignore */ }
        }
    }

    // ------------------------------------------------------------------- start
    public async Task StartAsync(string configJson, int clashPort)
    {
        if (!Info.Exists) throw new InvalidOperationException("Ядро sing-box не найдено");

        // Гонка с удалением wintun-адаптера: при переключении режимов TUN остановка ядра удаляет
        // сетевой адаптер асинхронно (Windows завершает удаление ещё секунду-две), и мгновенный
        // повторный запуск падает: CreateAdapter видит ещё живой адаптер («Cannot create a file
        // when that file already exists»), OpenAdapter — уже удалённый («Element not found»), и
        // ядро выходит с FATAL «configure tun interface». Чуть позже удаление завершается, и
        // создание проходит — ровно то, что пользователь делает вручную («подождать и снова»).
        // На этот и подобные временные сбои повторяем запуск с паузой (имя TUN-адаптера каждый
        // раз новое — см. StartAttemptAsync).
        int[] retryDelaysMs = { 3000, 5000 };
        for (var attempt = 1; ; attempt++)
        {
            await StartAttemptAsync(configJson, clashPort);
            if (State == ConnectionState.Running) return;
            if (attempt > retryDelaysMs.Length) return;

            var delayMs = retryDelaysMs[attempt - 1];
            AppendLog($"Ядро не поднялось — похоже, ресурс ещё освобождается после остановки. " +
                      $"Повтор запуска через {delayMs / 1000} с (попытка {attempt + 1} из {retryDelaysMs.Length + 1})…");
            await Task.Delay(delayMs);
        }
    }

    /// <summary>Одна попытка запуска ядра: gRPC-режим с автоматическим откатом на run -c.</summary>
    private async Task StartAttemptAsync(string configJson, int clashPort)
    {
        await StopAsync();

        // Имя TUN-адаптера — новое на каждую попытку: адаптер предыдущего запуска может ещё
        // удаляться драйвером wintun, и его имя использовать нельзя (см. SingBoxConfigBuilder).
        configJson = RefreshTunAdapterName(configJson);

        AppPaths.EnsureCreated();

        // TUN-режим: снимаем остаточный wintun-адаптер прошлого сеанса (после Kill ядро
        // не успевает его удалить, и следующий старт падает с FATAL «adapter already exists»).
        if (TunCleanup.ConfigHasTun(configJson))
        {
            var cleanupError = TunCleanup.PrepareForTun();
            if (cleanupError is not null)
                AppendLog("Очистка TUN-адаптера: " + cleanupError);
        }

        await File.WriteAllTextAsync(AppPaths.RuntimeConfig, configJson, new UTF8Encoding(false));
        _clashPort = clashPort;

        // Ядро из исходников nekobox умеет gRPC-режим — основной путь для GoreBox-сборки.
        // Любой сбой на этом пути (старый бинарник, порт, разбор конфига) — автоматический
        // откат на обычный запуск run -c, как раньше.
        if (Info.SupportsNekoRpc)
        {
            try
            {
                await StartViaRpcAsync(configJson);
                return;
            }
            catch (Exception ex)
            {
                AppendLog("gRPC-режим ядра недоступен (" + FirstLine(ex.Message) + ") — пробуем обычный запуск.");
                try { await StopAsync(); } catch { /* ignore */ }
            }
        }

        // gRPC-попытка могла успеть создать TUN-адаптер и упасть — его имя уже «горит»
        // (драйвер удаляет адаптер). Для запуска run -c берём свежее имя адаптера.
        configJson = RefreshTunAdapterName(configJson);
        await File.WriteAllTextAsync(AppPaths.RuntimeConfig, configJson, new UTF8Encoding(false));

        var psi = new ProcessStartInfo
        {
            FileName = Info.Path,
            Arguments = $"run -c \"{AppPaths.RuntimeConfig}\" -D \"{AppPaths.RuntimeDir}\"",
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };

        SetState(ConnectionState.Starting);

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) => AppendLog(e.Data);
        process.ErrorDataReceived += (_, e) => AppendLog(e.Data);
        process.Exited += (_, _) =>
        {
            if (State != ConnectionState.Stopping)
            {
                AppendLog($"Ядро неожиданно завершилось (код {SafeExitCode(process)}).");
                SetState(ConnectionState.Error);
            }
            else
            {
                SetState(ConnectionState.Stopped);
            }
        };

        lock (_sync) _process = process;

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        var (up, apiUp) = await WaitForReadyAsync(process, configJson, clashPort);
        if (up)
        {
            SetState(ConnectionState.Running);
            AppendLog(apiUp ? "Ядро запущено." : "Ядро запущено (API не ответил, работаем без статистики).");
        }
        else
        {
            SetState(ConnectionState.Error);
        }
    }

    private static int SafeExitCode(Process p)
    {
        try { return p.ExitCode; } catch { return -1; }
    }

    /// <summary>
    /// Ждёт готовности ядра и возвращает (ядро живо и готово, отвечает ли clash API).
    /// Ключевой момент: TUN-адаптер стартует дольше всех (его создание может ждать завершения
    /// удаления адаптера предыдущего запуска — до ~15 с), при этом clash API уже отвечает.
    /// Если считать ядро запущенным по одному API, получаем ложное «Подключено» с последующим
    /// FATAL («Ошибка ядра» уже после подключения) и без автоповтора. Поэтому при TUN-конфиге
    /// успех фиксируется только когда адаптер реально появился в системе; выход процесса
    /// в любой момент — это ошибка попытки (её подхватит повтор в StartAsync).
    /// </summary>
    private async Task<(bool Up, bool ApiUp)> WaitForReadyAsync(Process process, string configJson, int clashPort)
    {
        var tunName = TunAdapterNameRegex().Match(configJson).Groups["name"].Value;
        var wantTun = tunName.Length > 0 || configJson.Contains("tun-in", StringComparison.Ordinal);

        using var api = new ClashApiClient();
        var apiUp = false;
        // TUN ждать дольше: создание адаптера упирается в драйвер wintun
        var deadline = DateTime.UtcNow.AddSeconds(wantTun ? 25 : 6);
        while (DateTime.UtcNow < deadline)
        {
            if (process.HasExited)
            {
                // даём stderr/stdout доехать из асинхронных обработчиков, чтобы FATAL попал в лог
                await Task.Delay(400);
                return (false, apiUp);
            }
            if (!apiUp && await api.PingAsync(clashPort)) apiUp = true;
            var tunReady = !wantTun || TunAdapterExists(tunName) || TunStartLogged();
            if (apiUp && tunReady)
            {
                // короткая выдержка: ошибки настройки адаптера (IP, стек) сыпятся сразу после создания
                await Task.Delay(1500);
                return (!process.HasExited, apiUp);
            }
            await Task.Delay(250);
        }

        return (!process.HasExited, apiUp);
    }

    /// <summary>Ядро сообщило, что TUN-inbound реально поднят
    /// («inbound/tun[tun-in]: started at …») — надёжный сигнал готовности TUN.</summary>
    private bool TunStartLogged()
    {
        lock (_sync)
        {
            return _recentLog.TakeLast(80).Any(l =>
                l.Contains("started at ", StringComparison.Ordinal) &&
                l.Contains("tun[", StringComparison.Ordinal));
        }
    }

    /// <summary>Появился ли в системе сетевой интерфейс с таким именем (wintun-адаптер создан).</summary>
    private static bool TunAdapterExists(string adapterName)
    {
        try
        {
            foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
                if (string.Equals(nic.Name, adapterName, StringComparison.OrdinalIgnoreCase))
                    return true;
        }
        catch { /* ignore */ }
        return false;
    }

    /// <summary>
    /// Запуск через gRPC-режим nekobox_core: процесс <c>sing-box nekobox --token … --port …</c>
    /// слушает 127.0.0.1, конфиг уходит по RPC Start (та же семантика, что у run -c: разбор,
    /// instance.Start(), clash API поднимается внутри инстанса). Логи ядра идут в neko.log
    /// в RuntimeDir (рабочая директория процесса) и дублируются в кольцо приложения.
    /// </summary>
    private async Task StartViaRpcAsync(string configJson)
    {
        var token = Guid.NewGuid().ToString("N");
        var port = FreePort.Get();

        var psi = new ProcessStartInfo
        {
            FileName = Info.Path,
            Arguments = $"nekobox --token {token} --port {port}",
            WorkingDirectory = AppPaths.RuntimeDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8
        };

        SetState(ConnectionState.Starting);

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        process.OutputDataReceived += (_, e) => AppendLog(e.Data);
        process.ErrorDataReceived += (_, e) => AppendLog(e.Data);
        process.Exited += (_, _) =>
        {
            if (State != ConnectionState.Stopping)
            {
                AppendLog($"Ядро неожиданно завершилось (код {SafeExitCode(process)}).");
                SetState(ConnectionState.Error);
            }
            else
            {
                SetState(ConnectionState.Stopped);
            }
        };

        lock (_sync) _process = process;

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        // ждём, пока gRPC-порт откроется (RunCore: SetupLog → Listen), до 5 секунд
        var listening = false;
        for (var i = 0; i < 50; i++)
        {
            if (process.HasExited) break;
            if (IsPortListening(port))
            {
                listening = true;
                break;
            }
            await Task.Delay(100);
        }

        if (!listening)
            throw new InvalidOperationException(process.HasExited
                ? $"gRPC-хост завершился до старта (код {SafeExitCode(process)})"
                : "gRPC-порт ядра не открылся");

        var rpc = new NekoCoreRpcClient($"http://127.0.0.1:{port}", token);
        string? rpcError;
        try
        {
            rpcError = await rpc.StartAsync(configJson, new[] { "proxy" });
        }
        catch
        {
            rpc.Dispose();
            throw;
        }

        if (!string.IsNullOrEmpty(rpcError))
        {
            rpc.Dispose();
            throw new InvalidOperationException("ядро отклонило конфиг: " + rpcError);
        }

        lock (_sync) _rpc = rpc;

        // clash API живёт внутри инстанса — та же проверка готовности, что и в run -c
        var (up, apiUp) = await WaitForReadyAsync(process, configJson, _clashPort);
        if (up)
        {
            SetState(ConnectionState.Running);
            AppendLog(apiUp
                ? "Ядро запущено (gRPC nekobox)."
                : "Ядро запущено (gRPC nekobox; API не ответил, работаем без статистики).");
        }
        else
        {
            SetState(ConnectionState.Error);
        }
    }

    private static bool IsPortListening(int port)
    {
        try
        {
            using var client = new TcpClient();
            client.Connect(IPAddress.Loopback, port);
            return true;
        }
        catch
        {
            return false;
        }
    }

    /// <summary>
    /// Остановка ядра. Сначала мягкая команда по gRPC (не дольше 3 с), затем завершение процесса
    /// (не дольше 5 с). Все ожидания ограничены по времени: остановка не может зависнуть навсегда.
    /// Параллельные вызовы выстраиваются в очередь: второй дождётся завершения первого.
    /// </summary>
    public async Task StopAsync()
    {
        await _stopGate.WaitAsync();
        try
        {
            await StopCoreAsync();
        }
        catch (Exception ex)
        {
            AppendLog("Ошибка остановки ядра: " + FirstLine(ex.Message));
            SetState(ConnectionState.Stopped);   // состояние всё равно должно стать «Отключено»
        }
        finally
        {
            _stopGate.Release();
        }
    }

    private async Task StopCoreAsync()
    {
        Process? process;
        NekoCoreRpcClient? rpc;
        lock (_sync)
        {
            process = _process;
            rpc = _rpc;
            _rpc = null;
        }
        if (process is null && rpc is null) return;

        SetState(ConnectionState.Stopping);

        // Сначала просим инстанс остановиться аккуратно (закрывает сокеты/туннели),
        // затем убиваем процесс-хост целиком — как и в обычном режиме.
        if (rpc is not null)
        {
            try { await rpc.StopAsync(); } catch { /* ignore */ }
            rpc.Dispose();
        }

        if (process is not null)
        {
            await KillProcessAsync(process, TimeSpan.FromSeconds(5));
            lock (_sync)
            {
                if (ReferenceEquals(_process, process)) _process = null;
            }
        }

        AppendLog("Ядро остановлено.");
        SetState(ConnectionState.Stopped);
    }

    /// <summary>
    /// Завершает процесс ядра и ждёт его выхода не дольше <paramref name="timeout"/>.
    /// Process.WaitForExitAsync здесь не используем: он дополнительно ждёт закрытия потоков stdout/stderr
    /// без таймаута, а их может держать живой потомок ядра, и тогда ожидание не заканчивается.
    /// Событие Exited подписываем до проверки HasExited, чтобы не пропустить выход процесса.
    /// </summary>
    /// <summary>
    /// Мягкая остановка sing-box через Ctrl+C: ядро успевает снять маршруты и удалить
    /// wintun-адаптер само. Без этого после Kill адаптер остаётся, и следующий старт
    /// падает с «create adapter: file already exists».
    /// </summary>
    private static async Task<bool> TryGracefulShutdownAsync(Process process, TimeSpan timeout)
    {
        try
        {
            FreeConsole();
            if (!AttachConsole((uint)process.Id)) return false;
            try
            {
                // игнорируем Ctrl+C в своей консоли, пока шлём его ядру
                SetConsoleCtrlHandler(null, true);
                if (!GenerateConsoleCtrlEvent(CtrlCEvent, 0)) return false;
                var wait = process.WaitForExitAsync();
                return await Task.WhenAny(wait, Task.Delay(timeout)) == wait;
            }
            finally
            {
                FreeConsole();
            }
        }
        catch
        {
            return false;
        }
    }

    private delegate bool ConsoleCtrlHandlerRoutine(uint ctrlType);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")] private static extern bool FreeConsole();
    [System.Runtime.InteropServices.DllImport("kernel32.dll")] private static extern bool AttachConsole(uint dwProcessId);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")] private static extern bool SetConsoleCtrlHandler(ConsoleCtrlHandlerRoutine? handler, bool add);
    [System.Runtime.InteropServices.DllImport("kernel32.dll")] private static extern bool GenerateConsoleCtrlEvent(uint dwCtrlEvent, uint dwProcessGroupId);
    private const uint CtrlCEvent = 0;

    private async Task KillProcessAsync(Process process, TimeSpan timeout)
    {
        var exited = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        EventHandler onExited = (_, _) => exited.TrySetResult();
        try
        {
            process.Exited += onExited;   // EnableRaisingEvents включён при создании процесса
            if (!process.HasExited)
            {
                // Сначала мягкая остановка (Ctrl+C), по таймауту — Kill как раньше.
                if (!await TryGracefulShutdownAsync(process, TimeSpan.FromSeconds(4)))
                {
                    try
                    {
                        process.Kill(entireProcessTree: true);
                    }
                    catch (Exception ex)
                    {
                        AppendLog("Не удалось завершить процесс ядра: " + FirstLine(ex.Message));
                    }

                    if (!process.HasExited)
                        await Task.WhenAny(exited.Task, Task.Delay(timeout));
                }
            }

            if (!process.HasExited)
                AppendLog("Процесс ядра не завершился за " + (int)timeout.TotalSeconds + " с.");
        }
        catch (Exception ex)
        {
            AppendLog("Ошибка при остановке ядра: " + FirstLine(ex.Message));
        }
        finally
        {
            process.Exited -= onExited;
            try { process.CancelOutputRead(); } catch { /* поток вывода уже остановлен */ }
            try { process.CancelErrorRead(); } catch { /* поток ошибок уже остановлен */ }
            try { process.Dispose(); } catch { /* ignore */ }
        }
    }

    public async Task<int> MeasureDelayAsync(string proxyTag = "proxy", int timeoutMs = 6000)
    {
        if (State != ConnectionState.Running || _clashPort == 0) return -1;
        using var api = new ClashApiClient();
        return await api.MeasureDelayAsync(_clashPort, proxyTag, timeoutMs);
    }

    /// <summary>
    /// Подменяет имя TUN-адаптера в конфиге на свежее (случайный суффикс). Каждый запуск ядра
    /// должен получать новое имя: старый адаптер после остановки/падения ядра удаляется драйвером
    /// wintun асинхронно, и повторное использование имени даёт FATAL «configure tun interface:
    /// (create adapter: … already exists | open existing adapter: Element not found)».
    /// </summary>
    private static string RefreshTunAdapterName(string configJson)
    {
        var name = "\"interface_name\":\"GoreBox-" + Guid.NewGuid().ToString("N")[..8] + "\"";
        var m = TunAdapterNameRegex().Match(configJson);
        if (m.Success)
            return string.Concat(configJson.AsSpan(0, m.Index), name, configJson.AsSpan(m.Index + m.Length));
        // Поля нет (конфиг собран старой версией билдера) — вписываем его в TUN-inbound по тегу.
        var tag = TunTagRegex().Match(configJson);
        if (tag.Success)
            return string.Concat(configJson.AsSpan(0, tag.Index + tag.Length), ",", name, configJson.AsSpan(tag.Index + tag.Length));
        return configJson;
    }

    [GeneratedRegex("\"tag\"\\s*:\\s*\"tun-in\"")]
    private static partial Regex TunTagRegex();

    private void SetState(ConnectionState state)
    {
        State = state;
        StateChanged?.Invoke(state);
    }

    private void AppendLog(string? line)
    {
        if (string.IsNullOrWhiteSpace(line)) return;
        lock (_sync)
        {
            _recentLog.Add(line);
            if (_recentLog.Count > 500) _recentLog.RemoveRange(0, 200);
        }
        LogReceived?.Invoke(line);
    }

    public string RecentLogText()
    {
        lock (_sync) return string.Join(Environment.NewLine, _recentLog.TakeLast(25));
    }

    public void Dispose()
    {
        try
        {
            _rpc?.Dispose();
        }
        catch { /* ignore */ }
        try
        {
            if (_process is { HasExited: false }) _process.Kill(entireProcessTree: true);
        }
        catch { /* ignore */ }
    }
}
