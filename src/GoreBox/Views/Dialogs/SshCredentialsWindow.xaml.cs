using System.Windows;
using System.Windows.Input;

namespace GoreBox.Views.Dialogs;

/// <summary>Небольшое окно: логин и пароль для одного SSH-подключения (шестерёнка в списке).</summary>
public partial class SshCredentialsWindow : Window
{
    public SshCredentialsWindow(string title, string user, string password)
    {
        InitializeComponent();
        TitleText.Text = title;
        UserBox.Text = user;
        PwdBox.Password = password;
    }

    public string User { get; private set; } = "";
    public string Password { get; private set; } = "";

    private void OnDrag(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void OnKeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter) OnOk(sender, e);
        if (e.Key == Key.Escape) OnCancel(sender, e);
    }

    private void OnOk(object sender, RoutedEventArgs e)
    {
        User = UserBox.Text?.Trim() ?? "";
        Password = PwdBox.Password ?? "";
        DialogResult = true;
        Close();
    }

    private void OnCopyUser(object sender, RoutedEventArgs e)
        => CopyToClipboard(UserBox.Text?.Trim() ?? "");

    private void OnCopyPassword(object sender, RoutedEventArgs e)
        => CopyToClipboard(PwdBox.Password ?? "");

    private void CopyToClipboard(string value)
    {
        if (value.Length == 0) return;
        Clipboard.SetText(value);
        // короткая подсветка-подтверждение, без всплывающих окон
        CopiedHint.Opacity = 1;
        var timer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromSeconds(1.2) };
        timer.Tick += (_, _) => { timer.Stop(); CopiedHint.Opacity = 0; };
        timer.Start();
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }
}
