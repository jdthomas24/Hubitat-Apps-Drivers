/*
    Leviton Smart Load Center - CT Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One device per CT clamp in use (Grid, Solar, Generator and so on), created by the Panel device (LWHEM only).
      Values come from the Panel through parse(). Power is negative while the source is generating.
     -The Panel sends only values that changed past their threshold (2.0.1), so parse() posts them without
      reading values back. Hubitat drops an event whose value didn't change.
     -Log level comes from the app through the Panel.
*/

metadata {
    definition(name: "Leviton Smart Load Center CT", namespace: "jdthomas24", author: "jdthomas24") {
        capability "PowerMeter"
        capability "CurrentMeter"
        capability "EnergyMeter"
        capability "Sensor"
        capability "Refresh"

        attribute "energyImport", "number"
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.transform.Field

@Field static final String VERSION     = "2.0.1"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-leviton-smart-load-center/163950"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]

String fmtHelpInfo() {
    String info     = "Leviton Smart Load Center v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

def installed() { }
def updated() { }

def refresh() { parent?.restRefresh() }

def parse(List<Map> events) {
    events.each { e ->
        Map out = [name: e.name, value: e.value]
        if (e.unit) out.unit = e.unit
        sendEvent(out)
    }
}


void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
}
