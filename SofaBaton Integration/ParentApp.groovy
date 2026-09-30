/*
    Sofabaton Integration - Parent App
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: the X1/X1S local receive path (Sofabaton Remote driver) is a
    fork of Derek Osborn's (dJOS1475) Hubitat community driver, built on
    push-command building blocks originated by Mike Maxwell (mike.maxwell).

    Notes:
     -Setup only. Runtime (HTTP listener, MQTT, webhooks, state sync, buttons) lives in
      the Bridge, Remote, and Activity drivers.
     -Device tree: Bridge -> one Remote per hub -> one Activity per activity.
      X1S keyed by IP (ipToHex DNI), X2 keyed by bare uppercase MAC.
     -X2 needs Hubitat 2.5.2.126+. Activities are created automatically from the
      hub's list; Add an Activity is X1S only. "Find My X2" reads MACs the Bridge
      has seen, so users never need MQTT Explorer.
     -Activity labels default to "<Hub label>-<Activity>" so they group in device pickers.
      The main page calls applyActivityLabels() on each hub (a no-op after the first run);
      renaming a hub relabels its default-named activities. Pills show the short name.
     -X2 remote buttons: the Remote learns the Hubitat Control device from the first press.
      Edit Hub can set it manually. Button Actions maps each button to switches (Toggle/On/Off);
      the app subscribes to the hub's pushed events. Group toggle: any on -> all off, else all on.
      Button events still fire on the hub device for Rule Machine or Button Controller.
     -Add/Edit pages save ONLY on an explicit Save click (state.*SaveRequested).
      Hubitat's Done is hidden there (it left without saving). Cancel and Save sit together at
      the bottom as links to mainPage (so a browser reload stays on the main page), with a
      one-time token so a reload can't re-submit. Save is gray until required fields are filled.
     -Edit/Remove use buttons + state, not hrefs with params (Hubitat drops
      params across same-page hrefs).
     -No required:true on Add page inputs (browser validation blocked Cancel).
      No refreshInterval (bounced to mainPage); button presses re-render instead.
     -uninstalled() must delete the Bridge, or everything under it is orphaned.
     -Model, MAC, and X1S IP set the DNI, so they're not editable. Remove and re-add.
     -Log level is the only logging control. It cascades App -> Bridge -> Remotes -> Activities.
      Every driver sends its log lines back up to childLog(), so all Sofabaton logging appears
      under this app, labeled [Bridge], [hub], or [activity]. View logs shows everything.
     -Refresh and Add Hub pause 1.5s so the page redraws after the hub's list reply (~0.3s).
     -UI follows HubitatAppUiTemplate (Reolink v1.6.1, UI design by gopher.ny).
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Sofabaton Integration"
@Field static final String APP_VERSION = "1.1.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com"   // TODO: release thread
@Field static final String COFFEE_URL = "https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String DEFAULT_TIP_TOPIC = "start"
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

// Add/Edit pages: Hubitat's Done would leave without saving, so only Cancel and Save are shown.
@Field static final String HIDE_DONE_CSS = """<style>
  #fieldsetAppButtons { display: none !important; }
  button.hrefElem[name^='_action_href_sbCancel'], button.hrefElem[name^='_action_href_sbSave'] {
    width: auto !important; min-width: 140px; min-height: 0 !important; height: auto !important;
    display: inline-block !important; padding: 8px 20px !important; margin: 4px 12px 4px 0 !important;
    border: 1px solid transparent !important; border-radius: 4px !important; box-shadow: none !important;
    font-size: 15px !important; font-weight: 600 !important; text-align: center !important; line-height: 1.4 !important;
    cursor: pointer; transition: background 0.15s, border-color 0.15s, box-shadow 0.15s, transform 0.15s; }
  button.hrefElem[name^='_action_href_sbCancel'] { background: #f1f3f5 !important; border-color: #d0d7de !important; }
  button.hrefElem[name^='_action_href_sbCancel']:hover { background: #eaf2fc !important; border-color: #1565c0 !important; }
  button.hrefElem[name^='_action_href_sbCancel']:hover span { color: #1565c0 !important; }
  button.hrefElem[name^='_action_href_sbSave'] { background: #bdbdbd !important; border-color: #bdbdbd !important; }
  button.hrefElem[name^='_action_href_sbSave']:not([name*='Ready']) { cursor: not-allowed !important; }
  button.hrefElem[name*='Ready'] { background: #2e7d32 !important; border-color: #2e7d32 !important; }
  button.hrefElem[name*='Ready']:hover { background: #1b5e20 !important; border-color: #1b5e20 !important;
    box-shadow: 0 2px 6px rgba(0,0,0,0.25) !important; transform: translateY(-1px); }
  button.hrefElem[name^='_action_href_sbCancel']::before, button.hrefElem[name^='_action_href_sbSave']::before,
  button.hrefElem[name^='_action_href_sbCancel']::after, button.hrefElem[name^='_action_href_sbSave']::after { display: none !important; }
  button.hrefElem[name^='_action_href_sbCancel'] span { color: #333 !important; font-size: 15px !important; }
  button.hrefElem[name^='_action_href_sbSave'] span { color: #fff !important; font-size: 15px !important; }
  button.hrefElem[name^='_action_href_sbCancel'] .state-incomplete-text, button.hrefElem[name^='_action_href_sbSave'] .state-incomplete-text,
  button.hrefElem[name^='_action_href_sbCancel'] .state-complete-text, button.hrefElem[name^='_action_href_sbSave'] .state-complete-text { display: none !important; }
  .mdl-cell:has(> button.hrefElem[name^='_action_href_sbCancel']), .mdl-cell:has(> button.hrefElem[name^='_action_href_sbSave']) {
    width: auto !important; flex: 0 0 auto !important; background: transparent !important; border: 0 !important; padding: 0 !important; }
</style>"""

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
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/SofaBaton%20Integration/ParentApp.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "addHubPage")
    page(name: "addActivityPage")
    page(name: "buttonActionsPage")
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
    pushLogLevel()
    subscribeButtons()
}

// Sends the app's level to every Sofabaton device via the Bridge.
private void pushLogLevel() {
    String level = currentLogLevel()
    getBridge()?.setLogLevel(level)
    state.pushedLogLevel = level
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

// Called by the Bridge for every driver log line. Drivers already filter by level.
void childLog(String level, String source, String msg) {
    String line = "${APP_NAME} [${source}]: ${msg}"
    switch (level) {
        case "error": log.error line; break
        case "warn": log.warn line; break
        case "info": log.info line; break
        default: log.debug line
    }
}

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
        pushLogLevel()
        log.warn "${APP_NAME}: Full logging turned off after 30 minutes"
    }
}

// ============================================================================
// Main page
// ============================================================================

def mainPage(params = null) {
    // Save/Cancel on Add/Edit pages are links to mainPage, so the browser URL ends up here
    // and a reload stays on the main page. The token stops a reload from re-submitting.
    if (params?.formAction && params?.token && params.token == state.formToken) {
        state.remove("formToken")
        switch (params.formAction) {
            case "saveHub": state.hubSaveRequested = true; return addHubPage()
            case "cancelHub": state.hubPageCancelled = true; return addHubPage()
            case "saveActivity": state.activitySaveRequested = true; return addActivityPage()
            case "cancelActivity": state.activityPageCancelled = true; return addActivityPage()
        }
    }
    if (state.editingHubDni) return addHubPage()
    if (state.editingActivityKey) return addActivityPage()

    // Clear half-entered fields from a cancelled attempt.
    clearHubSettings()
    clearActivitySettings()
    ["activitySaveRequested", "discoveryStarted", "findError"].each { state.remove(it) }
    armLogRevert()
    if (state.pushedLogLevel != currentLogLevel()) pushLogLevel()

    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []
    hubs.each { h -> try { h.applyActivityLabels() } catch (e) { } }   // one-time label migration per hub
    subscribeButtons()   // picks up changes made on Button Actions
    def x2Hubs = hubs.findAll { it.currentValue("hubModel") == "X2" }
    def x1sHubs = hubs.findAll { it.currentValue("hubModel") != "X2" }
    List allActivities = hubs.collectMany { it.getChildDevices() ?: [] }
    int removedCount = allActivities.count { it.currentValue("onHub") == "false" } as int
    int activityCount = allActivities.size() - removedCount
    Map banner = bannerState(bridge, hubs, x2Hubs, activityCount, removedCount)

    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-main-content") {
            paragraph rawHtml: true, columnHeader("Hubs")
            if (!hubs) {
                paragraph "<span class='text-color-secondary'>No hubs added yet. Tap Add a Hub below.</span>"
            }
            hubs.each { hub ->
                String dni = hub.deviceNetworkId
                String hubLabel = hub.getLabel()
                def activities = hub.getChildDevices() ?: []
                paragraph rawHtml: true, hubCardHtml(hub, activities)
                // Hubitat can't put inputs inside HTML, so these sit tight under the card (see sb-hub-btn CSS).
                if (hub.currentValue("hubModel") == "X2") {
                    input name: "editHub_${dni}", type: "button", title: "Edit Hub", width: 3
                    input name: "syncHub_${dni}", type: "button", title: "Refresh Activities", width: 3
                    href name: "btnActs_${dni}", title: "Button Actions", description: "", page: "buttonActionsPage", params: [hub: dni], width: 3
                    input name: "removeHub_${dni}", type: "button", title: "Remove Hub", width: 3
                    activities.findAll { it.currentValue("onHub") == "false" }.each { act ->
                        input name: "removeAct_${dni}_${act.deviceNetworkId}", type: "button", title: "Remove ${shortName(act, hubLabel)}", width: 3,
                            backgroundColor: "#c62828", textColor: "white"
                    }
                } else {
                    input name: "editHub_${dni}", type: "button", title: "Edit Hub", width: 3
                    input name: "removeHub_${dni}", type: "button", title: "Remove Hub", width: 3
                    activities.each { act ->
                        input name: "editAct_${dni}_${act.deviceNetworkId}", type: "button", title: "Edit ${shortName(act, hubLabel)}", width: 3
                        input name: "removeAct_${dni}_${act.deviceNetworkId}", type: "button", title: "Remove ${shortName(act, hubLabel)}", width: 3
                    }
                }
                paragraph rawHtml: true, "<div class='sb-hub-end'></div>"
            }
            href name: "toAddHub", title: "Add a Hub", description: "X1S or X2", page: "addHubPage", width: 12, style: "margin:8px;"
            if (x1sHubs) {
                href name: "toAddActivity", title: "Add an Activity", description: "X1S only. X2 activities are added automatically.", page: "addActivityPage", width: 12, style: "margin:8px;"
            }
            if (hubs) {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Tap <b>Done</b> when finished. That's what finalizes the install.</span>"
            }
        }
        section(sectionClass: "app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            if (bridge) {
                paragraph rawHtml: true, supportLinkHtml("/device/edit/${bridge.id}", "pi pi-sitemap",
                    "Bridge Device", "Connection status and MQTT diagnostics")
            }
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

// Activity name without the "<Hub label>-" prefix, for pills and buttons.
private String shortName(act, String hubLabel) {
    String label = act.getLabel() ?: act.displayName
    String prefix = hubLabel ? "${hubLabel}-".toString() : null
    return (prefix && label?.startsWith(prefix) && label.length() > prefix.length()) ? label.substring(prefix.length()) : label
}

// Green only when everything is confirmed. X2 needs a real reply from the hub.
private Map bannerState(bridge, List hubs, List x2Hubs, int activityCount, int removedCount) {
    String counts = "${countText(hubs.size(), 'hub')} &middot; ${countText(activityCount, 'activity', 'activities')}" +
        (removedCount ? " &middot; ${removedCount} removed from hub" : "")
    if (!hubs) {
        return [ok: false, title: "Setup required", summary: "Add a hub to get started. Tips &amp; Troubleshooting has the full walkthrough."]
    }
    String mqtt = bridge?.currentValue("mqttStatus")
    if (x2Hubs && mqtt == "broker off") {
        return [ok: false, title: "MQTT broker is off", summary: "${counts} &middot; Enable Hubitat's built-in broker (Tips, X2 setup step 1)."]
    }
    if (x2Hubs && mqtt in ["error", "connect failed"]) {
        return [ok: false, title: "MQTT connection problem", summary: "${counts} &middot; Check the broker settings, then run Force Reconnect on the Bridge device."]
    }
    def waiting = x2Hubs.findAll { !it.currentValue("lastMqttMessage") }
    if (waiting) {
        String names = waiting.collect { it.getLabel() }.join(", ")
        return [ok: false, title: "Waiting for your X2", summary: "${counts} &middot; No reply yet from ${names}. Check it's connected to the broker (Tips, X2 setup step 2), then tap Refresh Activities."]
    }
    return [ok: true, title: "Integration ready", summary: counts]
}

private String hubCardHtml(hub, List activities) {
    String model = hub.currentValue("hubModel") ?: "unknown"
    boolean x2 = model == "X2"
    String hubLabel = hub.getLabel()
    String idShown = x2 ? (hub.currentValue("remoteMac") ?: "no MAC set") : (hub.currentValue("remoteIp") ?: "no IP set")
    StringBuilder card = new StringBuilder()
    card << "<div style='border:1px solid #ccc;border-radius:10px;padding:12px 14px;margin:4px 0 0;background:#fafafa'>"
    String badge = x2 ? "#5f8b6f" : "#e8a33d"   // matches the Remote driver's pills
    card << "<div><span style='background:${badge};color:#fff;border-radius:8px;padding:2px 9px;font-size:0.9em;font-weight:bold'>${model}</span> "
    card << "<a href='/device/edit/${hub.id}' target='_blank' class='sb-link' style='font-size:0.85em'>${hubLabel}" +
        "<i class='fa-regular fa-external-link' style='margin-left:5px;font-size:0.8em'></i></a> "
    card << "<span style='color:#888;font-size:0.8em'>${idShown}</span></div>"
    if (x2) {
        String seen = hub.currentValue("lastMqttMessage")
        card << "<div style='margin-top:6px;font-size:0.8em;color:${seen ? '#5f8b6f' : '#b26a00'}'>"
        card << (seen ? "Last heard from hub: ${seen}" : "Waiting for the hub to reply") << "</div>"
    }
    if (!activities) {
        card << "<div style='margin-top:10px;color:#aaa;font-size:0.85em'>"
        card << (x2 ? "Loading activities from the hub. Reload this page in a few seconds." : "No activities yet. Tap Add an Activity.") << "</div>"
    } else {
        card << "<div style='margin-top:10px;display:flex;flex-wrap:wrap;gap:8px'>"
        activities.each { act -> card << activityPillHtml(act, x2, hubLabel) }
        card << "</div>"
    }
    if (x2) card << buttonMapHtml(hub)
    card << "</div>"
    return card.toString()
}

// Opens the device in a new tab. Green dot = on. X2 shows its ID, pending state, and a flag if deleted on the hub.
private String activityPillHtml(act, boolean x2, String hubLabel) {
    String sw = act.currentValue("switch")
    String status = act.currentValue("activityStatus")
    String dot = "<span style='display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:${sw == 'on' ? '#2e9e5b' : '#bbb'}'></span>"
    String extra = ""
    if (x2) {
        extra += " <span style='margin-left:6px;background:#455a64;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em;font-weight:bold'>ID ${act.currentValue('sofabatonActivityId') ?: '?'}</span>"
        if (status in ["starting", "stopping"]) extra += " <span style='color:#b26a00;font-size:0.8em'>${status}...</span>"
        if (act.currentValue("onHub") == "false") extra += " <span style='margin-left:6px;background:#c62828;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Not on hub</span>"
    } else {
        String body = act.currentValue("bodyValue")
        extra += body ? " <span style='margin-left:6px;background:#455a64;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em;font-family:monospace'>${body}</span>" :
            " <span style='margin-left:6px;background:#b26a00;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>No body value</span>"
        if (!act.getSetting("webhookUrlOn")) extra += " <span style='margin-left:6px;background:#90a4ae;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Follow only</span>"
    }
    return "<a href='/device/edit/${act.id}' target='_blank' class='sb-pill' title='Open ${act.getLabel()?.replace("'", "&#39;")}'>${dot}${shortName(act, hubLabel)}${extra}" +
        "<i class='fa-regular fa-external-link' style='margin-left:8px;font-size:0.75em;color:#1565c0'></i></a>"
}

// X2 remote buttons: which device they come from and the number-to-name map.
private String buttonMapHtml(hub) {
    Map btns = [:]
    Integer devId = null
    Map devs = [:]
    try {
        btns = hub.getButtonMap() ?: [:]
        devId = hub.getButtonDeviceId()
        devs = hub.getHubDevices() ?: [:]
    } catch (e) { }
    StringBuilder out = new StringBuilder("<div style='margin-top:12px;font-size:0.85em'>")
    if (devId == null) {
        out << "<span style='color:#888'>Remote buttons: not set up. Press a Hubitat Control button on the remote (Tips, X2 setup step 4).</span>"
    } else {
        String devName = devs[devId.toString()] ?: "device ${devId}"
        out << "<span style='font-weight:600;color:#555'>Remote buttons</span> <span style='color:#888'>from ${devName}</span>"
        if (!btns) {
            out << "<div style='margin-top:4px;color:#aaa'>Loading button names from the hub. Tap Refresh Activities if they don't appear.</div>"
        } else {
            out << "<div style='margin-top:6px;display:flex;flex-wrap:wrap;gap:6px'>"
            String dni = hub.deviceNetworkId
            btns.sort { a, b -> (a.key as Integer) <=> (b.key as Integer) }.each { k, v ->
                String assigned = buttonAssignmentText(dni, k.toString())
                out << "<span style='background:#eef2f6;border-radius:10px;padding:2px 10px'><b>${k}</b> &middot; ${v}" +
                    (assigned ? " <span style='color:#1565c0'>&rarr; ${assigned}</span>" : " <span style='color:#aaa'>(not assigned)</span>") + "</span>"
            }
            out << "</div>"
        }
    }
    out << "</div>"
    return out.toString()
}

private String mqttStatusHtml(bridge, List x2Hubs) {
    String status = bridge?.currentValue("mqttStatus") ?: "not connected"
    String tone = status == "connected" ? "bg-green-50 text-green-700" :
        status in ["error", "connect failed", "broker off"] ? "bg-red-50 text-red-700" : "bg-gray-100 text-gray-700"
    Boolean broker = null
    try { broker = bridge?.checkBuiltInBroker() } catch (e) { }
    String attr = bridge?.currentValue("brokerRunning")
    if (broker == null && attr in ["true", "false"]) broker = (attr == "true")
    String brokerText = broker == null ? (attr == "error" ? "Check failed, see logs" : "Unknown") :
        (broker ? "Running" : "Not running (fine if you use an external broker)")
    String brokerRow = "<div class='flex justify-content-between gap-3 py-1'><span>Built-in broker</span>" +
        "<span class='text-color-secondary' style='font-size:14px;'>${brokerText}</span></div>"
    String rows = brokerRow + x2Hubs.collect { h ->
        "<div class='flex justify-content-between gap-3 py-1'><span>${h.getLabel()}</span>" +
        "<span class='text-color-secondary' style='font-size:14px;'>${h.currentValue('lastMqttMessage') ?: 'No reply yet'}</span></div>"
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
// Button Actions (X2 remote buttons -> switches)
// ============================================================================

private List buttonDevices(String dni, String btn) {
    return (settings["btnDev_${dni}_${btn}"] ?: []).findAll { it != null }
}

private String buttonAction(String dni, String btn) {
    return settings["btnAct_${dni}_${btn}"] ?: "toggle"
}

// "Lamp, Fan (toggle)" or "Lamp +2 more (on)". Empty if nothing is assigned.
private String buttonAssignmentText(String dni, String btn) {
    List devs = buttonDevices(dni, btn)
    if (!devs) return ""
    String names = devs.size() <= 2 ? devs*.displayName.join(", ") : "${devs[0].displayName} +${devs.size() - 1} more"
    return "${names} (${buttonAction(dni, btn)})"
}

def buttonActionsPage(params = null) {
    if (params?.hub) state.buttonActionsHub = params.hub
    String dni = state.buttonActionsHub
    def hub = dni ? getBridge()?.getChildDevice(dni) : null
    Map btns = [:]
    try { btns = hub?.getButtonMap() ?: [:] } catch (e) { }
    dynamicPage(name: "buttonActionsPage", title: hub ? "Button Actions: ${hub.getLabel()}" : "Button Actions", install: false, uninstall: false) {
        if (!hub) {
            section { paragraph "Hub not found. Go back and pick a hub again." }
            return
        }
        if (!btns) {
            section {
                paragraph rawHtml: true, warningMessageHtml("<b>No buttons yet.</b> Set up the Hubitat Control remote in the Sofabaton app " +
                    "(Tips, X2 setup step 4), then press one of its buttons once. The buttons appear here automatically.")
            }
            return
        }
        section {
            paragraph "<span class='text-color-secondary' style='font-size:14px;'>Pick what each remote button controls. " +
                "<b>Toggle</b> works as a group: if any are on, all turn off, otherwise all turn on. " +
                "For a Room Lighting scene, pick its activator device. Button events still fire on the hub device for Rule Machine or Button Controller.</span>"
        }
        btns.sort { a, b -> (a.key as Integer) <=> (b.key as Integer) }.each { k, v ->
            section("Button ${k}: ${v}") {
                input "btnDev_${dni}_${k}", "capability.switch", title: "Devices", multiple: true, required: false, submitOnChange: true, width: 8
                input "btnAct_${dni}_${k}", "enum", title: "Action", options: [toggle: "Toggle", on: "Turn on", off: "Turn off"],
                    defaultValue: "toggle", required: false, submitOnChange: true, width: 4
            }
        }
    }
}

// Subscribes only to hubs with at least one assigned button.
private void subscribeButtons() {
    unsubscribe()
    (getBridge()?.getChildDevices() ?: []).each { hub ->
        String prefix = "btnDev_${hub.deviceNetworkId}_".toString()
        if (settings.keySet().any { it.startsWith(prefix) && settings[it] }) subscribe(hub, "pushed", "hubButtonHandler")
    }
}

def hubButtonHandler(evt) {
    def hub = evt.device
    String dni = hub.deviceNetworkId
    String btn = evt.value?.toString()
    List devs = buttonDevices(dni, btn)
    if (!devs) return
    String action = buttonAction(dni, btn)
    if (action == "toggle") action = devs.any { it.currentValue("switch") == "on" } ? "off" : "on"
    devs.each { d ->
        try {
            if (action == "on") d.on() else d.off()
        } catch (e) {
            logErr "button ${btn} on ${hub.getLabel()}: couldn't turn ${action} ${d.displayName}: ${e.message}"
        }
    }
    logInfo "${hub.getLabel()} button ${btn}: turned ${action} ${devs*.displayName.join(', ')}"
}

private void clearButtonSettings(String dni) {
    settings.keySet().findAll { it.startsWith("btnDev_${dni}_".toString()) || it.startsWith("btnAct_${dni}_".toString()) }.toList().each {
        app.removeSetting(it)
    }
}

// ============================================================================
// Add / Edit Hub
// ============================================================================

def addHubPage(params = [:]) {
    logDbg "addHubPage() name=${newHubName}, model=${newHubModel}, editing=${state.editingHubDni}, saveRequested=${state.hubSaveRequested}"
    if (state.hubPageCancelled) {
        ["hubPageCancelled", "editingHubDni", "editHubPrefilled", "hubSaveRequested", "discoveryStarted", "findError"].each { state.remove(it) }
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
            app.updateSetting("newHubBuiltIn", [value: editingHub.currentValue("mqttBroker") != "external", type: "bool"])
            app.updateSetting("newHubMqttHost", [value: editingHub.currentValue("mqttHost") ?: "", type: "text"])
            app.updateSetting("newHubMqttPort", [value: editingHub.currentValue("mqttPort") ?: "1883", type: "text"])
            app.updateSetting("newHubMqttUser", [value: editingHub.currentValue("mqttUser") ?: "", type: "text"])
            Integer bd = null
            try { bd = editingHub.getButtonDeviceId() } catch (e) { }
            app.updateSetting("newHubButtonDev", [value: bd != null ? bd.toString() : "auto", type: "enum"])
        }
        state.editHubPrefilled = true
    }

    boolean builtIn = newHubBuiltIn != false
    String pickedMac = newHubMacPick ?: newHubMac

    String saveError = null
    if (state.hubSaveRequested) {
        state.remove("hubSaveRequested")
        if (!editingHub && newHubName && newHubModel == "X1S" && newHubIp) {
            logInfo "adding X1S hub '${newHubName}'"
            createHttpHub(newHubName, newHubIp)
            clearHubSettings()
            return mainPage()
        } else if (!editingHub && newHubName && newHubModel == "X2" && pickedMac && (builtIn || newHubMqttHost)) {
            logInfo "adding X2 hub '${newHubName}'"
            createMqttHub(newHubName, pickedMac, builtIn, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            return mainPage()
        } else if (editingHub && newHubName && (editingHub.currentValue("hubModel") == "X1S" || builtIn || newHubMqttHost)) {
            logInfo "updating hub '${editingDni}'"
            updateExistingHub(editingHub, newHubName, builtIn, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass, newHubButtonDev)
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
    boolean hubReady = editingHub ?
        (newHubName && (editModel == "X1S" || builtIn || newHubMqttHost)) as boolean :
        (newHubName && ((newHubModel == "X1S" && newHubIp) || (newHubModel == "X2" && pickedMac && (builtIn || newHubMqttHost)))) as boolean

    dynamicPage(name: "addHubPage", title: editingHub ? "Edit ${editingHub.getLabel()}" : "Add a Sofabaton Hub", install: false, uninstall: false) {
        section {
            paragraph rawHtml: true, HIDE_DONE_CSS
            input name: "newHubName", type: "text", title: "Hub Name (e.g. Living Room)", submitOnChange: true
            paragraph "<span class='text-color-secondary' style='font-size:14px;'>Activities are named after the hub, e.g. <b>Living Room-Watch TV</b>, so they group together in device pickers.</span>"
            if (editingHub) {
                paragraph "Model: <b>${editModel}</b> <span class='text-color-secondary'>(remove and re-add to change)</span>"
            } else {
                input name: "newHubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], submitOnChange: true
            }
        }
        if (!editingHub && newHubModel == "X1S") {
            section {
                input name: "newHubIp", type: "text", title: "Hub IP Address (set a DHCP reservation first)", submitOnChange: true
            }
        }
        if (editModel == "X1S") {
            section {
                paragraph "IP Address: <b>${editingHub.currentValue('remoteIp') ?: 'not set'}</b> <span class='text-color-secondary'>(remove and re-add to change)</span>"
            }
        }
        if (showX2) {
            section("Broker") {
                paragraph rawHtml: true, warningMessageHtml("<b>Do X2 setup steps 1 and 2 first.</b> Enable Hubitat's MQTT broker, then connect the X2 to it in the Sofabaton app. " +
                    "Both are in Tips &amp; Troubleshooting.")
                input name: "newHubBuiltIn", type: "bool", title: "Use Hubitat's built-in MQTT broker (recommended)", defaultValue: true, submitOnChange: true
                if (builtIn) {
                    paragraph "<span class='text-color-secondary' style='font-size:14px;'>Hubitat connects to its own broker automatically. No login needed here.</span>"
                } else {
                    input name: "newHubMqttHost", type: "text", title: "Broker Host/IP", submitOnChange: true
                    input name: "newHubMqttPort", type: "text", title: "Broker Port", defaultValue: "1883", required: false
                    input name: "newHubMqttUser", type: "text", title: "Broker Username (blank if none)", required: false
                    input name: "newHubMqttPass", type: "password", title: "Broker Password (blank if none${editingHub ? ', or to keep the current one' : ''})", required: false
                }
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>All X2 hubs share one MQTT connection.</span>"
            }
            if (editingHub) {
                section {
                    paragraph "MAC ID: <b>${editingHub.deviceNetworkId}</b> <span class='text-color-secondary'>(remove and re-add to change)</span>"
                }
                section("Remote buttons") {
                    Map devs = [:]
                    try { devs = editingHub.getHubDevices() ?: [:] } catch (e) { }
                    Map opts = [auto: "Auto (learn from the first button press)"]
                    devs.each { k, v -> opts[k.toString()] = "${v} (ID ${k})".toString() }
                    input name: "newHubButtonDev", type: "enum", title: "Hubitat Control device", options: opts, submitOnChange: true
                    paragraph "<span class='text-color-secondary' style='font-size:14px;'>The Home Assistant Remote you made in the Sofabaton app for Hubitat buttons (Tips, X2 setup step 4). " +
                        "Leave on Auto unless the wrong device was learned.</span>"
                }
            } else {
                section("Find your X2") {
                    paragraph "<span class='text-color-secondary' style='font-size:14px;'>1. Tap <b>${state.discoveryStarted ? 'Check Again' : 'Find My X2'}</b>. " +
                        "2. On the Sofabaton remote, start or switch any activity. 3. Tap <b>Check Again</b>.</span>"
                    if (state.findError) paragraph "<b style='color:#c00'>${state.findError}</b>"
                    input name: "findX2Btn", type: "button", title: state.discoveryStarted ? "Check Again" : "Find My X2"
                    List found = bridge?.getDiscoveredMacs() ?: []
                    if (found) {
                        input name: "newHubMacPick", type: "enum", title: "X2 hubs found", options: found, submitOnChange: true
                    } else if (state.discoveryStarted) {
                        paragraph "<span style='color:#b26a00'>Nothing yet. Volume and channel presses don't count, it has to be an activity change.</span>"
                    }
                    input name: "newHubMac", type: "text", title: "Or enter the MAC manually (optional)", required: false, submitOnChange: true
                }
            }
        }
        section {
            if (saveError) paragraph "<b style='color:#c00'>${saveError}</b>"
            formButtons("Hub", editingHub ? "Save Changes" : "Add This Hub", hubReady)
        }
    }
}

private void clearHubSettings() {
    ["newHubName", "newHubModel", "newHubIp", "newHubMac", "newHubMacPick", "newHubBuiltIn",
     "newHubMqttHost", "newHubMqttPort", "newHubMqttUser", "newHubMqttPass", "newHubButtonDev"].each {
        app.removeSetting(it)
    }
}

// Password only overwritten when a new one is typed. A rename relabels default-named activities.
private void updateExistingHub(def hub, String name, boolean builtIn, String mqttHost, String mqttPort, String mqttUser, String mqttPass, String buttonDev = null) {
    String oldLabel = hub.getLabel()
    hub.setLabel(name)
    if (oldLabel && oldLabel != name) {
        try { hub.applyActivityLabels(oldLabel, name) } catch (e) { logErr "failed to relabel activities after renaming '${oldLabel}': ${e.message}" }
    }
    if (hub.currentValue("hubModel") == "X2") {
        if (buttonDev) {
            try { hub.setButtonDevice(buttonDev == "auto" ? null : (buttonDev as Integer)) } catch (e) { logErr "failed to set the Hubitat Control device: ${e.message}" }
        }
        hub.updateSetting("useBuiltInBroker", [value: builtIn, type: "bool"])
        if (!builtIn) {
            hub.updateSetting("mqttHost", [value: mqttHost, type: "text"])
            hub.updateSetting("mqttPort", [value: mqttPort, type: "text"])
            hub.updateSetting("mqttUser", [value: mqttUser ?: "", type: "text"])
            if (mqttPass) hub.updateSetting("mqttPass", [value: mqttPass, type: "password"])
        }
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

// Remote.updated() connects MQTT and requests the activity list.
private void createMqttHub(String name, String mac, boolean builtIn, String host, String port, String user, String pass) {
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
    hub.updateSetting("useBuiltInBroker", [value: builtIn, type: "bool"])
    if (!builtIn) {
        hub.updateSetting("mqttHost", [value: host, type: "text"])
        hub.updateSetting("mqttPort", [value: port, type: "text"])
        if (user) hub.updateSetting("mqttUser", [value: user, type: "text"])
        if (pass) hub.updateSetting("mqttPass", [value: pass, type: "password"])
    }
    hub.updated()
    pauseExecution(1500)   // let the first list reply land before mainPage redraws
    logDbg "createMqttHub() done, DNI $dni"
}

// ============================================================================
// Add / Edit Activity (X1S only. X2 activities come from the hub.)
// ============================================================================

def addActivityPage() {
    logDbg "addActivityPage() hub=${newActivityHub}, name=${newActivityName}, saveRequested=${state.activitySaveRequested}, editing=${state.editingActivityKey}"
    if (state.activityPageCancelled) {
        ["activityPageCancelled", "editingActivityKey", "editActivityPrefilled"].each { state.remove(it) }
        clearActivitySettings()
        return mainPage()
    }
    def bridge = getBridge()
    def x1sHubs = (bridge?.getChildDevices() ?: []).findAll { it.currentValue("hubModel") != "X2" }

    // state.editingActivityKey = "<hubDni>|<actDni>", set by the Edit button.
    String editingHubDni = null
    def editingActivity = null
    def editingHub = null
    if (state.editingActivityKey) {
        def keyParts = state.editingActivityKey.split(/\|/, 2)
        if (keyParts.length == 2) {
            editingHubDni = keyParts[0]
            editingHub = bridge?.getChildDevice(editingHubDni)
            editingActivity = editingHub?.getChildDevice(keyParts[1])
        }
    }

    if (editingActivity && !state.editActivityPrefilled) {
        app.updateSetting("newActivityHub", [value: editingHubDni, type: "enum"])
        app.updateSetting("newActivityName", [value: editingActivity.currentValue("sofabatonName") ?: shortName(editingActivity, editingHub?.getLabel()), type: "text"])
        app.updateSetting("newActivityUrlOn", [value: editingActivity.getSetting("webhookUrlOn") ?: "", type: "text"])
        app.updateSetting("newActivityUrlOff", [value: editingActivity.getSetting("webhookUrlOff") ?: "", type: "text"])
        app.updateSetting("newActivityBody", [value: editingActivity.currentValue("bodyValue") ?: "", type: "text"])
        state.editActivityPrefilled = true
    }

    def selectedHub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null

    // Suggest a body value from the name until the user sets one.
    if (newActivityName && !newActivityBody) {
        app.updateSetting("newActivityBody", [value: suggestBody(newActivityName), type: "text"])
    }
    String body = (settings.newActivityBody ?: "").trim()
    def clash = selectedHub?.getChildDevices()?.find {
        it.deviceNetworkId != editingActivity?.deviceNetworkId && body && it.currentValue("bodyValue")?.equalsIgnoreCase(body)
    }

    List missing = []
    if (!editingActivity && !newActivityHub) missing << "Sofabaton Hub"
    if (!newActivityName) missing << "Activity Name"
    if (!body) missing << "Body Value"

    String saveError = null
    if (state.activitySaveRequested) {
        state.remove("activitySaveRequested")
        if (clash) {
            saveError = "Body value '${body}' is already used by ${clash.getLabel()}. Pick a different one."
        } else if (missing) {
            saveError = "Still needed: ${missing.join(', ')}."
        } else if (editingActivity) {
            updateExistingActivity(editingActivity, editingHub, newActivityName, newActivityUrlOn, newActivityUrlOff, body)
            clearActivitySettings()
            state.remove("editingActivityKey")
            state.remove("editActivityPrefilled")
            return mainPage()
        } else {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, body)
            clearActivitySettings()
            return mainPage()
        }
    }

    dynamicPage(name: "addActivityPage", title: editingActivity ? "Edit ${editingActivity.getLabel()}" : "Add an X1S Activity", install: false, uninstall: false) {
        section {
            paragraph rawHtml: true, HIDE_DONE_CSS
            if (editingActivity) {
                paragraph "Hub: <b>${selectedHub?.getLabel() ?: editingHubDni}</b> <span class='text-color-secondary'>(remove and re-add to move it)</span>"
            } else {
                input name: "newActivityHub", type: "enum", title: "<b>Sofabaton Hub</b>", options: x1sHubs.collectEntries { [(it.deviceNetworkId): it.getLabel()] }, submitOnChange: true
            }
            input name: "newActivityName", type: "text", title: "<b>Activity Name</b> (e.g. Watch TV)", submitOnChange: true
            if (selectedHub && newActivityName) {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Device name: <b>${selectedHub.getLabel()}-${newActivityName}</b></span>"
            }
        }
        if (selectedHub) {
            section {
                paragraph rawHtml: true, groupHeaderHtml("SOFABATON &rarr; HUBITAT", "#26897a",
                    "Sent by the Sofabaton IP control device when this activity starts. Filled in from the name.")
                input name: "newActivityBody", type: "text", title: "<b>Body Value</b>", submitOnChange: true
                if (body) paragraph rawHtml: true, "<div style='font-size:14px'>In the Sofabaton app, set the body to " +
                    "<code style='background:#eef2f6;padding:2px 8px;border-radius:6px;font-size:15px'>${body}</code> " +
                    "<span class='text-color-secondary'>(X1S setup, step 3)</span></div>"
            }
            section {
                paragraph rawHtml: true, groupHeaderHtml("HUBITAT &rarr; SOFABATON", "#e8a33d",
                    "Optional. Lets Hubitat start and stop this activity. From <b>Turn on API</b> in the Sofabaton app (X1S setup, step 1). " +
                    "Leave blank if Hubitat only needs to follow the activity.")
                input name: "newActivityUrlOn", type: "text", title: "<b>Start Webhook URL</b> (optional)", submitOnChange: true
                input name: "newActivityUrlOff", type: "text", title: "<b>Stop Webhook URL</b> (optional)", required: false
            }
        }
        section {
            if (saveError) paragraph "<b style='color:#c00'>${saveError}</b>"
            boolean activityReady = (!missing && !clash) as boolean
            formButtons("Activity", editingActivity ? "Save Changes" : "Add This Activity", activityReady)
        }
    }
}

// Cancel and Save side by side at the bottom of Add/Edit pages, as links to mainPage styled
// as buttons (see HIDE_DONE_CSS). Save is gray until required fields are filled; pressing it
// anyway just shows what's missing.
private void formButtons(String kind, String saveTitle, boolean ready) {
    if (!state.formToken) state.formToken = now().toString()
    href name: "sbCancel${kind}", title: "Cancel", description: "", page: "mainPage",
        params: [formAction: "cancel${kind}", token: state.formToken], width: 3
    // "Ready" in the name lets HIDE_DONE_CSS color it green (href style lands on an outer box).
    href name: "sbSave${kind}${ready ? 'Ready' : ''}", title: saveTitle, description: "", page: "mainPage",
        params: [formAction: "save${kind}", token: state.formToken], width: 3
}

// Small colored pill plus one line of help, used as a group header on the Activity page.
private String groupHeaderHtml(String pill, String color, String help) {
    "<div style='display:flex;align-items:center;gap:10px;flex-wrap:wrap'>" +
        "<span style='background:${color};color:#fff;border-radius:8px;padding:2px 10px;font-size:0.75em;font-weight:bold;letter-spacing:0.03em'>${pill}</span>" +
        "<span class='text-color-secondary' style='font-size:14px'>${help}</span></div>"
}

private void clearActivitySettings() {
    ["newActivityHub", "newActivityName", "newActivityUrlOn", "newActivityUrlOff", "newActivityBody"].each {
        app.removeSetting(it)
    }
}

// "Watch Apple TV" -> "watchappletv". Letters and numbers only, so it's easy to type in the Sofabaton app.
private String suggestBody(String name) {
    return (name ?: "").toLowerCase().replaceAll(/[^a-z0-9]/, "")
}

private void createActivity(String hubDni, String name, String urlOn, String urlOff, String body) {
    def hub = getBridge()?.getChildDevice(hubDni)
    if (!hub) {
        logErr "cannot add activity '$name': hub not found"
        return
    }
    if (!hub.createActivityDevice(name, urlOn, urlOff, null, body)) {
        logErr "failed to create Activity device '$name'"
    } else {
        logInfo "added activity '$name'"
    }
}

private void updateExistingActivity(def activity, def hub, String name, String urlOn, String urlOff, String body) {
    activity.setLabel(hub ? "${hub.getLabel()}-${name}".toString() : name)
    activity.setHubInfo(name, true)
    activity.updateSetting("webhookUrlOn", [value: urlOn ?: "", type: "text"])
    activity.updateSetting("webhookUrlOff", [value: urlOff ?: "", type: "text"])
    activity.updateSetting("bodyValue", [value: body, type: "text"])
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
    if (btn == "findX2Btn") {
        state.remove("findError")
        boolean builtIn = newHubBuiltIn != false
        if (!builtIn && !newHubMqttHost) {
            state.findError = "Enter the broker host first."
            return
        }
        bridge?.startDiscovery(builtIn, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
        state.discoveryStarted = true
        return
    }
    if (btn.startsWith("editHub_")) {
        state.editingHubDni = btn - "editHub_"
        state.remove("editHubPrefilled")
        return
    }
    if (btn.startsWith("syncHub_")) {
        bridge?.getChildDevice(btn - "syncHub_")?.requestActivityList()
        pauseExecution(1500)   // let the reply land before the page redraws
        return
    }
    if (btn.startsWith("removeHub_")) {
        String dni = btn - "removeHub_"
        bridge?.removeRemoteDevice(dni)
        clearButtonSettings(dni)
        subscribeButtons()
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
            body: "<p>Find your hub below, then follow its setup in the sidebar.</p>" +
                "<table style='width:100%;border-collapse:collapse;font-size:14px;margin-bottom:16px'>" +
                "<tr style='background:#f5f7fa'><th style='text-align:left;padding:8px'>Hub</th><th style='text-align:left;padding:8px'>Hubitat knows the activity</th>" +
                "<th style='text-align:left;padding:8px'>Hubitat starts/stops it</th><th style='text-align:left;padding:8px'>Activities</th></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>X2</b></td><td style='padding:8px'>Yes, local</td><td style='padding:8px'>Yes, local</td><td style='padding:8px'>Added automatically</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>X1S</b></td><td style='padding:8px'>Yes, local</td><td style='padding:8px'>Optional, through Sofabaton's cloud</td><td style='padding:8px'>Added by you</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>X1</b></td><td style='padding:8px' colspan='3'>Not supported. See Common questions.</td></tr>" +
                "</table>" +
                "<p>Setup moves back and forth between two apps, so each step is tagged:</p>" +
                "<p>${HUBITAT_PILL}<br>This Hubitat app or a device page.</p>" +
                "<p>${SOFABATON_PILL}<br>The Sofabaton mobile app.</p>",
            checklist: [[title: "Know your model", detail: "It's printed on the hub, or shown in the Sofabaton app under the hub's settings."],
                        [title: "X2: Hubitat 2.5.2.126 or newer", detail: "Earlier versions can't receive MQTT messages. Check Settings &rarr; Hub Details."],
                        [title: "X2: Hubitat's MQTT broker", detail: "Enabled through MQTT Import Integration. See X2 setup, step 1."],
                        [title: "X1S: a static IP", detail: "Set a DHCP reservation on your router. If the IP changes later, the integration stops working with no error."]]],

        [id: "x2broker", label: "1. Enable the MQTT broker", title: "Enable Hubitat's MQTT broker", group: "X2 setup", icon: "pi-server",
            body: "<p>${HUBITAT_PILL}<br>Go to <b>Integrations &rarr; Add Built-In App &rarr; MQTT Import Integration</b>. Enable it and turn on <b>Use built-in MQTT service</b>.</p>" +
                "<p>Write down the port, username, and password it shows. You'll need them once, in the Sofabaton app. This app connects to the broker on its own.</p>"],
        [id: "x2connect", label: "2. Connect the X2", title: "Point the X2 at the broker", group: "X2 setup", icon: "pi-wifi",
            body: "<p>${SOFABATON_PILL}<br>Tap <b>Me</b> &rarr; <b>Connect to Home Assistant (MQTT broker)</b> &rarr; <b>Confirmed MQTT Installed</b>. " +
                "Enter your Hubitat hub's IP (<code>${location.hub.localIP}</code>), then the port, username, and password from step 1. " +
                "If it asks for Home Assistant account credentials, that's just the broker username and password. Confirm it shows connected before moving on.</p>",
            warning: "<b>Easy to skip, and nothing works without it.</b><br>Ignore the Home Assistant wording, you're pointing it at Hubitat's broker. " +
                "<b>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Home Assistant Remote</b> is a different feature, used later for remote buttons (step 4). Don't use it for this step."],
        [id: "x2add", label: "3. Add the hub", title: "Add the X2 hub", group: "X2 setup", icon: "pi-plus-circle",
            body: "<p>${HUBITAT_PILL}<br><b>Add a Hub</b> &rarr; Model <b>X2</b> &rarr; leave <b>Use Hubitat's built-in MQTT broker</b> on.</p>" +
                "<p>${HUBITAT_PILL}<br>Tap <b>Find My X2</b>. On the Sofabaton remote, start or switch any activity, then tap <b>Check Again</b>. Pick your hub from the list and tap <b>Add This Hub</b>.</p>" +
                "<p>Your activities appear automatically within a few seconds, named after the hub, e.g. <code>Living Room-Watch Apple TV</code>, " +
                "so they group together in Room Lighting and other device pickers. Tap <b>Done</b> on the main page when finished.</p>" +
                exampleBoxHtml("<b>Added or renamed an activity in the Sofabaton app?</b><br>Tap <b>Refresh Activities</b> on the hub. " +
                    "Names you set in Hubitat are never overwritten."),
            warning: "<b>Find My X2 needs an activity change.</b><br>Volume, channel, and other button presses never touch the network, so they won't show up."],
        [id: "x2buttons", label: "4. Remote buttons (optional)", title: "Control Hubitat with remote buttons", group: "X2 setup", icon: "pi-th-large",
            body: "<p>Turn spare keys on the remote (A, B, C, or a color key) into buttons that control anything in Hubitat.</p>" +
                "<p>${SOFABATON_PILL}<br><b>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Home Assistant Remote</b>. Name it (e.g. <b>Hubitat Control</b>) " +
                "and add a command for each button you want (e.g. <b>HE Button 1</b>, <b>HE Button 2</b>). The topic and payload are filled in for you.</p>" +
                "<p>${SOFABATON_PILL}<br>Add Hubitat Control to an activity, then <b>Customize remote buttons</b> &rarr; pick an unused key &rarr; " +
                "<b>Short press</b> and/or <b>Long press</b> &rarr; <b>Normal Key</b> &rarr; Device <b>Hubitat Control</b> &rarr; Command <b>HE Button 1</b>. " +
                "Repeat in every activity where the key should work.</p>" +
                "<p>${HUBITAT_PILL}<br>Press the key once. The hub learns Hubitat Control, loads its commands, and shows them on the main page, " +
                "e.g. <b>1 &middot; HE Button 1</b>. The command number is the button number.</p>" +
                "<p>${HUBITAT_PILL}<br>Tap <b>Button Actions</b> under the hub and pick what each button controls, with <b>Toggle</b>, <b>Turn on</b>, or <b>Turn off</b>. " +
                "For a Room Lighting scene, pick its activator device.</p>" +
                "<p>Need something fancier? Rule Machine and Button Controller can use the hub device (e.g. <b>Living Room</b>) as a button device too.</p>" +
                exampleBoxHtml("<b>Example, a lamp on key A:</b><br>" +
                    "&bull; Key A short press = <code>HE Button 1</code><br>" +
                    "&bull; Button Actions: HE Button 1 &rarr; Living Room Lamp, Toggle<br>" +
                    "Want a dedicated off? Set key A long press to <code>HE Button 3</code>, then HE Button 3 &rarr; the lamp, Turn off."),
            warning: "Keys only send while you're in an activity they're assigned in. Added or renamed a command? Tap <b>Refresh Activities</b>."],

        [id: "x1swebhook", label: "1. Webhook URL (optional)", title: "Get the activity's webhook URL (optional)", group: "X1S setup", icon: "pi-link",
            body: "<p>${SOFABATON_PILL}<br>Open the activity, turn on <b>Turn on API</b> (just <b>API</b> in some app versions), and copy the webhook URL, " +
                "a long <code>https://app1.sofabaton.com/...</code> link. Some versions give separate start and stop URLs, others just one.</p>" +
                "<p>This is how Hubitat starts and stops the activity. Skip it if Hubitat only needs to know which activity is running, " +
                "for example to dim lights when a movie starts. Those activities show <b>Follow only</b> on the main page.</p>"],
        [id: "x1sadd", label: "2. Add hub and activities", title: "Add the X1S hub and its activities", group: "X1S setup", icon: "pi-plus-circle",
            body: "<p>${HUBITAT_PILL}<br><b>Add a Hub</b> &rarr; Model X1S &rarr; enter the hub's static IP.</p>" +
                "<p>${HUBITAT_PILL}<br><b>Add an Activity</b> &rarr; pick the hub, name it, and paste the webhook URL from step 1 if you got one. " +
                "A space in the URL is normal, it's encoded automatically.</p>" +
                "<p>Note the <b>Body Value</b> it suggests, e.g. <code>watchappletv</code>. You'll need it in step 3. Tap <b>Done</b> on the main page when finished.</p>"],
        [id: "x1sreport", label: "3. Report activity changes", title: "Let the X1S report activity changes", group: "X1S setup", icon: "pi-mobile",
            body: "<p>${SOFABATON_PILL}<br><b>Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Create a virtual device for IP control</b>. " +
                "URL <code>http://${location.hub.localIP}:39501/</code>, method <b>PUT</b>, body = the activity's Body Value.</p>" +
                "<p>${SOFABATON_PILL}<br>Add that command to the activity's start sequence, so the hub tells Hubitat whenever the activity starts. Repeat for each activity.</p>" +
                exampleBoxHtml("<b>Example, an Apple TV activity:</b><br>" +
                    "&bull; Activity name in Hubitat: <code>Watch Apple TV</code><br>" +
                    "&bull; Body Value (shown on its pill on the main page): <code>watchappletv</code><br>" +
                    "&bull; IP control body in the Sofabaton app: <code>watchappletv</code>"),
            warning: "The body must match the Body Value exactly. If it doesn't, the remote still controls your gear, Hubitat just won't know the activity changed."],
        [id: "x1smatch", label: "4. Button events (optional)", title: "Button events (optional)", group: "X1S setup", icon: "pi-th-large",
            body: "<p>Activities sync from their Body Value alone. Button slots are only for rules that react to a button press.</p>" +
                "<p><b>Buttons 1-10</b> fire when the remote sends a plain number. <b>Buttons 11-20</b> are slots on the hub device's Preferences tab, " +
                "entered as <code>matchString|Description</code>.</p>" +
                exampleBoxHtml("<b>Example:</b> a body of <code>lightsdim</code> and User 1 (11) set to <code>lightsdim|Dim Lights</code> " +
                    "fires button 11 on the hub device, which a rule can use.")],

        [id: "x2timing", label: "X2 commands and timing", title: "X2 commands and timing", group: "Troubleshooting", icon: "pi-clock",
            body: "<p>The hub confirms a command only after its start or stop sequence finishes, usually <b>8 to 10 seconds</b>. " +
                "Until then the activity's <b>activityStatus</b> shows <b>starting</b> or <b>stopping</b>, and the switch flips once the hub confirms. " +
                "If no confirmation arrives within 20 seconds, the integration resyncs from the hub.</p>" +
                "<p>Turning off any activity powers off the hub, the same as the remote's Power Off key. Turning off an activity that's already off does nothing. " +
                "To change activities, just turn on the new one.</p>"],
        [id: "x2missing", label: "X2 activities missing", title: "X2 activities missing or out of date", group: "Troubleshooting", icon: "pi-exclamation-triangle",
            body: "<p>Activity lists and button names refresh automatically every 3 hours. For an immediate update, tap <b>Refresh Activities</b> on the hub. If the banner says <b>Waiting for your X2</b>, the hub isn't replying: " +
                "recheck X2 setup step 2, then run <b>Force Reconnect</b> on the Bridge device.</p>" +
                "<p>An activity deleted in the Sofabaton app shows <b>Not on hub</b>, with a Remove button on the main page. It's never deleted automatically, so your rules don't break unexpectedly.</p>"],
        [id: "traffic", label: "What creates MQTT traffic", title: "What creates MQTT traffic", group: "Troubleshooting", icon: "pi-info-circle",
            body: "<p>From the remote: starting or switching an activity, Power Off, and keys assigned to your Hubitat Control remote (X2 setup, step 4). " +
                "Other button presses within an activity (volume, channel, play/pause) never touch the network.</p>" +
                "<p>X1S never uses MQTT. It reports over local HTTP, and takes commands through Sofabaton's cloud webhook if you added one.</p>"],
        [id: "faq", label: "Common questions", title: "Common questions", group: "Troubleshooting", icon: "pi-question-circle",
            body: "<p><b>Why isn't the original X1 supported?</b><br>The X1 can only send its IP control messages to port 8060, and Hubitat only listens on port 39501, " +
                "so an X1 can't report to Hubitat directly. The X1S and X2 don't have this limit.</p>" +
                "<p><b>Do I need the X1S webhook?</b><br>No. Without it, Hubitat still knows which activity is running (<b>Follow only</b>). " +
                "Add it if you also want Hubitat to start and stop activities.</p>" +
                "<p><b>Can I rename an activity?</b><br>Yes, on its device page. New activities are named <b>Hub-Activity</b> so they group together in pickers; " +
                "a name you set yourself is never overwritten. The Sofabaton name is kept in the sofabatonName attribute. Renaming a hub in Edit Hub updates its default-named activities.</p>" +
                "<p><b>Where does an X1S webhook URL go?</b><br>The activity's Start and Stop Webhook URL fields, when adding it or later through Edit.</p>" +
                "<p><b>Can I change a hub's model, MAC, or IP?</b><br>No, those set the device ID. Remove the hub and add it again.</p>"],

        [id: "removal", label: "Removing hubs", title: "Remove hubs and activities safely", group: "Devices", icon: "pi-trash",
            body: "<p>Use the Remove buttons on the main page, not Hubitat's Devices page. Removing a hub also removes its activities.</p>" +
                "<p>X2 activities come from the hub, so remove them in the Sofabaton app first. Then tap Refresh Activities and use the Remove button that appears.</p>"],

        [id: "logging", label: "Log levels", title: "Log levels", group: "Logging", icon: "pi-file",
            body: "<p>The <b>Log level</b> on the main page is the only logging setting. It applies to this app and every Sofabaton device.</p>" +
                "<p>" + logLevelPill("Errors Only") + " Default. Warnings and errors only.</p>" +
                "<p>" + logLevelPill("Normal") + " Errors, plus activities turning on and off, button presses, and hubs or activities added or removed.</p>" +
                "<p>" + logLevelPill("Full") + " Everything, including raw MQTT traffic. Use while troubleshooting. Turns itself off after 30 minutes.</p>" +
                "<p>Every Sofabaton log line appears under <b>Sofabaton Integration</b> on Hubitat's <b>Logs</b> page, labeled by where it came from, " +
                "e.g. <code>[Bridge]</code>, <code>[Sofabaton LR Hub]</code>, or an activity name. <b>View logs</b> in the banner shows all of it in one place.</p>"]
    ]
}

// ============================================================================
// UI template (HubitatAppUiTemplate, from Reolink v1.6.1)
// ============================================================================

private void helpAndSupportSection() {
    section(title: "<b>Help & Support</b>", sectionClass: "app-main-support") {
        href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
            page: "tipsPage", description: "Setup guides for X2 and X1S", width: 4, style: "margin:8px;"
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
  <span class='min-w-0 flex-1'>
    <span class='block text-blue-700 font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
  <i class='fa-regular fa-external-link text-blue-700 flex-shrink-0'></i>
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
  a.sb-pill { display: inline-flex; align-items: center; background: #e3eaf3; border-radius: 20px; padding: 6px 14px;
    font-size: 0.9em; color: inherit; text-decoration: none; cursor: pointer; transition: background 0.15s; }
  a.sb-pill:hover { background: #cfdcec; }
  a.sb-link { color: #1565c0; text-decoration: none; }
  .app-main-content :is([name*='editHub_'],[name*='syncHub_'],[name*='removeHub_'],[name*='editAct_'],[name*='removeAct_'],
    [id*='editHub_'],[id*='syncHub_'],[id*='removeHub_'],[id*='editAct_'],[id*='removeAct_']) {
    font-size: 13px !important; padding: 3px 12px !important; min-height: 0 !important; height: auto !important;
    line-height: 1.5 !important; margin: 2px 0 !important; box-shadow: none !important; border: 1px solid #d0d7de !important;
    white-space: nowrap; }
  .mdl-cell:has(> button.hrefElem[name^='_action_href_btnActs_']) {
    background: transparent !important; border: 0 !important; box-shadow: none !important; padding: 0 !important; }
  .app-main-content button.hrefElem[name^='_action_href_btnActs_'] {
    width: auto !important; min-width: 0 !important; display: inline-block !important;
    font-size: 13px !important; padding: 3px 12px !important; min-height: 0 !important; height: auto !important;
    line-height: 1.5 !important; margin: 2px 0 !important; box-shadow: none !important; border: 1px solid #d0d7de !important;
    border-radius: 2px !important; background: #eeeeee !important; color: #333 !important; text-align: center !important; white-space: nowrap; }
  .app-main-content button.hrefElem[name^='_action_href_btnActs_']:hover { background: #e0e0e0 !important; }
  .app-main-content button.hrefElem[name^='_action_href_btnActs_'] span { font-size: 13px !important; font-weight: 400 !important; color: #333 !important; }
  .app-main-content button.hrefElem[name^='_action_href_btnActs_']::before,
  .app-main-content button.hrefElem[name^='_action_href_btnActs_']::after,
  .app-main-content button.hrefElem[name^='_action_href_btnActs_'] .state-incomplete-text,
  .app-main-content button.hrefElem[name^='_action_href_btnActs_'] .state-complete-text { display: none !important; }
  .sb-hub-end { border-bottom: 1px solid #e0e0e0; margin: 4px 0 18px; }
  a.sb-link:hover { text-decoration: underline; }
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
  <div class='text-color-secondary text-base mb-3'>One setting for the app and every Sofabaton device.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus activities turning on and off, button presses, and hubs or activities added or removed.", current)}
  ${logLevelDetailHtml("Full", "Everything, including raw MQTT traffic. Use while troubleshooting. Turns off after 30 minutes.", current)}
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
