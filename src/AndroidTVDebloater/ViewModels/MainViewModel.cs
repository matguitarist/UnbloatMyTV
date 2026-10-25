using System.Collections.ObjectModel;
using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Models;
using AndroidTVDebloater.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AndroidTVDebloater.ViewModels;

public abstract class PageViewModel(MainViewModel main) : ObservableObject
{
    protected MainViewModel Main { get; } = main;
    public abstract int StepIndex { get; }
    public virtual void OnLanguageChanged() { }
    /// <summary>The page is being replaced: stop anything running in the background.</summary>
    public virtual void OnLeave() { }
}

public sealed partial class StepInfo(int number, string titleKey) : ObservableObject
{
    public int Number { get; } = number;
    public string TitleKey { get; } = titleKey;
    public string Title => Loc.Get(TitleKey);
    [ObservableProperty] private bool _isActive;
    [ObservableProperty] private bool _isDone;
    /// <summary>A finished step the user can click to go back to.</summary>
    [ObservableProperty] private bool _canNavigate;
    public void Refresh() => OnPropertyChanged(nameof(Title));
}

public sealed partial class MainViewModel : ObservableObject
{
    public AdbService Adb { get; }
    public Loc Loc => Loc.Instance;
    public string Version => "v" + UpdateService.Current;
    public UpdaterViewModel Updater { get; }

    public ObservableCollection<StepInfo> Steps { get; } =
    [
        new(1, "Step_Prepare"), new(2, "Step_Connect"), new(3, "Step_Choose"), new(4, "Step_Apply"),
    ];

    [ObservableProperty] private PageViewModel _currentPage;
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasDevice), nameof(DeviceName), nameof(DeviceAddress))]
    private TvDevice? _device;

    public bool HasDevice => Device is not null;
    public string DeviceName => Device?.DisplayName ?? "";
    public string DeviceAddress => Device?.IpAddress ?? "";

    public MainViewModel(AdbService adb)
    {
        Adb = adb;
        _currentPage = new WelcomeViewModel(this);
        Updater = new UpdaterViewModel(this);
        RefreshSteps();
        Loc.Instance.LanguageChanged += () =>
        {
            foreach (var s in Steps) s.Refresh();
            CurrentPage.OnLanguageChanged();
            Updater.OnLanguageChanged();
        };
    }

    partial void OnCurrentPageChanged(PageViewModel? oldValue, PageViewModel newValue)
    {
        oldValue?.OnLeave();
        RefreshSteps();
        foreach (var name in new[] { nameof(OnInfo), nameof(OnHealth), nameof(OnPrivacy), nameof(OnApps), nameof(OnRemote), nameof(OnInstall), nameof(OnRestore) })
            OnPropertyChanged(name);
    }

    // Which sidebar tool is open, to mark it.
    public bool OnInfo => CurrentPage is TvInfoViewModel;
    public bool OnHealth => CurrentPage is HealthViewModel;
    public bool OnPrivacy => CurrentPage is PrivacyViewModel;
    public bool OnApps => CurrentPage is AppsViewModel;
    public bool OnRemote => CurrentPage is RemoteViewModel;
    public bool OnInstall => CurrentPage is InstallViewModel;
    public bool OnRestore => CurrentPage is RestoreViewModel;

    /// <summary>Changes are being made to the TV: don't let the user leave the page.</summary>
    public bool IsBusy => CurrentPage is ApplyViewModel { IsFinished: false } or RestoreViewModel { IsBusy: true } or TvInfoViewModel { IsRestarting: true };

    public void RefreshSteps()
    {
        foreach (var s in Steps)
        {
            s.IsActive = s.Number - 1 == CurrentPage.StepIndex;
            s.IsDone = s.Number - 1 < CurrentPage.StepIndex;
            // From a tool page (also step 3), "Choose" leads back to the list.
            var onTool = CurrentPage is TvInfoViewModel or HealthViewModel or PrivacyViewModel or AppsViewModel or InstallViewModel or RemoteViewModel or RestoreViewModel;
            s.CanNavigate = (s.IsDone || (s.Number == 3 && onTool)) && !IsBusy && s.Number < 4 && (s.Number != 3 || HasDevice);
        }
    }

    [RelayCommand]
    private async Task GoToStepAsync(StepInfo step)
    {
        if (!step.CanNavigate) return;
        switch (step.Number)
        {
            case 1: CurrentPage = new WelcomeViewModel(this); break;
            case 2: GoConnect(); break;
            case 3:
                try { await GoChooseAsync(reload: true); }
                catch { GoConnect(); }
                break;
        }
    }

    /// <summary>Back to the first page with no TV connected, e.g. to clean another one.</summary>
    public async Task StartOverAsync()
    {
        if (Device is { } d && d.Serial.Contains(':'))
            await Adb.RunAsync(["disconnect", d.Serial], 5000);
        Device = null;
        CurrentPage = new WelcomeViewModel(this);
    }

    public void GoConnect() => CurrentPage = new ConnectViewModel(this);

    public async Task GoChooseAsync(bool reload = false)
    {
        if (Device is null) { GoConnect(); return; }
        if (reload) Device = await TvDevice.LoadAsync(Adb, Device.Serial);
        await Catalog.RefreshFromGitHubAsync(); // the app may have stayed open since the list changed
        CurrentPage = new ChooseViewModel(this, Device);
    }

    public void GoApply(List<Tweak> selected, bool reboot)
    {
        if (Device is null) return;
        ChoiceSet.From(Device.DisplayName, selected).SaveAsLast();
        var page = new ApplyViewModel(this, Device, selected, reboot);
        CurrentPage = page;
        _ = page.RunAsync();
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoRestore()
    {
        if (Device is not null && !IsBusy) CurrentPage = new RestoreViewModel(this, Device);
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoInfo()
    {
        if (Device is not null && !IsBusy) CurrentPage = new TvInfoViewModel(this, Device);
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoHealth()
    {
        if (Device is not null && !IsBusy) CurrentPage = new HealthViewModel(this, Device);
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoPrivacy()
    {
        if (Device is not null && !IsBusy) CurrentPage = new PrivacyViewModel(this, Device);
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoApps()
    {
        if (Device is not null && !IsBusy) CurrentPage = new AppsViewModel(this, Device);
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoInstall()
    {
        if (Device is not null && !IsBusy) CurrentPage = new InstallViewModel(this, Device);
    }

    [RelayCommand(CanExecute = nameof(HasDevice))]
    private void GoRemote()
    {
        if (Device is not null && !IsBusy) CurrentPage = new RemoteViewModel(this, Device);
    }

    partial void OnDeviceChanged(TvDevice? value)
    {
        GoRestoreCommand.NotifyCanExecuteChanged();
        GoInfoCommand.NotifyCanExecuteChanged();
        GoHealthCommand.NotifyCanExecuteChanged();
        GoPrivacyCommand.NotifyCanExecuteChanged();
        GoAppsCommand.NotifyCanExecuteChanged();
        GoInstallCommand.NotifyCanExecuteChanged();
        GoRemoteCommand.NotifyCanExecuteChanged();
        RefreshSteps();
    }
}
