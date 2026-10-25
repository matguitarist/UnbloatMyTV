using System.Text.RegularExpressions;
using AndroidTVDebloater.Models;

namespace AndroidTVDebloater.Services;

/// <summary>One kind of access an app can be given ("Location", "Microphone"…) and the permissions behind it.</summary>
public sealed record AccessKind(string Key, string[] Permissions);

/// <summary>An app that has one kind of access, with the granted permissions that removing it would revoke.</summary>
public sealed record AppAccess(string Package, AccessKind Kind, List<string> Granted, bool IsThirdParty);

/// <summary>Which apps can use location, microphone, camera and personal data. Reading changes nothing.</summary>
public static partial class Privacy
{
    private const string Prefix = "android.permission.";

    /// <summary>In the order shown. "BgLocation" removes only "all the time", so the app keeps it while open.</summary>
    public static readonly AccessKind[] Kinds =
    [
        new("Microphone", ["RECORD_AUDIO"]),
        new("Camera", ["CAMERA"]),
        new("BgLocation", ["ACCESS_BACKGROUND_LOCATION"]),
        new("Location", ["ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION"]),
        new("Contacts", ["READ_CONTACTS", "WRITE_CONTACTS"]),
        new("Calendar", ["READ_CALENDAR", "WRITE_CALENDAR"]),
    ];

    /// <summary>
    /// Apps you installed and preinstalled apps with an icon. System parts without an icon (voice search,
    /// TV inputs…) and everything the TV needs are left out: removing their access breaks things.
    /// </summary>
    public static async Task<List<AppAccess>> ReadAsync(AdbService adb, TvDevice dev)
    {
        var withIcon = await UserApps.WithIconAsync(adb, dev.Serial);

        var wanted = string.Join("|", Kinds.SelectMany(k => k.Permissions).Distinct());
        var dump = await adb.ShellAsync(dev.Serial,
            $"dumpsys package packages | grep -E '^Hidden|^  Package \\[|^    User [0-9]+:|permission\\.({wanted}): granted=true'", 60000);
        // Only the installed packages, not the original copies of updated system apps listed after them.
        var text = "\n" + dump.Output.Replace("\r", "").Split("\nHidden")[0];

        var result = new List<AppAccess>();
        foreach (var block in text.Split("\n  Package [").Skip(1))
        {
            var pkg = block[..block.IndexOf(']')];
            var thirdParty = dev.ThirdParty.Contains(pkg);
            if (!dev.Enabled.Contains(pkg) || dev.IsProtected(pkg) || Catalog.IsKeep(pkg)) continue;
            if (!thirdParty && !withIcon.Contains(pkg)) continue;

            // Main user's permissions only: "    User 0: …" up to the next user.
            var user0 = User0().Match(block);
            if (!user0.Success) continue;
            var granted = Granted().Matches(user0.Groups[1].Value)
                .Where(m => !m.Groups[2].Value.Contains("SYSTEM_FIXED") && !m.Groups[2].Value.Contains("POLICY_FIXED"))
                .Select(m => m.Groups[1].Value)
                .ToHashSet();

            foreach (var kind in Kinds)
            {
                var g = kind.Permissions.Where(granted.Contains).ToList();
                // Location counts only for precise or approximate; background alone is shown under "all the time".
                if (kind.Key == "Location" && !g.Any(p => p != "ACCESS_BACKGROUND_LOCATION")) continue;
                if (g.Count > 0) result.Add(new AppAccess(pkg, kind, g.Select(p => Prefix + p).ToList(), thirdParty));
            }
        }
        return result;
    }

    /// <returns>Null when it worked, else the TV's error.</returns>
    public static async Task<string?> RevokeAsync(AdbService adb, string serial, string pkg, string permission)
    {
        var r = await adb.ShellAsync(serial, $"pm revoke --user 0 {pkg} {permission}");
        return r.ExitCode == 0 && !r.All.Contains("Exception") ? null : r.All.Trim();
    }

    [GeneratedRegex(@"\n    User 0:(.*?)(?=\n    User \d+:|\z)", RegexOptions.Singleline)] private static partial Regex User0();
    [GeneratedRegex(@"android\.permission\.(\w+): granted=true(?:, flags=\[([^\]]*)\])?")] private static partial Regex Granted();
}
