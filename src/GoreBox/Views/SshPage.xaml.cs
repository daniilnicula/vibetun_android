using System.Text;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Documents;
using System.Windows.Input;
using System.Windows.Media;
using GoreBox.Models;
using GoreBox.Services;
using Renci.SshNet;

namespace GoreBox.Views;

/// <summary>
/// Страница одного SSH-подключения: строка подключения и интерактивный терминал (PTY), как
/// в PuTTY. Ctrl+C прерывает процесс, Ctrl+Shift+V / Ctrl+V вставляют из буфера, Ctrl+Shift+C
/// копирует. Адрес и порт запоминаются за записью подключения (общие логин/пароль — в
/// настройках); в трей соединение НЕ рвётся, рвётся только при выходе или удалении записи.
/// </summary>
public partial class SshPage : UserControl
{
    private readonly SshEntry _entry;
    private SshClient? _client;
    private ShellStream? _shell;
    private bool _ready;
    private bool _initialized;   // страницу показывают многократно — инициализация один раз

    // состояние потокового разбора вывода терминала
    private int _esc;              // 0 — текст, 1 — ESC, 2 — CSI, 3 — OSC, 4 — ESC внутри OSC
    private bool _crPending;       // видели \r, ждём \n
    private Paragraph? _line;      // текущая (незаконченная) строка вывода

    public SshPage(SshEntry entry)
    {
        _entry = entry;
        InitializeComponent();
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        if (_initialized) return;   // повторные показы страницы не трогают живое соединение
        _initialized = true;

        HostBox.Text = _entry.Host;
        PortBox.Text = _entry.Port > 0 ? _entry.Port.ToString() : "22";

        _ready = true;
        var size = Math.Clamp(_entry.FontSize, 9, 20);
        FontBar.Alpha = (size - 9) / 11.0;
        // полоска красится акцентом — как прозрачность в пикере акцента
        var stored = App.Settings.Current.AccentColor;
        FontBar.BaseColor = !string.IsNullOrWhiteSpace(stored) && ThemeService.TryParseColor(stored!, out var c)
            ? c
            : ThemeService.DefaultAccent;
        ApplyFont(size);

        SetConnected(false);
        Write("GoreBox SSH — укажите адрес (user@host или host) и порт, затем кнопку подключения.",
            GetBrush("TextSecondary"));
    }

    // ------------------------------------------------------------- подключение

    private async void OnConnectClick(object sender, RoutedEventArgs e)
    {
        if (_client is { IsConnected: true }) return;

        var raw = HostBox.Text.Trim();
        if (raw.Length == 0)
        {
            Write("Укажите адрес: user@host или host.", GetBrush("Warning"));
            return;
        }

        var user = "";
        var host = raw;
        var at = raw.IndexOf('@');
        if (at >= 0)
        {
            user = raw[..at].Trim();
            host = raw[(at + 1)..].Trim();
        }
        if (host.Length == 0)
        {
            Write("После @ не указан хост.", GetBrush("Warning"));
            return;
        }

        if (!int.TryParse(PortBox.Text, out var port) || port is < 1 or > 65535) port = 22;
        PortBox.Text = port.ToString();

        _entry.Host = raw;
        _entry.Port = port;

        var owner = Window.GetWindow(this) ?? Application.Current.MainWindow;

        // логин свой для записи (шестерёнка в списке), важнее user@ из адреса.
        var storedLogin = _entry.User?.Trim() ?? "";
        if (storedLogin.Length > 0)
        {
            user = storedLogin;
        }

        // пароль свой для записи. Если логина или пароля нет — показываем окно с тем, что
        // известно: логин и пароль подставляются и редактируются разом, сохраняем за записью.
        var password = _entry.Password ?? "";
        if (user.Length == 0 || password.Length == 0)
        {
            var window = new Dialogs.SshCredentialsWindow($"SSH — {host}", user, password) { Owner = owner };
            if (window.ShowDialog() != true)
            {
                Write("Подключение отменено: нет логина или пароля.", GetBrush("Warning"));
                return;
            }
            user = window.User;
            password = window.Password;
            _entry.User = user;
            _entry.Password = password;
        }
        App.Settings.SaveSoon();

        Write($"Подключение к {user}@{host}:{port}…", GetBrush("TextSecondary"));
        SetConnected(false, busy: true);
        try
        {
            var conn = new ConnectionInfo(host, port, user,
                new PasswordAuthenticationMethod(user, password))
            {
                Timeout = TimeSpan.FromSeconds(12),
            };
            var client = new SshClient(conn);
            var shell = await Task.Run(() =>
            {
                client.Connect();
                return client.CreateShellStream("xterm-256color", 140, 40, 1280, 720, 4096);
            });

            _client = client;
            _shell = shell;
            SetConnected(true);
            AppLog.Write($"SSH: подключено к {user}@{host}:{port}");
            Write($"Подключено к {user}@{host}:{port}. Enter — ввод, Ctrl+C — прервать, " +
                  "Ctrl+Shift+V — вставить, Ctrl+Shift+C — копировать.", GetBrush("Accent"));
            StartReader(shell);
        }
        catch (Exception ex)
        {
            Write("Ошибка подключения: " + ex.Message, GetBrush("Warning"));
            AppLog.Write("SSH: ошибка подключения: " + ex.Message);
            SetConnected(false);
        }
    }

    private void OnDisconnectClick(object sender, RoutedEventArgs e) => Disconnect("Отключено.");

    private void Disconnect(string message)
    {
        var shell = _shell;
        _shell = null;
        var client = _client;
        _client = null;

        if (shell is not null || client is not null) AppLog.Write("SSH: соединение закрыто.");
        try { shell?.Dispose(); } catch { /* канал мог уже закрыться */ }
        if (client is not null)
        {
            try { client.Disconnect(); } catch { /* уже отключён */ }
            try { client.Dispose(); } catch { /* ignore */ }
        }

        SetConnected(false);
        if (message.Length > 0) Write(message, GetBrush("TextSecondary"));
    }

    /// <summary>Полное закрытие при выходе из приложения (в трей — не вызывается).</summary>
    public void Shutdown() => Disconnect("");

    private void SetConnected(bool connected, bool busy = false)
    {
        ConnectBtn.IsEnabled = !connected && !busy;
        DisconnectBtn.IsEnabled = connected;
        if (connected) Term.Focus();
    }

    // ------------------------------------------------------------- поток вывода

    private void StartReader(ShellStream shell)
    {
        _ = Task.Run(() =>
        {
            var buf = new byte[4096];
            var decoder = Encoding.UTF8.GetDecoder();
            var chars = new char[16384];
            try
            {
                while (true)
                {
                    var n = shell.Read(buf, 0, buf.Length);
                    if (n <= 0) break;
                    var count = decoder.GetChars(buf, 0, n, chars, 0);
                    if (count <= 0) continue;
                    var text = new string(chars, 0, count);
                    try { Dispatcher.Invoke(() => Feed(text)); }
                    catch { break; }   // окно уже закрыто
                }
            }
            catch { /* канал закрыт */ }
            finally
            {
                try
                {
                    Dispatcher.BeginInvoke(new Action(() =>
                    {
                        if (!ReferenceEquals(_shell, shell)) return;  // отключились сами
                        _shell = null;
                        var client = _client;
                        _client = null;
                        try { client?.Dispose(); } catch { /* ignore */ }
                        SetConnected(false);
                        Write("Соединение закрыто.", GetBrush("TextSecondary"));
                        AppLog.Write("SSH: соединение закрыто сервером.");
                    }));
                }
                catch { /* dispatcher завершён */ }
            }
        });
    }

    /// <summary>
    /// Разбор потока терминала: отбрасываем ANSI-последовательности (цвета/коды),
    /// \r\n считаем одним переводом, бэкспейс укорачивает строку. Полноэмуляции
    /// терминала нет — но читаемый текст и команды работают стабильно.
    /// </summary>
    private void Feed(string text)
    {
        foreach (var ch in text)
        {
            // состояние машины ANSI
            if (_esc != 0)
            {
                switch (_esc)
                {
                    case 1:                       // после ESC
                        if (ch == '[') { _esc = 2; continue; }
                        if (ch == ']') { _esc = 3; continue; }
                        _esc = 0; continue;       // прочие односимвольные — пропускаем
                    case 2:                       // CSI: до финального байта 0x40..0x7E
                        if (ch >= '\u0040' && ch <= '\u007e') _esc = 0;
                        continue;
                    case 3:                       // OSC: до BEL или ST
                        if (ch == '\a') { _esc = 0; }
                        else if (ch == '\u001b') { _esc = 4; }
                        continue;
                    case 4:
                        _esc = ch == '\\' ? 0 : 3;
                        continue;
                }
            }

            if (ch == '\u001b') { _esc = 1; continue; }

            // одиночный \r без \n — тоже перевод строки (без колонок это лучшее приближение)
            if (_crPending && ch != '\n')
            {
                _crPending = false;
                NewLine();
            }

            switch (ch)
            {
                case '\r':
                    _crPending = true;
                    continue;
                case '\n':
                    _crPending = false;
                    NewLine();
                    continue;
                case '\b':
                    TrimLast();
                    continue;
                case '\t':
                    Append("    ");
                    continue;
                case '\a':
                case '\0':
                    continue;
            }

            if (ch < ' ') continue;   // прочие управляющие — пропускаем
            Append(ch.ToString());
        }
    }

    private void Append(string s)
    {
        if (_line is null)
        {
            _line = new Paragraph { Margin = new Thickness(0, 1, 0, 1) };
            Term.Document.Blocks.Add(_line);
        }
        _line.Inlines.Add(new Run(s));
        PruneAndScroll();
    }

    private void NewLine()
    {
        if (_line is null)
        {
            // пустая строка — иначе подряд идущие \n схлопнутся
            Term.Document.Blocks.Add(new Paragraph { Margin = new Thickness(0, 1, 0, 1) });
        }
        _line = null;
        PruneAndScroll();
    }

    private void TrimLast()
    {
        if (_line is null || _line.Inlines.Count == 0) return;
        var last = _line.Inlines.Last();
        if (last is not Run run) return;
        if (run.Text.Length > 1) run.Text = run.Text[..^1];
        else _line.Inlines.Remove(run);
    }

    private void Write(string text, Brush? brush = null)
    {
        _line = null;   // заканчиваем текущую строку — дальше пойдёт новый абзац
        var para = new Paragraph { Margin = new Thickness(0, 1, 0, 1) };
        var run = new Run(text);
        if (brush is not null) run.Foreground = brush;
        para.Inlines.Add(run);
        Term.Document.Blocks.Add(para);
        PruneAndScroll();
    }

    private void PruneAndScroll()
    {
        while (Term.Document.Blocks.Count > 400 && Term.Document.Blocks.First() != _line)
            Term.Document.Blocks.Remove(Term.Document.Blocks.First());
        Term.ScrollToEnd();
    }

    // ------------------------------------------------------------- ввод

    private void OnTermKeyDown(object sender, KeyEventArgs e)
    {
        var connected = _client is { IsConnected: true } && _shell is not null;
        var ctrl = Keyboard.Modifiers.HasFlag(ModifierKeys.Control);
        var shift = Keyboard.Modifiers.HasFlag(ModifierKeys.Shift);

        // вставка из буфера
        if ((ctrl && e.Key == Key.V) || (shift && e.Key == Key.Insert))
        {
            e.Handled = true;
            if (!connected) return;
            var text = Clipboard.GetText();
            if (!string.IsNullOrEmpty(text))
                Send(text.Replace("\r\n", "\n").Replace('\r', '\n').Replace("\n", "\r"));
            return;
        }

        // копирование выделения (Ctrl+Shift+C явно, Ctrl+C — когда есть выделение)
        if (ctrl && shift && e.Key == Key.C)
        {
            e.Handled = true;
            if (!Term.Selection.IsEmpty) ApplicationCommands.Copy.Execute(null, Term);
            return;
        }

        if (!connected) return;

        switch (e.Key)
        {
            case Key.Enter: Send("\r"); e.Handled = true; break;
            case Key.Back: Send("\u007f"); e.Handled = true; break;
            case Key.Tab: Send("\t"); e.Handled = true; break;
            case Key.Escape: Send("\u001b"); e.Handled = true; break;
            case Key.Left: Send("\u001b[D"); e.Handled = true; break;
            case Key.Right: Send("\u001b[C"); e.Handled = true; break;
            case Key.Up: Send("\u001b[A"); e.Handled = true; break;
            case Key.Down: Send("\u001b[B"); e.Handled = true; break;
            case Key.Home: Send("\u001b[H"); e.Handled = true; break;
            case Key.End: Send("\u001b[F"); e.Handled = true; break;
            case Key.Delete: Send("\u001b[3~"); e.Handled = true; break;
            case Key.PageUp: Send("\u001b[5~"); e.Handled = true; break;
            case Key.PageDown: Send("\u001b[6~"); e.Handled = true; break;
            default:
                // Ctrl+C без выделения = SIGINT; Ctrl+D = EOF
                if (ctrl && !shift && e.Key == Key.C && Term.Selection.IsEmpty)
                {
                    Send("\u0003");
                    e.Handled = true;
                }
                else if (ctrl && e.Key == Key.D)
                {
                    Send("\u0004");
                    e.Handled = true;
                }
                break;
        }
    }

    private void OnTermTextInput(object sender, TextCompositionEventArgs e)
    {
        if (_client is not { IsConnected: true } || _shell is null) return;
        if (string.IsNullOrEmpty(e.Text)) return;
        Send(e.Text);
        e.Handled = true;
    }

    private void Send(string text)
    {
        var shell = _shell;
        if (shell is null) return;
        var bytes = Encoding.UTF8.GetBytes(text);
        try
        {
            shell.Write(bytes, 0, bytes.Length);
            shell.Flush();
        }
        catch (Exception ex)
        {
            Write("Канал закрыт: " + ex.Message, GetBrush("Warning"));
            Disconnect("");
        }
    }

    // ------------------------------------------------------------- размер текста

    private void OnFontBar()
    {
        if (!_ready) return;
        var size = Math.Round(9 + FontBar.Alpha * 11);
        FontBar.Alpha = (size - 9) / 11.0;   // щёлкаем по целым размерам шрифта
        ApplyFont(size);
    }

    private void ApplyFont(double size)
    {
        Term.FontSize = size;
        FontValue.Text = size.ToString("0");
        _entry.FontSize = size;
        App.Settings.SaveSoon();
    }

    private static SolidColorBrush GetBrush(string key)
    {
        if (Application.Current?.TryFindResource(key) is SolidColorBrush brush) return brush;
        return Brushes.Gray;
    }
}
