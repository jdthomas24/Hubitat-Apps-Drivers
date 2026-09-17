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
        -FIXED A FOURTH REAL BUG: Edit used an href with params to reach
         addHubPage(), but the main page also has a plain "Add a Hub" href
         to that same page name with no params -- Hubitat has a confirmed
         platform bug where multiple hrefs targeting the same page name
         with different params can mix up or drop those params. Replaced
         Edit (and Cancel, for consistency and to let it properly exit
         edit mode) with buttons that set/clear state directly instead,
         the same button+state pattern already proven reliable tonight
         for Remove and Listen mode.
        -FIXED A FIFTH REAL BUG: Edit's prefill step and its save-check ran
         in the same page pass -- the prefill echoed the hub's own current
         values into the form fields, and the very next lines immediately
         re-read those same fields as if the user had just submitted them,
         silently saving (a no-op, since it was the hub's own data) and
         returning to the main page before the form was ever shown. Added
         a justPrefilled guard so the save-check only fires on an actual
         later postback (the Save button), not the same pass as prefill.
        -Wrapped each hub's summary line in a bordered/pill-styled box
         (model shown as a small badge) instead of plain text, so multiple
         hubs read as distinct cards rather than a flat list.
        -Styled the model badge blue, capped the card's width instead of
         stretching full-page, and put Edit/Remove side by side (width: 6
         each) instead of stacked, using Hubitat's input width grid.
        -FIXED A SIXTH REAL BUG (same family as the Hub Edit one, caught
         proactively before it surfaced): if the Activity Name was typed
         before pressing Listen, the auto-refresh that captures the ID
         would read it as a real submission on that same pass and silently
         create the Activity before the user ever saw the captured ID.
         Added the same justCaptured-style guard so a captured ID only
         fills the field; creation still requires an explicit later click
         of "Add This Activity".
        -Main page now shows each X2 activity's Sofabaton Activity ID
         inline next to its name (e.g. "Watch TV (ID: 101)"), so it's
         visible without opening the device page.
        -FIXED A SEVENTH REAL BUG, confirmed by live logs this time: the
         page's refreshInterval (meant to auto-poll while Listening) was
         bouncing straight back to mainPage() every cycle instead of
         reloading addActivityPage() -- matches a known class of Hubitat
         auto-refresh bug (confirmed via community reports of the same
         "bounces back unexpectedly" behavior). Removed refreshInterval
         entirely rather than fight an unreliable platform mechanism;
         Listen mode now relies on state persisting across navigation
         instead -- press Listen, press the remote whenever, then simply
         reopen Add an Activity and the captured ID will already be there.
        -SUPERSEDED the justPrefilled/justCapturedLearn patches above with
         a more fundamental fix, after a live test showed the underlying
         bug family wasn't actually closed: any field becoming non-null on
         its own (Edit's prefill, Listen's capture) could still trigger a
         save on a LATER, unrelated page reload, not just the exact same
         pass -- because the save logic only ever checked "are the fields
         full", never "did the user actually just click Save". Both
         addHubPage() and addActivityPage() now gate all creation/update
         behind an explicit state.hubSaveRequested / state.
         activitySaveRequested flag, set only by the Save button's own
         click handler and consumed (cleared) the instant it's checked.
         Field values being present is no longer sufficient on its own,
         under any circumstance, to trigger a save. A saveError message
         now also shows on the page if Save is clicked with required
         fields still missing, instead of silently doing nothing.
        -FIXED: added a missing uninstalled() method. Without it, removing
         the app left the Bridge device (and everything under it) orphaned
         on the hub with nothing to clean it up, guaranteeing a DNI
         conflict on the next install. Now explicitly deletes the Bridge
         child, which triggers its own uninstalled() to tear down MQTT and
         its children in turn.

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

// Without this, removing the app leaves the Bridge (and everything under
// it) orphaned on the hub -- nothing would ever clean it up, and the next
// install would immediately hit the same DNI-conflict mess this whole
// project fought through tonight. Explicitly delete the Bridge child here;
// deleting it triggers the Bridge driver's own uninstalled(), which is
// responsible for tearing down MQTT and its own children in turn.
def uninstalled() {
    def bridge = getBridge()
    if (bridge) {
        try {
            deleteChildDevice(bridge.deviceNetworkId)
        } catch (e) {
            log.error "Sofabaton Integration: failed to remove Bridge device on uninstall: ${e.message}"
        }
    }
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
    log.debug "mainPage() entered -- state.editingHubDni=${state.editingHubDni}"
    // Edit mode is entered by a button (editHub_<dni>) rather than an
    // href with params -- Hubitat has a known bug where multiple hrefs on
    // one page targeting the SAME page name with different params can mix
    // up or drop those params. A button + state sidesteps it entirely.
    if (state.editingHubDni) {
        return addHubPage()
    }

    // Wipe any half-entered Add Hub / Add Activity fields whenever we land
    // back here normally, so a cancelled attempt doesn't leave stale
    // values sitting in those forms the next time they're opened.
    clearHubSettings()
    clearActivitySettings()
    state.remove("learnStartedFor")
    state.remove("activitySaveRequested")

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
                    paragraph "<div style='display:inline-block;max-width:420px;border:1px solid #ccc;border-radius:10px;padding:10px 14px;margin-bottom:8px;background:#fafafa'>" +
                        "<b>${hub.getLabel()}</b> <span style='background:#1976d2;color:#fff;border-radius:8px;padding:1px 8px;font-size:0.85em'>${model}</span> " +
                        "<span style='color:#888;font-size:0.9em'>${idShown}</span></div>"
                    input name: "editHub_${hub.deviceNetworkId}", type: "button", title: "Edit ${hub.getLabel()}", width: 6
                    input name: "removeHub_${hub.deviceNetworkId}", type: "button", title: "Remove ${hub.getLabel()}", width: 6
                    if (!activities) {
                        paragraph "&nbsp;&nbsp;&nbsp;&nbsp;No activities yet."
                    } else {
                        activities.each { act ->
                            String idInfo = model == "X2" ? " <span style='color:#888'>(ID: ${act.currentValue('sofabatonActivityId') ?: '?'})</span>" : ""
                            paragraph "&nbsp;&nbsp;&nbsp;&nbsp;&bull; ${act.getLabel()}${idInfo}"
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
    log.debug "addHubPage() entered -- newHubName=${newHubName}, newHubModel=${newHubModel}, newHubIp=${newHubIp}, newHubMac=${newHubMac}, newHubMqttHost=${newHubMqttHost}, params=${params}, state.editingHubDni=${state.editingHubDni}, state.hubSaveRequested=${state.hubSaveRequested}"
    if (state.hubPageCancelled) {
        state.remove("hubPageCancelled")
        state.remove("editingHubDni")
        state.remove("editHubPrefilled")
        state.remove("hubSaveRequested")
        clearHubSettings()
        return mainPage()
    }
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

    // ONLY an explicit click of "Add This Hub" / "Save Changes" -- which
    // sets state.hubSaveRequested in appButtonHandler -- is ever treated
    // as a real submission. Fields being non-null is not enough on its
    // own: they can become non-null from Edit's prefill, or simply because
    // the user filled them in earlier and the page reloaded for an
    // unrelated reason (an auto-refresh, a stray postback). Gating on an
    // explicit flag instead of "are the fields full" closes off that
    // whole bug family at once rather than patching each trigger path
    // individually.
    String saveError = null
    if (state.hubSaveRequested) {
        state.remove("hubSaveRequested")
        if (!editingHub && newHubName && newHubModel == "X1S" && newHubIp) {
            log.debug "addHubPage() creating X1S hub '${newHubName}'"
            createHttpHub(newHubName, newHubIp)
            clearHubSettings()
            return mainPage()
        } else if (!editingHub && newHubName && newHubModel == "X2" && newHubMac && newHubMqttHost) {
            log.debug "addHubPage() creating X2 hub '${newHubName}'"
            createMqttHub(newHubName, newHubMac, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            return mainPage()
        } else if (editingHub && newHubName && (editingHub.currentValue("hubModel") == "X1S" || newHubMqttHost)) {
            log.debug "addHubPage() updating existing hub '${editingDni}'"
            updateExistingHub(editingHub, newHubName, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            state.remove("editingHubDni")
            state.remove("editHubPrefilled")
            return mainPage()
        } else {
            saveError = "Please fill in all required fields before saving."
        }
    }

    dynamicPage(name: "addHubPage", title: editingHub ? "Edit ${editingHub.getLabel()}" : "Add a Sofabaton Hub", install: false, uninstall: false) {
        section {
            input name: "cancelHubBtn", type: "button", title: "&larr; Cancel and go back"
        }
        if (saveError) {
            section {
                paragraph "<b style='color:#c00'>${saveError}</b>"
            }
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
    log.debug "addActivityPage() entered -- newActivityHub=${newActivityHub}, newActivityName=${newActivityName}, newActivitySofabatonId=${newActivitySofabatonId}, state.learnStartedFor=${state.learnStartedFor}, state.activitySaveRequested=${state.activitySaveRequested}"
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

    // ONLY an explicit click of "Add This Activity" -- which sets
    // state.activitySaveRequested in appButtonHandler -- is ever treated
    // as a real submission. A captured Listen result filling in the ID
    // field is not enough on its own to create the Activity; the user
    // still has to review and click Add. This closes off the same bug
    // family as addHubPage()'s explicit-save gate, for the same reason:
    // fields becoming non-null by themselves (here, via Listen capture)
    // must never be treated as equivalent to a real user submission.
    String saveError = null
    if (state.activitySaveRequested) {
        state.remove("activitySaveRequested")
        if (!isX2 && newActivityHub && newActivityName && newActivityUrlOn) {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, null)
            clearActivitySettings()
            return mainPage()
        } else if (isX2 && newActivityHub && newActivityName && newActivitySofabatonId != null) {
            createActivity(newActivityHub, newActivityName, null, null, newActivitySofabatonId as Integer)
            clearActivitySettings()
            return mainPage()
        } else {
            saveError = "Please fill in all required fields before saving."
        }
    }

    dynamicPage(name: "addActivityPage", title: "Add an Activity", install: false, uninstall: false) {
        section {
            href name: "cancelAddActivity", title: "&larr; Cancel and go back", page: "mainPage"
        }
        if (saveError) {
            section {
                paragraph "<b style='color:#c00'>${saveError}</b>"
            }
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
                paragraph "X2 hubs use MQTT, not a webhook. Press Listen below, press the activity's button on the physical remote, then come back to this page (see note below) to see the ID auto-filled -- or enter it manually if you already know it (e.g. from MQTT Explorer)."
                if (listening) {
                    paragraph "<b>Listening...</b> press the activity's button on the physical remote now. This page does NOT auto-refresh (Hubitat's own auto-refresh was unreliable here) -- once you've pressed the button, leave this page and come back (tap Add an Activity again, or Cancel and reopen it) and the captured ID will already be filled in below."
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
    log.debug "appButtonHandler() received btn=${btn}"
    def bridge = getBridge()

    if (btn == "saveHubBtn") {
        state.hubSaveRequested = true
        return
    }
    if (btn == "cancelHubBtn") {
        state.remove("editingHubDni")
        state.remove("editHubPrefilled")
        state.hubPageCancelled = true
        return
    }
    if (btn.startsWith("editHub_")) {
        String dni = btn - "editHub_"
        state.editingHubDni = dni
        state.remove("editHubPrefilled")
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
        state.activitySaveRequested = true
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
