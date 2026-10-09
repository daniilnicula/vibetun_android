using System.Diagnostics;
using System.Text.Json;

namespace GoreBox.Services;

/// <summary>
/// Уборка остаточного wintun-адаптера перед запуском ядра в TUN-режиме.
/// sing-box удаляет адаптер только при корректной остановке; после снятия процесса
/// адаптер остаётся, и следующий запуск падает с FATAL
/// «create adapter: Cannot create a file when that file already exists».
/// Подход как в nekobox: перед стартом удаляем свой адаптер (TUN-режим и так требует админа).
/// </summary>
public static class TunCleanup
{
    /// <summary>Имена адаптеров уникальны на запуск («GoreBox-xxxxxxxx», см. SingBoxConfigBuilder),
    /// поэтому чистим по маске — старые «GoreBox» и оборванные «GoreBox-*».</summary>
    public const string AdapterNamePattern = "GoreBox*";

    /// <summary>true, если в конфиге есть TUN-инбаунд — тогда перед стартом чистим адаптер.</summary>
    public static bool ConfigHasTun(string configJson)
    {
        try
        {
            using var doc = JsonDocument.Parse(configJson);
            if (!doc.RootElement.TryGetProperty("inbounds", out var inbounds)) return false;
            foreach (var inbound in inbounds.EnumerateArray())
                if (inbound.TryGetProperty("type", out var t) && t.GetString() == "tun")
                    return true;
        }
        catch { /* разбор не удался — не мешаем запуску */ }
        return false;
    }

    /// <summary>Удаляет остаточный адаптер с нашим именем, если он есть. Возвращает null при успехе/отсутствии.</summary>
    public static string? PrepareForTun()
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "powershell.exe",
                Arguments = $"-NoProfile -NonInteractive -Command \"Get-NetAdapter -Name '{AdapterNamePattern}' -ErrorAction SilentlyContinue | Remove-NetAdapter -Confirm:$false\"",
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var p = Process.Start(psi);
            if (p == null) return null;
            if (!p.WaitForExit(10000)) return "таймаут очистки адаптера";
            var err = p.StandardError.ReadToEnd().Trim();
            return p.ExitCode == 0 ? null : err; // чаще всего адаптера просто нет — это норма
        }
        catch (Exception ex)
        {
            return $"{ex.GetType().Name}: {ex.Message}";
        }
    }
}
