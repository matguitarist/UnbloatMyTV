using System.Collections.ObjectModel;
using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Models;
using AndroidTVDebloater.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AndroidTVDebloater.ViewModels;

public sealed record InfoRow(string Label, string Value);

/// <summary>
/// Software, updates and boot security, with shortcuts to the TV's own screens and a restart button.
/// Reading changes nothing; buttons only open screens on the TV, except Restart, which asks first.
/// </summary>
public sealed partial class TvInfoViewModel : PageViewModel
{
    public override int StepIndex => 2;
    public TvDevice Device { get; private set; }

    [ObservableProperty] private bool _isLoading;
    [ObservableProperty] private TvInfo? _info;
    [ObservableProperty] private string _error = "";
    [ObservableProperty] private string _status = "";
    [ObservableProperty] private bool _statusIsError;

    /// <summary>Restart pressed, waiting for a yes.</summary>
    [ObservableProperty] private bool _isConfirmingRestart;

    /// <summary>The TV is restarting: the page waits for it to come back.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanRestart))]
    private bool _isRestarting;

    public ObservableCollection<HealthTip> Tips { get; } = [];
    public ObservableCollection<InfoRow> Software { get; } = [];
    public ObservableCollection<InfoRow> Boot { get; } = [];

    /// <param name="read">False only for screenshots, which set <see cref="Info"/> themselves.</param>
    public TvInfoViewModel(MainViewModel main, TvDevice device, bool read = true) : base(main)
    {
        Device = device;
        if (read) _ = RefreshAsync();
    }

    public bool HasInfo => Info is not null;
    public bool CanRestart => !IsRestarting;

    partial void OnInfoChanged(TvInfo? value) => Rebuild();
    partial void OnIsRestartingChanged(bool value) => Main.RefreshSteps();

    public override void OnLanguageChanged() => Rebuild();

    [RelayCommand]
    private async Task RefreshAsync()
    {
        IsLoading = true;
        Error = "";
        try { Info = await TvInfo.ReadAsync(Main.Adb, Device.Serial); }
        catch (Exception ex) { Error = ex.Message; }
        finally { IsLoading = false; }
    }

    private void Rebuild()
    {
        Tips.Clear();
        Software.Clear();
        Boot.Clear();
        OnPropertyChanged(nameof(HasInfo));
        if (Info is not { } i) return;

        var unknown = Loc.Get("Info_Unknown");
        var age = i.PatchAgeMonths(DateOnly.FromDateTime(DateTime.Today));
        var ago = age switch { 0 => Loc.Get("Info_ThisMonth"), 1 => Loc.Get("Info_OneMonth"), _ => Loc.Format("Info_MonthsAgo", age) };

        Software.Add(new InfoRow(Loc.Get("Info_Android"), Loc.Format("Info_AndroidValue", i.AndroidVersion)));
        if (Device.FireOsVersion.Length > 0) Software.Add(new InfoRow("Fire OS", Device.FireOsVersion.Replace("Fire OS ", "")));
        Software.Add(new InfoRow(Loc.Get("Info_Patch"), i.SecurityPatch is { } p
            ? Loc.Format("Info_PatchValue", p.ToString("d MMMM yyyy", Loc.Culture), ago)
            : unknown));
        Software.Add(new InfoRow(Loc.Get("Info_Build"), i.BuildId.Length > 0 ? i.BuildId : unknown));

        Boot.Add(new InfoRow(Loc.Get("Info_Bootloader"),
            i.BootloaderLocked switch { true => Loc.Get("Info_Locked"), false => Loc.Get("Info_Unlocked"), _ => unknown }));
        Boot.Add(new InfoRow(Loc.Get("Info_Verified"), VerifiedText(i.VerifiedBoot)));

        // TVs get fewer updates than phones: a year without one is when to worry.
        if (age >= 12) Tips.Add(new HealthTip(Loc.Format("Info_TipPatchVeryOld", age), TipKind.Warn));
        else if (age >= 6) Tips.Add(new HealthTip(Loc.Format("Info_TipPatchOld", age), TipKind.Warn));
        else if (age >= 0) Tips.Add(new HealthTip(Loc.Get("Info_TipPatchOk"), TipKind.Ok));
        if (i.BootloaderLocked == false) Tips.Add(new HealthTip(Loc.Get("Info_TipUnlocked"), TipKind.Warn));
        else if (i.VerifiedBoot is not ("" or "green")) Tips.Add(new HealthTip(Loc.Format("Info_TipNotVerified", VerifiedText(i.VerifiedBoot)), TipKind.Warn));
        else if (i.BootloaderLocked == true && i.VerifiedBoot == "green") Tips.Add(new HealthTip(Loc.Get("Info_TipLocked"), TipKind.Ok));
    }

    private static string VerifiedText(string state) => state switch
    {
        "green" => Loc.Get("Info_VbGreen"),
        "yellow" => Loc.Get("Info_VbYellow"),
        "orange" => Loc.Get("Info_VbOrange"),
        "red" => Loc.Get("Info_VbRed"),
        _ => Loc.Get("Info_Unknown"),
    };

    /// <summary>Android TV keeps System update under About, so that is the fallback.</summary>
    [RelayCommand]
    private async Task OpenUpdateAsync()
    {
        if (!await OpenOnTvAsync("am start -a android.settings.SYSTEM_UPDATE_SETTINGS", "Info_UpdateOpened"))
            await OpenOnTvAsync("am start -a android.settings.DEVICE_INFO_SETTINGS", "Info_AboutOpened");
    }

    /// <summary>About, where Reset is. Android doesn't let anything else open the reset screen itself.</summary>
    [RelayCommand]
    private async Task OpenAboutAsync()
    {
        if (!await OpenOnTvAsync("am start -a android.settings.DEVICE_INFO_SETTINGS", "Info_AboutOpened"))
            await OpenOnTvAsync("am start -a android.settings.SETTINGS", "Info_SettingsOpened");
    }

    private async Task<bool> OpenOnTvAsync(string command, string okKey)
    {
        var r = await Main.Adb.ShellAsync(Device.Serial, command);
        var failed = r.All.Contains("Error", StringComparison.OrdinalIgnoreCase) || r.All.Contains("Exception");
        StatusIsError = failed;
        Status = failed ? Loc.Format("Info_OpenFailed", r.All.Split('\n').LastOrDefault(l => l.Trim().Length > 0)?.Trim() ?? "") : Loc.Get(okKey);
        return !failed;
    }

    [RelayCommand]
    private void AskRestart() => IsConfirmingRestart = true;

    [RelayCommand]
    private void CancelRestart() => IsConfirmingRestart = false;

    /// <summary>Restarts the TV and waits for it, reconnecting over the network, like the Apply page does.</summary>
    [RelayCommand]
    private async Task RestartAsync()
    {
        if (!IsConfirmingRestart || IsRestarting) return;
        IsConfirmingRestart = false;
        IsRestarting = true;
        StatusIsError = false;
        Status = Loc.Get("Info_Restarting");
        var serial = Device.Serial;
        await Main.Adb.RunAsync(["-s", serial, "reboot"], 15000);
        await Task.Delay(8000);
        var back = false;
        var deadline = DateTime.UtcNow.AddMinutes(4);
        while (DateTime.UtcNow < deadline && !back)
        {
            if (serial.Contains(':')) await Main.Adb.RunAsync(["connect", serial], 10000);
            back = await Main.Adb.GetStateAsync(serial) == "device"
                && (await Main.Adb.ShellAsync(serial, "getprop sys.boot_completed", 8000)).Output.Trim() == "1";
            if (!back) await Task.Delay(3000);
        }
        if (back)
        {
            try { Main.Device = Device = await TvDevice.LoadAsync(Main.Adb, serial); } catch { }
            Status = Loc.Get("Info_RestartDone");
        }
        else
        {
            StatusIsError = true;
            Status = Loc.Get("Info_RestartTimeout");
        }
        IsRestarting = false;
        if (back) await RefreshAsync();
    }
}

public sealed partial class AccessRow(AppAccess access, string name) : ObservableObject
{
    public AppAccess Access { get; } = access;
    public string Name { get; } = name;
    public string Package => Access.Package;
    public bool HasFriendlyName => Name != Package;
    public string Origin => Loc.Get(Access.IsThirdParty ? "Privacy_Yours" : "Privacy_Preinstalled");

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanRemove))]
    private bool _isRemoved;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanRemove))]
    private bool _isWorking;

    public bool CanRemove => !IsRemoved && !IsWorking;
    public void Refresh() => OnPropertyChanged(nameof(Origin));
}

public sealed partial class AccessGroup(AccessKind kind) : ObservableObject
{
    public AccessKind Kind { get; } = kind;
    public string Title => Loc.Get("Privacy_Kind_" + Kind.Key);
    public string Text => Loc.Get("Privacy_KindText_" + Kind.Key);
    public string CountText => Loc.Format("Privacy_Count", Rows.Count);
    public ObservableCollection<AccessRow> Rows { get; } = [];

    public void Refresh()
    {
        foreach (var name in new[] { nameof(Title), nameof(Text), nameof(CountText) }) OnPropertyChanged(name);
        foreach (var r in Rows) r.Refresh();
    }
}

/// <summary>Which apps can use the microphone, camera, location and personal data, with one click to remove an access.</summary>
public sealed partial class PrivacyViewModel : PageViewModel
{
    public override int StepIndex => 2;
    public TvDevice Device { get; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsEmpty))]
    private bool _isLoading;

    [ObservableProperty] private string _error = "";
    [ObservableProperty] private string _status = "";
    [ObservableProperty] private bool _statusIsError;

    public ObservableCollection<AccessGroup> Groups { get; } = [];
    public bool IsEmpty => !IsLoading && Error.Length == 0 && Groups.Count == 0;

    /// <summary>One backup per visit, started on the first removal.</summary>
    private Backup? _backup;

    /// <param name="read">False only for screenshots, which call <see cref="Show"/> themselves.</param>
    public PrivacyViewModel(MainViewModel main, TvDevice device, bool read = true) : base(main)
    {
        Device = device;
        if (read) _ = RefreshAsync();
    }

    public override void OnLanguageChanged()
    {
        foreach (var g in Groups) g.Refresh();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        IsLoading = true;
        Error = "";
        Groups.Clear();
        try { Show(await Privacy.ReadAsync(Main.Adb, Device)); }
        catch (Exception ex) { Error = ex.Message; }
        finally
        {
            IsLoading = false;
            OnPropertyChanged(nameof(IsEmpty));
        }
    }

    /// <summary>One group per kind of access, your own apps first.</summary>
    public void Show(IReadOnlyList<AppAccess> all)
    {
        foreach (var kind in Privacy.Kinds)
        {
            var group = new AccessGroup(kind);
            foreach (var a in all.Where(a => a.Kind == kind)
                         .OrderBy(a => !a.IsThirdParty).ThenBy(a => Catalog.NameOf(a.Package) ?? a.Package))
                group.Rows.Add(new AccessRow(a, Catalog.NameOf(a.Package) ?? a.Package));
            if (group.Rows.Count > 0) Groups.Add(group);
        }
        OnPropertyChanged(nameof(IsEmpty));
    }

    [RelayCommand]
    private async Task RemoveAsync(AccessRow row)
    {
        if (!row.CanRemove) return;
        row.IsWorking = true;
        Status = "";
        StatusIsError = false;
        _backup ??= Backup.Start(Device);
        string? error = null;
        foreach (var perm in row.Access.Granted)
        {
            // Saved before the change, like every other change, so even an interrupted run can be undone.
            var change = new PermissionChange { Package = row.Package, Permission = perm };
            _backup.RevokedPermissions.Add(change);
            _backup.Save();
            error = await Privacy.RevokeAsync(Main.Adb, Device.Serial, row.Package, perm);
            if (error is null) continue;
            _backup.RevokedPermissions.Remove(change);
            _backup.Save();
            break;
        }
        row.IsWorking = false;
        if (error is not null)
        {
            StatusIsError = true;
            Status = Loc.Format("Privacy_Failed", row.Name, error);
            return;
        }
        row.IsRemoved = true;
        // Removing location also removed "all the time".
        if (row.Access.Kind.Key == "Location")
            foreach (var other in Groups.Where(g => g.Kind.Key == "BgLocation").SelectMany(g => g.Rows).Where(r => r.Package == row.Package))
                other.IsRemoved = true;
    }
}
