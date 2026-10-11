using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Threading;
using GoreBox.ViewModels;

namespace GoreBox.Views;

public partial class ProfilesView : UserControl
{
    private ScrollViewer? _logScroll;
    private bool _logFollow = true;

    public ProfilesView()
    {
        InitializeComponent();
        // высота панели логов запоминается между запусками
        LogRow.Height = new GridLength(Math.Clamp(App.Settings.Current.ProfilesLogHeight, 100, 600));
    }

    private ProfilesViewModel? Vm => DataContext as ProfilesViewModel;

    private void OnItemDoubleClick(object sender, MouseButtonEventArgs e)
    {
        Vm?.ConnectCommand.Execute(null);
    }

    /// <summary>ПКМ по элементу списка должен выделять его — тогда меню работает по нужному профилю.</summary>
    private void OnPreviewRightClick(object sender, MouseButtonEventArgs e)
    {
        var item = FindAncestor<ListBoxItem>(e.OriginalSource as DependencyObject);
        if (item is not null) item.IsSelected = true;
    }

    private static T? FindAncestor<T>(DependencyObject? current) where T : DependencyObject
    {
        while (current is not null)
        {
            if (current is T typed) return typed;
            // TextElement (Run внутри TextBlock) не является Visual — VisualTreeHelper.GetParent
            // падает с «not a Visual or Visual3D»; для них идём по логическому дереву.
            current = current is Visual
                ? VisualTreeHelper.GetParent(current)
                : LogicalTreeHelper.GetParent(current);
        }
        return null;
    }

    private static T? FindChild<T>(DependencyObject? parent) where T : DependencyObject
    {
        if (parent is null || parent is not Visual) return null;
        for (var i = 0; i < VisualTreeHelper.GetChildrenCount(parent); i++)
        {
            var child = VisualTreeHelper.GetChild(parent, i);
            if (child is T typed) return typed;
            var deeper = FindChild<T>(child);
            if (deeper is not null) return deeper;
        }
        return null;
    }

    /// <summary>Разделитель отпустили: запоминаем новую высоту панели логов.</summary>
    private void OnLogSplitterDragCompleted(object sender, DragCompletedEventArgs e)
    {
        var rowHeight = LogPanel.ActualHeight + LogPanel.Margin.Top + LogPanel.Margin.Bottom;
        App.Settings.Current.ProfilesLogHeight = Math.Round(rowHeight);
        App.Settings.SaveSoon();
    }

    /// <summary>
    /// Автопрокрутка журнала: следим, у конца ли пользователь. Если он прокрутил вверх,
    /// новые строки его не сдвигают.
    /// </summary>
    private void OnLogBoxLoaded(object sender, RoutedEventArgs e)
    {
        if (_logScroll is not null) return;
        LogBox.ApplyTemplate();
        var scroll = FindChild<ScrollViewer>(LogBox);
        if (scroll is null) return;

        _logScroll = scroll;
        scroll.ScrollChanged += (_, args) =>
        {
            if (args.VerticalChange != 0)
                _logFollow = scroll.VerticalOffset >= scroll.ScrollableHeight - 4;
        };
    }

    private void OnLogBoxTextChanged(object sender, TextChangedEventArgs e)
    {
        if (!_logFollow || _logScroll is null) return;
        // прокручиваем после пересчёта раскладки, иначе конец ещё не известен
        Dispatcher.BeginInvoke(DispatcherPriority.Background, new Action(() => _logScroll?.ScrollToEnd()));
    }

    private void OnLogCopyClick(object sender, RoutedEventArgs e)
    {
        try
        {
            Clipboard.SetText(Vm?.Log.Snapshot() ?? "");
        }
        catch (Exception)
        {
            // буфер обмена занят другим приложением — пропускаем
        }
    }
}
