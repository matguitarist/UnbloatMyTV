using System.Collections.ObjectModel;
using Avalonia.Media.Imaging;
using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Models;
using AndroidTVDebloater.Services;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AndroidTVDebloater.ViewModels;

/// <summary>One installed app, with its actions. Uninstall waits for a yes on the row.</summary>
public sealed partial class AppRow : ObservableObject
{
    private readonly AppsViewModel _page;

    public AppRow(AppsViewModel page, InstalledApp app, bool isProtected)
    {
        _page = page;
        _app = app;
        IsProtected = isProtected;
        _name = Catalog.NameOf(app.Package) ?? app.Package;
    }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(Details), nameof(IsOff), nameof(CanOpen), nameof(CanTurnOff), nameof(CanTurnOn), nameof(CanStop))]
    private InstalledApp _app;

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasFriendlyName))]
    private string _name;

    /// <summary>The icon as the TV draws it; null until it arrives, or when the TV can't give one.</summary>
    [ObservableProperty] private Bitmap? _icon;
    public string Package => App.Package;
    public bool HasFriendlyName => Name != Package;

    /// <summary>The name on the TV wins over the catalog's.</summary>
    public void SetFromTv(TvIcons.Entry e)
    {
        if (e.Label.Length > 0 && e.Label != Package) Name = e.Label;
        if (e.Png is { } png && Icon is null)
            try { Icon = new Bitmap(new MemoryStream(png)); } catch { }
    }
    public bool IsProtected { get; }
    public bool IsOff => !App.IsEnabled && !IsGone;
    /// <summary>From a file or another source, not from Google Play, the Amazon Appstore or the TV's maker.</summary>
    public bool IsOutside => !IsGone && !App.FromStore && !App.ByMaker;

    /// <summary>"Uninstalled" once done; the row stays until the next refresh.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(GoneText), nameof(IsGone), nameof(IsPresent), nameof(IsOff), nameof(IsOutside), nameof(CanOpen), nameof(CanTurnOff), nameof(CanTurnOn),
        nameof(CanStop), nameof(CanRemove))]
    private string _goneKey = "";

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(CanOpen), nameof(CanTurnOff), nameof(CanTurnOn), nameof(CanStop), nameof(CanRemove))]
    private bool _isWorking;

    /// <summary>Waiting for a yes before uninstalling.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(ConfirmText))]
    private bool _isConfirming;

    public string GoneText => IsGone ? Loc.Get(GoneKey) : "";
    public bool IsGone => GoneKey.Length > 0;
    public bool IsPresent => !IsGone;
    private bool Ready => !IsGone && !IsWorking;
    public bool CanOpen => Ready && App.IsEnabled && App.HasIcon;
    public bool CanStop => Ready && App.IsEnabled;
    public bool CanTurnOff => Ready && App.IsEnabled && !IsProtected;
    public bool CanTurnOn => Ready && !App.IsEnabled;
    public bool CanRemove => Ready && !IsProtected;

    public string ConfirmText => IsConfirming ? Loc.Format("Apps_ConfirmUninstall", Name) : "";

    /// <summary>"4.16.8 · 245 MB · Google Play · installed 11 Sep 2025"</summary>
    public string Details
    {
        get
        {
            var parts = new List<string>();
            if (App.Version.Length > 0) parts.Add(Loc.Format("Apps_Version", App.Version));
            if (App.TotalBytes > 0) parts.Add(Size(App.TotalBytes));
            parts.Add(Origin(App));
            if (App.Installed is { } d) parts.Add(Loc.Format("Apps_InstalledOn", d.ToString("d MMM yyyy", Loc.Culture)));
            return string.Join(" · ", parts);
        }
    }

    public static string Size(long bytes)
    {
        var mb = bytes / 1024.0 / 1024.0;
        return mb < 1 ? Loc.Get("Apps_LessThanMb")
            : mb < 1000 ? Loc.Format("Apps_Mb", mb.ToString("0", Loc.Culture))
            : Loc.Format("Apps_Gb", (mb / 1024).ToString("0.0", Loc.Culture));
    }

    public static string Origin(InstalledApp app) => app.ByMaker ? Loc.Get("Apps_FromMaker") : app.Installer switch
    {
        "com.android.vending" => "Google Play",
        "com.amazon.venezia" => "Amazon Appstore",
        "" or "com.android.shell" or "com.google.android.packageinstaller" or "com.android.packageinstaller" => Loc.Get("Apps_FromFile"),
        "com.esaba.downloader" => "Downloader",
        "com.aurora.store" => "Aurora Store",
        "org.fdroid.fdroid" or "org.fdroid.basic" => "F-Droid",
        _ => app.Installer,
    };

    public void Refresh()
    {
        foreach (var name in new[] { nameof(Details), nameof(ConfirmText), nameof(GoneText) }) OnPropertyChanged(name);
    }

    [RelayCommand] private Task OpenAsync() => _page.RunAsync(this, "Apps_Opened", UserApps.OpenAsync);
    [RelayCommand] private Task ShowInfoAsync() => _page.RunAsync(this, "Apps_InfoOpened", UserApps.ShowInfoAsync);
    [RelayCommand] private Task StopAsync() => _page.RunAsync(this, "Apps_Stopped", UserApps.StopAsync);
    [RelayCommand] private Task TurnOffAsync() => _page.SetEnabledAsync(this, false);
    [RelayCommand] private Task TurnOnAsync() => _page.SetEnabledAsync(this, true);
    [RelayCommand] private void AskUninstall() => IsConfirming = true;
    [RelayCommand] private void Cancel() => IsConfirming = false;
    [RelayCommand] private Task ConfirmAsync() => _page.UninstallAsync(this);
}

/// <summary>The apps the owner installed: size, origin, and open, stop, turn off or uninstall.</summary>
public sealed partial class AppsViewModel : PageViewModel
{
    public override int StepIndex => 2;
    public TvDevice Device { get; }

    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsEmpty))]
    private bool _isLoading;

    [ObservableProperty] private string _error = "";
    [ObservableProperty] private string _status = "";
    [ObservableProperty] private bool _statusIsError;
    [ObservableProperty] private string _search = "";
    /// <summary>0 name, 1 size, 2 most recently installed.</summary>
    [ObservableProperty] private int _sortIndex;

    private List<AppRow> _all = [];
    public ObservableCollection<AppRow> Rows { get; } = [];
    public bool IsEmpty => !IsLoading && Error.Length == 0 && _all.Count == 0;
    public bool NoMatch => _all.Count > 0 && Rows.Count == 0;
    public string Summary => Loc.Format("Apps_Summary", _all.Count(r => !r.IsGone), AppRow.Size(_all.Where(r => !r.IsGone).Sum(r => r.App.TotalBytes)));
    public string[] SortOptions => [Loc.Get("Apps_SortName"), Loc.Get("Apps_SortSize"), Loc.Get("Apps_SortNewest")];

    private IEnumerable<AppRow> Outside => _all.Where(r => r.IsOutside);
    public bool HasOutside => Outside.Any();
    public string OutsideText => Loc.Format("Apps_Outside", Outside.Count());
    /// <summary>"• Name · installed from a file", one line per app, by name.</summary>
    public string OutsideList => string.Join("\n", Outside.OrderBy(r => r.Name, StringComparer.Create(Loc.Culture, true))
        .Select(r => $"• {r.Name} · {AppRow.Origin(r.App)}"));
    /// <summary>The list shows only the apps that didn't come from a store.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(OnlyOutsideText))]
    private bool _onlyOutside;
    public string OnlyOutsideText => Loc.Get(OnlyOutside ? "Apps_ShowAll" : "Apps_ShowOutside");

    /// <summary>One backup per visit, started on the first app turned off or on.</summary>
    private Backup? _backup;

    /// <param name="read">False only for screenshots, which call <see cref="Show"/> themselves.</param>
    public AppsViewModel(MainViewModel main, TvDevice device, bool read = true) : base(main)
    {
        Device = device;
        if (read) _ = RefreshAsync();
    }

    /// <summary>The list for these apps, with icons and names when given.</summary>
    public void Show(IEnumerable<InstalledApp> apps, IReadOnlyDictionary<string, TvIcons.Entry>? fromTv = null)
    {
        _all = apps.Select(a => new AppRow(this, a, IsNeeded(a))).ToList();
        foreach (var r in _all)
            if (fromTv?.TryGetValue(r.Package, out var e) == true) r.SetFromTv(e);
        Filter();
    }

    partial void OnSearchChanged(string value) => Filter();
    partial void OnSortIndexChanged(int value) => Filter();
    partial void OnOnlyOutsideChanged(bool value) => Filter();
    [RelayCommand] private void ToggleOutside() => OnlyOutside = !OnlyOutside;

    public override void OnLanguageChanged()
    {
        var sort = SortIndex;
        OnPropertyChanged(nameof(SortOptions));
        OnPropertyChanged(nameof(OnlyOutsideText));
        SortIndex = sort;
        foreach (var r in _all) r.Refresh();
        Counts();
    }

    [RelayCommand]
    private async Task RefreshAsync()
    {
        IsLoading = true;
        Error = "";
        Status = "";
        try
        {
            var apps = await UserApps.ReadAsync(Main.Adb, Device);
            _all = apps.Select(a => new AppRow(this, a, IsNeeded(a))).ToList();
        }
        catch (Exception ex)
        {
            Error = ex.Message;
            _all = [];
        }
        finally
        {
            IsLoading = false;
            Filter();
        }
        await LoadIconsAsync();
    }

    /// <summary>Icons and names from the TV, after the list shows: they take a few seconds the first time.</summary>
    private async Task LoadIconsAsync()
    {
        var rows = _all;
        if (rows.Count == 0) return;
        var found = await TvIcons.ReadAsync(Main.Adb, Device.Serial, rows.Select(r => r.App).ToList());
        if (rows != _all || found.Count == 0) return;  // refreshed meanwhile
        foreach (var r in rows)
            if (found.TryGetValue(r.Package, out var e)) r.SetFromTv(e);
        // Re-sort by the real names, unless someone is waiting on a yes on a row.
        if (SortIndex == 0 && rows.All(r => !r.IsConfirming)) Filter();
        else Counts();
    }

    /// <summary>
    /// The home screen, keyboard and so on, Amazon's apps on Fire TV, and the parts of Android that Google
    /// installs through Play: those have no icon and aren't in the catalog.
    /// </summary>
    private bool IsNeeded(InstalledApp a) =>
        Device.IsProtected(a.Package) || Catalog.IsKeep(a.Package) ||
        (!a.HasIcon && a.Package.StartsWith("com.google.android.") && Catalog.NameOf(a.Package) is null);

    private void Filter()
    {
        var q = Search.Trim();
        IEnumerable<AppRow> rows = _all.Where(r => (!OnlyOutside || r.IsOutside) && (q.Length == 0
            || r.Name.Contains(q, StringComparison.OrdinalIgnoreCase) || r.Package.Contains(q, StringComparison.OrdinalIgnoreCase)));
        rows = SortIndex switch
        {
            1 => rows.OrderByDescending(r => r.App.TotalBytes),
            2 => rows.OrderByDescending(r => r.App.Installed ?? DateTime.MinValue),
            _ => rows.OrderBy(r => r.Name, StringComparer.Create(Loc.Culture, true)),
        };
        Rows.Clear();
        foreach (var r in rows) Rows.Add(r);
        Counts();
    }

    private void Counts()
    {
        if (OnlyOutside && !HasOutside) OnlyOutside = false;
        foreach (var name in new[] { nameof(IsEmpty), nameof(NoMatch), nameof(Summary), nameof(HasOutside), nameof(OutsideText), nameof(OutsideList) })
            OnPropertyChanged(name);
    }

    private void Report(string? error, string okKey, AppRow row)
    {
        StatusIsError = error is not null;
        Status = error is null ? Loc.Format(okKey, row.Name) : Loc.Format("Apps_Failed", row.Name, error);
    }

    public async Task RunAsync(AppRow row, string okKey, Func<AdbService, string, string, Task<string?>> action)
    {
        if (row.IsWorking) return;
        row.IsWorking = true;
        var error = await action(Main.Adb, Device.Serial, row.Package);
        row.IsWorking = false;
        Report(error, okKey, row);
    }

    /// <summary>Saved in a backup before the change, like every other change, so Restore can undo it.</summary>
    public async Task SetEnabledAsync(AppRow row, bool on)
    {
        if (row.IsWorking) return;
        row.IsWorking = true;
        _backup ??= Backup.Start(Device);
        var list = on ? _backup.EnabledPackages : _backup.DisabledPackages;
        list.Add(row.Package);
        _backup.Save();
        var error = await UserApps.SetEnabledAsync(Main.Adb, Device.Serial, row.Package, on);
        row.IsWorking = false;
        if (error is not null)
        {
            list.Remove(row.Package);
            _backup.Save();
        }
        else
        {
            row.App = row.App with { IsEnabled = on };
            // Keep the connected TV's lists in step, for the other pages.
            (on ? Device.Disabled : Device.Enabled).Remove(row.Package);
            (on ? Device.Enabled : Device.Disabled).Add(row.Package);
        }
        Report(error, on ? "Apps_TurnedOn" : "Apps_TurnedOff", row);
    }

    /// <summary>After the yes on the row. Can't be undone from Restore.</summary>
    public async Task UninstallAsync(AppRow row)
    {
        row.IsConfirming = false;
        if (!row.CanRemove) return;
        row.IsWorking = true;
        var error = await UserApps.UninstallAsync(Main.Adb, Device.Serial, row.Package);
        row.IsWorking = false;
        if (error is null)
        {
            row.GoneKey = "Apps_Uninstalled";
            Device.Enabled.Remove(row.Package);
            Device.Disabled.Remove(row.Package);
            Device.ThirdParty.Remove(row.Package);
            Counts();
        }
        Report(error, "Apps_UninstalledDone", row);
    }
}
