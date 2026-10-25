using AndroidTVDebloater.Localization;
using System.Text.Json;
using System.Text.Json.Serialization;
using AndroidTVDebloater.Services;

namespace AndroidTVDebloater.Models;

public sealed class SettingChange
{
    /// <summary>"global", "secure", "system", or "device_config:&lt;namespace&gt;".</summary>
    public string Namespace { get; set; } = "";
    public string Key { get; set; } = "";
    /// <summary>Value before we touched it; null means it was not set.</summary>
    public string? OldValue { get; set; }
    public string NewValue { get; set; } = "";
}

/// <summary>An access removed in the privacy check-up; Restore gives it back.</summary>
public sealed class PermissionChange
{
    public string Package { get; set; } = "";
    public string Permission { get; set; } = "";
}

/// <summary>Everything one run changed, with the original values, so it can be undone exactly.</summary>
public sealed class Backup
{
    public DateTime Created { get; set; } = DateTime.Now;
    public string DeviceName { get; set; } = "";
    public string SerialNo { get; set; } = "";
    public string AndroidVersion { get; set; } = "";
    public List<string> DisabledPackages { get; set; } = [];
    /// <summary>Apps a choice turned back on (e.g. for the screensaver); Restore turns them off again.</summary>
    public List<string> EnabledPackages { get; set; } = [];
    public List<SettingChange> Settings { get; set; } = [];
    public bool? WifiWasOn { get; set; }
    public List<PermissionChange> RevokedPermissions { get; set; } = [];

    [JsonIgnore] public string FilePath { get; set; } = "";
    [JsonIgnore] public int ChangeCount => DisabledPackages.Count + EnabledPackages.Count + Settings.Count + (WifiWasOn == true ? 1 : 0) + RevokedPermissions.Count;
    [JsonIgnore] public string CreatedText => Created.ToString("g", Loc.Culture);

    private static readonly JsonSerializerOptions Json = new() { WriteIndented = true };

    public static string FolderFor(TvDevice dev)
    {
        var id = string.IsNullOrWhiteSpace(dev.SerialNo) ? dev.Serial : dev.SerialNo;
        var safe = new string($"{dev.DisplayName}-{id}".Select(c => char.IsLetterOrDigit(c) || c == '-' ? c : '_').ToArray());
        return Path.Combine(AdbService.BackupDir, safe);
    }

    public static Backup Start(TvDevice dev)
    {
        var folder = FolderFor(dev);
        Directory.CreateDirectory(folder);
        var b = new Backup
        {
            DeviceName = dev.DisplayName,
            SerialNo = dev.SerialNo,
            AndroidVersion = dev.AndroidVersion,
        };
        b.FilePath = Path.Combine(folder, $"changes-{b.Created:yyyyMMdd-HHmmss}.json");
        return b;
    }

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        File.WriteAllText(FilePath, JsonSerializer.Serialize(this, Json));
    }

    public static List<Backup> ListFor(TvDevice dev)
    {
        var folder = FolderFor(dev);
        if (!Directory.Exists(folder)) return [];
        var list = new List<Backup>();
        foreach (var f in Directory.GetFiles(folder, "changes-*.json"))
        {
            try
            {
                var b = JsonSerializer.Deserialize<Backup>(File.ReadAllText(f), Json);
                if (b is null) continue;
                b.FilePath = f;
                if (b.ChangeCount > 0) list.Add(b);
            }
            catch { /* ignore unreadable files */ }
        }
        return list.OrderByDescending(b => b.Created).ToList();
    }
}
