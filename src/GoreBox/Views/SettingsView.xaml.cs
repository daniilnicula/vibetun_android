using System.Windows;
using System.Windows.Controls;

namespace GoreBox.Views;

public partial class SettingsView : UserControl
{
    public SettingsView() => InitializeComponent();

    private async void OnClearPanelLogins(object sender, RoutedEventArgs e)
    {
        if (Window.GetWindow(this) is not MainWindow window) return;
        await window.ClearPanelLoginsAsync();
    }
}
