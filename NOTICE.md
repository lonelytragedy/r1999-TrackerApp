# Third-party attribution

This application is licensed under the GNU General Public License v3.0 (see LICENSE)
because it includes and links against GPLv3 components.

## tun2http native engine

The native packet-capture engine under `app/src/main/cpp/` (dhcp.c, dns.c, http.c,
icmp.c, ip.c, session.c, tcp.c, tls.c, tun2http.c, udp.c, util.c and their headers)
is taken from:

- TunProxy — https://github.com/raise-isayan/TunProxy

which itself derives from:

- NetGuard — https://github.com/M66B/NetGuard
  Copyright (C) 2015-2017 Marcel Bokhorst (M66B)

Both are distributed under the GNU General Public License v3.0. The original
copyright and license headers are retained inside the source files.

The `tun.proxy.service.Tun2HttpVpnService` and `tun.utils.Util` classes are a
reimplementation of the VpnService glue needed to drive that engine, kept in the
original package names so the engine's JNI symbols resolve.
