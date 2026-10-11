using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using GoreBox.Models;
using GoreBox.Services;
using GoreBox.Utils;
using GoreBox.ViewModels;

namespace GoreBox.Views;

public partial class MainWindow : Window
{
    private MainViewModel? _vm;
    private bool _reallyExit;
    private bool _closed;
    private DialogService? _dialogs;
    private NavSectionMenu<PanelEntry>? _panelMenu;
    private NavSectionMenu<SshEntry>? _sshMenu;

    public MainWindow()
    {
        InitializeComponent();
        PreviewKeyDown += OnPreviewKeyDown;
        ApplyNavOrder(App.Settings.Current.NavOrder);
        // страховка для «Развернуть»: после изменения состояния окна дожимаем его до рабочей
        // области (см. ClampMaximizedToWorkArea) — WM_GETMINMAXINFO не всегда этого добивается
        StateChanged += (_, _) =>
        {
            ClampMaximizedToWorkArea();
            UpdateRootChrome();   // в развёрнутом состоянии уголки прямые
        };

        // выпадающие списки «Панели»/«SSH» в навигации
        _dialogs = new DialogService(this);
        _panelMenu = new NavSectionMenu<PanelEntry>(PanelMenu, () => App.Settings.Current.Panels,
            name => new PanelEntry { Name = name }, OpenPanelEntry, OnPanelEntryDeleted,
            () => App.Settings.SaveSoon(), _dialogs, "Панель", "Добавить панель");
        _sshMenu = new NavSectionMenu<SshEntry>(SshMenu, () => App.Settings.Current.SshItems,
            name => new SshEntry { Name = name }, OpenSshEntry, OnSshEntryDeleted,
            () => App.Settings.SaveSoon(), _dialogs, "SSH", "Добавить подключение",
            OnSshEntryConfigure, "Логин и пароль");

        // раскрытые списки остаются раскрытыми и после перезапуска
        if (App.Settings.Current.NavPanelsExpanded)
        {
            PanelMenu.Visibility = Visibility.Visible;
            PanelChevronRot.Angle = 180;
        }
        if (App.Settings.Current.NavSshExpanded)
        {
            SshMenu.Visibility = Visibility.Visible;
            SshChevronRot.Angle = 180;
        }
    }

    public async Task InitializeAsync()
    {
        _vm = new MainViewModel(new DialogService(this));
        DataContext = _vm;

        // подсветка записей в списках следует за открытой вкладкой
        _vm.PropertyChanged += (_, e) =>
        {
            if (e.PropertyName == nameof(MainViewModel.ActiveTab)) SyncMenuSelection();
        };

        // вызовы приходят из трея (обычный поток WinForms-сообщений) — защищаемся от исключений,
        // иначе WPF/WinForms покажет системный диалог сбоя
        _vm.ExitRequested += () => Dispatcher.BeginInvoke(new Action(() => Safe(() => ExitApplication())));
        _vm.RestartRequested += () => Dispatcher.BeginInvoke(new Action(() => Safe(() => ExitApplication(restart: true))));
        _vm.ShowWindowRequested += () => Dispatcher.BeginInvoke(new Action(() => Safe(ShowFromTray)));

        UpdateLayout();
        var settings = App.Settings.Current;
        if (settings.WindowWidth > 600) Width = settings.WindowWidth;
        if (settings.WindowHeight > 400) Height = settings.WindowHeight;
        if (settings.WindowLeft is { } left && settings.WindowTop is { } top &&
            left > SystemParameters.VirtualScreenLeft - 50 && top > SystemParameters.VirtualScreenTop - 50 &&
            left < SystemParameters.VirtualScreenLeft + SystemParameters.VirtualScreenWidth - 100 &&
            top < SystemParameters.VirtualScreenTop + SystemParameters.VirtualScreenHeight - 100)
        {
            WindowStartupLocation = WindowStartupLocation.Manual;
            Left = left;
            Top = top;
        }
        if (string.Equals(settings.WindowState, "Maximized", StringComparison.OrdinalIgnoreCase))
            WindowState = WindowState.Maximized;

        await _vm.InitializeAsync();
        RestoreSelectedEntries();

        Opacity = 0;
        BeginAnimation(OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(220))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        });
    }

    public void PrepareForSystemShutdown() => _vm?.PrepareForSystemShutdown();

    /// <summary>Показать окно из трея (ЛКМ по иконке). После закрытия окна ничего не делаем.</summary>
    public void ShowFromTray()
    {
        if (_closed) return;

        if (!IsVisible) Show();
        // возврат из трея — подгружаем страницу выбранной панели обратно
        PanelTab?.Restore();
        if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;

        Activate();
        Topmost = true;
        Topmost = false;
        Focus();
    }

    /// <summary>Выполнить действие, не давая исключению уйти в цикл сообщений WinForms.</summary>
    private static void Safe(Action action)
    {
        try
        {
            action();
        }
        catch (Exception ex)
        {
            App.LogCrash(ex);
        }
    }

    private void OnPreviewKeyDown(object sender, KeyEventArgs e)
    {
        if (_vm is null) return;

        if (e.Key == Key.V && Keyboard.Modifiers == ModifierKeys.Control && _vm.ActiveTab == "profiles")
        {
            _vm.ActiveTab = "profiles";
            _vm.Profiles.AddFromClipboardCommand.Execute(null);
            e.Handled = true;
        }
    }

    // ------------------------------------------------------------- порядок вкладок
    private ListBoxItem? _navPressed;     // вкладка, по которой нажали ЛКМ
    private Point _navPressPoint;
    private bool _navDragging;

    /// <summary>Восстановить сохранённый порядок вкладок. Неизвестные ключи пропускаем.</summary>
    private void ApplyNavOrder(IEnumerable<string>? order)
    {
        if (order is null) return;

        var applied = new HashSet<string>(StringComparer.Ordinal);
        var position = 0;
        foreach (var key in order)
        {
            if (!applied.Add(key)) continue;
            var item = NavList.Items.OfType<ListBoxItem>()
                .FirstOrDefault(i => string.Equals(i.Tag as string, key, StringComparison.Ordinal));
            if (item is null) continue;

            var from = NavList.Items.IndexOf(item);
            if (from != position)
            {
                NavList.Items.RemoveAt(from);
                NavList.Items.Insert(position, item);
            }
            position++;
        }
    }

    private void OnNavPreviewMouseDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ChangedButton != MouseButton.Left) return;
        // внутри выпадающих списков «Панели»/«SSH» записи перетаскиваются по-своему —
        // перетаскивание самой вкладки отсюда не начинаем
        if (IsInsideMenu(e.OriginalSource as DependencyObject)) return;
        _navPressed = FindAncestor<ListBoxItem>(e.OriginalSource as DependencyObject);
        _navPressPoint = e.GetPosition(NavList);
    }

    /// <summary>Находится ли элемент внутри выпадающего списка «Панели» или «SSH».</summary>
    private bool IsInsideMenu(DependencyObject? node)
    {
        while (node is not null)
        {
            if (ReferenceEquals(node, PanelMenu) || ReferenceEquals(node, SshMenu)) return true;
            node = VisualTreeHelper.GetParent(node);
        }
        return false;
    }

    /// <summary>Клик по заголовку раздела не выделяет вкладку (и не переключает вкладку):
    /// само нажатие нужно только для клика/перетаскивания, а не для выбора.</summary>
    private void OnNavHeaderMouseDown(object sender, MouseButtonEventArgs e) => e.Handled = true;

    private void OnNavPreviewMouseMove(object sender, MouseEventArgs e)
    {
        var dragged = _navPressed;
        if (dragged is null) return;
        if (e.LeftButton != MouseButtonState.Pressed)
        {
            EndNavDrag();
            return;
        }

        var pos = e.GetPosition(NavList);
        if (!_navDragging)
        {
            if (Math.Abs(pos.Y - _navPressPoint.Y) < 5) return;   // короткое движение — это обычный клик
            _navDragging = true;
            dragged.Opacity = 0.7;
            NavList.CaptureMouse();
        }

        var target = NavItemNearest(pos.Y);
        if (target is null || ReferenceEquals(target, dragged)) return;

        var from = NavList.Items.IndexOf(dragged);
        var to = NavList.Items.IndexOf(target);
        if (from < 0 || to < 0) return;

        // при удалении выбранной вкладки WPF сбрасывает выбор — вернём его тем же значением
        var active = NavList.SelectedValue;
        NavList.Items.RemoveAt(from);
        NavList.Items.Insert(to, dragged);
        NavList.SelectedValue = active;
    }

    private void OnNavPreviewMouseUp(object sender, MouseButtonEventArgs e) => EndNavDrag();

    private void OnNavLostCapture(object sender, MouseEventArgs e) => EndNavDrag();

    private void EndNavDrag()
    {
        var dragged = _navDragging;
        var item = _navPressed;
        _navPressed = null;
        _navDragging = false;
        if (item is not null) item.Opacity = 1;
        if (NavList.IsMouseCaptured) NavList.ReleaseMouseCapture();

        if (!dragged) return;
        App.Settings.Current.NavOrder = NavList.Items.OfType<ListBoxItem>()
            .Select(i => i.Tag as string ?? "")
            .Where(t => t.Length > 0)
            .ToList();
        App.Settings.SaveSoon();
    }

    /// <summary>Вкладка, центр которой ближе всех к точке по вертикали.</summary>
    private ListBoxItem? NavItemNearest(double y)
    {
        ListBoxItem? best = null;
        var bestDistance = double.MaxValue;
        foreach (var item in NavList.Items.OfType<ListBoxItem>())
        {
            var centerY = item.TranslatePoint(new Point(0, item.ActualHeight / 2), NavList).Y;
            var distance = Math.Abs(centerY - y);
            if (distance < bestDistance)
            {
                bestDistance = distance;
                best = item;
            }
        }
        return best;
    }

    private static T? FindAncestor<T>(DependencyObject? current) where T : DependencyObject
    {
        while (current is not null)
        {
            if (current is T typed) return typed;
            current = VisualTreeHelper.GetParent(current);
        }
        return null;
    }

    private void OnMinimizeClick(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;

    // ------------------------------------------------------------- пикер акцента
    private bool _syncingAccent;

    private void OnAccentClick(object sender, RoutedEventArgs e)
    {
        if (AccentOverlay.Visibility == Visibility.Visible)
        {
            AccentOverlay.Visibility = Visibility.Collapsed;
            return;
        }
        SyncAccentFlyout();
        AccentOverlay.Visibility = Visibility.Visible;
    }

    private void OnAccentOverlayClick(object sender, MouseButtonEventArgs e)
        => AccentOverlay.Visibility = Visibility.Collapsed;

    /// <summary>Клик внутри карточки не должен закрывать пикер (всплытие гасим).</summary>
    private void AccentCard_Click(object sender, MouseButtonEventArgs e) => e.Handled = true;

    /// <summary>Показать сохранённый цвет в круге и полоске прозрачности (без сохранения).</summary>
    private void SyncAccentFlyout()
    {
        var stored = App.Settings.Current.AccentColor;
        var color = !string.IsNullOrWhiteSpace(stored) && ThemeService.TryParseColor(stored!, out var c)
            ? c
            : ThemeService.DefaultAccent;

        _syncingAccent = true;
        ColorMath.ToHsv(color, out var hue, out var sat, out var val);
        if (sat > 0.001) AccentWheel.Hue = hue;   // у серого оттенок не определён: круг не прыгает
        AccentWheel.Saturation = sat;
        AccentWheel.Brightness = val;
        AccentAlpha.Alpha = color.A / 255.0;
        _syncingAccent = false;
        UpdateAccentPreview(color);
    }

    private void OnAccentWheelChanged() => ApplyAccentFromControls();

    private void OnAccentAlphaChanged() => ApplyAccentFromControls();

    /// <summary>Собрать цвет из круга и полоски прозрачности, применить сразу и сохранить.</summary>
    private void ApplyAccentFromControls()
    {
        if (_syncingAccent || AccentPreview is null) return;

        var color = ColorMath.FromHsv(AccentWheel.Hue, AccentWheel.Saturation, AccentWheel.Brightness,
            (byte)Math.Round(AccentAlpha.Alpha * 255));
        UpdateAccentPreview(color);

        var hex = color.ToString();   // #AARRGGBB
        ThemeService.ApplyAccent(hex);
        App.Settings.Current.AccentColor = hex;
        App.Settings.SaveSoon();
    }

    private void UpdateAccentPreview(Color color)
    {
        AccentPreview.Background = new SolidColorBrush(color);
        AccentHexText.Text = color.ToString();
        AccentAlpha.BaseColor = color;   // полоска сама убирает альфу из заполнения
    }

    private void OnAccentReset(object sender, RoutedEventArgs e)
    {
        App.Settings.Current.AccentColor = null;
        ThemeService.ApplyAccent(null);
        App.Settings.SaveSoon();
        SyncAccentFlyout();
    }

    /// <summary>Скруглённые углы окна: прозрачное окно + Border (CornerRadius=12),
    /// содержимое обрезаем геометрией, иначе углы будут квадратными. В развёрнутом
    /// состоянии скругление снимается — окно занимает всю рабочую область.</summary>
    private void OnRootSizeChanged(object sender, SizeChangedEventArgs e) => UpdateRootChrome();

    private void UpdateRootChrome()
    {
        var radius = WindowState == WindowState.Maximized ? 0 : 12;
        RootBorder.CornerRadius = new CornerRadius(radius);
        if (RootBorder.ActualWidth <= 0 || RootBorder.ActualHeight <= 0) return;
        RootBorder.Clip = new RectangleGeometry(
            new Rect(0, 0, RootBorder.ActualWidth, RootBorder.ActualHeight), radius, radius);
    }

    // ------------------------------------------------------------- списки «Панели»/«SSH»

    private void OnPanelHeaderClick(object sender, MouseButtonEventArgs e)
        => ToggleNavSection(PanelMenu, PanelChevronRot,
            expanded => App.Settings.Current.NavPanelsExpanded = expanded);

    private void OnSshHeaderClick(object sender, MouseButtonEventArgs e)
        => ToggleNavSection(SshMenu, SshChevronRot,
            expanded => App.Settings.Current.NavSshExpanded = expanded);

    /// <summary>Раскрыть/свернуть выпадающий список раздела навигации и запомнить состояние.</summary>
    private static void ToggleNavSection(Panel menu, RotateTransform chevron, Action<bool> persist)
    {
        var expanded = menu.Visibility != Visibility.Visible;
        menu.Visibility = expanded ? Visibility.Visible : Visibility.Collapsed;
        chevron.Angle = expanded ? 180 : 0;
        persist(expanded);
        App.Settings.SaveSoon();
    }

    /// <summary>Клик по панели в списке: показать её браузер и перейти на вкладку «Панели».</summary>
    private void OpenPanelEntry(PanelEntry entry)
    {
        App.Settings.Current.SelectedPanelId = entry.Id;
        App.Settings.SaveSoon();
        if (_vm is not null) _vm.ActiveTab = "panel";
        PanelTab.ShowEntry(entry);
    }

    /// <summary>Клик по подключению в списке: показать его терминал и перейти на вкладку «SSH».</summary>
    private void OpenSshEntry(SshEntry entry)
    {
        App.Settings.Current.SelectedSshId = entry.Id;
        App.Settings.SaveSoon();
        if (_vm is not null) _vm.ActiveTab = "ssh";
        SshTab.ShowEntry(entry);
    }

    private void OnPanelEntryDeleted(PanelEntry entry)
    {
        PanelTab.RemoveEntry(entry);
        if (App.Settings.Current.SelectedPanelId == entry.Id) App.Settings.Current.SelectedPanelId = null;
        App.Settings.SaveSoon();
    }

    private void OnSshEntryDeleted(SshEntry entry)
    {
        SshTab.RemoveEntry(entry);
        if (App.Settings.Current.SelectedSshId == entry.Id) App.Settings.Current.SelectedSshId = null;
        App.Settings.SaveSoon();
    }

    /// <summary>Шестерёнка в строке SSH: логин и пароль конкретного подключения.</summary>
    private void OnSshEntryConfigure(SshEntry entry)
    {
        var window = new Dialogs.SshCredentialsWindow("SSH — «" + entry.Name + "»", entry.User, entry.Password)
        {
            Owner = this
        };
        if (window.ShowDialog() != true) return;
        entry.User = window.User;
        entry.Password = window.Password;
        App.Settings.SaveSoon();
    }

    /// <summary>При старте показываем последнюю открытую панель/подключение (если запись ещё есть).</summary>
    private void RestoreSelectedEntries()
    {
        var s = App.Settings.Current;

        var panel = s.Panels.FirstOrDefault(p => p.Id == s.SelectedPanelId);
        PanelTab.ShowEntry(panel);

        var ssh = s.SshItems.FirstOrDefault(x => x.Id == s.SelectedSshId);
        SshTab.ShowEntry(ssh);

        SyncMenuSelection();
    }

    /// <summary>Подсветка записей в списках «Панели»/«SSH» — строго по тому, что реально открыто.</summary>
    private void SyncMenuSelection()
    {
        _panelMenu?.Rebuild(PanelTab.Current?.Id);
        _sshMenu?.Rebuild(SshTab.Current?.Id);
    }

    private void OnMaximizeClick(object sender, RoutedEventArgs e)
        => WindowState = WindowState == WindowState.Maximized ? WindowState.Normal : WindowState.Maximized;

    // ------------------------------------------------------------- разворот окна
    // Окно без рамки (WindowStyle=None + AllowsTransparency): при WindowState.Maximized WPF
    // растягивает окно на весь монитор, и оно залезает под панель задач — как фуллскрин,
    // который такому приложению не положен. Две защиты:
    //  1) перехватываем WM_GETMINMAXINFO и ограничиваем максимум рабочей областью монитора;
    //  2) после разворачивания дополнительно прижимаем окно к рабочей области через SetWindowPos —
    //     на слоёных окнах (AllowsTransparency) система местами игнорирует ограничение из п. 1.
    // «Развернуть» ведёт себя как обычное разворачивание, панель задач остаётся видимой.

    protected override void OnSourceInitialized(EventArgs e)
    {
        base.OnSourceInitialized(e);
        // HwndSource.FromHwnd надёжнее PresentationSource.FromVisual: последний возвращает null,
        // если HWND создан до Show (запуск свёрнутым в трей и т.п.), и хук тогда не установится
        if (HwndSource.FromHwnd(new WindowInteropHelper(this).Handle) is HwndSource source)
            source.AddHook(WndProc);
    }

    private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
    {
        const int WM_GETMINMAXINFO = 0x0024;
        const int WM_SIZE = 0x0005;
        const int SIZE_MAXIMIZED = 2;

        if (msg == WM_GETMINMAXINFO)
        {
            // не выставляем handled: пусть WPF обработает сообщение следом (обновит свои кэши
            // min/max-размеров); наши значения в ptMaxPosition/ptMaxSize он не трогает
            var mmi = Marshal.PtrToStructure<MINMAXINFO>(lParam);
            var monitor = MonitorFromWindow(hwnd, MonitorDefaultToNearest);
            if (monitor != IntPtr.Zero)
            {
                var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
                if (GetMonitorInfo(monitor, ref info))
                {
                    // рабочая область (rcWork) — монитор минус панель задач и док-панели
                    mmi.ptMaxPosition.X = info.rcWork.Left - info.rcMonitor.Left;
                    mmi.ptMaxPosition.Y = info.rcWork.Top - info.rcMonitor.Top;
                    mmi.ptMaxSize.X = info.rcWork.Right - info.rcWork.Left;
                    mmi.ptMaxSize.Y = info.rcWork.Bottom - info.rcWork.Top;
                    Marshal.StructureToPtr(mmi, lParam, false);
                }
            }
        }
        else if (msg == WM_SIZE && wParam.ToInt32() == SIZE_MAXIMIZED)
        {
            // окно только что развернулось — проверим и, если залезло под панель задач, дожмём
            Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.Loaded,
                new Action(ClampMaximizedToWorkArea));
        }

        return IntPtr.Zero;
    }

    /// <summary>
    /// Если развёрнутое окно вылезло за рабочую область монитора (под панель задач),
    /// явно прижимаем его туда через SetWindowPos. Если геометрия уже правильная — ничего не делаем.
    /// </summary>
    private void ClampMaximizedToWorkArea()
    {
        if (WindowState != WindowState.Maximized) return;

        var hwnd = new WindowInteropHelper(this).Handle;
        if (hwnd == IntPtr.Zero) return;

        var monitor = MonitorFromWindow(hwnd, MonitorDefaultToNearest);
        if (monitor == IntPtr.Zero) return;

        var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
        if (!GetMonitorInfo(monitor, ref info)) return;

        var x = info.rcWork.Left;
        var y = info.rcWork.Top;
        var w = info.rcWork.Right - info.rcWork.Left;
        var h = info.rcWork.Bottom - info.rcWork.Top;

        if (GetWindowRect(hwnd, out var rect) &&
            rect.Left == x && rect.Top == y &&
            rect.Right - rect.Left == w && rect.Bottom - rect.Top == h)
            return;   // окно уже на месте — не трогаем

        SetWindowPos(hwnd, IntPtr.Zero, x, y, w, h, SwpNoZOrder | SwpNoActivate);
    }

    private const uint MonitorDefaultToNearest = 2;
    private const uint SwpNoZOrder = 0x0004;
    private const uint SwpNoActivate = 0x0010;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int X;
        public int Y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct RECT
    {
        public int Left;
        public int Top;
        public int Right;
        public int Bottom;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MINMAXINFO
    {
        public POINT ptReserved;
        public POINT ptMaxSize;
        public POINT ptMaxPosition;
        public POINT ptMinTrackSize;
        public POINT ptMaxTrackSize;
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
    private struct MONITORINFO
    {
        public int cbSize;
        public RECT rcMonitor;
        public RECT rcWork;
        public uint dwFlags;
    }

    [DllImport("user32.dll")]
    private static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint dwFlags);

    [DllImport("user32.dll", CharSet = CharSet.Auto)]
    private static extern bool GetMonitorInfo(IntPtr hMonitor, ref MONITORINFO lpmi);

    [DllImport("user32.dll")]
    private static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);

    [DllImport("user32.dll")]
    private static extern bool SetWindowPos(IntPtr hWnd, IntPtr hWndInsertAfter,
        int x, int y, int cx, int cy, uint uFlags);

    private void OnCloseClick(object sender, RoutedEventArgs e)
    {
        switch (App.Settings.Current.CloseBehavior)
        {
            case CloseBehavior.HideToTray: HideToTray(); return;
            case CloseBehavior.Exit: ExitApplication(); return;
        }

        // Спрашиваем: скрыть в трей или выйти из приложения.
        var dialog = new Dialogs.CloseDialogWindow { Owner = this };
        var result = dialog.ShowDialog();
        if (result is null) return;   // Esc — остаёмся в приложении
        if (dialog.RememberMyChoice)
        {
            App.Settings.Current.CloseBehavior = result == true ? CloseBehavior.HideToTray : CloseBehavior.Exit;
            App.Settings.SaveSoon();
        }
        if (result == true) HideToTray();
        else ExitApplication();
    }

    private void HideToTray()
    {
        Hide();
        // свернули в трей — выгружаем страницы панелей, чтобы не ели ресурсы.
        // SSH-соединения при этом НЕ рвём.
        PanelTab?.UnloadAll();
        if (!App.Settings.Current.TrayHintShown)
        {
            App.Settings.Current.TrayHintShown = true;
            App.Settings.SaveSoon();
            _vm?.Tray.ShowHint("GoreBox", "Приложение свёрнуто в трей: клик по иконке открывает окно, " +
                                              "правая кнопка — «Закрыть».");
        }
    }

    /// <summary>
    /// Полный выход: остановить ядро, вернуть прежние настройки системного прокси, убрать иконку из трея
    /// и только затем закрыть окно и завершить приложение.
    /// </summary>
    private void ExitApplication(bool restart = false)
    {
        if (_reallyExit) return;
        _reallyExit = true;
        App.RestartOnExit = restart;   // после освобождения мьютекса App запустит новый экземпляр
        StartExitWatchdog();
        _ = CloseSequenceAsync();
    }

    /// <summary>
    /// Страховка от зависания при выходе: если приложение не закрылось за 20 секунд, принудительно возвращаем
    /// системный прокси, останавливаем ядро и завершаем процесс. Работает в пуле потоков, поэтому срабатывает,
    /// даже когда UI-поток заблокирован.
    /// </summary>
    private void StartExitWatchdog()
    {
        var vm = _vm;
        _ = Task.Delay(TimeSpan.FromSeconds(20)).ContinueWith(_ =>
        {
            // в errors.log остаётся след: по нему видно, что выход не завершился сам
            App.LogCrash(new TimeoutException("Выход из GoreBox не завершился за 20 секунд — принудительное завершение."));
            try { vm?.PrepareForSystemShutdown(); } catch { /* ignore */ }   // вернёт прокси и сохранит настройки
            try { vm?.Core.Dispose(); } catch { /* ignore */ }               // завершит процесс ядра
            if (App.RestartOnExit) App.StartNewInstance();
            Environment.Exit(0);
        }, TaskScheduler.Default);
    }

    protected override void OnClosing(CancelEventArgs e)
    {
        if (_reallyExit || _closed)
        {
            base.OnClosing(e);
            return;
        }

        // поведение при закрытии: свернуть в трей, выйти или спросить.
        // Из OnClosing (Alt+F4, системные закрытия) диалог не показываем — сворачиваем в трей,
        // выбор «скрыть/выйти» есть у кнопки-крестика.
        var behavior = App.Settings.Current.CloseBehavior;
        if (behavior == CloseBehavior.Ask) behavior = CloseBehavior.HideToTray;
        if (behavior == CloseBehavior.HideToTray)
        {
            e.Cancel = true;
            HideToTray();
            return;
        }

        // пользователь хочет выйти — сначала корректно завершаем работу
        e.Cancel = true;
        ExitApplication();
    }

    private void SaveWindowGeometry()
    {
        var settings = App.Settings.Current;
        if (WindowState == WindowState.Normal)
        {
            settings.WindowWidth = Width;
            settings.WindowHeight = Height;
            settings.WindowLeft = Left;
            settings.WindowTop = Top;
        }
        settings.WindowState = WindowState.ToString();
    }

    /// <summary>
    /// Последовательность выхода. Окно закрывается и приложение завершается в любом случае (finally):
    /// если какой-то шаг упал, приложение не должно остаться работать без окна.
    /// </summary>
    private async Task CloseSequenceAsync()
    {
        try
        {
            // перед выходом освобождаем браузеры панелей и ssh-сессии
            try { PanelTab?.UnloadAll(); } catch (Exception ex) { App.LogCrash(ex); }
            try { SshTab?.ShutdownAll(); } catch (Exception ex) { App.LogCrash(ex); }
            try { SaveWindowGeometry(); } catch (Exception ex) { App.LogCrash(ex); }

            if (_vm is not null)
            {
                try
                {
                    await _vm.ShutdownAsync();
                }
                catch (Exception ex)
                {
                    App.LogCrash(ex);
                }
            }
        }
        finally
        {
            try { Close(); } catch { /* ignore */ }
            Application.Current.Shutdown();
        }
    }

    protected override void OnClosed(EventArgs e)
    {
        _closed = true;
        base.OnClosed(e);
        try
        {
            App.Settings.SaveNow();
        }
        catch { /* ignore */ }
    }

    private void SettingsView_Loaded(object sender, RoutedEventArgs e)
    {

    }
}
