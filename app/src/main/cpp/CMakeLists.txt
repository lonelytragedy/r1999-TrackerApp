cmake_minimum_required(VERSION 3.10.2)

add_library(
        tun2http
        SHARED
        dhcp.c
        dns.c
        icmp.c
        ip.c
        http.c
        tun2http.c
        session.c
        tcp.c
        tls.c
        udp.c
        util.c)

find_library(log-lib log)

target_link_libraries(tun2http ${log-lib})
