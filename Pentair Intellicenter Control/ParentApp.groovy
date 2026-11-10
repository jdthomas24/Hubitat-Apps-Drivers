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
     -Tips & Troubleshooting page (1.8.1) is the same code as Sofabaton and Bambu; it scrolls to the top on open.
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
@Field static final String APP_VERSION   = "1.8.2"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS      = ["Errors Only", "Normal", "Full"]
@Field static final List KINDS = [[k: "body", label: "Pool and spa"], [k: "pump", label: "Pumps"], [k: "circuit", label: "Circuits, features and groups"],
                                  [k: "sensor", label: "Sensors"], [k: "chem", label: "Chlorinator"]]

preferences {
    page(name: "mainPage")
    page(name: "equipmentPage")
    page(name: "tipsPage")
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
        section(sectionClass: "app-main-overview") {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("Controller")
            input "intellicenterIP", "text", title: "IntelliCenter IP address", description: "e.g. 192.168.1.50", required: true, width: 8
            input "intellicenterPort", "number", title: "Port", description: "6680, or 6681 on some IntelliCenter 2 systems",
                defaultValue: 6680, required: true, width: 4
            paragraph "<span class='text-color-secondary' style='font-size:13px;'>Supported firmware: IntelliCenter 1.064 and later, " +
                "including 3.x. The 2.x firmware was withdrawn by Pentair and isn't supported; update to 3.x first.</span>"
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
            href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting", page: "tipsPage",
                description: "Setup, firmware, heat and rules", width: 4, style: "margin:8px;"
            paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
                "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 4
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
                "Buy Me a Coffee", "Support development"), width: 4
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
    if (status == "Waiting for hub startup") return [ok: false, title: "Starting up", summary: "The hub just restarted. The Bridge connects to ${ip}:${port} 3 minutes after startup."]
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
// Tips & Troubleshooting (same page as Sofabaton and Bambu)
// ============================================================================

@Field static final String DEFAULT_TIP_TOPIC = "setup"

/** Fields: id, label, title, group, icon (PrimeIcons), body; optional checklist, warning, append. */
private List tipsTopics() {
    [
        [id: "setup", label: "Setup", title: "Connecting to your controller", group: "Getting started", icon: "pi-cog",
            body: "<p>Enter the controller's IP address and tap <b>Done</b>. The Bridge connects and adds your equipment in about 20 seconds.</p>",
            checklist: [
                [title: "IP address", detail: "Your router's device list (look for IntelliCenter or Pentair), or the Pentair app under Settings, Status, Network. A DHCP reservation keeps it from changing."],
                [title: "Port", detail: "Leave it at 6680. Some IntelliCenter 2 systems use 6681."],
                [title: "Same network", detail: "The controller and the hub need to be on the same network, with nothing blocking the port."],
                [title: "Bridge device", detail: "Holds the connection and creates every pool device under itself. Its connectionStatus shows Connected once it's talking to the controller."]
            ]],
        [id: "firmware", label: "Supported firmware", title: "Supported controller firmware", group: "Getting started", icon: "pi-verified",
            body: "<p>IntelliCenter firmware <b>1.064 and later</b>, including <b>3.x</b>.</p>",
            checklist: [
                [title: "2.x isn't supported", detail: "Pentair withdrew the 2.x firmware line. If your panel is on 2.x, update it to the current 3.x release first."],
                [title: "Heat control on 3.x", detail: "Reworked in 1.8.0 for 3.x panels and confirmed on 3.014 with a gas heater."]
            ]],
        [id: "equipment", label: "Equipment", title: "Choosing your equipment", group: "Getting started", icon: "pi-list",
            body: "<p>The app's <b>Equipment</b> page lists everything the Bridge found on your controller, grouped by kind. Everything starts turned on.</p>",
            checklist: [
                [title: "Turn something off", detail: "Its device is removed right away and won't come back."],
                [title: "Turn it back on", detail: "The device is recreated in a few seconds."],
                [title: "Deleted from the Devices page", detail: "Counts as turned off, so it stays gone. Turn it back on here to recreate it."]
            ]],
        [id: "heat", label: "Heat control", title: "Heating the pool or spa", group: "How it works", icon: "pi-sun",
            body: "<p>Heat is controlled from the Pool and Spa devices.</p>",
            checklist: [
                [title: "Heat And Start", detail: "Sets the set point and heat source in one step and turns the body on. It uses the last heater used, else the first one your panel lists."],
                [title: "Stop Heat", detail: "Turns heat off and leaves the pump running. So does Set Heat Source Off."],
                [title: "heatSources", detail: "The heat choices your panel offers for that body, using your heater names. Use any of them with Set Heat Source."],
                [title: "heaterMode", detail: "Heating, Idle (heat is on but the heater isn't firing right now) or Off. heatSource shows the source that's selected."],
                [title: "Heat Lock", detail: "Locked blocks every heat change from Hubitat (set point, source, Heat And Start). Stop Heat still works."]
            ]],
        [id: "pumps", label: "Pumps and chlorinator", title: "Pumps and the chlorinator", group: "How it works", icon: "pi-sync",
            body: "<p>Both report what the controller tells them; changes show up as they happen.</p>",
            checklist: [
                [title: "Pump", detail: "RPM, GPM and watts when the pump reports them. Set Speed changes the RPM of the running program, within the limits in the pump's Preferences."],
                [title: "Pump on and off", detail: "The pump runs with its body, so turn it on and off from the Pool or Spa device."],
                [title: "Chlorinator", detail: "IntelliChlor salt level, pool and spa output and superchlorinate, read-only. It only appears when the controller reports a salt cell."]
            ]],
        [id: "rules", label: "Rules and dashboards", title: "Rules and dashboards", group: "Using it", icon: "pi-bolt",
            body: "<p>Some starting points:</p>",
            checklist: [
                [title: "Spa ready", detail: "Spa temperature reaches the set point: send a notification."],
                [title: "Heater running", detail: "heaterMode becomes Heating or goes back to Idle."],
                [title: "Lights and features", detail: "Each circuit, feature and group is a switch, so scenes and rules can turn them on and off."],
                [title: "Dashboard", detail: "Add the Pool, Spa or Pump device as an Attribute tile and pick tile."],
                [title: "Older rules", detail: "Rules made with the 1.6.x command names keep working."]
            ]],
        [id: "trouble", label: "Troubleshooting", title: "Troubleshooting", group: "Troubleshooting", icon: "pi-wrench",
            body: "<p>The status banner on the main page and the Bridge's connectionStatus usually say what's wrong.</p>",
            checklist: [
                [title: "No devices after setup", detail: "Check the IP, and that the Bridge shows connectionStatus Connected. Set Log level to Full, tap Initialize on the Bridge, and check the logs."],
                [title: "Waiting for hub startup", detail: "Normal right after a hub reboot. The Bridge waits 3 minutes, then connects on its own. Tap Refresh on the Bridge if you need it sooner."],
                [title: "Controller offline", detail: "The Bridge retries every 2 minutes and reconnects on its own. Check the controller has power and network, and that nothing blocks port 6680."],
                [title: "Short drops", detail: "A drop that reconnects within 5 minutes is normal, especially when the controller is on Wi-Fi or a wireless bridge. It logs at Normal level with a count for the day. Errors Only shows a warning at 5 minutes offline and an error at 30."],
                [title: "A deleted device came back, or one is missing", detail: "Use the Equipment page."],
                [title: "Heat commands do nothing", detail: "Check Heat Lock is unlocked and the heater is assigned to that body in the Pentair app (Settings, Advanced Settings, Heaters). On 3.002 and later the heater type also has to match how it's wired, or it won't fire even from the Pentair app. The log shows what was sent; post those lines in the community thread."],
                [title: "No chlorinator device", detail: "If the Pentair app shows salt but Hubitat doesn't, set Log level to Full, tap Initialize on the Bridge, and post the lines with CHEM in the community thread."]
            ]]
    ]
}

def tipsPage(params = null) {
    def topics = tipsTopics()
    def topic = topics.find { it.id == params?.topic } ?: topics.find { it.id == DEFAULT_TIP_TOPIC } ?: topics[0]
    int i = topics.indexOf(topic)
    def next = i + 1 < topics.size() ? topics[i + 1] : null
    dynamicPage(name: "tipsPage", title: "Tips & Troubleshooting") {
        section(sectionClass: "app-ui-links app-tips-index app-ui-navigation") {
            paragraph rawHtml: true, tipsStylesHtml() + tipsScrollScript(params?.topic != null)
            topics.groupBy { it.group }.each { group, entries ->
                paragraph rawHtml: true, "<div class='app-heading font-semibold mt-2'>${group}</div>"
                entries.each { entry ->
                    String selected = entry.id == topic.id ? "app-ui-navigation-selected" : ""
                    href name: "tip_${entry.id}",
                        title: "<span class='${selected}'><i class='pi ${entry.icon} mr-3' aria-hidden='true'></i>${entry.label}</span>",
                        description: "", page: "tipsPage", params: [topic: entry.id], width: 12, style: "margin:0 8px;"
                }
            }
        }
        section(sectionClass: "app-ui-links app-tips-article") {
            paragraph rawHtml: true, tipsArticleHtml(topic)
            if (topic.append) {
                def extra = topics.find { it.id == topic.append }
                if (extra) paragraph rawHtml: true, tipsArticleHtml(extra)
            }
            if (next) {
                href name: "nextTip", title: "<span class='hub-text-link'>${next.label}</span>",
                    description: "Next topic", page: "tipsPage", params: [topic: next.id], width: 12, style: "margin:8px;"
            }
        }
    }
}

private String tipsArticleHtml(Map topic) {
    String extra = ""
    if (topic.checklist) {
        extra += "<div class='border-1 surface-border border-round p-3 mb-3'>" + topic.checklist.collect { item ->
            "<div class='flex align-items-center gap-3 py-2'><i class='pi pi-check-circle hub-text-link text-xl' aria-hidden='true'></i>" +
            "<div><div class='font-semibold'>${item.title}</div><div class='text-sm text-color-secondary'>${item.detail}</div></div></div>"
        }.join("") + "</div>"
    }
    if (topic.warning) extra += warningMessageHtml(topic.warning)
    """
<article class='app-tip-card'>
  <div class='app-tip-card-header flex align-items-center gap-3'>
    <i class='pi ${topic.icon} hub-text-link text-xl' aria-hidden='true'></i>
    <h4 class='app-heading font-semibold m-0'>${topic.title}</h4>
  </div>
  <div class='app-tip-copy'>${topic.body}${extra}</div>
</article>
"""
}

// The page can open at the main page's scroll position. Start at the top; on a phone, after picking a topic,
// at the article (the index sits above it there).
private String tipsScrollScript(boolean picked) {
    """
<script>
(function(){
  function go(){
    var art = document.querySelector('.app-tips-article');
    if (${picked} && art && window.innerWidth <= 1000) { art.scrollIntoView(); return; }
    var el = document.getElementById('formApp');
    for (; el; el = el.parentElement) if (el.scrollTop) el.scrollTop = 0;
    window.scrollTo(0, 0);
  }
  go(); setTimeout(go, 50); setTimeout(go, 300);
})();
</script>
"""
}

private String tipsStylesHtml() {
    """
<style>
  ${appPageSpacingCss()}
  .app-tips-index { float: left; width: calc(27% - 8px); box-sizing: border-box; }
  .app-tips-article { float: right; width: 73%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); padding-left: 8px; box-sizing: border-box; }
  .app-tips-index > .mdl-grid, .app-tips-article > .mdl-grid { padding: 4px 0 !important; }
  .app-tips-index .mdl-cell:has(> style) { display: none; }
  .app-tip-card { border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; overflow: hidden; margin-bottom: 8px; }
  .app-tip-card-header { padding: 16px; background: var(--surface-ground, #fafafa); border-bottom: 1px solid var(--surface-border, rgba(0,0,0,.12)); }
  .app-tip-copy { padding: 16px; line-height: 1.55; overflow-wrap: anywhere; }
  .app-tip-copy p { margin: 0 0 16px; }
  .app-tip-copy p:last-child { margin-bottom: 0; }
  .app-tips-article button.hrefElem { background: var(--surface-card, #fff); box-shadow: none; border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; font-family: inherit; }
  .app-tips-article button.hrefElem::before { color: var(--hubitat-link-color, #1565c0); }
  .form:has(> .app-tips-index) + fieldset,
  #formApp:has(.app-tips-index) #fieldsetAppButtons { clear: both; }
  @media (max-width: 1000px) {
    .app-tips-index, .app-tips-article { float: none; width: 100%; padding: 0; border-left: 0; }
  }
</style>
"""
}

private String tipsCardCss() {
    """
  button.hrefElem[name^='_action_href_tips'] {
    position: relative; background: var(--surface-card, #fff); border: 1px solid var(--surface-border, rgba(0,0,0,.12));
    border-radius: 4px; box-shadow: none; color: var(--text-color, rgba(0,0,0,.87)); min-height: 56px;
    font-family: inherit; font-size: 16px; font-weight: 500; line-height: 1.4; padding: 8px 28px 8px 46px;
  }
  button.hrefElem[name^='_action_href_tips'] > span:first-child { color: var(--hubitat-link-color, #1565c0); font-weight: 600; font-size: 16px !important; }
  button.hrefElem[name^='_action_href_tips'] > span.state-incomplete-text { color: var(--text-color-secondary, rgba(0,0,0,.6)); font-size: 14px; font-weight: 500; line-height: 1.4; }
  button.hrefElem[name^='_action_href_tips'] i.pi {
    position: absolute; left: 14px; top: 50%; transform: translateY(-50%); color: var(--hubitat-link-color, #1565c0); font-size: 20px;
  }
  button.hrefElem[name^='_action_href_tips']::before { color: var(--hubitat-link-color, #1565c0); }
"""
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
  ${tipsCardCss()}
  .app-main-support button.hrefElem[name^='_action_href_tips'] { min-height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box; }
  #formApp .form > div:has(> h3:empty):has(+ .app-main-overview) { display: none; }
  #formApp .form > .app-main-overview { margin-bottom: 0.429rem !important; }
  .app-main-overview > .mdl-grid { padding-top: 0.286rem !important; padding-bottom: 0.286rem !important; }
  .app-main-overview .mdl-cell { margin-top: 0.286rem !important; margin-bottom: 0.286rem !important; min-width: 0; }
  .app-main-summary { overflow-wrap: anywhere; }
  .app-main-content > .mdl-grid:first-child > .mdl-cell:first-child { margin-top: 0.286rem !important; }
  .app-main-content { float: left; width: calc(62% - 8px); }
  .app-main-settings { float: right; width: 38%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); padding-left: 8px; box-sizing: border-box; }
  .app-main-content > .mdl-grid, .app-main-settings > .mdl-grid { padding: 4px 0 !important; }
  .app-main-support { clear: both; }
  @media (max-width: 1000px) {
    .app-main-content, .app-main-settings { float: none; width: 100%; border-left: 0; padding-left: 0; }
  }
  @media (max-width: 37.5rem) {
    .app-main-summary { flex-direction: column; align-items: flex-start !important; }
  }
</style>
<div class='app-main-summary flex align-items-center justify-content-between gap-3 ${tone} border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='fa-solid fa-water-ladder text-xl' aria-hidden='true'></i></div>
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

