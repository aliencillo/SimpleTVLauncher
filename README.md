# Simple TV Launcher

A lightweight, customizable Android TV launcher with a text-focused home screen, customizable backgrounds, local file management, and video-input shortcuts.

**Version:** 1.9.4 (`versionCode` 35)  
**Platform:** Android TV / Google TV (device support varies)  
**Source language:** Java  
**Build:** Gradle 9.8.2 / Android Gradle Plugin 9.4.0 / Java 25 for running Gradle

> **Status:** Experimental / work in progress. This source has not been independently build-tested or tested across TV models in the preparation environment. Device-specific behaviors—especially HOME, HDMI input switching, file installation, and Android settings—may vary.

## Features

- **Minimal TV home screen:** customizable app shortcuts on the left, clock and date in the top-right corner. The selected shortcut grows in size.
- **Simple navigation:** use the TV’s standard directional controls to browse menus. Press and hold OK on a shortcut to edit it.
- **Shortcut management:** add installed applications or URL links, edit destinations, reorder entries, and remove links. App names are taken from Android.
- **Built-in app browser:** an *All apps* entry on the main screen.
- **Wallpaper choices:** bundled image and abstract backgrounds, or a user-selected image.
- **Appearance settings:** fonts (including supported custom font files), text size, panel opacity, full-height panels, and quick menu animations.
- **Clock/date controls:** customize date/time display, including hiding the clock.
- **Video inputs:** list supported physical TV inputs and a tuner, with ordering and visibility controls. Availability and launching depend on the TV manufacturer's implementation.
- **Default HOME settings:** Android launcher selection where supported, plus an optional accessibility-based HOME redirection feature. It requires explicit activation and may not work on some TVs.
- **TV file explorer:** browse accessible folders and files, see modification dates, and request Android-compatible apps to open files. APK installation requires Android's *Install unknown apps* permission when applicable.
- **Local web file manager:** press **0** on the TV controller to run a local HTTP server on **port 8080**. From a browser on the same network, navigate accessible shared-storage folders, upload via drag and drop, download files, and delete files with confirmation. The current folder determines the upload destination.
- **Languages:** Spanish, English, Catalan, Japanese, Simplified Chinese, French, German, Portuguese, Italian, and Russian, including localized web interface text.

## Navigation shortcuts

| Key | Action |
| --- | --- |
| Up / Down | Move through options |
| OK | Open or confirm selected option |
| Right | Open selected item in the main menu |
| Hold OK | Edit an app shortcut from the home screen |
| BACK | Return to the previous screen/folder |
| 0 | Open the web-transfer screen |

Available keys and system shortcuts depend on the TV manufacturer.

## Web file transfers

1. Connect your TV and phone/computer to the same trusted local network.
2. From the launcher press **0** to start the server.
3. On the TV, read the displayed address, for example `http://192.168.1.100:8080/` (your IP will differ).
4. Open the address in a browser. Navigate to a folder; use **Upload**, drag-and-drop, **Download**, or **Delete** as needed.
5. Stop the server from the TV when finished.

**Security:** the web interface intentionally has **no password** and uses **unencrypted HTTP**. Other devices that can reach the TV over the local network may access the files the app can expose. Use it only on trusted networks, and stop it when not in use. Android storage restrictions may prevent browsing or changing certain folders.

## Build on Windows

1. Install **Java 25** (the launcher project's Gradle version supports it).
2. Extract or clone the repository on Windows.
3. Double-click **`build-debug-apk.bat`**. The helper script attempts to download/configure Android SDK tools and Gradle on the first run; Internet access is needed.
4. If the build completes successfully, find the APK at:
   `app/build/outputs/apk/debug/app-debug.apk`

The APK produced by this script is a **debug build** for testing, not a release-signed Google Play artifact. The supplied source has not been verified to compile in every environment.

## Install on Android TV

Transfer the APK to your TV, then open it using a file manager or an Android-supported installation method. Enable *Install unknown apps* for the installing application if Android requests it. To make Simple TV Launcher the default home screen, use the Android launcher/home application settings, if available. Some TV firmware restricts this.

## Settings overview

- **Links:** manage app and URL shortcuts.
- **Wallpapers:** choose a bundled or custom background.
- **Fonts / Text size:** adjust typography.
- **Clock & date:** enable, disable, or customize display elements.
- **Menu options:** transparency, full-height panels, animation toggle.
- **Default launcher:** home selection and optional accessibility alternatives.
- **Language:** choose a language or follow the system locale.
- **Web transfer / File explorer:** local file transfer and TV browsing.
- **Android settings:** open system settings when supported.
- **Reset / About:** restore defaults and read version information.

## Known limitations

- TV input discovery and direct HDMI switching are not uniformly exposed by Android TV devices.
- Manufacturer firmware can block HOME redirection or specific Android Settings intents.
- The Android file picker, file-access permissions, APK installation and handler selection vary by Android version and manufacturer.
- The local web server is meant for trusted LANs only. It does not provide TLS or authentication.
- This project is still evolving; please report reproducible issues with device model, Android version, and steps to reproduce.

## Contributions and issues

Bug reports and pull requests are welcome. When reporting a bug, include: TV/box model, Android version, app version, steps to reproduce, expected behavior, and actual result. Screenshots or logs are helpful when they do not reveal personal information.

## Releases and licensing

Publish downloadable APKs under **GitHub Releases** only after building and testing them. For upgrades to work, release APKs must retain the same `applicationId`, a higher `versionCode`, and the same signing key.

**License:** The project source code is released under the GNU General Public License v3.0 only (`GPL-3.0-only`). See [LICENSE](LICENSE). Distribution of included third-party assets is subject to their respective rights; review asset provenance before public release.

## Version 1.9.4 interface updates

- Settings and other panels open from the left side of the TV.
- A panel entry/exit transition plays only when opening or closing the panel, not when moving between settings sections.
- Optional vector menu icons (`Settings > Menu options > Show icons`).
- Selection mode: zoom text or highlighted selector (`Settings > Menu options > Selection style`).
- Date placement: top, bottom, or hidden (`Settings > Date & time > Date position`). Time visibility remains configurable separately.
- The embedded web file manager uses a light interface with a file table, folder navigation, and upload and refresh icon buttons.

These UI changes have not been tested on physical Android TV hardware.


### Version 1.9.4

Application entries now use the installed app icons supplied by Android. TV inputs display the device-provided icon when available and otherwise a distinct tuner or HDMI icon. The existing **Show icons** setting hides these icons everywhere.
