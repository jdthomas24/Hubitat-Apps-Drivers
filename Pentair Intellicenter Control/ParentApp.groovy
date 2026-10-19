/*
    Pentair IntelliCenter - Parent App
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Setup and settings only. The Bridge device holds the WebSocket to the controller, discovers the
      equipment and creates the Body, Pump, Chlorinator and generic circuit/sensor devices under itself.
     -Equipment page: one toggle per item the Bridge discovered (default on). Off removes the device and
      keeps it from coming back; deleting a device from the Devices page counts as off. The Bridge owns
      the excluded list. syncEquipment() copies Bridge-side exclusions (deleted devices) into the toggles,
      then pushes the toggles to the Bridge. state.pushedExcluded remembers what the app last sent, so it
      can tell a Devices-page deletion from a toggle the user just turned back on.
     -Log level is the only logging control. App -> Bridge -> devices; every log line comes back to
      childLog() and shows under this app labeled by device. Full turns off after 30 minutes.
     -1.6.x HTTP endpoints (mappings) removed: nothing used them.
     -UI follows HubitatAppUiTemplate (Reolink, UI design by gopher.ny). Dark mode uses the hub's theme
      classes (hub-text-*, app-ui-button*, app-ui-links, surface-*, p-message/p-tag) and var() tokens with
      fallbacks; legacyLightThemeCss() covers pre-2.5.3 firmware (pattern by vurvantsev, Reolink 1.6.13).
*/

definition(
    name: "Pentair IntelliCenter",
    namespace: "intellicenter",
    author: "jdthomas24",
    description: "Local control of a Pentair IntelliCenter pool controller",
    category: "Convenience",
    menu: "Integrations",
    installOnOpen: true,
    iconUrl: "",
    iconX2Url: ""
)

import groovy.transform.Field

@Field static final String APP_NAME      = "Pentair IntelliCenter"
@Field static final String APP_VERSION   = "1.7.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS      = ["Errors Only", "Normal", "Full"]
@Field static final List KINDS = [[k: "body", label: "Pool and spa"], [k: "pump", label: "Pumps"], [k: "circuit", label: "Circuits, features and groups"],
                                  [k: "sensor", label: "Sensors"], [k: "chem", label: "Chlorinator"]]

preferences {
    page(name: "mainPage")
    page(name: "equipmentPage")
}

private String bridgeDni() { "intellicenter-bridge-${app.id}" }
private getBridge() { getChildDevice(bridgeDni()) }

// ============================================================================
// Pages
// ============================================================================

def mainPage() {
    def bridge = getBridge()
    armLogRevert()
    if (bridge && state.pushedLogLevel != currentLogLevel()) pushLogLevel()
    Map eq = bridge ? syncEquipment(bridge) : [:]
    Map banner = bannerState(bridge)
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("Controller")
            input "intellicenterIP", "text", title: "IntelliCenter IP address", description: "e.g. 192.168.1.50", required: true, width: 8
            input "intellicenterPort", "number", title: "Port", description: "6680, or 6681 on some IntelliCenter 2 systems",
                defaultValue: 6680, required: true, width: 4
            if (bridge) {
                int total = (eq.catalog ?: [:]).size()
                int on = (eq.catalog ?: [:]).keySet().count { !(eq.excluded ?: []).contains(it) } as int
                href name: "toEquipment", title: "<i class='fa-solid fa-water-ladder mr-2' aria-hidden='true'></i>Equipment",
                    description: total ? "${on} of ${total} included. Choose which devices Hubitat creates." :
                        "Appears once the Bridge connects and reads your controller.",
                    page: "equipmentPage", width: 12, style: "margin:8px;"
                paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>Bridge device: " +
                    "<a href='/device/edit/${bridge.id}' target='_blank' class='hub-text-link no-underline'>${esc(bridge.displayName)} " +
                    "<i class='fa-regular fa-external-link' style='font-size:0.8em'></i></a></div>"
            } else {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Enter the IP address, then tap <b>Done</b>. " +
                    "The Bridge connects and adds your equipment in about 20 seconds.</span>"
            }
        }
        section(sectionClass: "app-ui-links app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
            label title: "App name", required: false
        }
        section(title: "<b>Help & Support</b>", sectionClass: "app-ui-links app-main-support") {
            paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
                "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 6
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
                "Buy Me a Coffee", "Support development"), width: 6
        }
        versionFooterSection()
    }
}

def equipmentPage() {
    def bridge = getBridge()
    Map eq = bridge ? syncEquipment(bridge) : [:]
    Map catalog = (eq.catalog ?: [:]) as Map
    dynamicPage(name: "equipmentPage", title: "Equipment", install: false, uninstall: false) {
        section {
            paragraph rawHtml: true, "<style>${appPageSpacingCss()}</style>" +
                "<div class='text-color-secondary' style='font-size:14px;'>Turn off anything you don't want in Hubitat. " +
                "Its device is removed right away and won't come back. Turning it back on recreates it in a few seconds. " +
                "Deleting a device from the Devices page turns it off here too.</div>"
        }
        if (!catalog) {
            section { paragraph "Nothing yet. The list fills in once the Bridge connects and reads your controller." }
        }
        KINDS.each { kind ->
            Map items = catalog.findAll { k, v -> v.k == kind.k }
            if (!items) return
            int on = items.count { k, v -> included(k as String) } as int
            section("<b>${kind.label}</b> <span class='text-color-secondary' style='font-weight:400;font-size:14px;'>${on} of ${items.size()} included</span>") {
                items.sort { a, b -> a.value.n <=> b.value.n }.each { objnam, v ->
                    input "inc_${objnam}", "bool", title: "${esc(v.n)} <span class='text-color-secondary' style='font-size:13px;'>${objnam}</span>",
                        defaultValue: true, submitOnChange: true, width: 6
                }
            }
        }
    }
}

// Bridge exclusions (devices deleted from the Devices page) -> toggles, then toggles -> Bridge.
private Map syncEquipment(bridge) {
    Map catalog = [:]
    List bridgeEx = []
    try {
        catalog = bridge.getCatalog() ?: [:]
        bridgeEx = bridge.getExcluded() ?: []
    } catch (e) {
        logDbg "equipment list not available yet: ${e.message}"
        return [catalog: [:], excluded: []]
    }
    List pushed = (state.pushedExcluded ?: []) as List
    (bridgeEx - pushed).each { objnam -> app.updateSetting("inc_${objnam}", [value: false, type: "bool"]) }
    List want = catalog.keySet().collect { it.toString() }.findAll { !included(it) }
    want += bridgeEx.findAll { !catalog.containsKey(it) && !pushed.contains(it) }   // excluded before it was listed
    want = want.unique().sort()
    if (want != bridgeEx.collect { it.toString() }.unique().sort()) {
        try { bridge.setExcluded(want) } catch (e) { logErr "could not update the Bridge's equipment list: ${e.message}" }
    }
    state.pushedExcluded = want
    return [catalog: catalog, excluded: want]
}

private boolean included(String objnam) { settings["inc_" + objnam] != false }

private Map bannerState(bridge) {
    String ip = esc(intellicenterIP ?: "")
    def port = intellicenterPort ?: 6680
    String status = bridge?.currentValue("connectionStatus")?.toString()
    int n = bridge ? (bridge.getChildDevices()?.size() ?: 0) : 0
    if (!intellicenterIP || status?.startsWith("Not Configured")) {
        return [ok: false, title: "Setup required", summary: "Enter your controller's IP address below, then tap <b>Done</b>."]
    }
    if (!bridge) return [ok: false, title: "Not connected yet", summary: "${ip}:${port} &middot; tap <b>Done</b> to connect."]
    if (status == "Connected") return [ok: true, title: "Integration ready", summary: "${ip}:${port} &middot; ${countText(n, 'device')}"]
    return [ok: false, title: "Controller offline", summary: "Can't reach ${ip}:${port}. The Bridge retries every 2 minutes."]
}

// ============================================================================
// Lifecycle
// ============================================================================

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
    def bridge = getBridge()
    if (!bridge) {
        try {
            bridge = addChildDevice("intellicenter", "Pentair IntelliCenter Bridge", bridgeDni(), [label: "IntelliCenter Bridge", isComponent: false])
            logInfo "created the IntelliCenter Bridge"
        } catch (e) {
            logErr "could not create the Bridge: ${e.message}. Is the Pentair IntelliCenter Bridge driver installed?"
            return
        }
    }
    bridge.updateSetting("ipAddress",  [value: intellicenterIP ?: "", type: "text"])
    bridge.updateSetting("portNumber", [value: intellicenterPort ?: 6680, type: "number"])
    ["debugMode", "endpointBase"].each { app.removeSetting(it) }
    pushLogLevel()
    armLogRevert()
    runIn(2, "initBridge")
}

def initBridge() { getBridge()?.initialize() }

def uninstalled() {
    def bridge = getBridge()
    bridge?.getChildDevices()?.each {
        try { bridge.deleteChildDevice(it.deviceNetworkId) } catch (e) { log.warn "${APP_NAME}: could not delete ${it.deviceNetworkId}: ${e.message}" }
    }
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) } catch (e) { log.warn "${APP_NAME}: could not delete ${it.deviceNetworkId}: ${e.message}" }
    }
}

// ============================================================================
// Logging
// ============================================================================

private String currentLogLevel() { LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only" }

private void pushLogLevel() {
    try { getBridge()?.setLogLevel(currentLogLevel()) } catch (e) { }
    state.pushedLogLevel = currentLogLevel()
}

private void logErr(String msg)  { log.error "${APP_NAME}: ${msg}" }
private void logInfo(String msg) { if (currentLogLevel() != "Errors Only") log.info "${APP_NAME}: ${msg}" }
private void logDbg(String msg)  { if (currentLogLevel() == "Full") log.debug "${APP_NAME}: ${msg}" }

// Called by the Bridge for itself and every pool device. They already filter by level.
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

private String esc(v) {
    (v == null ? "" : v.toString()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;")
}

// ============================================================================
// UI template (HubitatAppUiTemplate)
// ============================================================================

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "p-message p-message-success app-message" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle hub-text-success" : "fa-solid fa-exclamation-triangle hub-text-warning"
    """
<style>
  ${appPageSpacingCss()}
  .app-main-content { float: left; width: calc(62% - 8px); }
  .app-main-settings { float: right; width: 38%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); padding-left: 8px; box-sizing: border-box; }
  .app-main-content > .mdl-grid, .app-main-settings > .mdl-grid { padding: 4px 0 !important; }
  .app-main-support { clear: both; }
  @media (max-width: 1000px) {
    .app-main-content, .app-main-settings { float: none; width: 100%; border-left: 0; padding-left: 0; }
  }
</style>
<div class='flex align-items-center justify-content-between gap-3 ${tone} border-1 border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='fa-solid fa-water-ladder text-2xl' aria-hidden='true'></i></div>
    <div class='min-w-0'>
      <div class='app-heading font-semibold'><i class='${icon} mr-2' aria-hidden='true'></i>${title}</div>
      <div class='text-color-secondary mt-1' style='font-size:14px;'>${summary}</div>
    </div>
  </div>
  <a href='/logs?tab=past&amp;appId=${app.id}' target='_blank' class='hub-text-link font-semibold white-space-nowrap no-underline'>
    View logs <i class='fa-regular fa-external-link'></i>
  </a>
</div>
"""
}

private String columnHeader(String title) {
    "<div class='app-heading font-semibold'>${title}</div>"
}

private String supportLinkHtml(String url, String iconClass, String title, String subtitle) {
    """
<a href='${url}' target='_blank' rel='noopener noreferrer'
   class='flex align-items-center gap-3 border-1 surface-border border-round px-3 py-2 text-color no-underline'>
  <i class='${iconClass} hub-text-link text-xl flex-shrink-0'></i>
  <span class='min-w-0 flex-1'>
    <span class='block hub-text-link font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
  <i class='fa-regular fa-external-link hub-text-link flex-shrink-0'></i>
</a>
"""
}

private void versionFooterSection() {
    section {
        paragraph "<div class='text-center text-color-secondary text-xs mt-2'>${APP_NAME} v${APP_VERSION}</div>"
    }
}




private String countText(int count, String singular, String plural = null) {
    "${count} ${count == 1 ? singular : (plural ?: singular + 's')}"
}

private String loggingDetailsPopupHtml() {
    String current = currentLogLevel()
    String details = """
<div class='ic-dialog text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>One setting for the app, the Bridge and every pool device.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only, such as the controller going offline or a command that couldn't be sent.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus connections, commands, equipment turning on and off, set point and heat source changes, and devices added or removed.", current)}
  ${logLevelDetailHtml("Full", "Everything, including every message to and from the controller. Use while troubleshooting. Turns off after 30 minutes.", current)}
</div>
"""
    String js = groovy.json.JsonOutput.toJson(details)
        .replace("&", "&amp;").replace("'", "&#39;").replace("<", "&lt;").replace(">", "&gt;")
    """
<a href='#' onclick='window.alertHubitat(${js}); return false;'
   class='flex align-items-center justify-content-between gap-3 surface-ground border-1 surface-border border-round px-3 py-2 h-full text-color no-underline'>
  <span>
    <span class='block font-semibold'>Logging details</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>What each level records</span>
  </span>
  <i class='pi pi-chevron-right hub-text-link'></i>
</a>
"""
}

private String logLevelDetailHtml(String name, String description, String current) {
    String badge = name == current ? "<span class='hub-text-success text-xs font-bold'>Current</span>" : ""
    String tone = name == "Errors Only" ? "p-tag p-tag-danger" :
        name == "Full" ? "p-tag p-tag-warning" : "p-tag p-tag-info"
    """
<div class='border-1 surface-border border-round px-3 py-2 mb-2'>
  <div class='flex align-items-center justify-content-between gap-3 mb-1'>
    <span class='${tone} border-round-xl px-2 py-1 text-xs font-bold'>${name}${name == "Errors Only" ? " (default)" : ""}</span>
    ${badge}
  </div>
  <div class='text-base text-color-secondary'>${description}</div>
</div>
"""
}

private String logLevelPill(String level) {
    def tones = ["Errors Only": "p-tag p-tag-danger", "Normal": "surface-ground text-color-secondary", "Full": "p-tag p-tag-warning"]
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
  .app-message .text-color-secondary, .app-message .hub-text-link, .app-message .hub-text-warning,
  .app-message .hub-text-success, .app-message .hub-text-danger { color: inherit !important; }
  div.panel-body { padding: 0 !important; margin-left: -0.5em; margin-right: -0.5em; }
  fieldset#fieldsetAppButtons { margin-left: 0.5em; margin-right: 0.5em; }
"""
}

// Light fallback for firmware without the hub's semantic app classes (pre-2.5.3); scoped to this app's form
// and logging dialog. Capable firmware marks data-hubitat-app-ui-semantics="1" and these rules step aside.
private String legacyLightThemeCss() {
    """
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-link { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-success { color: var(--hubitat-success-color, #356700) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-warning { color: var(--hubitat-warning-color, #8a5a00) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-danger { color: var(--hubitat-validation-color, #b71c1c) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-links .hrefElem { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-links .hrefElem > .state-incomplete-text,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-links .hrefElem > .state-complete-text { color: var(--text-color-secondary, #616161); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button { background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 1px solid transparent; border-radius: 4px; box-shadow: 0 2px 2px rgba(0,0,0,.14); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:enabled:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-primary,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-primary:enabled:hover { background: var(--hubitat-brand-background, #81bc00); color: var(--hubitat-on-brand, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-secondary { background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border-color: var(--hubitat-control-border, #b0bec5); box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-danger,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-danger:enabled:hover { background: var(--hubitat-danger-background, #d32f2f); color: var(--hubitat-on-danger, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:enabled:active { box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:disabled { background: var(--hubitat-control-background, #eee); color: var(--text-color-secondary, #616161); opacity: .6; cursor: not-allowed; box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem { background: transparent; color: var(--hubitat-link-color, #1565c0) !important; border: 0; border-left: 3px solid transparent; border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem::before { display: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:not(:disabled):not(:has(.app-ui-navigation-selected)):hover { background: var(--surface-hover, #f3f6fa); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:has(.app-ui-navigation-selected) { background: var(--hubitat-selection-background, #eaf2fc); border-left-color: var(--hubitat-link-color, #1565c0); font-weight: 600; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:disabled { opacity: .6; cursor: not-allowed; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:focus-visible,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
"""
}

private String tileSecondaryTextCss() {
    legacyLightThemeCss() + "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

