# r1999-TrackerApp

Android companion for the Reverse:1999 summon tracker. It grabs the summon history link on-device by running a local MITM proxy, then hands the URL to the tracker.

The game does not validate the server certificate, so no root and no trusted CA are required — the proxy presents its own certificate and the game accepts it.

## Usage

There are two capture modes. In both, log in to the game first (the game
rejects the interception certificate only during login), then start the
capture and open Summon → Record.

### VPN capture (one tap, recommended)

1. Install the APK.
2. Log in to the game.
3. Tap "Start VPN capture" and allow the VPN request once.
4. Open Summon → Record. The link is captured, copied to the clipboard, and shown.
5. Tap "Open tracker", paste the link, then stop the VPN.

### Manual proxy capture

1. Tap "Manual proxy capture".
2. In Android Wi-Fi settings, set a Manual proxy for your network to the host and port shown in the app.
3. Open Summon → Record.
4. Turn the Wi-Fi proxy back off when done.

## License

GPLv3 (see LICENSE). The VPN mode embeds the tun2http native engine from
TunProxy / NetGuard — see NOTICE.md for attribution.

## Build

Debug APK is built by GitHub Actions on every push (`app-debug.apk` artifact). To build locally, open the project in Android Studio and run the `assembleDebug` task.

## Disclaimer

The app only reads the request URL made by the game client and does not modify game data. Use at your own risk.
