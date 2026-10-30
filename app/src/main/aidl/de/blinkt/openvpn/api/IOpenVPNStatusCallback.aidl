package de.blinkt.openvpn.api;

interface IOpenVPNStatusCallback {
    /** تُستدعى عند تغيّر حالة الاتصال. state مثل: CONNECTING, CONNECTED, NOPROCESS, AUTH_FAILED ... */
    oneway void newStatus(in String uuid, in String state, in String message, in String level);
}
