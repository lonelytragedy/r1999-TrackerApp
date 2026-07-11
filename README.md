# Reverse: Tracker

Android companion for the Reverse:1999 summon tracker (https://lonelytragedy.github.io/r1999-tracker/).

The app has two tabs, reachable from the pill nav at the bottom of the screen:

- **Tracker** — the live tracker site in an in-app WebView.
- **Link grabber** — captures the summon-history link on-device, with **VPN** and **Proxy** sub-tabs.

No root is required for either capture mode. The game only validates the
interception certificate on some of its connections to the summon service —
the pull-history request itself accepts it, so capture works without
installing any certificate as trusted.

## Link grabber — VPN capture (recommended, one tap)

1. Log in to the game first.
2. In the app, go to **Link grabber → VPN** and tap **Start VPN capture**.
   This arms a notification ("Ready — tap Start VPN") but does **not** start
   the tunnel yet.
3. Open the notification shade and tap **Start VPN** (grant the one-time VPN
   permission the first time). **Cancel** in the same notification dismisses
   it without enabling anything.
4. Open Summon → Record in the game.
5. The very first handshake to the summon service is sometimes rejected by
   the game — this is expected. Re-open the pull history a couple of times
   if the link isn't caught immediately.
6. Once caught, the link is copied to the clipboard, a "link captured"
   notification pops up, and the VPN stops automatically a moment later
   (it's no longer needed). Tap the notification, or **Open in tracker** in
   the app, to import the link straight into the Tracker tab.

Only the summon-history host is intercepted; all other game traffic is
tunnelled through untouched.

## Link grabber — Proxy capture (fallback)

1. Log in to the game first.
2. Go to **Link grabber → Proxy**, tap **Start**, then set a manual Wi-Fi
   proxy to the host:port shown in the app.
3. Open Summon → Record in the game.
4. Turn the Wi-Fi proxy back off when done.

## Tracker tab

The live site runs in a WebView with a themed loading splash and a custom
offline screen (instead of a blank page or the default Android error page).
File pickers (Select file, Load database) and the Save-as dialog for
Download Database work through native Android bridges.

Google Drive sign-in opens a Chrome Custom Tab (Google blocks OAuth inside a
plain WebView), exchanges the code via the tracker's Cloudflare Worker, and
stores the refresh token natively so the connection survives app restarts.

## Build

GitHub Actions builds a debug APK on every push (`app-debug.apk` artifact,
signed with the committed `app/signing.p12` keystore so updates install
cleanly over each other). To build locally, open the project in Android
Studio and run the `assembleDebug` task.

Releases are published manually as `Reverse1999TrackerApp.apk`.

## License

GPLv3 (see LICENSE). The VPN mode embeds the tun2http native engine from
TunProxy / NetGuard — see NOTICE.md for attribution.

## Disclaimer

The app only reads the request URL made by the game client and does not
modify game data. Use at your own risk.
