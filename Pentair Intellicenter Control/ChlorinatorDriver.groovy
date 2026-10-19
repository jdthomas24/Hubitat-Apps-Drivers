/*
    Pentair IntelliCenter - Chlorinator Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One device per IntelliChlor salt cell (OBJTYP CHEM, SUBTYP ICHLOR), created by the Bridge when the
      controller reports one. Read-only: salt level, pool and spa output %, superchlorinate.
     -Field names (SALT, PRIM, SEC, SUPER) follow the Home Assistant IntelliCenter integration. Not yet
      confirmed on a real system; controls (output %, superchlorinate) come once readings are confirmed.
     -Log level comes from the app through the Bridge.
*/

metadata {
    definition(name: "Pentair IntelliCenter Chlorinator", namespace: "intellicenter", author: "jdthomas24") {
        capability "Refresh"
        capability "Sensor"

        attribute "saltLevel",       "number"
        attribute "poolOutput",      "number"
        attribute "spaOutput",       "number"
        attribute "superChlorinate", "string"
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.transform.Field

@Field static final String VERSION     = "1.7.0"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]

String fmtHelpInfo() {
    String info     = "Pentair IntelliCenter v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

def installed() { }
def updated() { }

def refresh() { parent?.componentRefresh(this) }

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
}
