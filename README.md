<p align="center"><img src="docs/icon.png" width="96" alt=""></p>

<h1 align="center">UnbloatMyTV</h1>

<p align="center">
  Turn off the ads, tracking and preinstalled apps on any Android TV, Google TV or Fire TV.<br>
  For Windows, macOS, Linux and Android phones. Plain-language choices, a backup of everything, one-click undo.
</p>

<p align="center">
  <a href="../../releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/matguitarist/UnbloatMyTV?label=download"></a>
  <img alt="Android TV, Google TV, Fire TV" src="https://img.shields.io/badge/Android%20TV%20%C2%B7%20Google%20TV%20%C2%B7%20Fire%20TV-green">
  <img alt="Windows, macOS, Linux, Android" src="https://img.shields.io/badge/Windows%20%C2%B7%20macOS%20%C2%B7%20Linux%20%C2%B7%20Android-blue">
  <a href="LICENSE"><img alt="MIT licence" src="https://img.shields.io/github/license/matguitarist/UnbloatMyTV"></a>
  <br><sub>Formerly “Android TV Debloater”.</sub>
  <br><a href="README.fr.md">Version française</a>
</p>

![Choosing what to turn off](docs/choose-dark-en.png)

## Download

Get the file for your computer or phone from the **[latest release](../../releases/latest)**. Nothing else to
install: the first time, the computer apps download Google's official ADB tools (about 7 MB, from
`dl.google.com`) into its own folder.

| | File | First launch |
|---|---|---|
| **Windows** 10/11 | `UnbloatMyTV.exe` | SmartScreen may say the publisher is unknown (the exe isn't code-signed yet): **More info → Run anyway**. |
| **macOS** 14+ | `UnbloatMyTV-mac.zip` | Native Mac app for Apple silicon and Intel, signed and notarized by Apple. Unzip, move it to Applications, open it, and allow it to find devices on the local network. |
| **Linux** x64 | `UnbloatMyTV-x86_64.AppImage` | Most distributions (Ubuntu 22.04 and newer, Fedora, Debian, Arch…). Make it executable (`chmod +x`, or Properties → Allow executing) and open it. |
| **Android** 8+ | `UnbloatMyTV-android.apk` | For a phone or tablet on the same Wi-Fi as the TV; it speaks ADB itself, nothing else to download. Open the file on the phone and allow installing from your browser or file manager. The clean-up and every tool are there, with network debugging or Wireless debugging (pairing code). |

Every app updates itself: it shows what's new and installs the update in a few seconds (on Android,
after you confirm). Choices and backups are kept.

## What it does

**Clean up**
- **Finds your TV on the network** and connects over Wi-Fi or Ethernet. No cable, no root.
- **Lists only what is on your TV**, explained in plain words: tracking, ads and demo mode, unused
  system parts, Google features, preinstalled apps, and speed and privacy settings.
- **Ticks the safe choices for you.** Anything you might use (voice search, casting, streaming
  apps) is left unticked, with a warning that says what stops working.
- **Speeds the TV up**: faster animations, fewer apps kept in memory, freezing apps left in the
  background, app optimisation, clearing caches, a simple screensaver.
- **Blocks ads and trackers for the whole TV** with a filtering DNS, tested on the spot and undone
  if it breaks anything.
- **Backs up, applies, restarts the TV and checks it**: home screen, internet, name lookups.

**Keep it clean**
- **Notices when an update undoes your cleanup**: system updates and Google Play services sometimes
  turn apps back on or reset settings. The app shows what came back and ticks it again in one click.
- **Same choices on the next TV**, and choices can be saved to a file to reuse anywhere.
- **Undo any run exactly** from Restore, or turn every app back on in one click.
- **Suggests a home screen without ads** (Projectivy Launcher, FLauncher, AT4K Launcher, which it can also install straight from GitHub) and can put the original back.
- **Turns debugging off for you** when you are done, then **Start over** for the next TV.

**Tools**
- **TV info**: Android version, how old the security patch is, whether the system is locked and
  verified, with shortcuts to System update and About, and a restart that waits for the TV.
- **Privacy check-up**: which apps can use the microphone, camera, location, contacts and
  calendar, and one click to remove an access. Restore gives it back.
- **My apps**: the apps you installed, with their picture, size and where they came from. Open,
  stop, turn off or uninstall them, and spot apps that didn't come from a store.
- **Health check**: memory, storage, processor, the apps using the most memory, and apps stuck in a
  crash loop, with plain advice. Read-only.
- **Screen and remote**: see the TV screen live and control it from the computer, including typing
  into search and password fields.
- **Install an app**: drop an `.apk` on the window, handy on TVs without the Play Store.

Nothing is uninstalled: apps are switched off with Android's own `pm disable-user`, and a factory
reset brings everything back too. English and French; light and dark themes follow your system.

## Screens

| | |
|---|---|
| ![Prepare](docs/welcome-dark-en.png) | ![Connect](docs/connect-dark-en.png) |
| ![Some changes were undone](docs/choose-returned-dark-en.png) | ![Done](docs/apply-dark-en.png) |
| ![Health check](docs/health-dark-en.png) | ![Screen and remote](docs/remote-dark-en.png) |
| ![TV info](docs/info-dark-en.png) | ![Privacy check-up](docs/privacy-dark-en.png) |
| ![My apps](docs/apps-dark-en.png) | ![Restore](docs/restore-dark-en.png) |
| ![Choisir, in French](docs/choose-dark-fr.png) | ![The Mac app](docs/mac-welcome-en.png) |
| ![Fire TV](docs/choose-firetv-dark-en.png) | ![Install an app](docs/install-dark-en.png) |

## Before you start, on the TV

1. **Settings → Device Preferences → About** (Google TV: **Settings → System → About**).
2. Press OK on **Build** (or **Android TV OS build**) 7 times, until it says you are a developer.
3. Back one screen: **Developer options → USB debugging** on. If there is a **Network debugging**
   or **ADB over network** switch, turn it on too.
4. When the app connects, accept **Allow USB debugging?** on the TV and tick *Always allow*.

On a **Fire TV**: Settings → My Fire TV → About, press OK on the device name 7 times, then
Developer options → **ADB debugging** on.

Newer Google TV devices that only offer **Wireless debugging** with a pairing code are supported
from the Connect page. When you are done, the last screen turns debugging off again.

## Supported devices

Anything running **Android TV** or **Google TV**: TVs (Sony, TCL, Xiaomi, Philips, Hisense…),
streaming boxes and sticks (Chromecast with Google TV, NVIDIA Shield, Mi Box…) and the Android TV
set-top boxes that many TV operators rent out.

**Fire TV** (tested on a Fire TV Stick 4K Max, Fire OS 7.6) connects the same way. Fire OS refuses
to switch off some of Amazon's own apps, so the app only offers the Amazon parts known to switch off
cleanly (metrics, content recognition, sponsored rows, tutorials…) and hides the rest. It shows which
of Amazon's tracking switches are still on and opens the TV's menu to turn them off, and the speed and DNS settings work
as usual. Another home screen can't fully replace Amazon's: tested with Projectivy, it takes over the
Home button only after Android's 5-second delay, so Amazon's home screen still shows first.

**Not supported:** Samsung (Tizen), LG (webOS), Roku and Apple TV run other systems. On a Samsung
TV, turn off tracking in **Settings → General → Terms & Privacy** (Viewing Information Services,
Interest-Based Advertisement, Voice Recognition).

## How it stays safe

- Only apps in a reviewed list ([`shared/catalog.json`](shared/catalog.json)) are offered, plus apps
  installed on top of the system (always unticked).
- Never offered, whatever the list says: any app that can act as the home screen, the keyboard,
  Settings, the Play Store, Google Play Services, WebView, Bluetooth, the core of Android, and the
  operator parts a set-top box needs (the list's `keep` section).
- Every change is written to a backup with its original value *before* it is made, so even an
  interrupted run can be undone. "Turn Wi-Fi off" is only offered on an Ethernet cable.
- Backups (in folders still named after the app's old name) live in `%LOCALAPPDATA%\AndroidTVDebloater\backups` (Mac:
  `~/Library/Application Support/AndroidTVDebloater/backups`, Linux: `~/.local/share/AndroidTVDebloater/backups`),
  with a raw snapshot of all packages and settings. Same format on every system.

## Privacy

The app has no account, no analytics and no server of its own. Besides your TV, it only contacts:
- `dl.google.com`, once, to download Google's ADB tools;
- `github.com` to check for a new version (this can be turned off in the update window) and to fetch
  the latest list of apps;
- GitHub's "new issue" page in your browser, only if you click **Suggest them on GitHub**, with the
  TV model and app names filled in for you to review.

## For developers

**Windows and Linux app** (`src/`, C# with Avalonia): [.NET 10 SDK](https://dotnet.microsoft.com/download).
```sh
dotnet run --project src/AndroidTVDebloater                               # run it (any system)
dotnet publish src/AndroidTVDebloater -c Release -r win-x64 -o publish    # single Windows exe
linux/build-appimage.sh                                                   # AppImage (on Linux)
```

**Mac app** (`mac/`, Swift and SwiftUI, updates with [Sparkle](https://sparkle-project.org)): Xcode,
then `mac/build-app.sh`. Its text comes from the Windows app's `Strings.cs`: after changing it, run
`python3 tools/gen-mac-strings.py`.

**Android app** (`android/`, Kotlin and Jetpack Compose, ADB over the network with
[dadb](https://github.com/mobile-dev-inc/dadb), Wireless debugging with
[libadb-android](https://github.com/MuntashirAkon/libadb-android)): JDK 17+ and the Android SDK, then
`cd android && ./gradlew assembleDebug`. Its text also comes from `Strings.cs`: run
`python3 tools/gen-android-strings.py`.

**Screenshots**: `dotnet run --project tools/Screenshots -- docs` renders every page with a made-up TV.

**The list of apps** is [`shared/catalog.json`](shared/catalog.json), used by every app. Each entry
has a package `id` (or a `prefix`), a `group`, `recommended`, and `name` / `description` (plus an
optional `warning`) in English and French; settings and the `keep` list live there too. Only mark
something `recommended` if switching it off changes nothing a viewer would notice, test it on a
real device, and **increase `version`**: every installed copy picks up the new list from `main`.

**Releases**: bump `<Version>` in the csproj and push an annotated tag such as `v1.3.0`. GitHub
Actions builds them all, signs and notarizes the Mac app, signs the Android app, and publishes the release; the tag
message becomes the notes shown in the update window.

## Credits

Several catalog entries come from the device lists contributed to
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT). See [NOTICE](NOTICE).

## Licence

MIT. Use at your own risk: the app only switches things off and back on, but it is your device.
