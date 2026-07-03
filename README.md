# r1999-TrackerApp

Android companion for the Reverse:1999 summon tracker. It grabs the summon history link on-device by running a local MITM proxy, then hands the URL to the tracker.

The game does not validate the server certificate, so no root and no trusted CA are required — the proxy presents its own certificate and the game accepts it.

## Usage

1. Install the APK (from the Actions artifact or a release).
2. Open the app, start the capture.
3. In Android Wi-Fi settings, set a Manual proxy for your current network to the host and port shown in the app.
4. Launch Reverse:1999 and open Summon → Record.
5. The link is captured, copied to the clipboard, and shown in the app. Use "Open tracker" and paste it.
6. Turn the Wi-Fi proxy back off when done.

## Build

Debug APK is built by GitHub Actions on every push (`app-debug.apk` artifact). To build locally, open the project in Android Studio and run the `assembleDebug` task.

## Disclaimer

The app only reads the request URL made by the game client and does not modify game data. Use at your own risk.
