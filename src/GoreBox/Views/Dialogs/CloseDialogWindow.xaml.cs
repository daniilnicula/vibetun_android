using System.Windows;
using System.Windows.Input;

namespace GoreBox.Views.Dialogs;

/// <summary>
/// Диалог закрытия: «Скрыть или выйти из приложения?».
/// DialogResult: true — скрыть в трей, false — выйти полностью, null — отмена (Esc).
/// </summary>
public partial class CloseDialogWindow : Window
{
    public CloseDialogWindow()
    {
        InitializeComponent();
    }

    /// <summary>Отметка «Запомнить мой выбор» — вызывающий код сохраняет выбранный режим.</summary>
    public bool RememberMyChoice => RememberBox.IsChecked == true;

    private void OnDrag(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void OnHide(object sender, RoutedEventArgs e)
    {
        DialogResult = true;
        Close();
    }

    private void OnExit(object sender, RoutedEventArgs e)
    {
        DialogResult = false;
        Close();
    }

    protected override void OnKeyDown(KeyEventArgs e)
    {
        if (e.Key == Key.Escape) { Close(); return; }   // DialogResult остаётся null — отмена
        base.OnKeyDown(e);
    }
}
