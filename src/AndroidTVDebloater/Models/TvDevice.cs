using AndroidTVDebloater.Services;

namespace AndroidTVDebloater.Models;

public enum NetworkKind { Unknown, Ethernet, WiFi }

/// <summary>What we know about the connected TV. Read-only queries only.</summary>
public sealed class TvDevice
{
    public required string Serial { get; init; }
    public string Manufacturer { get; init; } = "";
    public string Brand { get; init; } = "";
    public string Model { get; init; } = "";
    public string SerialNo { get; init; } = "";
    public string AndroidVersion { get; init; } = "";
    /// <summary>"Fire OS 7.6.8.8 (PS7688/4591)" on Fire TV, empty elsewhere.</summary>
    public string FireOsVersion { get; init; } = "";
    public int Sdk { get; init; }
    public long RamMb { get; init; }
    public NetworkKind Network { get; init; }
    public string HomePackage { get; init; } = "";
    /// <summary>Every app that can act as the home screen, not just the current one.</summary>
    public HashSet<string> HomeCapable { get; init; } = [];
    public string InputMethodPackage { get; init; } = "";
    public string ScreensaverComponent { get; init; } = "";
    public bool SetupComplete { get; init; }
    public long StorageTotalMb { get; init; }
    public long StorageFreeMb { get; init; }

    public HashSet<string> Enabled { get; init; } = [];
    public HashSet<string> Disabled { get; init; } = [];
    public HashSet<string> ThirdParty { get; init; } = [];

    /// <summary>Amazon Fire TV: Fire OS refuses to switch off Amazon's own apps.</summary>
    public bool IsFireTv => Manufacturer.Equals("Amazon", StringComparison.OrdinalIgnoreCase);

    public bool IsInstalled(string pkg) => Enabled.Contains(pkg) || Disabled.Contains(pkg);

    public string DisplayName
    {
        get
        {
            if (IsFireTv) return FireModels.GetValueOrDefault(Model, "Fire TV " + Model);
            var maker = string.IsNullOrWhiteSpace(Brand) ? Manufacturer : Brand;
            return Model.StartsWith(maker, StringComparison.OrdinalIgnoreCase) || maker.Length == 0
                ? Model
                : $"{maker} {Model}";
        }
    }

    /// <summary>Fire TV model codes are not shown anywhere on the device.</summary>
    private static readonly Dictionary<string, string> FireModels = new()
    {
        ["AFTKA"] = "Fire TV Stick 4K Max", ["AFTKRT"] = "Fire TV Stick 4K Max (2nd gen)",
        ["AFTMM"] = "Fire TV Stick 4K", ["AFTKM"] = "Fire TV Stick 4K (2nd gen)",
        ["AFTSSS"] = "Fire TV Stick (3rd gen)", ["AFTSS"] = "Fire TV Stick Lite",
        ["AFTR"] = "Fire TV Cube (2nd gen)", ["AFTGAZL"] = "Fire TV Cube (3rd gen)",
    };

    public string IpAddress => Serial.Contains(':') ? Serial[..Serial.LastIndexOf(':')] : Serial;

    /// <summary>Packages that must never be disabled, whatever the catalog says.</summary>
    public bool IsProtected(string pkg) =>
        pkg == HomePackage || HomeCapable.Contains(pkg) || pkg == InputMethodPackage || ProtectedPackages.Contains(pkg) ||
        (IsFireTv && FireProtectedPrefixes.Any(pkg.StartsWith) && !Catalog.IsListed(pkg));

    /// <summary>
    /// Fire OS answers "Cannot disable a protected package" for many of Amazon's apps, and others hold the
    /// TV together: only the Amazon apps the catalog lists, tested on Fire TV, are offered.
    /// </summary>
    private static readonly string[] FireProtectedPrefixes = ["com.amazon.", "amazon.", "android.amazon.", "com.fireos.", "com.ivona."];

    private static readonly HashSet<string> ProtectedPackages =
    [
        "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
        "com.android.providers.settings", "com.android.providers.tv", "com.android.bluetooth",
        "com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.webview", "com.android.webview", "com.google.android.tvlauncher",
        "com.google.android.apps.tv.launcherx", "com.google.android.leanbacklauncher",
        "com.android.tv", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin", "com.android.inputdevices",
    ];

    public static async Task<TvDevice> LoadAsync(AdbService adb, string serial)
    {
        var props = await adb.ShellAsync(serial,
            "getprop ro.product.manufacturer; getprop ro.product.brand; getprop ro.product.model; " +
            "getprop ro.serialno; getprop ro.build.version.release; getprop ro.build.version.sdk; getprop ro.build.version.name");
        var p = props.Output.Split('\n');
        string Prop(int i) => i < p.Length ? p[i].Trim() : "";

        var mem = await adb.ShellAsync(serial, "grep MemTotal /proc/meminfo");
        var ramKb = long.TryParse(new string(mem.Output.Where(char.IsDigit).ToArray()), out var kb) ? kb : 0;

        var home = await adb.ShellAsync(serial,
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
        var homeLine = home.Output.Split('\n', StringSplitOptions.RemoveEmptyEntries).LastOrDefault() ?? "";
        var homePkg = homeLine.Contains('/') ? homeLine[..homeLine.IndexOf('/')].Trim() : "";

        var homes = await adb.ShellAsync(serial,
            "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME");
        var homeCapable = homes.Output.Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.Contains('/') && !l.Contains('=') && !l.Contains(' '))
            .Select(l => l[..l.IndexOf('/')])
            .ToHashSet();

        var ime = await adb.ShellAsync(serial, "settings get secure default_input_method");
        var imeLine = ime.Output.Trim();
        var imePkg = imeLine.Contains('/') ? imeLine[..imeLine.IndexOf('/')] : "";

        var dream = await adb.ShellAsync(serial, "settings get secure screensaver_components");
        var setup = await adb.ShellAsync(serial, "settings get secure user_setup_complete");

        var storage = await ReadStorageAsync(adb, serial);

        var net = await adb.ShellAsync(serial, "dumpsys connectivity | grep -E 'NetworkAgentInfo.*CONNECTED/CONNECTED'");
        var network = NetworkKind.Unknown;
        if (net.Output.Contains("type: Ethernet", StringComparison.OrdinalIgnoreCase)) network = NetworkKind.Ethernet;
        else if (net.Output.Contains("type: WIFI", StringComparison.OrdinalIgnoreCase)) network = NetworkKind.WiFi;

        return new TvDevice
        {
            Serial = serial,
            Manufacturer = Prop(0),
            Brand = Prop(1),
            Model = Prop(2),
            SerialNo = Prop(3),
            AndroidVersion = Prop(4),
            Sdk = int.TryParse(Prop(5), out var sdk) ? sdk : 0,
            FireOsVersion = Prop(6).StartsWith("Fire OS") ? Prop(6) : "",
            RamMb = ramKb / 1024,
            Network = network,
            HomePackage = homePkg,
            HomeCapable = homeCapable,
            InputMethodPackage = imePkg,
            ScreensaverComponent = dream.Output.Trim() is var d && d != "null" ? d : "",
            SetupComplete = setup.Output.Trim() == "1",
            StorageTotalMb = storage.TotalMb,
            StorageFreeMb = storage.FreeMb,
            Enabled = await ListPackagesAsync(adb, serial, "-e"),
            Disabled = await ListPackagesAsync(adb, serial, "-d"),
            ThirdParty = await ListPackagesAsync(adb, serial, "-3"),
        };
    }

    /// <summary>Size and free space of the app storage (/data), from "df -k". Zeros if unreadable.</summary>
    public static async Task<(long TotalMb, long FreeMb)> ReadStorageAsync(AdbService adb, string serial)
    {
        var r = await adb.ShellAsync(serial, "df -k /data");
        var last = r.Output.Split('\n', StringSplitOptions.RemoveEmptyEntries).LastOrDefault() ?? "";
        var cols = last.Split(' ', StringSplitOptions.RemoveEmptyEntries);
        // Filesystem 1K-blocks Used Available Use% Mounted-on
        return cols.Length >= 4 && long.TryParse(cols[1], out var total) && long.TryParse(cols[3], out var free)
            ? (total / 1024, free / 1024)
            : (0, 0);
    }

    private static async Task<HashSet<string>> ListPackagesAsync(AdbService adb, string serial, string flag)
    {
        var r = await adb.ShellAsync(serial, $"pm list packages {flag}", 30000);
        return r.Output.Split('\n')
            .Select(l => l.Trim())
            .Where(l => l.StartsWith("package:"))
            .Select(l => l["package:".Length..])
            .ToHashSet();
    }
}
