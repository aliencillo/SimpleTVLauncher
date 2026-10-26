# Simple TV Launcher

A lightweight, customizable Android TV launcher with a text-focused home screen, local file management and web server.

**Latest version:** 2.0.7  
**Platform:** Android TV / Google TV  
**License:** [GNU GPL-3.0](LICENSE)

## Screenshots

### Home
![Simple TV Launcher home screen](home.png)

### Settings
![Simple TV Launcher settings](settings.png)

### Web File Manager
![Simple TV Launcher web file manager](server.png)

## Features

- **Text-based home screen** with editable shortcuts for installed apps and URLs.
- **Custom appearance:** wallpapers, custom images, fonts, text sizes, selection style, panel position and transparency.
- **Installed app browser** and configurable app icons.
- **Clock and date** with visibility and date-position options.
- **TV input shortcuts** for available HDMI inputs and tuners, subject to device support.
- **File explorer** for browsing accessible folders on the TV.
- **Built-in web file manager** for browsing folders and uploading, downloading or deleting files over your local network.
- **10 languages:** English, Spanish, Catalan, Japanese, Simplified Chinese, French, German, Portuguese, Italian and Russian.
- **HOME configuration** options where supported by Android TV.

## Web File Manager

1. Connect your TV and computer or phone to the same local network.
2. Press **0** in Simple TV Launcher to start the web server.
3. Open the displayed address (for example, `http://192.168.1.100:8080`) in a web browser.
4. Browse folders and manage files from the browser.
5. Stop the server from the TV when finished.

The server uses **HTTP on port 8080 without authentication**. Enable it only on a trusted local network and stop it when not in use. Storage access depends on Android permissions.

## Controls

| Key | Action |
| --- | --- |
| Up / Down | Navigate the list |
| OK | Open or select |
| Right | Open the selected home-screen item |
| Hold OK on a shortcut | Edit the shortcut |
| Back | Return to the previous screen or folder |
| 0 | Open web transfer |
| 1 | Save a screenshot of the launcher |

## Download and Install

Download the APK from [Releases](../../releases) and install it on your Android TV device. Android may ask you to allow installation from the application used to open the APK.

Some devices restrict changing the default launcher, direct HDMI switching or access to system settings.

## Build from Source

The project uses Java and Gradle 9.8.2 with Android Gradle Plugin 9.4.0. On Windows, run `build-debug-apk.bat` to attempt an automated development build; internet access is required for first-time tooling downloads.

The resulting debug APK is normally created at `app/build/outputs/apk/debug/app-debug.apk`.

## Status and Feedback

This project is under active development. If you encounter a problem, please [open an issue](../../issues) with your TV model, Android version, steps to reproduce and the launcher version.

## License

Licensed under **GNU General Public License v3.0**. See [LICENSE](LICENSE). Third-party media assets, if any, may have separate licensing conditions.
