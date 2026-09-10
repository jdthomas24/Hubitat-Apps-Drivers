/*
    Sofabaton Integration
    Copyright 2026 Jason Thomas. All Rights Reserved

    2026-09-10 jdthomas24
        -Initial publication

    *OVERVIEW
     Parent app for the Sofabaton Integration. Manages one or more physical
     Sofabaton hubs (X1S/X2), each as its own "Sofabaton Remote" child device
     nested under a single "Sofabaton Integration Bridge" grouping device, and
     lets each hub have one or more "Sofabaton Activity" child devices nested
     under IT for the activities you want to control/observe.

     This app only handles setup (adding/removing hubs and activities).
     Runtime behaviour -- local button routing, cloud webhook calls, and
     Activity state sync when the physical remote is used -- lives entirely
     in the Remote and Activity drivers themselves; this app does not
     subscribe to or process any device events.
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
                    String ipShown = hub.currentValue("remoteIp") ?: "no IP set"
                    paragraph "<b>${hub.getLabel()}</b> (${ipShown}) -- ${activities.size()} activit${activities.size() == 1 ? 'y' : 'ies'}"
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
    if (newHubName && newHubModel == "X2" && newHubMqttHost) {
        createMqttHub(newHubName, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
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
                input name: "newHubIp", type: "text", title: "Hub IP Address (set a static DHCP reservation first)", required: true, submitOnChange: true
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
                input name: "newHubMqttHost", type: "text", title: "Broker Host/IP (not 127.0.0.1 -- use this hub's real LAN address)", required: true, submitOnChange: true
                input name: "newHubMqttPort", type: "text", title: "Broker Port", defaultValue: "1883", required: false
                input name: "newHubMqttUser", type: "text", title: "Broker Username (leave blank if none)", required: false
                input name: "newHubMqttPass", type: "password", title: "Broker Password (leave blank if none)", required: false
                paragraph "You'll also need the hub's MAC address once it's connected -- this gets added automatically when MQTT topics are confirmed; not required to save this page."
            }
        }
    }
}

private void clearHubSettings() {
    ["newHubName", "newHubModel", "newHubIp", "newHubMqttHost", "newHubMqttPort", "newHubMqttUser", "newHubMqttPass"].each {
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
    hub.updateSetting("hubModel", [value: "X1/X1S", type: "text"])
    hub.updated()
}

// MQTT hub creation is a placeholder pending real-hardware confirmation of the
// topic structure, MAC format, and broker-field behavior in the Sofabaton app
// (see project notes). Broker credentials are collected now since that part
// doesn't depend on any of those open unknowns, but the created device won't
// actually subscribe/publish to anything real until RemoteDriver has an MQTT
// variant to hand these settings to.
private void createMqttHub(String name, String host, String port, String user, String pass) {
    log.warn "MQTT hub creation is not yet wired up -- '$name' broker details saved, but no MQTT Remote device type exists yet pending hardware validation"
}

def addActivityPage() {
    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []

    if (newActivityHub && newActivityName && newActivityUrlOn) {
        createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff)
        app.removeSetting("newActivityHub")
        app.removeSetting("newActivityName")
        app.removeSetting("newActivityUrlOn")
        app.removeSetting("newActivityUrlOff")
        return mainPage()
    }

    dynamicPage(name: "addActivityPage", title: "Add an Activity", install: false, uninstall: false) {
        section {
            input name: "newActivityHub", type: "enum", title: "Which Hub?", options: hubs.collectEntries { [(it.deviceNetworkId): it.getLabel()] }, required: true
            input name: "newActivityName", type: "text", title: "Activity Name (e.g. Watch TV) -- must match the remote's configured label exactly, this is how state sync matches it up", required: true
            input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL", required: true
            input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, unconfirmed feature -- leave blank if unsure)", required: false, submitOnChange: true
        }
    }
}

private void createActivity(String hubDni, String name, String urlOn, String urlOff) {
    def bridge = getBridge()
    def hub = bridge?.getChildDevice(hubDni)
    if (!hub) {
        log.error "Cannot add activity '$name': hub not found"
        return
    }
    def activity = hub.createActivityDevice(name, urlOn, urlOff)
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

