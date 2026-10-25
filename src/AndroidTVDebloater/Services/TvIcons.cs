using System.Reflection;

namespace AndroidTVDebloater.Services;

/// <summary>
/// App names and icons as the TV shows them, from Android itself. A tiny helper (helper/umtv-helper.dex)
/// is copied to the TV's temp folder, run with app_process, and deleted: nothing is installed.
/// </summary>
public static class TvIcons
{
    /// <param name="Label">The app's name in the TV's language; "" when the TV didn't give one.</param>
    /// <param name="Png">
    /// The app's banner (its tile on the TV's home screen), or else its icon centred, <see cref="Height"/> pixels high
    /// and 16:9 wide; null when the TV didn't draw one.
    /// </param>
    public sealed record Entry(string Label, byte[]? Png);

    public const int Height = 90;
    private const string Remote = "/data/local/tmp/umtv-helper.dex";
    /// <summary>Keyed by TV, package and last update, so a refresh only asks for what changed.</summary>
    private static readonly Dictionary<string, Entry> Cache = [];

    private static string Key(string serial, InstalledApp a) => $"{serial}|{a.Package}|{a.Updated:O}";

    /// <summary>Reading changes nothing on the TV, apart from the helper that comes and goes in its temp folder.</summary>
    public static async Task<Dictionary<string, Entry>> ReadAsync(AdbService adb, string serial, IReadOnlyList<InstalledApp> apps)
    {
        var missing = apps.Where(a => !Cache.ContainsKey(Key(serial, a))).ToList();
        if (missing.Count > 0) await FetchAsync(adb, serial, missing);
        return apps.Where(a => Cache.ContainsKey(Key(serial, a))).ToDictionary(a => a.Package, a => Cache[Key(serial, a)]);
    }

    private static async Task FetchAsync(AdbService adb, string serial, List<InstalledApp> apps)
    {
        var local = Path.Combine(Path.GetTempPath(), $"umtv-helper-{Environment.ProcessId}.dex");
        try
        {
            await using (var s = Assembly.GetExecutingAssembly().GetManifestResourceStream("umtv-helper.dex")!)
            await using (var f = File.Create(local))
                await s.CopyToAsync(f);
            if ((await adb.RunAsync(["-s", serial, "push", local, Remote], 30000)).ExitCode != 0) return;
            // A few dozen at a time keeps the command line short on older TVs.
            foreach (var chunk in apps.Chunk(40))
            {
                var r = await adb.RunAsync(["-s", serial, "exec-out",
                    $"CLASSPATH={Remote} app_process / app.unbloatmytv.AppIcons {Height} {string.Join(' ', chunk.Select(a => a.Package))}"], 60000);
                var byPkg = chunk.ToDictionary(a => a.Package);
                foreach (var line in r.Output.Split('\n'))
                {
                    var f = line.Split('\t');
                    if (f.Length < 3 || !byPkg.TryGetValue(f[0], out var app)) continue;
                    byte[]? png = null;
                    try { if (f[2].Length > 0) png = Convert.FromBase64String(f[2]); } catch (FormatException) { }
                    Cache[Key(serial, app)] = new Entry(f[1].Trim(), png);
                }
            }
        }
        catch { /* no icons this time; names fall back to the catalog */ }
        finally
        {
            await adb.ShellAsync(serial, $"rm -f {Remote}");
            try { File.Delete(local); } catch { }
        }
    }
}
