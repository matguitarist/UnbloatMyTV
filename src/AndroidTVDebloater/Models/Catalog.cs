using System.Globalization;
using System.Reflection;
using System.Text.Json;
using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Services;
using CommunityToolkit.Mvvm.ComponentModel;

namespace AndroidTVDebloater.Models;

public sealed partial class TweakGroup : ObservableObject
{
    public required string Id { get; init; }
    public required LocText TitleText { get; init; }
    public required LocText DescriptionText { get; init; }
    public List<Tweak> Tweaks { get; } = [];

    public string Title => TitleText.ToString();
    public string Description => DescriptionText.ToString();
    public string CountText => Loc.Format("Choose_GroupCount", Tweaks.Count(t => t.IsSelected), Tweaks.Count);

    /// <summary>Keep <see cref="CountText"/> up to date once the list is filled.</summary>
    public void WatchSelection()
    {
        foreach (var t in Tweaks)
            t.PropertyChanged += (_, e) =>
            {
                if (e.PropertyName == nameof(Tweak.IsSelected)) OnPropertyChanged(nameof(CountText));
            };
    }

    public void RefreshTexts()
    {
        OnPropertyChanged(nameof(Title));
        OnPropertyChanged(nameof(Description));
        OnPropertyChanged(nameof(CountText));
        foreach (var t in Tweaks) t.RefreshTexts();
    }
}

/// <summary>
/// Builds the list of choices for one TV from shared/catalog.json: the copy built into the app,
/// or a newer one downloaded from GitHub (same file the Mac app uses).
/// </summary>
public static class Catalog
{
    public const string BasicScreensaver = "com.android.dreams.basic/com.android.dreams.basic.Colors";
    /// <summary>Catalog format this version understands.</summary>
    private const int Schema = 1;
    private const string RemoteUrl = "https://raw.githubusercontent.com/matguitarist/UnbloatMyTV/main/shared/catalog.json";
    private static string CachePath => Path.Combine(AdbService.DataDir, "catalog.json");

    private sealed class Text { public string En { get; set; } = ""; public string Fr { get; set; } = ""; }
    private sealed class GroupDef { public string Id { get; set; } = ""; public Text Title { get; set; } = new(); public Text Description { get; set; } = new(); }
    private sealed class PackageDef
    {
        public string? Id { get; set; }
        public string? Prefix { get; set; }
        public string Group { get; set; } = "";
        public bool Recommended { get; set; }
        public bool RequiresSetupComplete { get; set; }
        public Text Name { get; set; } = new();
        public Text Description { get; set; } = new();
        public Text? Warning { get; set; }
    }
    /// <summary>A known system part that must stay on: never offered, never reported as unknown.</summary>
    private sealed class KeepDef { public string Id { get; set; } = ""; }
    /// <summary>A readable name for a system part (Health page). Never offered.</summary>
    private sealed class NameDef { public string Id { get; set; } = ""; public Text Name { get; set; } = new(); }
    private sealed class OptionDef
    {
        public Text Label { get; set; } = new();
        public string Value { get; set; } = "";
        /// <summary>Offered only when this app is installed (it is turned back on if needed).</summary>
        public string? Installed { get; set; }
        public List<string>? Keeps { get; set; }
    }
    private sealed class WriteDef { public string Namespace { get; set; } = ""; public string Key { get; set; } = ""; public string Value { get; set; } = ""; }
    private sealed class WhenDef
    {
        public int? MinSdk { get; set; }
        public int? MaxSdk { get; set; }
        public string? Network { get; set; }
        public string? Installed { get; set; }
        public string? ScreensaverIsNot { get; set; }
        public int? StorageFreeBelowPercent { get; set; }
    }
    private sealed class SettingDef
    {
        public string Id { get; set; } = "";
        public string Kind { get; set; } = "settings";
        public string Group { get; set; } = "";
        public bool Recommended { get; set; }
        public WhenDef? When { get; set; }
        public WhenDef? RecommendedWhen { get; set; }
        public Text Name { get; set; } = new();
        public Text Description { get; set; } = new();
        public Text? Warning { get; set; }
        public List<OptionDef>? Options { get; set; }
        public List<WriteDef>? Writes { get; set; }
    }
    private sealed class CatalogFile
    {
        public int Schema { get; set; }
        public int Version { get; set; }
        public List<GroupDef> Groups { get; set; } = [];
        public List<PackageDef> Packages { get; set; } = [];
        public List<SettingDef> Settings { get; set; } = [];
        public List<KeepDef> Keep { get; set; } = [];
        public List<NameDef> Names { get; set; } = [];
    }

    private static readonly JsonSerializerOptions Json = new()
    {
        PropertyNameCaseInsensitive = true,
        ReadCommentHandling = JsonCommentHandling.Skip,
    };

    /// <summary>Null if the text isn't a catalog this version can use.</summary>
    private static CatalogFile? Parse(string json)
    {
        try
        {
            var c = JsonSerializer.Deserialize<CatalogFile>(json, Json);
            if (c is null || c.Schema != Schema || c.Groups.Count == 0) return null;
            var groups = c.Groups.Select(g => g.Id).ToHashSet();
            if (!groups.Contains("other")) return null;
            if (c.Packages.Any(p => !groups.Contains(p.Group) || (p.Id is null && string.IsNullOrEmpty(p.Prefix)))) return null;
            if (c.Settings.Any(s => !groups.Contains(s.Group))) return null;
            return c;
        }
        catch { return null; }
    }

    private static readonly CatalogFile Embedded = LoadEmbedded();

    private static CatalogFile LoadEmbedded()
    {
        using var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("catalog.json")!;
        using var r = new StreamReader(s);
        return Parse(r.ReadToEnd()) ?? throw new InvalidDataException("built-in catalog.json is invalid");
    }

    private static CatalogFile _current = LoadCachedOrEmbedded();

    private static CatalogFile LoadCachedOrEmbedded()
    {
        try
        {
            if (File.Exists(CachePath) && Parse(File.ReadAllText(CachePath)) is { } cached && cached.Version > Embedded.Version)
                return cached;
        }
        catch { }
        return Embedded;
    }

    public static int Version => _current.Version;

    private static DateTime _lastRefresh = DateTime.MinValue;

    /// <summary>Fetch the latest list from GitHub; it is used from the next TV list on.
    /// Called at start and before each Choose page, at most once a minute.</summary>
    public static async Task RefreshFromGitHubAsync()
    {
        if (DateTime.UtcNow - _lastRefresh < TimeSpan.FromMinutes(1)) return;
        _lastRefresh = DateTime.UtcNow;
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(20) };
            var json = await http.GetStringAsync(RemoteUrl);
            if (Parse(json) is not { } remote || remote.Version <= _current.Version) return;
            Directory.CreateDirectory(AdbService.DataDir);
            await File.WriteAllTextAsync(CachePath, json);
            _current = remote;
        }
        catch { /* offline: keep what we have */ }
    }

    private static LocText L(Text t) => new(t.En, t.Fr);

    public static bool IsKeep(string pkg) => _current.Keep.Any(k => k.Id == pkg);

    /// <summary>Whether the catalog lists this exact app as one that can be turned off.</summary>
    public static bool IsListed(string pkg) => _current.Packages.Any(p => p.Id == pkg);

    /// <summary>The catalog's name for an app, in the current language, if it has one.</summary>
    /// <summary>A readable name for an app or process ("com.google.android.gms:snet" counts as its app).</summary>
    public static string? NameOf(string pkg)
    {
        var app = pkg.Split(':')[0];
        foreach (var id in new[] { pkg, app })
        {
            if (_current.Packages.FirstOrDefault(p => p.Id == id) is { } def) return L(def.Name).ToString();
            if (_current.Names.FirstOrDefault(n => n.Id == id) is { } n) return L(n.Name).ToString();
        }
        return null;
    }

    /// <summary>Whether the Choose page would offer to turn this app off on this TV.</summary>
    public static bool IsOffered(TvDevice dev, string pkg) =>
        dev.Enabled.Contains(pkg) && !dev.IsProtected(pkg) && !IsKeep(pkg) &&
        _current.Packages.Any(p => p.Id == pkg || (p.Prefix is { Length: > 0 } pre && pkg.StartsWith(pre)));

    private static bool IsKnown(CatalogFile c, string pkg) =>
        c.Keep.Any(k => k.Id == pkg) ||
        c.Packages.Any(p => p.Id == pkg || (p.Prefix is { Length: > 0 } pre && pkg.StartsWith(pre)));

    public static List<TweakGroup> Build(TvDevice dev)
    {
        var c = _current;
        var groups = c.Groups.Select(g => new TweakGroup
        {
            Id = g.Id, TitleText = L(g.Title), DescriptionText = L(g.Description),
        }).ToDictionary(g => g.Id);

        var used = new HashSet<string>();

        // Apps from the catalog, only those installed, enabled and not protected on this TV.
        foreach (var def in c.Packages)
        {
            if (def.RequiresSetupComplete && !dev.SetupComplete) continue;
            var matches = def.Id is not null
                ? (dev.Enabled.Contains(def.Id) ? [def.Id] : Array.Empty<string>())
                : dev.Enabled.Where(p => p.StartsWith(def.Prefix!)).OrderBy(p => p).ToArray();

            foreach (var pkg in matches)
            {
                if (dev.IsProtected(pkg) || !used.Add(pkg)) continue;
                groups[def.Group].Tweaks.Add(new PackageTweak
                {
                    Id = "pkg:" + pkg, GroupId = def.Group, Package = pkg, Recommended = def.Recommended,
                    TitleText = L(def.Name), DescriptionText = L(def.Description),
                    WarningText = def.Warning is null ? null : L(def.Warning),
                });
            }
        }

        // Apps installed on top of the system that the catalog doesn't know.
        foreach (var pkg in dev.ThirdParty.Where(dev.Enabled.Contains).OrderBy(p => p))
        {
            if (used.Contains(pkg) || dev.IsProtected(pkg) || c.Keep.Any(k => k.Id == pkg)) continue;
            groups["other"].Tweaks.Add(new PackageTweak
            {
                Id = "pkg:" + pkg, GroupId = "other", Package = pkg, Recommended = false,
                TitleText = new LocText(FriendlyName(pkg), FriendlyName(pkg)),
                DescriptionText = new LocText("Installed app.", "Application installée."),
            });
        }

        foreach (var def in c.Settings)
            if (Matches(def.When, dev) && CreateSetting(def, dev) is { } tweak)
                groups[def.Group].Tweaks.Add(tweak);

        var list = groups.Values.Where(g => g.Tweaks.Count > 0).ToList();
        foreach (var t in list.SelectMany(g => g.Tweaks))
        {
            t.SelectedOption ??= t.Options?.FirstOrDefault();
            t.IsSelected = t.Recommended;
        }
        foreach (var g in list) g.WatchSelection();
        return list;
    }

    private static bool Matches(WhenDef? w, TvDevice dev)
    {
        if (w is null) return true;
        if (w.MinSdk is { } sdk && dev.Sdk < sdk) return false;
        if (w.MaxSdk is { } max && dev.Sdk > max) return false;
        if (w.Network == "ethernet" && dev.Network != NetworkKind.Ethernet) return false;
        if (w.Network == "wifi" && dev.Network != NetworkKind.WiFi) return false;
        if (w.Installed is { } pkg && !dev.IsInstalled(pkg)) return false;
        if (w.ScreensaverIsNot is { } dream && dev.ScreensaverComponent == dream) return false;
        if (w.StorageFreeBelowPercent is { } pct && (dev.StorageTotalMb == 0 || dev.StorageFreeMb * 100 >= dev.StorageTotalMb * pct)) return false;
        return true;
    }

    /// <summary>A setting from the catalog, or null for a kind this version doesn't know.</summary>
    private static Tweak? CreateSetting(SettingDef def, TvDevice dev)
    {
        var description = L(def.Description);
        if (description.En.Contains("{storage}"))
            description = new LocText(description.En.Replace("{storage}", StorageText(dev, "en")),
                                      description.Fr.Replace("{storage}", StorageText(dev, "fr")));
        var options = def.Options?
            .Where(o => o.Installed is null || dev.IsInstalled(o.Installed))
            .Select(o => new TweakOption(L(o.Label), o.Value, o.Keeps)).ToList();
        var recommended = def.Recommended || (def.RecommendedWhen is not null && Matches(def.RecommendedWhen, dev));
        var title = L(def.Name);
        var warning = def.Warning is null ? null : L(def.Warning);

        return def.Kind switch
        {
            "settings" when def.Writes is { Count: > 0 } => new SettingsTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning, Options = options,
                Writes = def.Writes.Select(w => new SettingWrite(w.Namespace, w.Key, w.Value)).ToList(),
            },
            // A choice of screensaver, starting on the current one. Nothing to choose: not offered.
            "screensaver" when def.Writes is { Count: > 0 } && options is { Count: > 0 }
                               && !(options.Count == 1 && options[0].Value == dev.ScreensaverComponent) => new SettingsTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning, Options = options,
                SelectedOption = recommended ? options[0]
                    : options.FirstOrDefault(o => o.Value == dev.ScreensaverComponent) ?? options[0],
                CanBeDone = false,
                Writes = def.Writes.Select(w => new SettingWrite(w.Namespace, w.Key, w.Value)).ToList(),
            },
            "optimizeApps" => new OptimizeAppsTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning,
            },
            "backgroundLimit" => new BackgroundLimitTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning, Options = options,
            },
            "privateDns" => new PrivateDnsTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning, Options = options,
            },
            "wifiOff" => new WifiOffTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning,
            },
            "trimCaches" when dev.StorageTotalMb > 0 => new TrimCachesTweak
            {
                Id = def.Id, GroupId = def.Group, Recommended = recommended, TitleText = title,
                DescriptionText = description, WarningText = warning,
            },
            _ => null,
        };
    }

    private static string StorageText(TvDevice dev, string lang)
    {
        var culture = CultureInfo.GetCultureInfo(lang);
        string Gb(long mb) => (mb / 1024.0).ToString("0.0", culture);
        return lang == "fr"
            ? $"{Gb(dev.StorageFreeMb)} Go libres sur {Gb(dev.StorageTotalMb)} Go"
            : $"{Gb(dev.StorageFreeMb)} GB free of {Gb(dev.StorageTotalMb)} GB";
    }

    private static readonly string[] CorePrefixes =
    [
        "android.", "com.android.", "com.google.android.", "com.qualcomm.", "com.qti.", "org.codeaurora.",
        "com.mediatek.", "vendor.", "com.amlogic.", "com.realtek.", "com.broadcom.",
    ];

    /// <summary>
    /// Preinstalled apps the catalog doesn't know yet, for the "help improve the list" report.
    /// Android's own parts, chip vendor parts and resource overlays are left out.
    /// </summary>
    public static List<string> UnknownSystemApps(TvDevice dev) =>
        dev.Enabled.Concat(dev.Disabled)
            .Where(p => !dev.ThirdParty.Contains(p) && !dev.IsProtected(p) && !IsKnown(_current, p))
            .Where(p => !CorePrefixes.Any(p.StartsWith) && p != "android")
            .Where(p => !p.Contains("overlay", StringComparison.OrdinalIgnoreCase) && !p.Contains("auto_generated_rro")
                        && !p.EndsWith("rro", StringComparison.OrdinalIgnoreCase))
            .OrderBy(p => p)
            .ToList();

    private static string FriendlyName(string pkg)
    {
        var parts = pkg.Split('.');
        var last = parts.Length > 1 && parts[^1] is "android" or "tv" or "app" or "androidtv" ? parts[^2] : parts[^1];
        return last.Length == 0 ? pkg : char.ToUpperInvariant(last[0]) + last[1..];
    }
}
