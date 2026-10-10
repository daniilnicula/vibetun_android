using System.Collections.ObjectModel;
using System.Windows.Threading;
using GoreBox.Models;
using GoreBox.Services;
using GoreBox.Utils;

namespace GoreBox.ViewModels;

/// <summary>Вкладка «Маршрутизация»: профили входа/TUN, наборы правил и приложения.</summary>
public sealed class RoutingViewModel : ObservableObject
{
    private readonly RouteStore _store;
    private readonly IDialogService _dialogs;
    private readonly DispatcherTimer _saveTimer;

    private RouteProfileItemVm? _selectedProfile;
    private RouteSetItemVm? _selectedSet;
    private string _newRulePattern = "";
    private MatchKind _newRuleKind = MatchKind.Domain;
    private RouteAction _newRuleAction = RouteAction.Proxy;
    private string _appSearch = "";

    public RoutingViewModel(RouteStore store, IDialogService dialogs)
    {
        _store = store;
        _dialogs = dialogs;

        _saveTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };
        _saveTimer.Tick += (_, _) =>
        {
            _saveTimer.Stop();
            Persist();
            RulesPersisted?.Invoke();
        };

        AddRuleCommand = new RelayCommand(_ => AddRule(), _ => !string.IsNullOrWhiteSpace(NewRulePattern));
        RemoveRuleCommand = new RelayCommand(p => RemoveRule(p as RuleItemVm));
        AddAppFromExeCommand = new AsyncRelayCommand(AddAppFromExeAsync);
        AddAppFromProcessesCommand = new AsyncRelayCommand(AddAppsFromProcessesAsync);
        RemoveAppCommand = new RelayCommand(p => RemoveApp(p as AppRuleItemVm));
        NewProfileCommand = new RelayCommand(_ => NewProfile());
        DeleteProfileCommand = new RelayCommand(_ => DeleteProfile(), _ => Profiles.Count > 1);
        DuplicateSetCommand = new RelayCommand(_ => DuplicateSet(), _ => SelectedSet is not null);
        NewSetCommand = new RelayCommand(_ => NewSet());
        DeleteSetCommand = new RelayCommand(_ => DeleteSet(), _ => SelectedSet is { BuiltIn: false });
        ImportSetCommand = new RelayCommand(_ => ImportSet());
        ExportSetCommand = new RelayCommand(_ => ExportSet(), _ => SelectedSet is not null);
        EnableTunCommand = new RelayCommand(_ => EnableTun());
    }

    public ObservableCollection<RouteProfileItemVm> Profiles { get; } = new();
    public ObservableCollection<RouteSetItemVm> Sets { get; } = new();
    public ObservableCollection<RuleItemVm> Rules { get; } = new();
    public ObservableCollection<AppRuleItemVm> Apps { get; } = new();
    public ObservableCollection<AppRuleItemVm> FilteredApps { get; } = new();

    public event Action<string, bool>? Notify;
    public event Action? SettingsChanged;
    /// <summary>Наборы правил/приложений сохранены на диск. Подписчик (MainViewModel)
    /// переподключает ядро, если прокси активен, — чтобы правила вступили в силу сразу.</summary>
    public event Action? RulesPersisted;

    public RelayCommand AddRuleCommand { get; }
    public RelayCommand RemoveRuleCommand { get; }
    public AsyncRelayCommand AddAppFromExeCommand { get; }
    public AsyncRelayCommand AddAppFromProcessesCommand { get; }
    public RelayCommand RemoveAppCommand { get; }
    public RelayCommand NewProfileCommand { get; }
    public RelayCommand DeleteProfileCommand { get; }
    public RelayCommand DuplicateSetCommand { get; }
    public RelayCommand NewSetCommand { get; }
    public RelayCommand DeleteSetCommand { get; }
    public RelayCommand ImportSetCommand { get; }
    public RelayCommand ExportSetCommand { get; }
    public RelayCommand EnableTunCommand { get; }

    public RouteProfileItemVm? SelectedProfile
    {
        get => _selectedProfile;
        set
        {
            if (!SetProperty(ref _selectedProfile, value)) return;
            foreach (var p in Profiles) p.IsSelected = ReferenceEquals(p, value);
            Raise(nameof(ShowTunHint));
            Raise(nameof(SelectedProfileName));
        }
    }

    public string SelectedProfileName => SelectedProfile?.Name ?? "—";

    public RouteSetItemVm? SelectedSet
    {
        get => _selectedSet;
        set
        {
            if (!SetProperty(ref _selectedSet, value)) return;
            LoadSetContents();
            DuplicateSetCommand.RaiseCanExecuteChanged();
            DeleteSetCommand.RaiseCanExecuteChanged();
            ExportSetCommand.RaiseCanExecuteChanged();
        }
    }

    public string NewRulePattern
    {
        get => _newRulePattern;
        set
        {
            if (!SetProperty(ref _newRulePattern, value)) return;
            AddRuleCommand.RaiseCanExecuteChanged();

            // тип записи определяется автоматически по введённому тексту
            if (!string.IsNullOrWhiteSpace(value))
            {
                var (_, kind) = Text.NormalizeUserPattern(value);
                if (_newRuleKind != kind)
                {
                    _newRuleKind = kind;
                    Raise(nameof(NewRuleKind));
                    Raise(nameof(NewRuleKindLabel));
                }
            }
        }
    }

    public MatchKind NewRuleKind
    {
        get => _newRuleKind;
        private set
        {
            if (!SetProperty(ref _newRuleKind, value)) return;
            Raise(nameof(NewRuleKindLabel));
        }
    }

    public string NewRuleKindLabel => _newRuleKind switch
    {
        MatchKind.Domain => "домен",
        MatchKind.Ip => "IP",
        MatchKind.Regex => "regex",
        MatchKind.Keyword => "ключ",
        _ => "—"
    };

    public RouteAction NewRuleAction
    {
        get => _newRuleAction;
        set
        {
            if (!SetProperty(ref _newRuleAction, value)) return;
            Raise(nameof(NewRuleIsProxy));
            Raise(nameof(NewRuleIsDirect));
            Raise(nameof(NewRuleIsBlock));
        }
    }

    public bool NewRuleIsProxy
    {
        get => _newRuleAction == RouteAction.Proxy;
        set
        {
            if (value) NewRuleAction = RouteAction.Proxy;
            else Raise(nameof(NewRuleIsProxy));
        }
    }

    public bool NewRuleIsDirect
    {
        get => _newRuleAction == RouteAction.Direct;
        set
        {
            if (value) NewRuleAction = RouteAction.Direct;
            else Raise(nameof(NewRuleIsDirect));
        }
    }

    public bool NewRuleIsBlock
    {
        get => _newRuleAction == RouteAction.Block;
        set
        {
            if (value) NewRuleAction = RouteAction.Block;
            else Raise(nameof(NewRuleIsBlock));
        }
    }

    public string AppSearch
    {
        get => _appSearch;
        set
        {
            if (SetProperty(ref _appSearch, value)) ApplyAppFilter();
        }
    }

    public bool ShowTunHint => Apps.Count > 0 && SelectedProfile?.TunEnabled == false;

    private string _routingTab = "domains";

    /// <summary>Открытая вкладка правой части: «domains» — домены и IP, «apps» — приложения.</summary>
    public string RoutingTab
    {
        get => _routingTab;
        set
        {
            if (!SetProperty(ref _routingTab, value)) return;
            Raise(nameof(IsDomainsTab));
            Raise(nameof(IsAppsTab));
        }
    }

    /// <summary>Привязка переключателя: выбрать можно только вкладку, снять выбор нельзя.</summary>
    public bool IsDomainsTab
    {
        get => _routingTab == "domains";
        set
        {
            if (value) RoutingTab = "domains";
            else Raise(nameof(IsDomainsTab));
        }
    }

    public bool IsAppsTab
    {
        get => _routingTab == "apps";
        set
        {
            if (value) RoutingTab = "apps";
            else Raise(nameof(IsAppsTab));
        }
    }

    public string RulesCountText => $"{Rules.Count} правил · {Apps.Count} приложений";

    // ------------------------------------------------------------------ загрузка
    public void Load()
    {
        _store.SeedBuiltInsIfNeeded();

        Sets.Clear();
        foreach (var set in _store.LoadSets())
            Sets.Add(new RouteSetItemVm(set));

        Profiles.Clear();
        foreach (var profile in _store.LoadProfiles())
        {
            var vm = new RouteProfileItemVm(profile);
            vm.Changed += ScheduleSave;
            Profiles.Add(vm);
        }

        _store.Normalize(Profiles.Select(p => p.Model).ToList(), Sets.Select(s => s.Model).ToList());

        var activeId = App.Settings.Current.ActiveRouteProfileId;
        SelectedProfile = Profiles.FirstOrDefault(p => p.Id == activeId) ?? Profiles.FirstOrDefault();
        SelectedSet = Sets.FirstOrDefault(s => s.Id == SelectedProfile?.RouteSetId) ?? Sets.FirstOrDefault();
        Raise(nameof(RulesCountText));
        Raise(nameof(ShowTunHint));
    }

    public RouteProfile ActiveProfile => SelectedProfile?.Model
        ?? Profiles.FirstOrDefault()?.Model
        ?? new RouteProfile();

    public RouteSet? ActiveSet => SelectedSet?.Model;

    private void LoadSetContents()
    {
        Rules.Clear();
        Apps.Clear();
        var set = SelectedSet?.Model;
        if (set is not null)
        {
            foreach (var rule in set.Rules)
            {
                var vm = new RuleItemVm(rule);
                vm.Changed += ScheduleSave;
                Rules.Add(vm);
            }
            foreach (var app in set.Apps)
            {
                var vm = new AppRuleItemVm(app);
                vm.Changed += ScheduleSave;
                Apps.Add(vm);
            }
        }
        ApplyAppFilter();
        Raise(nameof(RulesCountText));
        Raise(nameof(ShowTunHint));
    }

    private void ApplyAppFilter()
    {
        FilteredApps.Clear();
        var q = _appSearch.Trim();
        foreach (var app in Apps)
        {
            if (q.Length == 0 ||
                app.Name.Contains(q, StringComparison.CurrentCultureIgnoreCase) ||
                app.ProcessName.Contains(q, StringComparison.CurrentCultureIgnoreCase) ||
                app.Path.Contains(q, StringComparison.CurrentCultureIgnoreCase))
                FilteredApps.Add(app);
        }
    }

    // --------------------------------------------------------------- сохранение
    private void ScheduleSave()
    {
        _saveTimer.Stop();
        _saveTimer.Start();
        Raise(nameof(RulesCountText));
        Raise(nameof(ShowTunHint));
        SettingsChanged?.Invoke();
    }

    public void Persist()
    {
        if (SelectedSet is not null)
        {
            var set = SelectedSet.Model;
            set.Rules = Rules.Select(r => r.Model).ToList();
            set.Apps = Apps.Select(a => a.Model).ToList();
            _store.Save(set);
            SelectedSet.Refresh();
        }
        _store.SaveProfiles(Profiles.Select(p => p.Model).ToList());

        var settings = App.Settings.Current;
        settings.ActiveRouteProfileId = SelectedProfile?.Id;
        App.Settings.SaveSoon();
    }

    // -------------------------------------------------------------------- rules
    private void AddRule()
    {
        var pattern = NewRulePattern.Trim();
        if (pattern.Length == 0) return;

        var model = new RouteRule
        {
            Pattern = pattern,
            Kind = NewRuleKind,
            Action = NewRuleAction
        };
        var vm = new RuleItemVm(model);
        vm.Changed += ScheduleSave;

        Rules.Insert(0, vm);
        NewRulePattern = "";
        ScheduleSave();
    }

    private void RemoveRule(RuleItemVm? rule)
    {
        if (rule is null) return;
        Rules.Remove(rule);
        ScheduleSave();
    }

    // --------------------------------------------------------------------- apps
    private Task AddAppFromExeAsync()
    {
        var path = _dialogs.OpenFile("Приложения (*.exe)|*.exe|Все файлы (*.*)|*.*");
        if (path is null) return Task.CompletedTask;
        AddAppPath(path);
        return Task.CompletedTask;
    }

    private Task AddAppsFromProcessesAsync()
    {
        var picked = _dialogs.PickApplications();
        foreach (var app in picked)
            AddAppPath(app.Path, app.Title);
        return Task.CompletedTask;
    }

    public void AddAppPath(string exePath, string? displayName = null)
    {
        if (string.IsNullOrWhiteSpace(exePath)) return;
        if (Apps.Any(a => string.Equals(a.Path, exePath, StringComparison.OrdinalIgnoreCase))) return;

        var (processName, title) = ProcessScanner.Describe(exePath);
        var model = new AppRule
        {
            Name = displayName is { Length: > 0 } ? displayName! : title,
            Path = exePath,
            ProcessName = processName,
            Action = RouteAction.Proxy
        };
        var vm = new AppRuleItemVm(model);
        vm.Changed += ScheduleSave;
        Apps.Add(vm);
        ApplyAppFilter();
        ScheduleSave();
        Notify?.Invoke($"Приложение «{vm.Name}» добавлено", false);
    }

    private void RemoveApp(AppRuleItemVm? app)
    {
        if (app is null) return;
        Apps.Remove(app);
        ApplyAppFilter();
        ScheduleSave();
    }

    // ------------------------------------------------------------------ профили
    private void NewProfile()
    {
        var name = _dialogs.PromptText("Новый профиль маршрутизации", "Название", "Профиль " + (Profiles.Count + 1));
        if (string.IsNullOrWhiteSpace(name)) return;

        var model = new RouteProfile
        {
            Name = name!,
            ListenPort = Profiles.Count > 0 ? Profiles.Max(p => p.ListenPort) + 1 : 2080,
            RouteSetId = SelectedSet?.Id ?? Sets.FirstOrDefault()?.Id
        };
        var vm = new RouteProfileItemVm(model);
        vm.Changed += ScheduleSave;
        Profiles.Add(vm);
        SelectedProfile = vm;
        ScheduleSave();
    }

    private void DeleteProfile()
    {
        if (SelectedProfile is null || Profiles.Count <= 1) return;
        if (!_dialogs.Confirm("Удалить профиль маршрутизации",
                $"Удалить «{SelectedProfile.Name}»?", "Удалить")) return;

        Profiles.Remove(SelectedProfile);
        SelectedProfile = Profiles.FirstOrDefault();
        ScheduleSave();
    }

    // ---------------------------------------------------------------- наборы
    private void NewSet()
    {
        var name = _dialogs.PromptText("Новый набор правил", "Название", "Набор " + (Sets.Count + 1));
        if (string.IsNullOrWhiteSpace(name)) return;

        var set = new RouteSet { Name = name!, Description = "Пользовательский набор" };
        _store.Save(set);
        var vm = new RouteSetItemVm(set);
        Sets.Add(vm);
        SelectedSet = vm;
        Notify?.Invoke($"Набор «{set.Name}» создан", false);
    }

    private void DuplicateSet()
    {
        if (SelectedSet is null) return;
        var name = _dialogs.PromptText("Копия набора", "Название", SelectedSet.Name + " (копия)");
        if (string.IsNullOrWhiteSpace(name)) return;

        var copy = _store.Duplicate(SelectedSet.Model, name!);
        var vm = new RouteSetItemVm(copy);
        Sets.Add(vm);
        SelectedSet = vm;
        Notify?.Invoke("Набор продублирован", false);
    }

    private void DeleteSet()
    {
        if (SelectedSet is null || SelectedSet.BuiltIn) return;
        if (!_dialogs.Confirm("Удалить набор правил", $"Удалить «{SelectedSet.Name}»?", "Удалить")) return;

        _store.Delete(SelectedSet.Model);
        Sets.Remove(SelectedSet);
        SelectedSet = Sets.FirstOrDefault();
        Notify?.Invoke("Набор удалён", false);
    }

    private void ImportSet()
    {
        var path = _dialogs.OpenFile("Набор правил (*.json)|*.json|Все файлы (*.*)|*.*");
        if (path is null) return;
        try
        {
            var set = _store.Import(path);
            var vm = new RouteSetItemVm(set);
            Sets.Add(vm);
            SelectedSet = vm;
            Notify?.Invoke($"Набор «{set.Name}» импортирован", false);
        }
        catch (Exception ex)
        {
            _dialogs.Error("Не удалось импортировать набор", ex.Message);
        }
    }

    private void ExportSet()
    {
        if (SelectedSet is null) return;
        var path = _dialogs.SaveFile("Набор правил (*.json)|*.json", AppPaths.Slug(SelectedSet.Name) + ".json");
        if (path is null) return;
        try
        {
            _store.Export(SelectedSet.Model, path);
            Notify?.Invoke("Набор сохранён в файл", false);
        }
        catch (Exception ex)
        {
            _dialogs.Error("Не удалось сохранить набор", ex.Message);
        }
    }

    private void EnableTun()
    {
        if (SelectedProfile is null) return;
        SelectedProfile.TunEnabled = true;
        Raise(nameof(ShowTunHint));
        Notify?.Invoke("TUN включён — при подключении потребуются права администратора", false);
        ScheduleSave();
    }
}
