using System.Globalization;

namespace AndroidTVDebloater.Services;

/// <summary>Software, update and boot security facts about the TV. Read-only.</summary>
public sealed class TvInfo
{
    public string AndroidVersion { get; init; } = "";
    public string BuildId { get; init; } = "";
    public DateOnly? SecurityPatch { get; init; }
    /// <summary>Null when the TV doesn't say (not every maker sets these).</summary>
    public bool? BootloaderLocked { get; init; }
    /// <summary>"green" (original software, verified), "yellow" (own signing key), "orange" (unlocked), "red", or "".</summary>
    public string VerifiedBoot { get; init; } = "";

    /// <summary>Whole months between the security patch and today; -1 if unknown.</summary>
    public int PatchAgeMonths(DateOnly today) => SecurityPatch is { } p
        ? Math.Max(0, (today.Year - p.Year) * 12 + today.Month - p.Month - (today.Day < p.Day ? 1 : 0))
        : -1;

    public static async Task<TvInfo> ReadAsync(AdbService adb, string serial)
    {
        string[] keys =
        [
            "ro.build.version.release", "ro.build.display.id", "ro.build.version.security_patch",
            "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.verifiedbootstate",
        ];
        var r = await adb.ShellAsync(serial, string.Join("; ", keys.Select(k => $"echo \"{k}=$(getprop {k})\"")));
        var props = r.Output.Split('\n')
            .Select(l => l.Trim().Split('=', 2))
            .Where(p => p.Length == 2)
            .ToDictionary(p => p[0], p => p[1].Trim());
        string P(string k) => props.GetValueOrDefault(k, "");

        var locked = P("ro.boot.flash.locked") switch
        {
            "1" => true,
            "0" => false,
            _ => P("ro.boot.vbmeta.device_state") switch { "locked" => true, "unlocked" => false, _ => (bool?)null },
        };
        return new TvInfo
        {
            AndroidVersion = P("ro.build.version.release"),
            BuildId = P("ro.build.display.id"),
            SecurityPatch = DateOnly.TryParseExact(P("ro.build.version.security_patch"), "yyyy-MM-dd",
                CultureInfo.InvariantCulture, DateTimeStyles.None, out var d) ? d : null,
            BootloaderLocked = locked,
            VerifiedBoot = P("ro.boot.verifiedbootstate"),
        };
    }
}
