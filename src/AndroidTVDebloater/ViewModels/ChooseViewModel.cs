using System.ComponentModel;
using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Models;
using AndroidTVDebloater.Services;
using Avalonia;
using Avalonia.Controls;
using Avalonia.Controls.ApplicationLifetimes;
using Avalonia.Platform.Storage;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;

namespace AndroidTVDebloater.ViewModels;

public sealed partial class ChooseViewModel : PageViewModel
{
    public override int StepIndex => 2;

    public TvDevice Device { get; }
    public List<TweakGroup> Groups { get; }

    [ObservableProperty] private bool _rebootAfter = true;

    /// <summary>Choices from the last run (possibly on another TV), offered at the top.</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasLastChoices), nameof(LastChoicesText))]
    private ChoiceSet? _lastChoices;

    [ObservableProperty] private string _choicesStatus = "";

    /// <summary>Things earlier runs changed that are back (usually after a system update).</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(HasReturned), nameof(ReturnedText), nameof(ReturnedNames))]
    private ReturnedItems? _returned;

    /// <summary>Fire TV only: Amazon's tracking switches that are on (string keys of their names).</summary>
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(FirePrivacyText), nameof(FirePrivacyAllOff))]
    private List<string>? _firePrivacyOn;

    /// <summary>Preinstalled apps the catalog doesn't know, for the report to GitHub.</summary>
    public List<string> UnknownApps { get; }

    public ChooseViewModel(MainViewModel main, TvDevice device) : base(main)
    {
        Device = device;
        Groups = Catalog.Build(device);
        UnknownApps = Catalog.UnknownSystemApps(device);
        LastChoices = ChoiceSet.LoadLast();
        foreach (var t in AllTweaks) t.PropertyChanged += OnTweakChanged;
        _ = CheckReturnedAsync();
        _ = CheckDoneAsync();
        if (device.IsFireTv) _ = CheckFirePrivacyAsync();
    }

    private async Task CheckReturnedAsync()
    {
        try
        {
            var r = await ReturnCheck.FindAsync(Main.Adb, Device);
            // Only worth showing if something on this list can be ticked again.
            if (r is not null && MatchingTweaks(r).Any())
            {
                Returned = r;
                LastChoices = null; // one suggestion at a time
            }
        }
        catch { /* only a suggestion */ }
    }

    /// <summary>Settings the TV already has are shown as done and unticked. Reading changes nothing.</summary>
    private async Task CheckDoneAsync()
    {
        foreach (var t in AllTweaks.Where(t => t is not PackageTweak).ToList())
        {
            try
            {
                var (done, option) = await t.ReadDoneAsync(Main.Adb, Device);
                if (!done) continue;
                if (option is not null) t.SelectedOption = option;
                t.IsSelected = false;
                t.IsDone = true;
            }
            catch { /* only a hint */ }
        }
    }

    private IEnumerable<(Tweak Tweak, SettingChange? Setting)> MatchingTweaks(ReturnedItems r)
    {
        foreach (var t in AllTweaks)
        {
            if (t is PackageTweak p && r.Packages.Contains(p.Package)) yield return (t, null);
            else if (r.Settings.FirstOrDefault(s => t.SettingKeys.Contains(s.Key)) is { } s) yield return (t, s);
        }
    }

    private IEnumerable<Tweak> AllTweaks => Groups.SelectMany(g => g.Tweaks);

    public int SelectedCount => AllTweaks.Count(t => t.IsSelected);
    public string SelectedText => Loc.Format(SelectedCount == 1 ? "Choose_SelectedOne" : "Choose_Selected", SelectedCount);
    public string ApplyText => Loc.Format("Choose_Apply", SelectedCount);
    public bool CanApply => SelectedCount > 0;

    public string AndroidText => Device.FireOsVersion.Length > 0
        ? Device.FireOsVersion.Split(" (")[0]
        : Loc.Format("Choose_Android", Device.AndroidVersion);
    public string RamText => Loc.Format("Choose_Ram", (Device.RamMb / 1024.0).ToString("0.#", Loc.Culture));
    public string StorageText => Loc.Format("Choose_Storage", (Device.StorageFreeMb / 1024.0).ToString("0.#", Loc.Culture));
    public bool HasStorage => Device.StorageTotalMb > 0;
    public string NetworkText => Loc.Get(Device.Network switch
    {
        NetworkKind.Ethernet => "Choose_NetEthernet",
        NetworkKind.WiFi => "Choose_NetWifi",
        _ => "Choose_NetUnknown",
    });
    public string AlreadyOffText => Device.Disabled.Count == 1 ? Loc.Get("Choose_AlreadyOffOne") : Loc.Format("Choose_AlreadyOff", Device.Disabled.Count);
    public bool HasAlreadyOff => Device.Disabled.Count > 0;

    public bool HasLastChoices => LastChoices is not null;
    public string LastChoicesText => LastChoices is null ? ""
        : Loc.Format("Choose_LastText", LastChoices.Choices.Count, LastChoices.Name, LastChoices.CreatedText);

    public bool HasReturned => Returned is not null;
    /// <summary>Counts only what can be ticked again, so they match the list below.</summary>
    public string ReturnedText
    {
        get
        {
            if (Returned is null) return "";
            var matches = MatchingTweaks(Returned).ToList();
            return Loc.Format("Choose_ReturnedText", Returned.Since.ToString("g", Loc.Culture),
                matches.Count(m => m.Setting is null), matches.Count(m => m.Setting is not null));
        }
    }

    public List<string> ReturnedNames => Returned is null ? [] : MatchingTweaks(Returned).Select(m => "• " + m.Tweak.Title).ToList();

    public bool IsFireTv => Device.IsFireTv;
    public bool FirePrivacyAllOff => FirePrivacyOn is [];
    public string FirePrivacyText => FirePrivacyOn switch
    {
        null => "",
        [] => Loc.Get("Fire_PrivacyAllOff"),
        var on => Loc.Format("Fire_PrivacyOn", string.Join(", ", on.Select(Loc.Get))),
    };

    public bool HasUnknownApps => UnknownApps.Count > 0;
    public string UnknownText => Loc.Format("Choose_UnknownText", UnknownApps.Count);

    private void OnTweakChanged(object? sender, PropertyChangedEventArgs e)
    {
        // A chosen option that needs an app (e.g. Google Ambient) unticks turning that app off.
        if (sender is Tweak { KeptPackages.Count: > 0 } t && e.PropertyName is nameof(Tweak.IsSelected) or nameof(Tweak.SelectedOption))
            foreach (var other in AllTweaks.OfType<PackageTweak>().Where(p => t.KeptPackages.Contains(p.Package)))
                other.IsSelected = false;
        if (e.PropertyName != nameof(Tweak.IsSelected)) return;
        OnPropertyChanged(nameof(SelectedCount));
        OnPropertyChanged(nameof(SelectedText));
        OnPropertyChanged(nameof(ApplyText));
        OnPropertyChanged(nameof(CanApply));
        ApplyCommand.NotifyCanExecuteChanged();
    }

    public override void OnLanguageChanged()
    {
        foreach (var g in Groups) g.RefreshTexts();
        foreach (var name in new[]
                 {
                     nameof(SelectedText), nameof(ApplyText), nameof(AndroidText), nameof(RamText), nameof(StorageText),
                     nameof(NetworkText), nameof(AlreadyOffText), nameof(LastChoicesText), nameof(UnknownText), nameof(ReturnedText), nameof(ReturnedNames),
                     nameof(FirePrivacyText),
                 })
            OnPropertyChanged(name);
    }

    [RelayCommand]
    private void SelectRecommended()
    {
        foreach (var t in AllTweaks) t.IsSelected = t.Recommended && !t.IsDone;
        ChoicesStatus = "";
    }

    [RelayCommand]
    private void SelectNone()
    {
        foreach (var t in AllTweaks) t.IsSelected = false;
        ChoicesStatus = "";
    }

    [RelayCommand]
    private void UseLastChoices()
    {
        if (LastChoices is null) return;
        UseChoices(LastChoices);
        LastChoices = null;
    }

    [RelayCommand]
    private void DismissLastChoices() => LastChoices = null;

    /// <summary>Tick exactly what came back, with the values chosen last time.</summary>
    [RelayCommand]
    private void TickReturned()
    {
        if (Returned is null) return;
        var matches = MatchingTweaks(Returned).ToList();
        foreach (var t in AllTweaks) t.IsSelected = false;
        foreach (var (t, setting) in matches)
        {
            t.IsSelected = true;
            // Pick the option that gives the value set last time ("16" or "max_cached_processes=16").
            if (setting is not null && t.Options?.FirstOrDefault(o =>
                    setting.NewValue == o.Value || setting.NewValue.EndsWith("=" + o.Value)) is { } option)
                t.SelectedOption = option;
        }
        ChoicesStatus = Loc.Format("Choose_ReturnedTicked", matches.Count);
        Returned = null;
    }

    [RelayCommand]
    private void DismissReturned() => Returned = null;

    [RelayCommand]
    private async Task CheckFirePrivacyAsync()
    {
        try { FirePrivacyOn = await FirePrivacy.ReadOnAsync(Main.Adb, Device.Serial); }
        catch { /* only advice */ }
    }

    [RelayCommand]
    private Task OpenFirePrivacyAsync() => FirePrivacy.OpenOnTvAsync(Main.Adb, Device.Serial);

    private void UseChoices(ChoiceSet set)
    {
        var found = set.ApplyTo(AllTweaks);
        var missing = set.Choices.Count - found;
        ChoicesStatus = missing == 0
            ? Loc.Format("Choose_UsedAll", found)
            : Loc.Format("Choose_UsedSome", found, missing);
    }

    private static TopLevel? Window =>
        (Application.Current?.ApplicationLifetime as IClassicDesktopStyleApplicationLifetime)?.MainWindow;

    private static readonly FilePickerFileType ChoicesFileType = new("UnbloatMyTV")
    {
        Patterns = ["*.json"],
        AppleUniformTypeIdentifiers = ["public.json"],
        MimeTypes = ["application/json"],
    };

    [RelayCommand]
    private async Task SaveChoicesAsync()
    {
        if (Window?.StorageProvider is not { } storage) return;
        var file = await storage.SaveFilePickerAsync(new FilePickerSaveOptions
        {
            Title = Loc.Get("Choose_SaveChoices"),
            SuggestedFileName = $"choices-{Device.DisplayName}.json",
            DefaultExtension = "json",
            FileTypeChoices = [ChoicesFileType],
        });
        if (file is null) return;
        await using var s = await file.OpenWriteAsync();
        ChoiceSet.From(Device.DisplayName, AllTweaks.Where(t => t.IsSelected)).Save(s);
        ChoicesStatus = Loc.Format("Choose_Saved", file.Name);
    }

    [RelayCommand]
    private async Task LoadChoicesAsync()
    {
        if (Window?.StorageProvider is not { } storage) return;
        var files = await storage.OpenFilePickerAsync(new FilePickerOpenOptions
        {
            Title = Loc.Get("Choose_LoadChoices"),
            FileTypeFilter = [ChoicesFileType],
        });
        if (files.Count == 0) return;
        await using var s = await files[0].OpenReadAsync();
        if (ChoiceSet.Load(s) is { } set) UseChoices(set);
        else ChoicesStatus = Loc.Get("Choose_LoadFailed");
    }

    /// <summary>Open a pre-filled GitHub issue. Nothing is posted until the user submits it there.</summary>
    [RelayCommand]
    private async Task ReportUnknownAsync()
    {
        var (url, complete) = IssueReport.Build(Device, UnknownApps, Catalog.Version);
        if (!complete && Window?.Clipboard is { } clipboard)
            await clipboard.SetTextAsync(string.Join("\n", UnknownApps));
        UpdateService.OpenInBrowser(url);
    }

    [RelayCommand]
    private async Task CopyUnknownAsync()
    {
        if (Window?.Clipboard is { } clipboard)
            await clipboard.SetTextAsync(string.Join("\n", UnknownApps));
    }

    [RelayCommand(CanExecute = nameof(CanApply))]
    private void Apply() => Main.GoApply(AllTweaks.Where(t => t.IsSelected).ToList(), RebootAfter);

    [RelayCommand]
    private void ChangeTv() => Main.GoConnect();
}
