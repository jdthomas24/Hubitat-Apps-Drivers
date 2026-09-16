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
        -Relabeled the X2 MAC field to "Sofabaton Hub MAC ID" and added
         guidance on where to find it (it's the X2's own MAC, read out of
         the activity_control_up topic name via a tool like MQTT Explorer --
         not the Hubitat hub's MAC, which is an easy mix-up).
        -Fixed getBridge() to lazily create the Bridge device if missing,
         instead of only creating it in initialize() (which only runs after
         the first "Done" on the main page). Previously, adding a hub before
         ever completing that first install would silently fail since there
         was no Bridge yet to attach the child device to.
        -Added a "Listen for Next Activity" button to the X2 branch of Add
         Activity, working with the Bridge driver's new learn-mode support:
         press Listen, press the activity's button on the physical remote,
         and the numeric Sofabaton Activity ID auto-fills, no more manual
         MQTT Explorer lookup required. Page auto-refreshes while listening.
        -Added a "Cancel and go back" link to both Add Hub and Add Activity,
         and mainPage() now clears any half-entered fields from those pages
         whenever it's reached, so a cancelled attempt doesn't leave stale
         values sitting in the form the next time it's opened.
        -Changed the Hub Model dropdown from "X1/X1S" to just "X1S", since
         the plain X1 isn't supported by the underlying driver this forks.
        -Main page now lists each hub's activities nested underneath it
         instead of just a count, so with multiple hubs it's clear which
         activity belongs to which hub.
        -Added Edit (name, and for X2 the broker host/port/user/pass) and
         Remove for hubs, and Remove for activities, right on the main
         page. Model, MAC, and X1S IP are intentionally not editable after
         creation (those change the device's DNI) -- remove and re-add if
         one of those needs to change. Editing relies on new mqttHost/
         mqttPort/mqttUser attributes published by the Remote driver.
        -FIXED A REAL BUG: neither Add Hub nor Add Activity had an actual
         "finish" button for fields with no submitOnChange (the X2 broker
         fields, the X1S webhook URL fields). The only clickable thing on
         the page was the "Cancel and go back" link, which jumps straight
         to mainPage() without ever running the creation logic in
         addHubPage()/addActivityPage() -- and since mainPage() clears
         those fields on entry, clicking Cancel (easy to do by mistake,
         since it looked like the way to finish) silently discarded
         everything with zero error, zero log output. Added explicit
         "Add This Hub" / "Add This Activity" buttons that actually submit
         the page and trigger creation.
        -FIXED ANOTHER REAL BUG: the Listen-mode methods (startActivityLearn,
         getActivityLearnResult, cancelActivityLearn, clearActivityLearnResult)
         live on the Bridge device, but addActivityPage() and
         appButtonHandler() were calling them on the individual hub
         (Remote) device instead -- those methods don't exist there, so
         every click of "Listen for Next Activity" would have errored out
         silently. Now calls bridge.* directly.
        -FIXED A THIRD REAL BUG: required:true on the page's inputs (Hub
         Name, Hub Model, etc.) triggers the browser's own HTML5 "please
         fill out this field" validation, which blocks submitting the form
         at all -- including clicking "Cancel and go back", since it's the
         same <form>. That made Cancel completely unusable on a partially
         filled page. Dropped required:true from every field on both
         pages; the app's own logic already gates real creation on those
         fields being present, so nothing is lost except the native red
         asterisk/validation nudge.

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
    def bridge = getChildDevice(bridgeDni())
    if (!bridge) {
        // Lazily create the Bridge if it doesn't exist yet. installed()/
        // updated() only fire once the user hits "Done" on the main page,
        // but a first-run user can navigate straight into Add a Hub before
        // that happens -- without this, the hub-creation calls below would
        // silently fail with no Bridge to attach the child device to.
        log.debug "getBridge() -- Bridge not found, creating it now"
        bridge = addChildDevice("jdthomas24", "Sofabaton Integration Bridge", bridgeDni(), [label: "Sofabaton Integration Bridge"])
    }
    return bridge
}

def mainPage() {
    // Wipe any half-entered Add Hub / Add Activity fields whenever we land
    // back here, so a cancelled attempt doesn't leave stale values sitting
    // in those forms the next time they're opened.
    clearHubSettings()
    clearActivitySettings()

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
                    paragraph "<b>${hub.getLabel()}</b> (${model}, ${idShown})"
                    href name: "editHub_${hub.deviceNetworkId}", title: "Edit ${hub.getLabel()}", page: "addHubPage", params: [editDni: hub.deviceNetworkId]
                    input name: "removeHub_${hub.deviceNetworkId}", type: "button", title: "Remove ${hub.getLabel()}"
                    if (!activities) {
                        paragraph "&nbsp;&nbsp;&nbsp;&nbsp;No activities yet."
                    } else {
                        activities.each { act ->
                            paragraph "&nbsp;&nbsp;&nbsp;&nbsp;&bull; ${act.getLabel()}"
                            input name: "removeAct_${hub.deviceNetworkId}_${act.deviceNetworkId}", type: "button", title: "&nbsp;&nbsp;&nbsp;&nbsp;Remove ${act.getLabel()}"
                        }
                    }
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

def addHubPage(params = [:]) {
    log.debug "addHubPage() entered -- newHubName=${newHubName}, newHubModel=${newHubModel}, newHubIp=${newHubIp}, newHubMac=${newHubMac}, newHubMqttHost=${newHubMqttHost}, params=${params}, state.editingHubDni=${state.editingHubDni}"
    // Edit mode: entered via a href with params:[editDni: <hub dni>] from
    // the main page's Edit link. Persisted in state so it survives the
    // page's own submitOnChange postbacks (params are only present on the
    // very first navigation in, not later reloads of the same page).
    if (params?.editDni) {
        state.editingHubDni = params.editDni
        state.remove("editHubPrefilled")
    }
    String editingDni = state.editingHubDni
    def bridge = getBridge()
    def editingHub = editingDni ? bridge?.getChildDevice(editingDni) : null

    if (editingHub && !state.editHubPrefilled) {
        app.updateSetting("newHubName", [value: editingHub.getLabel(), type: "text"])
        app.updateSetting("newHubModel", [value: editingHub.currentValue("hubModel"), type: "enum"])
        if (editingHub.currentValue("hubModel") == "X2") {
            app.updateSetting("newHubMqttHost", [value: editingHub.currentValue("mqttHost") ?: "", type: "text"])
            app.updateSetting("newHubMqttPort", [value: editingHub.currentValue("mqttPort") ?: "1883", type: "text"])
            app.updateSetting("newHubMqttUser", [value: editingHub.currentValue("mqttUser") ?: "", type: "text"])
        }
        state.editHubPrefilled = true
    }

    if (!editingHub && newHubName && newHubModel == "X1S" && newHubIp) {
        log.debug "addHubPage() creating X1S hub '${newHubName}'"
        createHttpHub(newHubName, newHubIp)
        clearHubSettings()
        return mainPage()
    }
    if (!editingHub && newHubName && newHubModel == "X2" && newHubMac && newHubMqttHost) {
        log.debug "addHubPage() creating X2 hub '${newHubName}'"
        createMqttHub(newHubName, newHubMac, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
        clearHubSettings()
        return mainPage()
    }
    if (editingHub && newHubName && (editingHub.currentValue("hubModel") == "X1S" || newHubMqttHost)) {
        log.debug "addHubPage() updating existing hub '${editingDni}'"
        updateExistingHub(editingHub, newHubName, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
        clearHubSettings()
        state.remove("editingHubDni")
        state.remove("editHubPrefilled")
        return mainPage()
    }

    dynamicPage(name: "addHubPage", title: editingHub ? "Edit ${editingHub.getLabel()}" : "Add a Sofabaton Hub", install: false, uninstall: false) {
        section {
            href name: "cancelAddHub", title: "&larr; Cancel and go back", page: "mainPage"
        }
        section {
            input name: "newHubName", type: "text", title: "Hub Name (e.g. Living Room)"
            if (editingHub) {
                paragraph "Model: <b>${editingHub.currentValue('hubModel')}</b> (can't be changed here -- remove and re-add if you need a different model)"
            } else {
                input name: "newHubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], submitOnChange: true
            }
        }
        if (!editingHub && newHubModel == "X1S") {
            section {
                input name: "newHubIp", type: "text", title: "Hub IP Address (set a static DHCP reservation first)"
            }
        }
        if (editingHub && editingHub.currentValue("hubModel") == "X1S") {
            section {
                paragraph "IP Address: <b>${editingHub.currentValue('remoteIp') ?: 'not set'}</b> (can't be changed here -- remove and re-add if it changed)"
            }
        }
        if ((!editingHub && newHubModel == "X2") || (editingHub && editingHub.currentValue("hubModel") == "X2")) {
            section("MQTT Connection") {
                paragraph "<b>Before adding an X2 hub, MQTT needs to be running on this Hubitat hub.</b><br>" +
                    "Go to Integrations &rarr; Add Built-In App &rarr; MQTT Import Integration (or Export Integration), enable it, " +
                    "and turn on 'Use built-in MQTT service' if you don't already have an external broker. " +
                    "The app will show you a host, port, and login -- enter that same information below.<br>" +
                    "Then, in the Sofabaton app, go to Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Add Home Assistant Remote, " +
                    "and enter the same broker details there so the hub connects to the same broker Hubitat does.<br><br>" +
                    "<b>Sofabaton Hub MAC ID:</b> this is the X2's own MAC (12 hex characters, colons ok, they'll be stripped), " +
                    "NOT your Hubitat hub's MAC. If you don't already have it, connect a tool like MQTT Explorer to the broker, " +
                    "press an activity button on the remote, and read it out of the topic name: activity/&lt;MAC&gt;/activity_control_up"
                if (editingHub) {
                    paragraph "MAC ID: <b>${editingHub.deviceNetworkId}</b> (can't be changed here -- remove and re-add if it changed)"
                } else {
                    input name: "newHubMac", type: "text", title: "Sofabaton Hub MAC ID (not your Hubitat hub's MAC -- see note above for how to find it)"
                }
                input name: "newHubMqttHost", type: "text", title: "Hubitat Hub's LAN IP (running the broker -- not the Sofabaton hub's IP, not 127.0.0.1)"
                input name: "newHubMqttPort", type: "text", title: "Broker Port", defaultValue: "1883", required: false
                input name: "newHubMqttUser", type: "text", title: "Broker Username (leave blank if none)", required: false
                input name: "newHubMqttPass", type: "password", title: "Broker Password (leave blank if none${editingHub ? ' -- leave blank to keep the current password' : ''})", required: false
                paragraph "One shared MQTT connection is used for all X2 hubs you add -- entering broker details again for a second X2 hub reconnects to the same broker, it doesn't open a second connection."
            }
        }
        section {
            input name: "saveHubBtn", type: "button", title: editingHub ? "Save Changes" : "Add This Hub"
        }
    }
}

private void clearHubSettings() {
    ["newHubName", "newHubModel", "newHubIp", "newHubMac", "newHubMqttHost", "newHubMqttPort", "newHubMqttUser", "newHubMqttPass"].each {
        app.removeSetting(it)
    }
}

// Applies edits to an already-existing hub in place (name always, plus the
// X2 broker fields if this is an X2 hub -- model, MAC, and X1S IP are
// intentionally not editable here, see addHubPage()'s notes on why).
// Password is only overwritten if a new one was actually typed, so leaving
// it blank on an edit keeps whatever was already saved.
private void updateExistingHub(def hub, String name, String mqttHost, String mqttPort, String mqttUser, String mqttPass) {
    hub.setLabel(name)
    if (hub.currentValue("hubModel") == "X2") {
        hub.updateSetting("mqttHost", [value: mqttHost, type: "text"])
        hub.updateSetting("mqttPort", [value: mqttPort, type: "text"])
        hub.updateSetting("mqttUser", [value: mqttUser ?: "", type: "text"])
        if (mqttPass) hub.updateSetting("mqttPass", [value: mqttPass, type: "password"])
        hub.updated()
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
    hub.updateSetting("hubModel", [value: "X1S", type: "enum"])
    hub.updated()
}

private void createMqttHub(String name, String mac, String host, String port, String user, String pass) {
    def bridge = getBridge()
    if (!bridge) {
        log.error "Cannot add hub '$name': Bridge device not found"
        return
    }
    String dni = (mac ?: "").replaceAll(/[^A-Fa-f0-9]/, "").toUpperCase()
    if (dni.length() != 12) {
        log.error "Cannot add hub '$name': '$mac' does not look like a valid 12-character MAC address"
        return
    }
    log.debug "createMqttHub() creating child device with DNI '${dni}' under bridge"
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        log.error "Failed to create Remote device for hub '$name' (createRemoteDevice returned null -- check Bridge driver logs)"
        return
    }
    log.debug "createMqttHub() child device created, setting hubModel/mac/mqtt* and calling updated()"
    hub.updateSetting("hubModel", [value: "X2", type: "enum"])
    hub.updateSetting("mac", [value: dni, type: "text"])
    hub.updateSetting("mqttHost", [value: host, type: "text"])
    hub.updateSetting("mqttPort", [value: port, type: "text"])
    if (user) hub.updateSetting("mqttUser", [value: user, type: "text"])
    if (pass) hub.updateSetting("mqttPass", [value: pass, type: "password"])
    hub.updated()
    log.debug "createMqttHub() done"
}

def addActivityPage() {
    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []
    def selectedHub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    boolean isX2 = selectedHub?.currentValue("hubModel") == "X2"
    boolean listening = isX2 && state.learnStartedFor == newActivityHub

    // If we're listening, check whether the Bridge has captured a result yet.
    // Learn-mode methods (startActivityLearn, getActivityLearnResult, etc.)
    // live on the BRIDGE device, not on the individual hub -- call bridge
    // here, not selectedHub.
    if (listening) {
        def result = bridge.getActivityLearnResult()
        if (result != null) {
            app.updateSetting("newActivitySofabatonId", [value: (result as Integer).toString(), type: "number"])
            bridge.clearActivityLearnResult()
            state.remove("learnStartedFor")
            listening = false
        }
    }

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

    dynamicPage(name: "addActivityPage", title: "Add an Activity", install: false, uninstall: false, refreshInterval: listening ? 3 : 0) {
        section {
            href name: "cancelAddActivity", title: "&larr; Cancel and go back", page: "mainPage"
        }
        section {
            input name: "newActivityHub", type: "enum", title: "Which Hub?", options: hubs.collectEntries { [(it.deviceNetworkId): it.getLabel()] }, submitOnChange: true
            input name: "newActivityName", type: "text", title: "Activity Name (e.g. Watch TV)" + (isX2 ? "" : " -- must match the remote's configured user-definable button label exactly, this is how state sync matches it up")
        }
        if (selectedHub && !isX2) {
            section {
                input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL"
                input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, unconfirmed feature -- leave blank if unsure)", required: false
            }
        }
        if (selectedHub && isX2) {
            section {
                paragraph "X2 hubs use MQTT, not a webhook. Press Listen below, then press the activity's button on the physical remote to auto-fill the ID below -- or enter it manually if you already know it (e.g. from MQTT Explorer)."
                if (listening) {
                    paragraph "<b>Listening...</b> press the activity's button on the physical remote now. This page refreshes automatically every few seconds."
                    input name: "cancelLearnBtn", type: "button", title: "Cancel"
                } else {
                    input name: "learnBtn", type: "button", title: "Listen for Next Activity"
                }
                input name: "newActivitySofabatonId", type: "number", title: "Sofabaton Activity ID", submitOnChange: true
            }
        }
        section {
            input name: "saveActivityBtn", type: "button", title: "Add This Activity"
        }
    }
}

def appButtonHandler(String btn) {
    def bridge = getBridge()

    if (btn == "saveHubBtn") {
        log.debug "addHubPage() Save button pressed -- page will re-run and process current field values"
        return
    }
    if (btn.startsWith("removeHub_")) {
        String dni = btn - "removeHub_"
        bridge?.removeRemoteDevice(dni)
        return
    }
    if (btn.startsWith("removeAct_")) {
        String rest = btn - "removeAct_"
        def parts = rest.split("_", 2)
        if (parts.length == 2) {
            def hub = bridge?.getChildDevice(parts[0])
            hub?.removeActivityDeviceByDni(parts[1])
        }
        return
    }

    def hub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    if (!hub) return
    if (btn == "saveActivityBtn") {
        log.debug "addActivityPage() Save button pressed -- page will re-run and process current field values"
        return
    }
    if (btn == "learnBtn") {
        bridge.startActivityLearn(hub.deviceNetworkId)
        state.learnStartedFor = newActivityHub
    }
    if (btn == "cancelLearnBtn") {
        bridge.cancelActivityLearn()
        state.remove("learnStartedFor")
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
