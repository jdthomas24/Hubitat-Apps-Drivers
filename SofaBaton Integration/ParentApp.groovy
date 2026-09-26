/*
    Sofabaton Integration - Parent App
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: the X1/X1S local receive path (Sofabaton Remote driver) is a
    fork of Derek Osborn's (dJOS1475) Hubitat community driver, built on
    push-command building blocks originated by Mike Maxwell (mike.maxwell).

    Notes:
     -Setup only. Runtime (HTTP listener, MQTT, webhooks, state sync) lives in
      the Bridge, Remote, and Activity drivers.
     -Device tree: Bridge -> one Remote per hub -> one Activity per activity.
      X1S keyed by IP (ipToHex DNI), X2 keyed by bare uppercase MAC.
     -X2 activities require a Sofabaton Activity ID. Webhook URLs are optional
      and take priority over MQTT when set.
     -Add/Edit pages save ONLY on an explicit Save click (state.*SaveRequested),
      never because fields happen to be filled. Edit/Remove use buttons + state,
      not hrefs with params (Hubitat drops params across same-page hrefs).
     -Explicit Save buttons exist because Cancel jumped to mainPage without running
      creation, silently discarding input.
     -No required:true on Add page inputs. Browser validation blocked the Cancel
      button, since it's in the same form.
     -No refreshInterval on Add Activity (bounced to mainPage). Listen result
      persists in the Bridge until the page is reopened.
     -uninstalled() must delete the Bridge, or everything under it is orphaned.
     -Model, MAC, and X1S IP set the DNI, so they're not editable. Remove and re-add.
     -UI follows HubitatAppUiTemplate (Reolink v1.6.1, UI design by gopher.ny).
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Sofabaton Integration"
@Field static final String APP_VERSION = "1.0.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com"   // TODO: release thread
@Field static final String COFFEE_URL = "https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String DEFAULT_TIP_TOPIC = "start"
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

@Field static final String HUBITAT_PILL = "<span style='background:#1976d2;color:#fff;border-radius:8px;padding:1px 9px;font-size:0.75em;font-weight:bold'>HUBITAT</span>"
@Field static final String SOFABATON_PILL = "<span style='background:#7c4dff;color:#fff;border-radius:8px;padding:1px 9px;font-size:0.75em;font-weight:bold'>SOFABATON APP</span>"

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
    page(name: "tipsPage")
}

def installed() { initialize() }

def updated() { initialize() }

// Deleting the Bridge triggers its own uninstalled(), which tears down MQTT and children.
def uninstalled() {
    def bridge = getChildDevice(bridgeDni())
    if (bridge) {
        try {
            deleteChildDevice(bridge.deviceNetworkId)
        } catch (e) {
            logErr "failed to remove Bridge device on uninstall: ${e.message}"
        }
    }
}

def initialize() {
    getBridge()
}

private String bridgeDni() {
    return "sofabaton-bridge-${app.id}"
}

// Created lazily: a first-run user can open Add a Hub before ever tapping Done.
def getBridge() {
    def bridge = getChildDevice(bridgeDni())
    if (!bridge) {
        logDbg "Bridge not found, creating it"
        bridge = addChildDevice("jdthomas24", "Sofabaton Integration Bridge", bridgeDni(), [label: "Sofabaton Integration Bridge"])
    }
    return bridge
}

// ============================================================================
// Logging
// ============================================================================

private String currentLogLevel() {
    return LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only"
}

private void logErr(String msg) { log.error "${APP_NAME}: ${msg}" }
private void logWarn(String msg) { log.warn "${APP_NAME}: ${msg}" }
private void logInfo(String msg) { if (currentLogLevel() != "Errors Only") log.info "${APP_NAME}: ${msg}" }
private void logDbg(String msg) { if (currentLogLevel() == "Full") log.debug "${APP_NAME}: ${msg}" }

// Full logging reverts to Errors Only after 30 minutes.
private void armLogRevert() {
    if (currentLogLevel() == "Full") {
        if (!state.fullLogArmed) {
            runIn(1800, "revertLogLevel")
            state.fullLogArmed = true
        }
    } else {
        state.remove("fullLogArmed")
    }
}

void revertLogLevel() {
    state.remove("fullLogArmed")
    if (currentLogLevel() == "Full") {
        app.updateSetting("logLevel", [value: "Errors Only", type: "enum"])
        log.warn "${APP_NAME}: Full logging turned off after 30 minutes"
    }
}

// ============================================================================
// Main page
// ============================================================================

def mainPage() {
    if (state.editingHubDni) return addHubPage()
    if (state.editingActivityKey) return addActivityPage()

    // Clear half-entered Add Hub / Add Activity fields from a cancelled attempt.
    clearHubSettings()
    clearActivitySettings()
    state.remove("learnStartedFor")
    state.remove("activitySaveRequested")
    armLogRevert()

    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []
    def x2Hubs = hubs.findAll { it.currentValue("hubModel") == "X2" }
    int activityCount = (hubs.collect { (it.getChildDevices() ?: []).size() }.sum() ?: 0) as int
    Map banner = bannerState(bridge, hubs, x2Hubs, activityCount)

    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-main-content") {
            paragraph rawHtml: true, columnHeader("Hubs")
            if (!hubs) {
                paragraph "<span class='text-color-secondary'>No hubs added yet.</span>"
            }
            hubs.each { hub ->
                def activities = hub.getChildDevices() ?: []
                paragraph rawHtml: true, hubCardHtml(hub, activities)
                // Pairs of width 3 fill a 12-wide row every two pairs.
                input name: "editHub_${hub.deviceNetworkId}", type: "button", title: "Edit Hub", width: 3
                input name: "removeHub_${hub.deviceNetworkId}", type: "button", title: "Remove Hub", width: 3
                activities.each { act ->
                    input name: "editAct_${hub.deviceNetworkId}_${act.deviceNetworkId}", type: "button", title: "Edit Activity", width: 3
                    input name: "removeAct_${hub.deviceNetworkId}_${act.deviceNetworkId}", type: "button", title: "Remove Activity", width: 3
                }
                paragraph "<div style='margin-bottom:6px'></div>"
            }
            href name: "toAddHub", title: "Add a Hub", description: "X1S or X2", page: "addHubPage", width: 12, style: "margin:8px;"
            if (hubs) {
                href name: "toAddActivity", title: "Add an Activity", description: "Attach an activity to one of your hubs", page: "addActivityPage", width: 12, style: "margin:8px;"
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Tap <b>Done</b> when finished. That's what finalizes the install.</span>"
            }
        }
        section(sectionClass: "app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            if (x2Hubs) {
                paragraph rawHtml: true, mqttStatusHtml(bridge, x2Hubs)
            }
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
        }
        helpAndSupportSection()
        versionFooterSection()
    }
}

// Green only when everything is confirmed. X2 needs a real MQTT message, not just a connection.
private Map bannerState(bridge, List hubs, List x2Hubs, int activityCount) {
    String counts = "${countText(hubs.size(), 'hub')} &middot; ${countText(activityCount, 'activity', 'activities')}"
    if (!hubs) {
        return [ok: false, title: "Setup required", summary: "Add a hub to get started. Tips &amp; Troubleshooting has the full walkthrough."]
    }
    String mqtt = bridge?.currentValue("mqttStatus")
    if (x2Hubs && mqtt in ["error", "connect failed"]) {
        return [ok: false, title: "MQTT connection problem", summary: "${counts} &middot; Check the X2 broker details, then run Force Reconnect on the Bridge device."]
    }
    def waiting = x2Hubs.findAll { !it.currentValue("lastMqttMessage") }
    if (waiting) {
        String names = waiting.collect { it.getLabel() }.join(", ")
        return [ok: false, title: "Waiting for first MQTT message", summary: "${counts} &middot; Nothing received yet from ${names}. See Known issue in Tips."]
    }
    return [ok: true, title: "Integration ready", summary: counts]
}

private String hubCardHtml(hub, List activities) {
    String model = hub.currentValue("hubModel") ?: "unknown"
    boolean x2 = model == "X2"
    String idShown = x2 ? (hub.currentValue("remoteMac") ?: "no MAC set") : (hub.currentValue("remoteIp") ?: "no IP set")
    StringBuilder card = new StringBuilder()
    card << "<div style='border:1px solid #ccc;border-radius:10px;padding:12px 14px;margin:4px 0;background:#fafafa'>"
    String badge = x2 ? "#5f8b6f" : "#e8a33d"   // matches the Remote driver's pills
    card << "<div><span style='background:${badge};color:#fff;border-radius:8px;padding:2px 9px;font-size:0.9em;font-weight:bold'>${model}</span> "
    card << "<span style='font-size:0.85em'>${hub.getLabel()}</span> "
    card << "<span style='color:#888;font-size:0.8em'>${idShown}</span></div>"
    if (x2) {
        String seen = hub.currentValue("lastMqttMessage")
        card << "<div style='margin-top:6px;font-size:0.8em;color:${seen ? '#5f8b6f' : '#b26a00'}'>"
        card << (seen ? "Last MQTT message: ${seen}" : "Waiting for first MQTT message") << "</div>"
    }
    if (!activities) {
        card << "<div style='margin-top:10px;color:#aaa;font-size:0.85em'>No activities yet.</div>"
    } else {
        card << "<div style='margin-top:10px;display:flex;flex-wrap:wrap;gap:8px'>"
        activities.each { act ->
            String idBadge = x2 ? " <span style='background:#455a64;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.85em;font-weight:bold'>ID ${act.currentValue('sofabatonActivityId') ?: '?'}</span>" : ""
            // X1S is always webhook. X2 shows whichever path is configured. Amber = cloud, teal = local.
            boolean webhook = !x2 || (act.getSetting("webhookUrlOn") as boolean)
            String tag = webhook ?
                " <span style='background:#e8a33d;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Webhook</span>" :
                " <span style='background:#26897a;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>MQTT</span>"
            card << "<span style='display:inline-block;background:#e3eaf3;border-radius:20px;padding:6px 14px;font-size:0.9em'>${act.getLabel()}${idBadge}${tag}</span>"
        }
        card << "</div>"
    }
    card << "</div>"
    return card.toString()
}

private String mqttStatusHtml(bridge, List x2Hubs) {
    String status = bridge?.currentValue("mqttStatus") ?: "not connected"
    String tone = status == "connected" ? "bg-green-50 text-green-700" :
        status in ["error", "connect failed"] ? "bg-red-50 text-red-700" : "bg-gray-100 text-gray-700"
    Boolean broker = null
    try { broker = bridge?.checkBuiltInBroker() } catch (e) { }
    // Fallback: older Bridge versions set the attribute but return nothing.
    String attr = bridge?.currentValue("brokerRunning")
    if (broker == null && attr in ["true", "false"]) broker = (attr == "true")
    String brokerText = broker == null ? (attr == "error" ? "Check failed, see logs" : "Unknown") :
        (broker ? "Running" : "Not running (fine if you use an external broker)")
    String brokerRow = "<div class='flex justify-content-between gap-3 py-1'><span>Built-in broker</span>" +
        "<span class='text-color-secondary' style='font-size:14px;'>${brokerText}</span></div>"
    String rows = brokerRow + x2Hubs.collect { h ->
        "<div class='flex justify-content-between gap-3 py-1'><span>${h.getLabel()}</span>" +
        "<span class='text-color-secondary' style='font-size:14px;'>${h.currentValue('lastMqttMessage') ?: 'No message yet'}</span></div>"
    }.join("")
    """
<div class='border-1 border-gray-200 border-round px-3 py-2 mb-2'>
  <div class='flex align-items-center justify-content-between gap-3 mb-1'>
    <span class='font-semibold'>MQTT</span>
    <span class='${tone} border-round-xl px-2 py-1 text-xs font-bold'>${status}</span>
  </div>
  ${rows}
</div>
"""
}

// ============================================================================
// Add / Edit Hub
// ============================================================================

def addHubPage(params = [:]) {
    logDbg "addHubPage() newHubName=${newHubName}, newHubModel=${newHubModel}, editing=${state.editingHubDni}, saveRequested=${state.hubSaveRequested}"
    if (state.hubPageCancelled) {
        ["hubPageCancelled", "editingHubDni", "editHubPrefilled", "hubSaveRequested"].each { state.remove(it) }
        clearHubSettings()
        return mainPage()
    }
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

    String saveError = null
    if (state.hubSaveRequested) {
        state.remove("hubSaveRequested")
        if (!editingHub && newHubName && newHubModel == "X1S" && newHubIp) {
            logInfo "adding X1S hub '${newHubName}'"
            createHttpHub(newHubName, newHubIp)
            clearHubSettings()
            return mainPage()
        } else if (!editingHub && newHubName && newHubModel == "X2" && newHubMac && newHubMqttHost) {
            logInfo "adding X2 hub '${newHubName}'"
            createMqttHub(newHubName, newHubMac, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            return mainPage()
        } else if (editingHub && newHubName && (editingHub.currentValue("hubModel") == "X1S" || newHubMqttHost)) {
            logInfo "updating hub '${editingDni}'"
            updateExistingHub(editingHub, newHubName, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            state.remove("editingHubDni")
            state.remove("editHubPrefilled")
            return mainPage()
        } else {
            saveError = "Please fill in all required fields before saving."
        }
    }

    String editModel = editingHub?.currentValue("hubModel")
    boolean showX2 = (!editingHub && newHubModel == "X2") || editModel == "X2"

    dynamicPage(name: "addHubPage", title: editingHub ? "Edit ${editingHub.getLabel()}" : "Add a Sofabaton Hub", install: false, uninstall: false) {
        section {
            input name: "cancelHubBtn", type: "button", title: "&larr; Cancel and go back"
        }
        if (saveError) {
            section { paragraph "<b style='color:#c00'>${saveError}</b>" }
        }
        section {
            input name: "newHubName", type: "text", title: "Hub Name (e.g. Living Room)"
            if (editingHub) {
                paragraph "Model: <b>${editModel}</b> <span class='text-color-secondary'>(remove and re-add to change)</span>"
            } else {
                input name: "newHubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], submitOnChange: true
            }
        }
        if (!editingHub && newHubModel == "X1S") {
            section {
                input name: "newHubIp", type: "text", title: "Hub IP Address (set a DHCP reservation first)"
            }
        }
        if (editModel == "X1S") {
            section {
                paragraph "IP Address: <b>${editingHub.currentValue('remoteIp') ?: 'not set'}</b> <span class='text-color-secondary'>(remove and re-add to change)</span>"
            }
        }
        if (showX2) {
            section("MQTT Connection") {
                paragraph rawHtml: true, warningMessageHtml("<b>Set up the broker first.</b> Enable Hubitat's MQTT broker, then in the Sofabaton app go to " +
                    "<b>Me &rarr; Connect to Home Assistant (MQTT broker)</b> and enter the same details. " +
                    "Full steps are in Tips &amp; Troubleshooting under X2 setup.")
                if (editingHub) {
                    paragraph "MAC ID: <b>${editingHub.deviceNetworkId}</b> <span class='text-color-secondary'>(remove and re-add to change)</span>"
                } else {
                    input name: "newHubMac", type: "text", title: "Sofabaton Hub MAC ID (the X2's MAC, not your Hubitat hub's)"
                }
                input name: "newHubMqttHost", type: "text", title: "Broker Host (this hub's LAN IP, filled in for you)", defaultValue: location.hub.localIP
                input name: "newHubMqttPort", type: "text", title: "Broker Port", defaultValue: "1883", required: false
                input name: "newHubMqttUser", type: "text", title: "Broker Username (blank if none)", required: false
                input name: "newHubMqttPass", type: "password", title: "Broker Password (blank if none${editingHub ? ', or to keep the current one' : ''})", required: false
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>All X2 hubs share one MQTT connection.</span>"
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

// Password only overwritten when a new one is typed.
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
        logErr "cannot add hub '$name': '$ip' is not a valid IPv4 address"
        return
    }
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        logErr "failed to create Remote device for hub '$name'"
        return
    }
    hub.updateSetting("ip", [value: ip, type: "text"])
    hub.updateSetting("hubModel", [value: "X1S", type: "enum"])
    hub.updated()
}

private void createMqttHub(String name, String mac, String host, String port, String user, String pass) {
    def bridge = getBridge()
    if (!bridge) {
        logErr "cannot add hub '$name': Bridge device not found"
        return
    }
    String dni = (mac ?: "").replaceAll(/[^A-Fa-f0-9]/, "").toUpperCase()
    if (dni.length() != 12) {
        logErr "cannot add hub '$name': '$mac' is not a valid 12-character MAC"
        return
    }
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        logErr "failed to create Remote device for hub '$name' (check Bridge logs)"
        return
    }
    hub.updateSetting("hubModel", [value: "X2", type: "enum"])
    hub.updateSetting("mac", [value: dni, type: "text"])
    hub.updateSetting("mqttHost", [value: host, type: "text"])
    hub.updateSetting("mqttPort", [value: port, type: "text"])
    if (user) hub.updateSetting("mqttUser", [value: user, type: "text"])
    if (pass) hub.updateSetting("mqttPass", [value: pass, type: "password"])
    hub.updated()
    logDbg "createMqttHub() done, DNI $dni"
}

// ============================================================================
// Add / Edit Activity
// ============================================================================

def addActivityPage() {
    logDbg "addActivityPage() hub=${newActivityHub}, name=${newActivityName}, id=${newActivitySofabatonId}, learn=${state.learnStartedFor}, saveRequested=${state.activitySaveRequested}, editing=${state.editingActivityKey}"
    if (state.activityPageCancelled) {
        ["activityPageCancelled", "editingActivityKey", "editActivityPrefilled", "learnStartedFor"].each { state.remove(it) }
        clearActivitySettings()
        return mainPage()
    }
    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []

    // state.editingActivityKey = "<hubDni>|<actDni>", set by the Edit Activity button.
    String editingHubDni = null
    def editingActivity = null
    if (state.editingActivityKey) {
        def keyParts = state.editingActivityKey.split(/\|/, 2)
        if (keyParts.length == 2) {
            editingHubDni = keyParts[0]
            editingActivity = bridge?.getChildDevice(editingHubDni)?.getChildDevice(keyParts[1])
        }
    }

    if (editingActivity && !state.editActivityPrefilled) {
        app.updateSetting("newActivityHub", [value: editingHubDni, type: "enum"])
        app.updateSetting("newActivityName", [value: editingActivity.getLabel(), type: "text"])
        if (editingActivity.currentValue("sofabatonActivityId") != null) {
            app.updateSetting("newActivitySofabatonId", [value: editingActivity.currentValue("sofabatonActivityId").toString(), type: "number"])
        }
        // Webhook URLs are device settings, not attributes. Worst case they prefill blank.
        app.updateSetting("newActivityUrlOn", [value: editingActivity.getSetting("webhookUrlOn") ?: "", type: "text"])
        app.updateSetting("newActivityUrlOff", [value: editingActivity.getSetting("webhookUrlOff") ?: "", type: "text"])
        state.editActivityPrefilled = true
    }

    def selectedHub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    boolean isX2 = selectedHub?.currentValue("hubModel") == "X2"
    boolean listening = isX2 && state.learnStartedFor == newActivityHub

    // Learn-mode methods live on the Bridge, not the hub.
    if (listening) {
        def result = bridge.getActivityLearnResult()
        if (result != null) {
            app.updateSetting("newActivitySofabatonId", [value: (result as Integer).toString(), type: "number"])
            bridge.clearActivityLearnResult()
            state.remove("learnStartedFor")
            listening = false
        }
    }

    String saveError = null
    if (state.activitySaveRequested) {
        state.remove("activitySaveRequested")
        if (editingActivity) {
            if (newActivityName && (isX2 ? newActivitySofabatonId != null : newActivityUrlOn)) {
                updateExistingActivity(editingActivity, newActivityName, newActivityUrlOn, newActivityUrlOff, isX2 ? (newActivitySofabatonId as Integer) : null)
                clearActivitySettings()
                state.remove("editingActivityKey")
                state.remove("editActivityPrefilled")
                return mainPage()
            }
            saveError = "Please fill in all required fields before saving."
        } else if (!isX2 && newActivityHub && newActivityName && newActivityUrlOn) {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, null)
            clearActivitySettings()
            return mainPage()
        } else if (isX2 && newActivityHub && newActivityName && newActivitySofabatonId != null) {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, newActivitySofabatonId as Integer)
            clearActivitySettings()
            return mainPage()
        } else {
            saveError = "Please fill in all required fields before saving."
        }
    }

    dynamicPage(name: "addActivityPage", title: editingActivity ? "Edit ${editingActivity.getLabel()}" : "Add an Activity", install: false, uninstall: false) {
        section {
            input name: "cancelActivityBtn", type: "button", title: "&larr; Cancel and go back"
        }
        if (saveError) {
            section { paragraph "<b style='color:#c00'>${saveError}</b>" }
        }
        section {
            if (editingActivity) {
                paragraph "Hub: <b>${selectedHub?.getLabel() ?: editingHubDni}</b> <span class='text-color-secondary'>(remove and re-add to move it)</span>"
            } else {
                input name: "newActivityHub", type: "enum", title: "Which Hub?", options: hubs.collectEntries { [(it.deviceNetworkId): it.getLabel()] }, submitOnChange: true
            }
            input name: "newActivityName", type: "text", title: "Activity Name (e.g. Watch TV)" + (isX2 || !selectedHub ? "" : ". Must exactly match the button's Description, see Tips.")
        }
        if (selectedHub && !isX2) {
            section {
                input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL"
                input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, unconfirmed feature)", required: false
            }
        }
        if (selectedHub && isX2) {
            section("Sofabaton Activity ID") {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Required. Keeps this device in sync when the activity changes from the remote. " +
                    "Press Listen, press the activity's button on the remote, then leave this page and reopen it. The ID will be filled in.</span>"
                paragraph rawHtml: true, warningMessageHtml("Listen needs incoming MQTT, which isn't working yet (see Known issue in Tips). Enter the ID manually for now.")
                if (listening) {
                    paragraph "<b>Listening...</b> press the activity's button on the remote, then leave and reopen this page."
                    input name: "cancelLearnBtn", type: "button", title: "Cancel"
                } else {
                    input name: "learnBtn", type: "button", title: "Listen for Next Activity"
                }
                input name: "newActivitySofabatonId", type: "number", title: "Sofabaton Activity ID", submitOnChange: true
            }
            section("Command Delivery (optional)") {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>On/off goes over local MQTT by default. To use Sofabaton's cloud webhook instead, " +
                    "turn on <b>Turn on API</b> for the activity in the Sofabaton app and paste the URL here. When set, the webhook is used first.</span>"
                input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL (optional)", required: false
                input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional)", required: false
            }
        }
        section {
            input name: "saveActivityBtn", type: "button", title: editingActivity ? "Save Changes" : "Add This Activity"
        }
    }
}

private void clearActivitySettings() {
    ["newActivityHub", "newActivityName", "newActivityUrlOn", "newActivityUrlOff", "newActivitySofabatonId"].each {
        app.removeSetting(it)
    }
}

private void createActivity(String hubDni, String name, String urlOn, String urlOff, Integer sofabatonActivityId) {
    def hub = getBridge()?.getChildDevice(hubDni)
    if (!hub) {
        logErr "cannot add activity '$name': hub not found"
        return
    }
    if (!hub.createActivityDevice(name, urlOn, urlOff, sofabatonActivityId)) {
        logErr "failed to create Activity device '$name'"
    } else {
        logInfo "added activity '$name'"
    }
}

// sofabatonActivityId is null for X1S and left untouched.
private void updateExistingActivity(def activity, String name, String urlOn, String urlOff, Integer sofabatonActivityId) {
    activity.setLabel(name)
    activity.updateSetting("webhookUrlOn", [value: urlOn ?: "", type: "text"])
    activity.updateSetting("webhookUrlOff", [value: urlOff ?: "", type: "text"])
    if (sofabatonActivityId != null) {
        activity.updateSetting("sofabatonActivityId", [value: sofabatonActivityId, type: "number"])
    }
    activity.updated()
}

// ============================================================================
// Buttons
// ============================================================================

def appButtonHandler(String btn) {
    logDbg "button: ${btn}"
    def bridge = getBridge()

    if (btn == "saveHubBtn") { state.hubSaveRequested = true; return }
    if (btn == "cancelHubBtn") {
        state.remove("editingHubDni")
        state.remove("editHubPrefilled")
        state.hubPageCancelled = true
        return
    }
    if (btn.startsWith("editHub_")) {
        state.editingHubDni = btn - "editHub_"
        state.remove("editHubPrefilled")
        return
    }
    if (btn.startsWith("removeHub_")) {
        bridge?.removeRemoteDevice(btn - "removeHub_")
        return
    }
    if (btn == "cancelActivityBtn") { state.activityPageCancelled = true; return }
    if (btn.startsWith("editAct_")) {
        def parts = (btn - "editAct_").split("_", 2)
        if (parts.length == 2) {
            state.editingActivityKey = "${parts[0]}|${parts[1]}"
            state.remove("editActivityPrefilled")
        }
        return
    }
    if (btn.startsWith("removeAct_")) {
        def parts = (btn - "removeAct_").split("_", 2)
        if (parts.length == 2) bridge?.getChildDevice(parts[0])?.removeActivityDeviceByDni(parts[1])
        return
    }
    if (btn == "saveActivityBtn") { state.activitySaveRequested = true; return }

    def hub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    if (!hub) return
    if (btn == "learnBtn") {
        bridge.startActivityLearn(hub.deviceNetworkId)
        state.learnStartedFor = newActivityHub
    }
    if (btn == "cancelLearnBtn") {
        bridge.cancelActivityLearn()
        state.remove("learnStartedFor")
    }
}

// Mirrors RemoteDriver's ipToHex(). Keep in sync.
private String ipToHexForApp(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) return null
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2, "0").toUpperCase() }.join()
}

// ============================================================================
// Tips & Troubleshooting content. This is the only setup guide; the README links here.
// ============================================================================

private String exampleBoxHtml(String content) {
    "<div style='background:#f5f5f5;border-left:4px solid #5f8b6f;padding:10px 14px;margin-top:6px'>${content}</div>"
}

private List tipsTopics() {
    [
        [id: "start", label: "Before you begin", title: "Before you begin", group: "Getting started", icon: "pi-sitemap",
            body: "<p>This integration supports two Sofabaton hubs, the <b>X1S</b> and the <b>X2</b>. They set up differently, so find yours in the sidebar.</p>" +
                "<p>Setup moves back and forth between two apps, so each step is tagged:</p>" +
                "<p>${HUBITAT_PILL}<br>This Hubitat app or a device page.</p>" +
                "<p>${SOFABATON_PILL}<br>The Sofabaton mobile app.</p>",
            checklist: [[title: "A static IP for your Sofabaton hub", detail: "Set a DHCP reservation on your router. If the IP changes later, the integration stops working with no error."],
                        [title: "Know your model", detail: "It's printed on the hub, or shown in the Sofabaton app under the hub's settings. X1S uses local HTTP plus a cloud webhook, X2 uses MQTT. The original X1 isn't supported."],
                        [title: "X2 only: Hubitat's MQTT broker", detail: "Enabled through MQTT Import Integration. See X2 setup, step 1."]]],

        [id: "x2broker", label: "1. Enable the MQTT broker", title: "Enable Hubitat's MQTT broker", group: "X2 setup", icon: "pi-server",
            body: "<p>${HUBITAT_PILL}<br>Go to <b>Integrations &rarr; Add Built-In App &rarr; MQTT Import Integration</b>. Enable it and turn on <b>Use built-in MQTT service</b>.</p>" +
                "<p>Write down the host, port, username, and password it shows. You'll enter them twice: in the Sofabaton app, and on the Add a Hub page here.</p>"],
        [id: "x2connect", label: "2. Connect the X2", title: "Point the X2 at the broker", group: "X2 setup", icon: "pi-wifi",
            body: "<p>${SOFABATON_PILL}<br>Tap <b>Me</b> &rarr; <b>Connect to Home Assistant (MQTT broker)</b> &rarr; <b>Confirmed MQTT Installed</b>. " +
                "Enter your Hubitat hub's IP, then the port, username, and password from step 1. " +
                "If it asks for Home Assistant account credentials, that's just the broker username and password. Confirm it shows connected before moving on.</p>",
            warning: "<b>Easy to skip, and nothing works without it.</b><br>Ignore the Home Assistant wording, you're pointing it at Hubitat's broker. " +
                "<b>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Home Assistant Remote</b> is a different feature. Don't use it here."],
        [id: "x2mac", label: "3. Find the MAC ID", title: "Find the X2's MAC ID", group: "X2 setup", icon: "pi-search",
            body: "<p>On your computer, install <b>MQTT Explorer</b> (free, mqtt-explorer.com) and connect it with the broker details from step 1. " +
                "On the physical remote, switch to a different activity. In the sidebar, look for <code>activity &rarr; 14639332AA40 &rarr; activity_control_up</code>. " +
                "That 12-character code is your X2's MAC ID.</p>",
            warning: "<b>It has to be an activity change.</b><br>Volume, channel, and other button presses never touch the network, so nothing will show up. " +
                "Also, this is the Sofabaton hub's MAC, not your Hubitat hub's."],
        [id: "x2add", label: "4. Add hub and activities", title: "Add the X2 hub and its activities", group: "X2 setup", icon: "pi-plus-circle",
            body: "<p>${HUBITAT_PILL}<br><b>Add a Hub</b> &rarr; Model X2 &rarr; enter the MAC ID and the broker details from step 1.</p>" +
                "<p>${HUBITAT_PILL}<br><b>Add an Activity</b> &rarr; pick the hub, give it a name, and enter its Sofabaton Activity ID. " +
                "For on/off control today, also paste a webhook URL (see the Known issue below).</p>" +
                "<p>${HUBITAT_PILL}<br>Tap <b>Done</b> on the main page when finished.</p>" +
                exampleBoxHtml("<b>Example, an Apple TV activity:</b><br>" +
                    "&bull; Sofabaton Activity ID: <code>101</code>, read from a payload like <code>{&quot;activity_id&quot;:101,&quot;state&quot;:&quot;on&quot;}</code><br>" +
                    "&bull; Activity Name: <code>Apple TV</code>. Just a label for you, it doesn't need to match anything in the Sofabaton app.<br>" +
                    "Only the ID has to be correct."),
            append: "knownissue"],

        [id: "x1slocal", label: "1. Local IP control", title: "Set up local IP control", group: "X1S setup", icon: "pi-mobile",
            body: "<p>${HUBITAT_PILL}<br><b>Add a Hub</b> &rarr; Model X1S &rarr; enter the hub's static IP.</p>" +
                "<p>${SOFABATON_PILL}<br><b>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Create a virtual device for IP control</b>. " +
                "URL <code>http://[Hubitat IP]:39501/</code>, method PUT. Create one per activity. Read <b>Buttons and name matching</b> before choosing a body value.</p>"],
        [id: "x1swebhook", label: "2. Webhook commands", title: "Add webhook commands", group: "X1S setup", icon: "pi-link",
            body: "<p>${SOFABATON_PILL}<br>On the activity, turn on <b>Turn on API</b> (just <b>API</b> in some app versions) and copy the webhook URL, " +
                "a long <code>https://app1.sofabaton.com/...</code> link. Some versions give separate on and off URLs, others just one. " +
                "This is separate from step 1. IP control only reports to Hubitat, the webhook sends commands.</p>" +
                "<p>${HUBITAT_PILL}<br><b>Add an Activity</b> &rarr; name it to exactly match the button's Description &rarr; paste the Start URL, " +
                "and the Stop URL only if you got a separate one. A space in the URL is normal, it's encoded automatically.</p>" +
                "<p>${HUBITAT_PILL}<br>Tap <b>Done</b> on the main page when finished.</p>"],
        [id: "x1smatch", label: "3. Buttons and name matching", title: "Buttons and name matching", group: "X1S setup", icon: "pi-th-large",
            body: "<p><b>Buttons 1-10</b> fire when the remote sends a plain number. <b>Buttons 11-20</b> are user-definable slots on the Remote device's Preferences tab, " +
                "entered as <code>matchString|Description</code>. Most setups should use 11-20, e.g. <code>watchAppleTV</code> instead of remembering that 7 means Apple TV.</p>" +
                "<p>Hubitat knows an activity changed by matching the slot's Description against the Activity Name here, exactly, including capitalization and spacing.</p>" +
                exampleBoxHtml("<b>Example, an Apple TV activity:</b><br>" +
                    "&bull; Body value in the Sofabaton app: <code>appleTV</code><br>" +
                    "&bull; User 1 (11) on the Remote device: <code>appleTV|Apple TV</code><br>" +
                    "&bull; Activity Name here: <code>Apple TV</code>"),
            warning: "If these don't line up exactly, the remote still controls your gear. Hubitat just won't know the activity changed."],

        [id: "knownissue", label: "X2 known issue", title: "Known issue: X2 and MQTT", group: "Troubleshooting", icon: "pi-exclamation-triangle",
            body: "<p>Hubitat's MQTT client currently connects and subscribes, but isn't delivering incoming messages. " +
                "This is a reported Hubitat platform bug, not a limit of MQTT or the X2.</p>" +
                "<p>Until it's fixed, Hubitat won't see activity changes from the remote, the main page will show <b>Waiting for first MQTT message</b>, " +
                "and <b>Listen for Next Activity</b> won't capture an ID. Enter IDs manually for now. Commands sent from Hubitat over MQTT haven't been confirmed yet either.</p>",
            warning: "<b>Workaround for on/off control today:</b><br>In the Sofabaton app, turn on <b>Turn on API</b> for the activity, then paste the webhook URL into " +
                "Edit Activity here. Once the fix lands, clear the webhook URL to go back to local MQTT.<br><br>" +
                "<b>Workaround for state sync today:</b><br>Add the X2 as model <b>X1S</b> and follow the X1S setup instead. It uses IP and name matching " +
                "rather than MAC and Activity ID. Switching to MQTT later means removing the hub and adding it again as X2."],
        [id: "traffic", label: "What creates MQTT traffic", title: "What creates MQTT traffic", group: "Troubleshooting", icon: "pi-info-circle",
            body: "<p>Only activity-level changes: starting or switching an activity, or the hub-wide Power Off. Button presses within an activity " +
                "(volume, channel, play/pause) never touch the network. This integration reacts to activities, not individual button presses.</p>" +
                "<p>X1S never uses MQTT. It reports over local HTTP and takes commands through the cloud webhook.</p>"],
        [id: "faq", label: "Common questions", title: "Common questions", group: "Troubleshooting", icon: "pi-question-circle",
            body: "<p><b>Where does the webhook URL go?</b><br>The Activity's Start or Stop Webhook URL fields, when adding it or later through Edit Activity.</p>" +
                "<p><b>My activity name has a space. Will the webhook break?</b><br>No, spaces are encoded automatically.</p>" +
                "<p><b>Can I change a hub's model, MAC, or IP?</b><br>No, those set the device ID. Remove the hub and add it again.</p>"],

        [id: "removal", label: "Removing hubs", title: "Remove hubs and activities safely", group: "Devices", icon: "pi-trash",
            body: "<p>Use <b>Remove Hub</b> and <b>Remove Activity</b> on the main page, not Hubitat's Devices page. Removing a hub also removes its activities.</p>"],

        [id: "logging", label: "Log levels", title: "Log levels", group: "Logging", icon: "pi-file",
            body: "<p>" + logLevelPill("Errors Only") + " Default. Warnings and errors only.</p>" +
                "<p>" + logLevelPill("Normal") + " Errors plus hubs and activities added or changed.</p>" +
                "<p>" + logLevelPill("Full") + " Everything. Use while troubleshooting. Turns itself off after 30 minutes.</p>" +
                "<p>Each device also has its own debug logging toggle on its Preferences tab.</p>"]
    ]
}

// ============================================================================
// UI template (HubitatAppUiTemplate, from Reolink v1.6.1)
// ============================================================================

private void helpAndSupportSection() {
    section(title: "<b>Help & Support</b>", sectionClass: "app-main-support") {
        href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
            page: "tipsPage", description: "Setup guide for X1S and X2", width: 4, style: "margin:8px;"
        paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
            "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 4
        paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
            "Buy Me a Coffee", "Support development"), width: 4
    }
}

private void versionFooterSection() {
    section {
        paragraph "<div class='text-center text-color-secondary text-xs mt-2'>${APP_NAME} v${APP_VERSION}</div>"
    }
}

private String supportLinkHtml(String url, String iconClass, String title, String subtitle) {
    """
<a href='${url}' target='_blank' rel='noopener noreferrer'
   class='flex align-items-center gap-3 border-1 border-gray-200 border-round px-3 py-2 text-color no-underline'>
  <i class='${iconClass} text-blue-700 text-xl flex-shrink-0'></i>
  <span class='min-w-0'>
    <span class='block text-blue-700 font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
</a>
"""
}

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "bg-green-50 border-green-200" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle text-green-700" : "fa-solid fa-exclamation-triangle text-yellow-700"
    """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .app-main-content { float: left; width: calc(62% - 8px); }
  .app-main-settings { float: right; width: 38%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box; }
  .app-main-content > .mdl-grid, .app-main-settings > .mdl-grid { padding: 4px 0 !important; }
  .app-main-support { clear: both; }
  .app-main-support button.hrefElem[name^='_action_href_tips'] { height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box; }
  @media (max-width: 1000px) {
    .app-main-content, .app-main-settings { float: none; width: 100%; border-left: 0; padding-left: 0; }
  }
</style>
<div class='flex align-items-center justify-content-between gap-3 ${tone} border-1 border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='${icon} text-2xl' aria-hidden='true'></i></div>
    <div class='min-w-0'>
      <div class='app-heading font-semibold'>${title}</div>
      <div class='text-color-secondary mt-1' style='font-size:14px;'>${summary}</div>
    </div>
  </div>
  <a href='/logs?tab=past&amp;appId=${app.id}' target='_blank' class='text-blue-700 font-semibold white-space-nowrap no-underline'>
    View logs <i class='fa-regular fa-external-link'></i>
  </a>
</div>
"""
}

private String columnHeader(String title) {
    "<div class='app-heading font-semibold'>${title}</div>"
}

private String countText(int count, String singular, String plural = null) {
    "${count} ${count == 1 ? singular : (plural ?: singular + 's')}"
}

private String loggingDetailsPopupHtml() {
    String current = currentLogLevel()
    String details = """
<div class='text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>Choose how much activity appears in the app logs.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus hubs and activities added or changed.", current)}
  ${logLevelDetailHtml("Full", "Everything. Use while troubleshooting. Turns off after 30 minutes.", current)}
</div>
"""
    String js = groovy.json.JsonOutput.toJson(details)
        .replace("&", "&amp;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;")
    """
<a href='#' onclick='window.alertHubitat(${js}); return false;'
   class='flex align-items-center justify-content-between gap-3 bg-gray-50 border-1 border-gray-200 border-round px-3 py-2 h-full text-color no-underline'>
  <span>
    <span class='block font-semibold'>Logging details</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>What each level records</span>
  </span>
  <i class='pi pi-chevron-right text-blue-700'></i>
</a>
"""
}

private String logLevelDetailHtml(String name, String description, String current) {
    String badge = name == current ? "<span class='text-green-700 text-xs font-bold'>Current</span>" : ""
    String tone = name == "Errors Only" ? "bg-red-50 text-red-700" :
        name == "Full" ? "bg-indigo-50 text-indigo-700" : "bg-blue-50 text-blue-700"
    """
<div class='border-1 border-gray-200 border-round px-3 py-2 mb-2'>
  <div class='flex align-items-center justify-content-between gap-3 mb-1'>
    <span class='${tone} border-round-xl px-2 py-1 text-xs font-bold'>${name}${name == "Errors Only" ? " (default)" : ""}</span>
    ${badge}
  </div>
  <div class='text-base text-color-secondary'>${description}</div>
</div>
"""
}

def tipsPage(params = null) {
    def topics = tipsTopics()
    def topic = topics.find { it.id == params?.topic } ?: topics.find { it.id == DEFAULT_TIP_TOPIC } ?: topics[0]
    int i = topics.indexOf(topic)
    def next = i + 1 < topics.size() ? topics[i + 1] : null
    dynamicPage(name: "tipsPage", title: "Tips & Troubleshooting") {
        section(sectionClass: "app-tips-index") {
            paragraph rawHtml: true, tipsStylesHtml()
            topics.groupBy { it.group }.each { group, entries ->
                paragraph rawHtml: true, "<div class='app-heading font-semibold mt-2'>${group}</div>"
                entries.each { entry ->
                    String selected = entry.id == topic.id ? "app-topic-current" : ""
                    href name: "tip_${entry.id}",
                        title: "<span class='${selected}'><i class='pi ${entry.icon} mr-3' aria-hidden='true'></i>${entry.label}</span>",
                        description: "", page: "tipsPage", params: [topic: entry.id], width: 12, style: "margin:0 8px;"
                }
            }
        }
        section(sectionClass: "app-tips-article") {
            paragraph rawHtml: true, tipsArticleHtml(topic)
            if (topic.append) {
                def extra = topics.find { it.id == topic.append }
                if (extra) paragraph rawHtml: true, tipsArticleHtml(extra)
            }
            if (next) {
                href name: "nextTip", title: "<span class='text-blue-700'>${next.label}</span>",
                    description: "Next topic", page: "tipsPage", params: [topic: next.id], width: 12, style: "margin:8px;"
            }
        }
    }
}

private String tipsArticleHtml(Map topic) {
    String extra = ""
    if (topic.checklist) {
        extra += "<div class='border-1 border-gray-200 border-round p-3 mb-3'>" + topic.checklist.collect { item ->
            "<div class='flex align-items-center gap-3 py-2'><i class='pi pi-check-circle text-blue-700 text-xl' aria-hidden='true'></i>" +
            "<div><div class='font-semibold'>${item.title}</div><div class='text-sm text-color-secondary'>${item.detail}</div></div></div>"
        }.join("") + "</div>"
    }
    if (topic.warning) extra += warningMessageHtml(topic.warning)
    """
<article class='app-tip-card'>
  <div class='app-tip-card-header flex align-items-center gap-3'>
    <i class='pi ${topic.icon} text-blue-700 text-xl' aria-hidden='true'></i>
    <h4 class='app-heading font-semibold m-0'>${topic.title}</h4>
  </div>
  <div class='app-tip-copy'>${topic.body}${extra}</div>
</article>
"""
}

private String tipsStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  .app-tips-index { float: left; width: calc(27% - 8px); box-sizing: border-box; }
  .app-tips-article { float: right; width: 73%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box; }
  .app-tips-index > .mdl-grid, .app-tips-article > .mdl-grid { padding: 4px 0 !important; }
  .app-tips-index .mdl-cell:has(> style) { display: none; }
  .app-tips-index button.hrefElem {
    background: transparent; box-shadow: none; border: 0; border-left: 3px solid transparent;
    border-radius: 0; padding: 9px 10px; font-family: inherit; font-size: 16px; color: #1565c0;
  }
  .app-tips-index button.hrefElem::before { display: none; }
  .app-tips-index button.hrefElem:has(.app-topic-current) { background: #eaf2fc; border-left-color: #1565c0; font-weight: 600; }
  .app-tips-index button.hrefElem:hover { background: #f3f6fa; }
  .app-tips-index button.hrefElem:focus-visible { outline: 2px solid #1565c0; outline-offset: 2px; }
  .app-tip-card { border: 1px solid #dfe3e8; border-radius: 4px; overflow: hidden; margin-bottom: 12px; }
  .app-tip-card-header { padding: 16px; background: #f5f7fa; border-bottom: 1px solid #e4e7ec; }
  .app-tip-copy { padding: 16px; line-height: 1.55; overflow-wrap: anywhere; }
  .app-tip-copy p { margin: 0 0 16px; }
  .app-tip-copy p:last-child { margin-bottom: 0; }
  .app-tip-copy .app-message { margin-top: 12px; }
  .app-tips-article button.hrefElem { background: #fff; box-shadow: none; border: 1px solid #dfe3e8; border-radius: 4px; font-family: inherit; }
  .app-tips-article button.hrefElem::before { color: #1565c0; }
  #formApp:has(.app-tips-index) #fieldsetAppButtons { clear: both; }
  @media (max-width: 1000px) {
    .app-tips-index, .app-tips-article { float: none; width: 100%; padding: 0; border-left: 0; }
  }
</style>
"""
}

private String logLevelPill(String level) {
    def tones = ["Errors Only": "bg-red-50 text-red-700", "Normal": "bg-gray-100 text-gray-700", "Full": "bg-indigo-50 text-indigo-700"]
    "<span class='inline-block ${tones[level] ?: tones['Normal']} font-bold text-xs px-2 py-1 border-round-xl'>${level}</span>"
}

private String warningMessageHtml(String content, String extraClasses = "") {
    "<div class='p-message p-message-warn app-message flex align-items-center gap-2 ${extraClasses}'>" +
        "<div class='flex-shrink-0'><i class='fa-solid fa-exclamation-triangle text-xl' aria-hidden='true'></i></div>" +
        "<div class='min-w-0 flex-1'>${content}</div></div>"
}

private String appPageSpacingCss() {
    """
  ${tileSecondaryTextCss()}
  .app-heading { font-size: 16px; }
  .p-message.app-message { padding: 0.75rem !important; margin: 0; border: 0 !important; }
  .app-message .text-color-secondary, .app-message .text-blue-700, .app-message .text-yellow-700 { color: inherit !important; }
  div.panel-body { padding: 0 !important; margin-left: -0.5em; margin-right: -0.5em; }
  fieldset#fieldsetAppButtons { margin-left: 0.5em; margin-right: 0.5em; }
"""
}

private String tileSecondaryTextCss() {
    "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

private String tipsCardCss() {
    """
  button.hrefElem[name^='_action_href_tips'] {
    position: relative; background: #fff; border: 1px solid #e0e0e0; border-radius: 4px; box-shadow: none;
    color: #333; height: 56px; font-family: inherit; font-size: 16px; font-weight: 500; line-height: 1.4;
    padding: 8px 28px 8px 46px;
  }
  button.hrefElem[name^='_action_href_tips'] > span:first-child { color: #1565c0; font-weight: 600; font-size: 16px !important; }
  button.hrefElem[name^='_action_href_tips'] > span.state-incomplete-text { color: #777; font-size: 14px; font-weight: 500; line-height: 1.4; }
  button.hrefElem[name^='_action_href_tips'] i.pi {
    position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: #1565c0; font-size: 20px;
  }
  button.hrefElem[name^='_action_href_tips']::before { color: #1565c0; }
"""
}
