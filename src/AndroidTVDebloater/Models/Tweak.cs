using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Services;
using CommunityToolkit.Mvvm.ComponentModel;

namespace AndroidTVDebloater.Models;

/// <param name="Keeps">Apps this option needs: they are not turned off while it is chosen.</param>
public sealed record TweakOption(LocText Label, string Value, IReadOnlyList<string>? Keeps = null)
{
    public override string ToString() => Label.ToString();
}

public sealed class ApplyContext(AdbService adb, TvDevice device, Backup backup)
{
    public AdbService Adb { get; } = adb;
    public TvDevice Device { get; } = device;
    public Backup Backup { get; } = backup;
    public string Serial => Device.Serial;
}

/// <summary>Result of applying one choice, shown as one line on the Apply page.</summary>
public sealed record TweakResult(bool Success, string Detail = "");

/// <summary>One thing the user can tick.</summary>
public abstract partial class Tweak : ObservableObject
{
    public required string Id { get; init; }
    public required string GroupId { get; init; }
    public bool Recommended { get; init; }
    public required LocText TitleText { get; init; }
    public required LocText DescriptionText { get; init; }
    public LocText? WarningText { get; init; }
    public IReadOnlyList<TweakOption>? Options { get; init; }

    [ObservableProperty] private bool _isSelected;
    [ObservableProperty] private TweakOption? _selectedOption;
    /// <summary>The TV is already set this way: shown with a check mark and not ticked.</summary>
    [ObservableProperty] private bool _isDone;

    public string Title => TitleText.ToString();
    public string Description => DescriptionText.ToString();
    public string? Warning => WarningText?.ToString();
    public bool HasWarning => WarningText is not null;
    public bool HasOptions => Options is { Count: > 0 };

    /// <summary>Apps the chosen option needs, which must not be turned off in the same run.</summary>
    public IReadOnlyList<string> KeptPackages => IsSelected ? SelectedOption?.Keeps ?? [] : [];

    /// <summary>Small technical line under the description (package name, setting key).</summary>
    public abstract string Technical { get; }

    /// <summary>Settings this choice writes, to recognise it when an update changed them back.</summary>
    public virtual IEnumerable<string> SettingKeys => [];

    public abstract Task<TweakResult> ApplyAsync(ApplyContext ctx);

    /// <summary>
    /// Whether the TV is already set the way this choice would set it, and if so with which option.
    /// Reading changes nothing. Choices that run something (clear caches…) are never done.
    /// </summary>
    public virtual Task<(bool Done, TweakOption? Option)> ReadDoneAsync(AdbService adb, TvDevice dev) =>
        Task.FromResult<(bool, TweakOption?)>((false, null));

    public void RefreshTexts()
    {
        OnPropertyChanged(nameof(Title));
        OnPropertyChanged(nameof(Description));
        OnPropertyChanged(nameof(Warning));
        // Re-pick the option so the ComboBox shows the label in the new language.
        if (Options is not null && SelectedOption is not null)
        {
            var current = SelectedOption;
            SelectedOption = null;
            SelectedOption = current;
        }
    }
}

/// <summary>Disable one app for the main user ("pm disable-user"). Nothing is uninstalled.</summary>
public sealed class PackageTweak : Tweak
{
    public required string Package { get; init; }
    public override string Technical => Package;

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        var r = await ctx.Adb.ShellAsync(ctx.Serial, $"pm disable-user --user 0 {Package}");
        if (r.All.Contains("disabled-user"))
        {
            ctx.Backup.DisabledPackages.Add(Package);
            return new TweakResult(true);
        }
        // Fire OS (and some makers) refuse for their own apps; say so instead of a Java error.
        if (r.All.Contains("protected package", StringComparison.OrdinalIgnoreCase))
            return new TweakResult(false, Loc.Get("Apply_ProtectedPackage"));
        return new TweakResult(false, FirstLine(r.All));
    }

    internal static string FirstLine(string s) => s.Split('\n', StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
}

public sealed record SettingWrite(string Namespace, string Key, string Value);

/// <summary>Write one or more Android settings. "{option}" in a value is replaced by the chosen option.</summary>
public sealed class SettingsTweak : Tweak
{
    public required IReadOnlyList<SettingWrite> Writes { get; init; }

    /// <summary>False for a plain choice between options, such as the screensaver.</summary>
    public bool CanBeDone { get; init; } = true;

    public override string Technical => string.Join(" · ", Writes.Select(w => w.Key).Distinct());
    public override IEnumerable<string> SettingKeys => Writes.Select(w => w.Key);

    /// <summary>Done when every setting already has the value of one same option (any option counts).</summary>
    public override async Task<(bool Done, TweakOption? Option)> ReadDoneAsync(AdbService adb, TvDevice dev)
    {
        if (!CanBeDone) return (false, null);
        var current = new Dictionary<SettingWrite, string?>();
        foreach (var w in Writes) current[w] = await SettingsIO.GetAsync(adb, dev.Serial, w.Namespace, w.Key);
        bool Matches(string option) => Writes.All(w => current[w] == w.Value.Replace("{option}", option));
        if (Options is not { Count: > 0 }) return (Matches(""), null);
        var match = Options.FirstOrDefault(o => Matches(o.Value));
        return (match is not null, match);
    }

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        // The chosen option needs an app that was turned off (e.g. Google Ambient): turn it back on.
        foreach (var pkg in KeptPackages.Where(ctx.Device.Disabled.Contains))
        {
            await ctx.Adb.ShellAsync(ctx.Serial, $"pm enable {pkg}");
            ctx.Backup.EnabledPackages.Add(pkg);
            ctx.Backup.Save();
        }
        foreach (var w in Writes)
        {
            var value = w.Value.Replace("{option}", SelectedOption?.Value ?? "");
            if (!await SettingsIO.ChangeAsync(ctx, w.Namespace, w.Key, value))
                return new TweakResult(false, w.Key);
        }
        return new TweakResult(true);
    }
}

/// <summary>Cap the number of apps Android keeps cached in memory.</summary>
public sealed class BackgroundLimitTweak : Tweak
{
    public override string Technical => "max_cached_processes";
    public override IEnumerable<string> SettingKeys => ["max_cached_processes", "activity_manager_constants"];

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        var value = SelectedOption?.Value ?? "16";
        // Android 10+ reads it from device_config; older versions from a global setting.
        // The catalog only offers it up to Android 9: Google Play services wipes device_config's
        // activity_manager flags on its next sync, so it never lasts on 10+.
        var ok = ctx.Device.Sdk >= 29
            ? await SettingsIO.ChangeAsync(ctx, "device_config:activity_manager", "max_cached_processes", value)
            : await SettingsIO.ChangeAsync(ctx, "global", "activity_manager_constants", $"max_cached_processes={value}");
        return new TweakResult(ok);
    }

    public override async Task<(bool Done, TweakOption? Option)> ReadDoneAsync(AdbService adb, TvDevice dev)
    {
        var v = dev.Sdk >= 29
            ? await SettingsIO.GetAsync(adb, dev.Serial, "device_config:activity_manager", "max_cached_processes")
            : (await SettingsIO.GetAsync(adb, dev.Serial, "global", "activity_manager_constants"))?
                .Split(',').FirstOrDefault(p => p.StartsWith("max_cached_processes="))?["max_cached_processes=".Length..];
        var match = Options?.FirstOrDefault(o => o.Value == v);
        return (match is not null, match);
    }
}

/// <summary>Turn the Wi-Fi radio off. Only offered when the TV is on Ethernet.</summary>
public sealed class WifiOffTweak : Tweak
{
    public override string Technical => "svc wifi disable";
    public override IEnumerable<string> SettingKeys => ["wifi_on"];

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        var before = await SettingsIO.GetAsync(ctx, "global", "wifi_on");
        ctx.Backup.WifiWasOn = before != "0";
        ctx.Backup.Save();
        await ctx.Adb.ShellAsync(ctx.Serial, "svc wifi disable");
        return new TweakResult(true);
    }

    public override async Task<(bool Done, TweakOption? Option)> ReadDoneAsync(AdbService adb, TvDevice dev) =>
        (await SettingsIO.GetAsync(adb, dev.Serial, "global", "wifi_on") == "0", null);
}

/// <summary>
/// Private DNS with a filtering provider. Checked straight away: if names stop resolving,
/// the previous DNS setting is put back on its own.
/// </summary>
public sealed class PrivateDnsTweak : Tweak
{
    public override string Technical => "private_dns_mode · private_dns_specifier";
    public override IEnumerable<string> SettingKeys => ["private_dns_mode", "private_dns_specifier"];

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        var host = SelectedOption?.Value ?? "dns.adguard-dns.com";
        var oldMode = await SettingsIO.GetAsync(ctx, "global", "private_dns_mode");
        var oldHost = await SettingsIO.GetAsync(ctx, "global", "private_dns_specifier");

        await SettingsIO.ChangeAsync(ctx, "global", "private_dns_specifier", host);
        await SettingsIO.ChangeAsync(ctx, "global", "private_dns_mode", "hostname");

        // Give the network stack a moment to validate the new resolver, then test a lookup.
        for (var attempt = 0; attempt < 4; attempt++)
        {
            await Task.Delay(2500);
            var ping = await ctx.Adb.ShellAsync(ctx.Serial, "ping -c 1 -W 3 connectivitycheck.gstatic.com", 10000);
            if (ping.Output.Contains("PING ")) return new TweakResult(true, host);
        }

        // Lookups fail: undo now rather than leave the TV offline.
        await SettingsIO.RestoreAsync(ctx.Adb, ctx.Serial, "global", "private_dns_mode", oldMode);
        await SettingsIO.RestoreAsync(ctx.Adb, ctx.Serial, "global", "private_dns_specifier", oldHost);
        ctx.Backup.Settings.RemoveAll(s => s.Key is "private_dns_mode" or "private_dns_specifier");
        ctx.Backup.Save();
        return new TweakResult(false, Loc.Get("Apply_DnsReverted"));
    }

    /// <summary>Done when Private DNS already points at one of the offered filtering services.</summary>
    public override async Task<(bool Done, TweakOption? Option)> ReadDoneAsync(AdbService adb, TvDevice dev)
    {
        if (await SettingsIO.GetAsync(adb, dev.Serial, "global", "private_dns_mode") != "hostname") return (false, null);
        var host = await SettingsIO.GetAsync(adb, dev.Serial, "global", "private_dns_specifier");
        var match = Options?.FirstOrDefault(o => o.Value == host);
        return (match is not null, match);
    }
}

/// <summary>
/// Clear every app's cache ("pm trim-caches"). Nothing to back up: apps rebuild caches on their own.
/// </summary>
public sealed class TrimCachesTweak : Tweak
{
    public override string Technical => "pm trim-caches";

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        var before = await TvDevice.ReadStorageAsync(ctx.Adb, ctx.Serial);
        var r = await ctx.Adb.ShellAsync(ctx.Serial, "pm trim-caches 999G", 120000);
        if (!r.Ok) return new TweakResult(false, PackageTweak.FirstLine(r.All));
        await Task.Delay(1500);
        var after = await TvDevice.ReadStorageAsync(ctx.Adb, ctx.Serial);
        var freed = Math.Max(0, after.FreeMb - before.FreeMb);
        return new TweakResult(true, Loc.Format("Apply_Freed", freed));
    }
}

/// <summary>
/// Run Android's background app optimisation now ("bg-dexopt-job"). Nothing to back up or undo.
/// </summary>
public sealed class OptimizeAppsTweak : Tweak
{
    public override string Technical => "cmd package bg-dexopt-job";

    public override async Task<TweakResult> ApplyAsync(ApplyContext ctx)
    {
        var started = DateTime.UtcNow;
        var r = await ctx.Adb.ShellAsync(ctx.Serial, "cmd package bg-dexopt-job", 20 * 60 * 1000);
        if (!r.Ok) return new TweakResult(false, PackageTweak.FirstLine(r.All));
        var minutes = Math.Max(1, (int)Math.Round((DateTime.UtcNow - started).TotalMinutes));
        return new TweakResult(true, Loc.Format("Apply_OptimizeDone", minutes));
    }
}

/// <summary>Reading and writing settings, recording old values in the backup first.</summary>
public static class SettingsIO
{
    private static (string cmd, string ns) Split(string ns) =>
        ns.StartsWith("device_config:") ? ("device_config", ns["device_config:".Length..]) : ("settings", ns);

    public static async Task<string?> GetAsync(ApplyContext ctx, string ns, string key) =>
        await GetAsync(ctx.Adb, ctx.Serial, ns, key);

    public static async Task<string?> GetAsync(AdbService adb, string serial, string ns, string key)
    {
        var (cmd, n) = Split(ns);
        var r = await adb.ShellAsync(serial, $"{cmd} get {n} {key}");
        var v = r.Output.Trim();
        return v.Length == 0 || v == "null" ? null : v;
    }

    public static async Task<bool> ChangeAsync(ApplyContext ctx, string ns, string key, string value)
    {
        var old = await GetAsync(ctx, ns, key);
        // Keep the first original value if the same key is changed twice in one run.
        if (!ctx.Backup.Settings.Any(s => s.Namespace == ns && s.Key == key))
        {
            ctx.Backup.Settings.Add(new SettingChange { Namespace = ns, Key = key, OldValue = old, NewValue = value });
            ctx.Backup.Save();
        }
        var (cmd, n) = Split(ns);
        await ctx.Adb.ShellAsync(ctx.Serial, $"{cmd} put {n} {key} {Quote(value)}");
        return await GetAsync(ctx, ns, key) == value;
    }

    public static async Task RestoreAsync(AdbService adb, string serial, string ns, string key, string? oldValue)
    {
        var (cmd, n) = Split(ns);
        if (oldValue is null)
            await adb.ShellAsync(serial, $"{cmd} delete {n} {key}");
        else
            await adb.ShellAsync(serial, $"{cmd} put {n} {key} {Quote(oldValue)}");
    }

    private static string Quote(string v) =>
        v.All(c => char.IsLetterOrDigit(c) || "._-/=:,".Contains(c)) ? v : "'" + v.Replace("'", "'\\''") + "'";
}
