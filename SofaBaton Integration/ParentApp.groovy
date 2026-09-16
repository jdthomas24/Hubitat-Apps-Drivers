/*
    Sofabaton Integration - Parent App
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: the X1/X1S local receive path (Sofabaton Remote driver) is a
    fork of Derek Osborn's (dJOS1475) Hubitat community driver, built on
    push-command building blocks originated by Mike Maxwell (mike.maxwell).

    2026-09-10 jdthomas24
        -Initial publication
    2026-09-16 jdthomas24
        -Fixed addHubPage: removed submitOnChange from newHubIp/newHubMac/
         newHubMqttHost, which was forcing a page postback (and required-
         field validation reset) on every keystroke into those fields.

    *OVERVIEW
     Parent app for the Sofabaton Integration. Manages one or more physical
     Sofabaton hubs (X1S/X2), each as its own "Sofabaton Remote" child device
     nested under a single "Sofabaton Integration Bridge" grouping device, and
     lets each hub have one or more "Sofabaton Activity" child devices nested
     under IT for the activities you want to control/observe.

     X1S hubs are identified by IP and use a local HTTP listener. X2 hubs are
     identified by MAC address and share one MQTT connection (held by the
     Bridge device) across however many X2 hubs you add -- confirmed against
     real hardware. Adding an X2 hub here also wires up that shared MQTT
     connection.

     Adding an Activity asks for different things depending on the hub's
     model: an X1S activity needs a Start (and optional Stop) webhook URL;
     an X2 activity needs its numeric Sofabaton Activity ID instead, since
     X2's MQTT broadcasts report activity ids, not button labels, and
     there's currently no confirmed way to auto-discover a hub's activity
     list.

     This app only handles setup (adding/removing hubs and activities).
     Runtime behaviour -- local button routing, MQTT connect/subscribe/
     publish, cloud webhook calls, and Activity state sync when the
     physical remote is used -- lives entirely in the Bridge, Remote, and
     Activity drivers; this app does not subscribe to or process any device
     events itself.
*/

definition(
    name: "Sofabaton Integration",
    namespace: "jdthomas24",
    author: "Jason Thomas",
    description: "Manage one or more Sofabaton X Series hubs and their activities",
    category: "Convenience",
    menu: "Integrations",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage")
    page(name: "addHubPage")
    page(name: "addActivityPage")
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    if (!getBridge()) {
        addChildDevice("jdthomas24", "Sofabaton Integration Bridge", bridgeDni(), [label: "Sofabaton Integration Bridge"])
    }
}

private String bridgeDni() {
    return "sofabaton-bridge-${app.id}"
}

def getBridge() {
    return getChildDevice(bridgeDni())
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Sofabaton Integration", install: true, uninstall: true) {
        def bridge = getBridge()
        def hubs = bridge?.getChildDevices() ?: []

        section("Hubs") {
            if (!hubs) {
                paragraph "No hubs added yet."
            } else {
                hubs.each { hub ->
                    def activities = hub.getChildDevices() ?: []
                    String model = hub.currentValue("hubModel") ?: "unknown model"
                    String idShown = model == "X2" ? (hub.currentValue("remoteMac") ?: "no MAC set") : (hub.currentValue("remoteIp") ?: "no IP set")
                    paragraph "<b>${hub.getLabel()}</b> (${model}, ${idShown}) -- ${activities.size()} activit${activities.size() == 1 ? 'y' : 'ies'}"
                }
            }
            href name: "toAddHub", title: "Add a Hub", page: "addHubPage"
        }

        if (hubs) {
            section("Activities") {
                href name: "toAddActivity", title: "Add an Activity", page: "addActivityPage"
            }
        }
    }
}

def addHubPage() {
    if (newHubName && newHubModel == "X1/X1S" && newHubIp) {
        createHttpHub(newHubName, newHubIp)
        clearHubSettings()
        return mainPage()
    }
    if (newHubName && newHubModel == "X2" && newHubMac && newHubMqttHost) {
        createMqttHub(newHubName, newHubMac, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
        clearHubSettings()
        return mainPage()
    }

    dynamicPage(name: "addHubPage", title: "Add a Sofabaton Hub", install: false, uninstall: false) {
        section {
            input name: "newHubName", type: "text", title: "Hub Name (e.g. Living Room)", required: true
            input name: "newHubModel", type: "enum", title: "Hub Model", options: ["X1/X1S", "X2"], required: true, submitOnChange: true
        }
        if (newHubModel == "X1/X1S") {
            section {
                input name: "newHubIp", type: "text", title: "Hub IP Address (set a static DHCP reservation first)", required: true
            }
        }
        if (newHubModel == "X2") {
            section("MQTT Connection") {
                paragraph "<b>Before adding an X2 hub, MQTT needs to be running on this Hubitat hub.</b><br>" +
                    "Go to Apps &rarr; Add Built-In App &rarr; MQTT Import Integration (or Export Integration), enable it, " +
                    "and turn on 'Use built-in MQTT service' if you don't already have an external broker. " +
                    "The app will show you a host, port, and login -- enter that same information below.<br>" +
                    "Then, in the Sofabaton app, go to Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Add Home Assistant Remote, " +
                    "and enter the same broker details there so the hub connects to the same broker Hubitat does."
                input name: "newHubMac", type: "text", title: "Hub MAC Address (12 hex characters, e.g. 14639332AA40 -- colons are fine too, they'll be stripped)", required: true
                input name: "newHubMqttHost", type: "text", title: "Broker Host/IP (not 127.0.0.1 -- use this hub's real LAN address)", required: true
                input name: "newHubMqttPort", type: "text", title: "Broker Port", defaultValue: "1883", required: false
                input name: "newHubMqttUser", type: "text", title: "Broker Username (leave blank if none)", required: false
                input name: "newHubMqttPass", type: "password", title: "Broker Password (leave blank if none)", required: false
                paragraph "One shared MQTT connection is used for all X2 hubs you add -- entering broker details again for a second X2 hub reconnects to the same broker, it doesn't open a second connection."
            }
        }
    }
}

private void clearHubSettings() {
    ["newHubName", "newHubModel", "newHubIp", "newHubMac", "newHubMqttHost", "newHubMqttPort", "newHubMqttUser", "newHubMqttPass"].each {
        app.removeSetting(it)
    }
}

private void createHttpHub(String name, String ip) {
    def bridge = getBridge()
    if (!bridge) return
    String dni = ipToHexForApp(ip)
    if (!dni) {
        log.error "Cannot add hub '$name': '$ip' is not a valid IPv4 address"
        return
    }
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        log.error "Failed to create Remote device for hub '$name'"
        return
    }
    hub.updateSetting("ip", [value: ip, type: "text"])
    hub.updateSetting("hubModel", [value: "X1/X1S", type: "enum"])
    hub.updated()
}

private void createMqttHub(String name, String mac, String host, String port, String user, String pass) {
    def bridge = getBridge()
    if (!bridge) return
    String dni = (mac ?: "").replaceAll(/[^A-Fa-f0-9]/, "").toUpperCase()
    if (dni.length() != 12) {
        log.error "Cannot add hub '$name': '$mac' does not look like a valid 12-character MAC address"
        return
    }
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        log.error "Failed to create Remote device for hub '$name'"
        return
    }
    hub.updateSetting("hubModel", [value: "X2", type: "enum"])
    hub.updateSetting("mac", [value: dni, type: "text"])
    hub.updateSetting("mqttHost", [value: host, type: "text"])
    hub.updateSetting("mqttPort", [value: port, type: "text"])
    if (user) hub.updateSetting("mqttUser", [value: user, type: "text"])
    if (pass) hub.updateSetting("mqttPass", [value: pass, type: "password"])
    hub.updated()
}

def addActivityPage() {
    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []
    def selectedHub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    boolean isX2 = selectedHub?.currentValue("hubModel") == "X2"

    if (newActivityHub && newActivityName) {
        if (!isX2 && newActivityUrlOn) {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, null)
            clearActivitySettings()
            return mainPage()
        }
        if (isX2 && newActivitySofabatonId != null) {
            createActivity(newActivityHub, newActivityName, null, null, newActivitySofabatonId as Integer)
            clearActivitySettings()
            return mainPage()
        }
    }

    dynamicPage(name: "addActivityPage", title: "Add an Activity", install: false, uninstall: false) {
        section {
            input name: "newActivityHub", type: "enum", title: "Which Hub?", options: hubs.collectEntries { [(it.deviceNetworkId): it.getLabel()] }, required: true, submitOnChange: true
            input name: "newActivityName", type: "text", title: "Activity Name (e.g. Watch TV)" + (isX2 ? "" : " -- must match the remote's configured user-definable button label exactly, this is how state sync matches it up"), required: true
        }
        if (selectedHub && !isX2) {
            section {
                input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL", required: true
                input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, unconfirmed feature -- leave blank if unsure)", required: false
            }
        }
        if (selectedHub && isX2) {
            section {
                paragraph "X2 hubs use MQTT, not a webhook. Enter the numeric Sofabaton Activity ID for this activity -- trigger the activity once while watching MQTT traffic (e.g. MQTT Explorer) and read the activity_id value out of the activity_control_up message. Auto-discovery of the hub's activity list is not yet implemented."
                input name: "newActivitySofabatonId", type: "number", title: "Sofabaton Activity ID", required: true
            }
        }
    }
}

private void clearActivitySettings() {
    ["newActivityHub", "newActivityName", "newActivityUrlOn", "newActivityUrlOff", "newActivitySofabatonId"].each {
        app.removeSetting(it)
    }
}

private void createActivity(String hubDni, String name, String urlOn, String urlOff, Integer sofabatonActivityId) {
    def bridge = getBridge()
    def hub = bridge?.getChildDevice(hubDni)
    if (!hub) {
        log.error "Cannot add activity '$name': hub not found"
        return
    }
    def activity = hub.createActivityDevice(name, urlOn, urlOff, sofabatonActivityId)
    if (!activity) {
        log.error "Failed to create Activity device '$name'"
    }
}

// Mirrors RemoteDriver.groovy's ipToHex() so the app can compute a matching
// DNI at hub-creation time. Kept in sync manually -- if the driver's logic
// ever changes, this needs to change with it.
private String ipToHexForApp(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) return null
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2, "0").toUpperCase() }.join()
}
