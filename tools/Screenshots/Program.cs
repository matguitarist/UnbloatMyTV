using AndroidTVDebloater;
using AndroidTVDebloater.Localization;
using AndroidTVDebloater.Models;
using AndroidTVDebloater.Services;
using AndroidTVDebloater.ViewModels;
using AndroidTVDebloater.Views;
using Avalonia;
using Avalonia.Headless;
using Avalonia.Styling;
using Avalonia.Threading;

// Usage: dotnet run --project tools/Screenshots -- <output folder> [--live <ip:port>]
// --live reads a real TV (read-only) and prints what the Choose page would offer.
var outDir = Path.GetFullPath(args.Length > 0 ? args[0] : "docs/screenshots");
if (args.Length >= 3 && args[1] == "--live")
{
    var adb = new AdbService();
    await adb.EnsureInstalledAsync(null);
    await adb.RunAsync(["connect", args[2]]);
    var dev = await TvDevice.LoadAsync(adb, args[2]);
    Console.WriteLine($"{dev.DisplayName}, Android {dev.AndroidVersion}, storage {dev.StorageFreeMb}/{dev.StorageTotalMb} MB, catalog v{Catalog.Version}");
    foreach (var g in Catalog.Build(dev))
        foreach (var t in g.Tweaks)
            Console.WriteLine($"  [{(t.IsSelected ? "x" : " ")}] {g.Id,-12} {t.Id,-50} {t.Description[..Math.Min(70, t.Description.Length)]}");
    if (dev.IsFireTv) Console.WriteLine($"{dev.FireOsVersion}; tracking on: [{string.Join(", ", await FirePrivacy.ReadOnAsync(adb, args[2]))}]");
    var unknown = Catalog.UnknownSystemApps(dev);
    Console.WriteLine($"unknown: {unknown.Count}: {string.Join(", ", unknown)}");
    var (url, complete) = IssueReport.Build(dev, unknown, Catalog.Version);
    Console.WriteLine($"issue url {url.Length} chars, complete={complete}");
    var h = await TvHealth.ReadAsync(adb, args[2]);
    Console.WriteLine($"health: mem {h.MemAvailableMb}/{h.MemTotalMb} MB, storage {h.StorageFreeMb}/{h.StorageTotalMb} MB, load {h.Load} on {h.Cores}, up {h.UptimeHours:0.0} h");
    foreach (var p in h.TopMemory) Console.WriteLine($"  {p.Mb,5} MB {p.Name}");
    foreach (var c in h.Crashing) Console.WriteLine($"  crashing: {c.Package} x{c.Restarts} keep={Catalog.IsKeep(c.Package)}");
    var back = await ReturnCheck.FindAsync(adb, dev);
    Console.WriteLine(back is null ? "returned: nothing" : $"returned since {back.Since}: apps [{string.Join(", ", back.Packages)}] settings [{string.Join(", ", back.Settings.Select(x => x.Key + "=" + x.NewValue))}]");
    var info = await TvInfo.ReadAsync(adb, args[2]);
    Console.WriteLine($"info: Android {info.AndroidVersion}, build {info.BuildId}, patch {info.SecurityPatch}, locked {info.BootloaderLocked}, boot {info.VerifiedBoot}");
    var apps = await UserApps.ReadAsync(adb, dev);
    var pictures = await TvIcons.ReadAsync(adb, args[2], apps);
    foreach (var a in apps)
        Console.WriteLine($"  app {a.Package,-40} {AppRow.Origin(a),-28} {a.TotalBytes / 1048576,5} MB on={a.IsEnabled} icon={a.HasIcon} " +
                          $"name={pictures.GetValueOrDefault(a.Package)?.Label} png={pictures.GetValueOrDefault(a.Package)?.Png?.Length ?? 0}");
    foreach (var a in await Privacy.ReadAsync(adb, dev))
        Console.WriteLine($"  access {a.Kind.Key,-10} {a.Package} [{string.Join(", ", a.Granted)}]");
    var shot = await adb.RunBinaryAsync(["-s", args[2], "exec-out", "screencap", "-p"]);
    Console.WriteLine($"screenshot: {shot?.Length ?? 0} bytes");
    return;
}
Directory.CreateDirectory(outDir);

AppBuilder.Configure<App>()
    .UseSkia()
    .UseHeadless(new AvaloniaHeadlessPlatformOptions { UseHeadlessDrawing = false })
    .WithInterFont()
    .SetupWithoutStarting();

// A made-up TV with a typical mix of bloat.
var tv = new TvDevice
{
    Serial = "192.168.1.42:5555",
    Manufacturer = "Example", Brand = "Example", Model = "Smart TV 4K", SerialNo = "DEMO",
    AndroidVersion = "11", Sdk = 30, RamMb = 2875, Network = NetworkKind.Ethernet,
    HomePackage = "com.example.launcher", SetupComplete = true,
    ScreensaverComponent = "com.google.android.backdrop/.Backdrop",
    Enabled =
    [
        "com.google.android.feedback", "com.google.android.tvrecommendations", "tv.samba.ssm",
        "android.autoinstalls.config.example.device", "com.google.android.play.games",
        "com.google.android.backdrop", "com.google.android.tungsten.setupwraith", "com.android.printspooler",
        "com.google.android.marvin.talkback", "com.google.android.katniss", "com.google.android.apps.mediashell",
        "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
        "com.android.dreams.basic", "com.example.launcher", "org.example.speedtest",
        "com.android.vending", "com.spocky.projengmenu",
    ],
    Disabled = ["com.example.oldapp"],
    ThirdParty = ["org.example.speedtest", "com.netflix.ninja"],
};

void Shot(string name, ThemeVariant theme, string lang, Func<MainViewModel, PageViewModel> page, int height = 800, TvDevice? device = null)
{
    Application.Current!.RequestedThemeVariant = theme;
    Loc.Instance.Language = lang;
    var main = new MainViewModel(new AdbService()) { Device = device ?? tv };
    main.CurrentPage = page(main);
    var w = new MainWindow { DataContext = main, Width = 1140, Height = height };
    w.Show();
    Dispatcher.UIThread.RunJobs();
    var frame = w.CaptureRenderedFrame();
    frame?.Save(Path.Combine(outDir, name + ".png"));
    w.Close();
    Console.WriteLine(name);
}

Shot("welcome-dark-en", ThemeVariant.Dark, "en", m => new WelcomeViewModel(m), 980);
Shot("welcome-dark-fr", ThemeVariant.Dark, "fr", m => new WelcomeViewModel(m), 980);
Shot("apply-dark-fr", ThemeVariant.Dark, "fr", m =>
{
    var a = new ApplyViewModel(m, tv, [], false) { AppsOff = 12, SettingsChanged = 5, BackupPath = @"C:\Users\vous\AppData\Local\AndroidTVDebloater\backups\Example" };
    a.HomeScreen = new HomeScreenViewModel(m, tv);
    a.IsFinished = true;
    return a;
}, 1500);
Shot("connect-dark-en", ThemeVariant.Dark, "en", m =>
{
    var c = new ConnectViewModel(m);
    c.Recent.Clear(); // never this PC's real TVs
    c.Recent.Add(new FoundTv("192.168.1.42") { Name = "Example Smart TV 4K" });
    c.Found.Add(new FoundTv("192.168.1.42") { Name = "android-livingroom.lan" });
    c.Found.Add(new FoundTv("192.168.1.57"));
    return c;
});
Shot("connect-waiting-fr", ThemeVariant.Dark, "fr", m =>
{
    var c = new ConnectViewModel(m) { State = ConnectState.WaitingForApproval };
    c.Recent.Clear();
    return c;
});
// Choose pages use made-up "last choices", never the ones saved on this PC.
var demoChoices = new ChoiceSet
{
    Name = "Bedroom TV", Created = "2026-10-12T20:15:00-04:00",
    Choices = [new() { Id = "pkg:com.google.android.feedback" }, new() { Id = "animations", Option = "0.5" }, new() { Id = "wifiscan" }],
};
ChooseViewModel Choose(MainViewModel m, ChoiceSet? last) => new(m, tv) { LastChoices = last };
Shot("choose-dark-en", ThemeVariant.Dark, "en", m => Choose(m, demoChoices), 1500);
Shot("choose-dark-fr", ThemeVariant.Dark, "fr", m => Choose(m, null), 1500);
Shot("choose-full-dark-en", ThemeVariant.Dark, "en", m => Choose(m, null), 3400);
Shot("apply-dark-en", ThemeVariant.Dark, "en", m =>
{
    var a = new ApplyViewModel(m, tv, [], false);
    foreach (var (text, state, detail) in new[]
             {
                 ("Saving a backup on this PC", StepState.Done, ""),
                 ("Google Feedback", StepState.Done, ""),
                 ("Samba TV", StepState.Done, ""),
                 ("Google recommendations", StepState.Done, ""),
                 ("Block ads and trackers (DNS)", StepState.Done, "dns.adguard-dns.com"),
                 ("Restarting the TV", StepState.Done, ""),
                 ("Checking home screen and internet", StepState.Done, "Home screen opens, internet and name lookups work."),
             })
        a.Items.Add(new StepItem(text) { State = state, Detail = detail });
    a.BackupPath = @"C:\Users\you\AppData\Local\AndroidTVDebloater\backups\Example_Smart_TV_4K-DEMO";
    a.AppsOff = 3;
    a.SettingsChanged = 1;
    a.HomeScreen = new HomeScreenViewModel(m, tv);
    a.IsFinished = true;
    return a;
}, 1500);
Shot("apply-working-dark-fr", ThemeVariant.Dark, "fr", m =>
{
    var a = new ApplyViewModel(m, tv, [], false);
    a.Items.Add(new StepItem("Enregistrement d'une sauvegarde sur ce PC") { State = StepState.Done });
    a.Items.Add(new StepItem("Commentaires Google") { State = StepState.Done });
    a.Items.Add(new StepItem("Samba TV") { State = StepState.Running });
    a.Items.Add(new StepItem("Recommandations Google"));
    a.Items.Add(new StepItem("Redémarrage du téléviseur"));
    a.Progress = 40;
    return a;
});
Shot("restore-dark-en", ThemeVariant.Dark, "en", m => new RestoreViewModel(m, tv));

// Tool pages, with made-up readings.
var demoHealth = new HealthReport
{
    MemTotalMb = 2875, MemAvailableMb = 610, StorageTotalMb = 8180, StorageFreeMb = 4870,
    Load = 2.4, Cores = 4, UptimeHours = 51,
    TopMemory =
    [
        new("com.google.android.youtube.tv", 212), new("com.google.android.gms", 179),
        new("com.google.android.katniss", 151), new("system", 99), new("com.example.launcher", 64),
        new("com.google.android.apps.mediashell", 46),
    ],
    Crashing = [new("tv.samba.ssm", 12)],
};
Shot("health-dark-en", ThemeVariant.Dark, "en", m => new HealthViewModel(m, tv, read: false) { Report = demoHealth }, 1000);
Shot("health-dark-fr", ThemeVariant.Dark, "fr", m => new HealthViewModel(m, tv, read: false) { Report = demoHealth }, 1000);
Shot("install-dark-en", ThemeVariant.Dark, "en", m => new InstallViewModel(m, tv));
Shot("remote-dark-en", ThemeVariant.Dark, "en", m =>
{
    // A stand-in for the TV picture: a dark home screen with a few tiles.
    var screen = new Avalonia.Controls.Canvas { Width = 1280, Height = 720, Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#14161B")) };
    var colors = new[] { "#3B72F0", "#E0533D", "#2DA36B", "#8E5BD9", "#E2A33A" };
    for (var i = 0; i < 5; i++)
    {
        var tile = new Avalonia.Controls.Border { Width = 210, Height = 120, CornerRadius = new CornerRadius(12), Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse(colors[i])) };
        Avalonia.Controls.Canvas.SetLeft(tile, 80 + i * 230);
        Avalonia.Controls.Canvas.SetTop(tile, 300);
        screen.Children.Add(tile);
    }
    screen.Measure(new Size(1280, 720));
    screen.Arrange(new Rect(0, 0, 1280, 720));
    var bmp = new Avalonia.Media.Imaging.RenderTargetBitmap(new PixelSize(1280, 720));
    bmp.Render(screen);
    return new RemoteViewModel(m, tv, live: false) { Screen = bmp };
});
Shot("choose-returned-dark-en", ThemeVariant.Dark, "en", m =>
{
    var c = new ChooseViewModel(m, tv) { LastChoices = null };
    c.Returned = new ReturnedItems(new DateTime(2026, 10, 12, 20, 15, 0), ["com.google.android.feedback"],
        [new SettingChange { Namespace = "device_config:activity_manager", Key = "max_cached_processes", NewValue = "16" }]);
    return c;
});

Shot("info-dark-en", ThemeVariant.Dark, "en", m => new TvInfoViewModel(m, tv, read: false)
{
    Info = new TvInfo
    {
        AndroidVersion = "11", BuildId = "RTT4.250710.001",
        SecurityPatch = DateOnly.FromDateTime(DateTime.Today.AddMonths(-7).AddDays(1 - DateTime.Today.Day)),
        BootloaderLocked = true, VerifiedBoot = "green",
    },
}, 1150);
Shot("privacy-dark-en", ThemeVariant.Dark, "en", m =>
{
    var p = new PrivacyViewModel(m, tv, read: false);
    AccessKind Kind(string key) => Privacy.Kinds.First(k => k.Key == key);
    AppAccess A(string pkg, string kind, bool yours) => new(pkg, Kind(kind), ["android.permission.X"], yours);
    p.Show(
    [
        A("com.example.karaoke", "Microphone", true), A("com.example.videochat", "Microphone", true),
        A("com.example.videochat", "Camera", true),
        A("com.example.weather", "Location", true), A("org.example.speedtest", "Location", true),
    ]);
    return p;
}, 1000);

// My apps: made-up apps with drawn 16:9 tiles, so no real app or brand appears.
byte[] Banner(string color, string text)
{
    var tile = new Avalonia.Controls.Border
    {
        Width = 160, Height = 90,
        Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse(color)),
        Child = new Avalonia.Controls.TextBlock
        {
            Text = text, FontSize = 22, FontWeight = Avalonia.Media.FontWeight.Bold, Foreground = Avalonia.Media.Brushes.White,
            HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center,
            VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center,
        },
    };
    tile.Measure(new Size(160, 90));
    tile.Arrange(new Rect(0, 0, 160, 90));
    using var bmp = new Avalonia.Media.Imaging.RenderTargetBitmap(new PixelSize(160, 90));
    bmp.Render(tile);
    using var ms = new MemoryStream();
    bmp.Save(ms);
    return ms.ToArray();
}
var demoApps = new (string Pkg, string Label, string Color, string Installer, int Mb, int DaysAgo)[]
{
    ("com.example.cinema", "Cinema Club", "#E0533D", "com.android.vending", 186, 90),
    ("com.example.karaoke", "Sing Along", "#8E5BD9", "com.android.vending", 92, 30),
    ("com.example.radio", "World Radio", "#2DA36B", "com.android.vending", 41, 400),
    ("com.example.sports", "Match Day", "#3B72F0", "com.android.vending", 230, 200),
    ("com.example.videochat", "Family Call", "#1FA3C4", "com.android.vending", 120, 12),
    ("com.example.weather", "Weather Now", "#E2A33A", "com.android.vending", 31, 600),
    ("org.example.speedtest", "Speed Check", "#5B6B82", "", 18, 3),
};
Shot("apps-dark-en", ThemeVariant.Dark, "en", m =>
{
    var a = new AppsViewModel(m, tv, read: false);
    var today = DateTime.Today;
    a.Show(
        demoApps.Select(d => new InstalledApp(d.Pkg, "2.4.1", d.Installer, today.AddDays(-d.DaysAgo), today.AddDays(-1),
            d.Mb * 1024L * 1024, 6 * 1024L * 1024, 2 * 1024L * 1024, IsEnabled: d.Pkg != "com.example.radio", HasIcon: true, ByMaker: false)),
        demoApps.ToDictionary(d => d.Pkg, d => new TvIcons.Entry(d.Label, Banner(d.Color, d.Label))));
    return a;
}, 1000);

// A made-up Fire TV: only the Amazon parts the catalog lists are offered, with its tracking switches.
var fire = new TvDevice
{
    Serial = "192.168.1.43:5555", Manufacturer = "Amazon", Brand = "Amazon", Model = "AFTKA",
    AndroidVersion = "9", Sdk = 28, FireOsVersion = "Fire OS 7.6.8.8 (PS7688/4591)", RamMb = 1680,
    Network = NetworkKind.WiFi, HomePackage = "com.amazon.tv.launcher", SetupComplete = true,
    StorageTotalMb = 5087, StorageFreeMb = 2830,
    Enabled = ["com.amazon.tv.launcher", "com.amazon.device.logmanager", "com.amazon.tv.fw.metrics",
               "com.amazon.tv.acr", "com.amazon.kso.blackbird", "com.amazon.sneakpeek", "com.amazon.tmm.tutorial",
               "com.amazon.bueller.photos", "com.android.printspooler", "com.netflix.ninja", "com.android.dreams.basic"],
    ThirdParty = ["com.netflix.ninja"],
};
Shot("choose-firetv-dark-en", ThemeVariant.Dark, "en", m =>
    new ChooseViewModel(m, fire) { LastChoices = null, FirePrivacyOn = ["Fire_IbAds", "Fire_DeviceUsage", "Fire_AppUsage"] },
    1100, fire);

// Update dialog with made-up notes, and a real check against GitHub.
void UpdateShot(string name, ThemeVariant theme, string lang)
{
    Application.Current!.RequestedThemeVariant = theme;
    Loc.Instance.Language = lang;
    var main = new MainViewModel(new AdbService());
    main.Updater.Update = new UpdateInfo(new Version(1, 2, 0),
        "• Remembers the TVs you used before\n• Turns debugging off for you at the end\n• Suggests a home screen without ads",
        "", 0, "", "");
    main.Updater.Dialog = UpdateDialog.Available;
    var w = new MainWindow { DataContext = main, Width = 1140, Height = 800 };
    w.Show();
    Dispatcher.UIThread.RunJobs();
    w.CaptureRenderedFrame()?.Save(Path.Combine(outDir, name + ".png"));
    w.Close();
    Console.WriteLine(name);
}
UpdateShot("update-dark-en", ThemeVariant.Dark, "en");
UpdateShot("update-dark-fr", ThemeVariant.Dark, "fr");
// Off the UI thread: awaiting on it here would wait for a dispatcher nobody is running.
Console.WriteLine($"current {UpdateService.Current}; check: {Task.Run(() => UpdateService.CheckAsync()).Result}");
Console.WriteLine($"check as 0.9.0: {Task.Run(() => UpdateService.CheckAsync(new Version(0, 9, 0))).Result}");

// App icon: the sidebar logo at Windows icon sizes (packed into Assets/app.ico by tools/make-ico.py).
foreach (var size in new[] { 16, 24, 32, 48, 64, 256 })
{
    var radius = size * 0.22;
    var logo = new Avalonia.Controls.Border
    {
        Width = size, Height = size,
        CornerRadius = new CornerRadius(radius),
        Background = new Avalonia.Media.SolidColorBrush(Avalonia.Media.Color.Parse("#2459D6")),
        Child = new Avalonia.Controls.Shapes.Path
        {
            Data = Avalonia.Media.Geometry.Parse("M3 5 H21 V16 H3 Z M8 20 H16 M12 16 V20 M9 9.5 L11 11.5 L15 7.5"),
            Stroke = Avalonia.Media.Brushes.White,
            StrokeThickness = Math.Max(1.6, size * 0.055),
            StrokeLineCap = Avalonia.Media.PenLineCap.Round,
            StrokeJoin = Avalonia.Media.PenLineJoin.Round,
            Stretch = Avalonia.Media.Stretch.Uniform,
            Width = size * 0.6, Height = size * 0.6,
        },
    };
    logo.Measure(new Size(size, size));
    logo.Arrange(new Rect(0, 0, size, size));
    using var bmp = new Avalonia.Media.Imaging.RenderTargetBitmap(new PixelSize(size, size));
    bmp.Render(logo);
    bmp.Save(Path.Combine(outDir, $"icon-{size}.png"));
}
Console.WriteLine("icons");

// macOS icon: the same logo on Apple's grid (824 px rounded square centred in 1024, with a soft shadow).
{
    const int size = 1024;
    var tile = new Avalonia.Controls.Border
    {
        Width = 824, Height = 824,
        CornerRadius = new CornerRadius(185),
        Background = new Avalonia.Media.LinearGradientBrush
        {
            StartPoint = new Avalonia.RelativePoint(0, 0, Avalonia.RelativeUnit.Relative),
            EndPoint = new Avalonia.RelativePoint(0, 1, Avalonia.RelativeUnit.Relative),
            GradientStops =
            {
                new Avalonia.Media.GradientStop(Avalonia.Media.Color.Parse("#3B72F0"), 0),
                new Avalonia.Media.GradientStop(Avalonia.Media.Color.Parse("#1F4FC4"), 1),
            },
        },
        BoxShadow = Avalonia.Media.BoxShadows.Parse("0 10 24 0 #40000000"),
        Child = new Avalonia.Controls.Shapes.Path
        {
            Data = Avalonia.Media.Geometry.Parse("M3 5 H21 V16 H3 Z M8 20 H16 M12 16 V20 M9 9.5 L11 11.5 L15 7.5"),
            Stroke = Avalonia.Media.Brushes.White,
            // Stroke width is in pixels, not scaled with the shape.
            StrokeThickness = 42,
            StrokeLineCap = Avalonia.Media.PenLineCap.Round,
            StrokeJoin = Avalonia.Media.PenLineJoin.Round,
            Stretch = Avalonia.Media.Stretch.Uniform,
            Width = 500, Height = 500,
        },
    };
    var canvas = new Avalonia.Controls.Panel { Width = size, Height = size, Children = { tile } };
    tile.HorizontalAlignment = Avalonia.Layout.HorizontalAlignment.Center;
    tile.VerticalAlignment = Avalonia.Layout.VerticalAlignment.Center;
    canvas.Measure(new Size(size, size));
    canvas.Arrange(new Rect(0, 0, size, size));
    using var bmp = new Avalonia.Media.Imaging.RenderTargetBitmap(new PixelSize(size, size));
    bmp.Render(canvas);
    bmp.Save(Path.Combine(outDir, "mac-icon-1024.png"));
    Console.WriteLine("mac icon");
}
