/*
    Bambu Lab Integration - App
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Local only. Each printer is a slot (name, IP, serial, LAN access code); Done creates one Printer device per
      complete slot and removes devices for printers taken out. Devices are keyed by serial.
     -Access codes stay in this app's settings and are never logged or stored on a device. getAccessCode() hands
      one to its Printer at connect time. Plain text input on purpose: a password field makes browsers autofill
      the user's own login into the serial field. Serials must be 8 to 20 letters and digits.
     -Mode is per printer (pN_mode): Monitor only (default, works with Bambu Cloud) or Monitor and control
      (Developer Mode). Shared options: update interval for busy printers, temperature step.
     -Equipment page as in IntelliCenter, one section per printer; state.pushedExcluded is per printer.
     -Log level is the only logging control. App -> Printer -> light/AMS; every line comes back to childLog().
      Full turns off after 30 minutes.
     -UI follows HubitatAppUiTemplate (Reolink, UI design by gopher.ny). Dark mode uses the hub's theme
      classes and var() tokens with fallbacks; legacyLightThemeCss() covers pre-2.5.3 firmware (pattern by
      vurvantsev, Reolink 1.6.13).
*/

definition(
    name: "Bambu Lab Integration",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Local monitoring and control of Bambu Lab 3D printers over the LAN",
    category: "Convenience",
    menu: "Integrations",
    installOnOpen: true,
    iconUrl: "",
    iconX2Url: ""
)

import groovy.transform.Field

@Field static final String APP_NAME      = "Bambu Lab Integration"
@Field static final String APP_VERSION   = "0.1.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS      = ["Errors Only", "Normal", "Full"]
@Field static final Map INTERVALS        = ["2": "2 seconds", "5": "5 seconds", "10": "10 seconds", "30": "30 seconds"]
@Field static final Map TEMP_STEPS       = ["0.5": "0.5°", "1": "1°", "2": "2°", "5": "5°"]
@Field static final Map MODES            = [monitor: "Monitor only (works with Bambu Cloud and the Bambu apps)",
                                            control: "Monitor and control (needs Developer Mode, no Bambu Cloud)"]

preferences {
    page(name: "mainPage")
    page(name: "equipmentPage")
}

private String printerDni(String serial) { "bambu-${serial}" }
private List printerDevices() { getChildDevices().findAll { it.deviceNetworkId.startsWith("bambu-") } }
private int slotCount() { ((printerCount ?: "1") as String).toInteger() }
private boolean validSerial(String s) { s ==~ /[A-Z0-9]{8,20}/ }

// Complete slots only: serial -> [serial, name, ip]
private Map configuredPrinters() {
    Map out = [:]
    (1..slotCount()).each { i ->
        String s = settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase()
        String ip = settings["p${i}_ip"]?.toString()?.trim()
        if (s && validSerial(s) && ip && settings["p${i}_code"]) out[s] = [serial: s, name: (settings["p${i}_name"]?.toString()?.trim() ?: "Bambu ${s.takeRight(4)}"), ip: ip,
                                                         control: settings["p${i}_mode"] == "control"]
    }
    return out
}

// ============================================================================
// Pages
// ============================================================================

def mainPage() {
    armLogRevert()
    if (state.pushedLogLevel != currentLogLevel()) pushLogLevel()
    Map banner = bannerState()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("Printers")
            input "printerCount", "enum", title: "Number of printers", options: (1..6).collect { "${it}" }, defaultValue: "1",
                submitOnChange: true, width: 4
            (1..slotCount()).each { i ->
                String s = settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase()
                def dev = s ? getChildDevice(printerDni(s)) : null
                String status = dev ? " <span class='text-color-secondary' style='font-weight:400;font-size:14px;'>${esc(dev.currentValue('connectionStatus') ?: '')}" +
                    " &middot; <a href='/device/edit/${dev.id}' target='_blank' class='hub-text-link no-underline'>device " +
                    "<i class='fa-regular fa-external-link' style='font-size:0.8em'></i></a></span>" : ""
                paragraph rawHtml: true, "<div class='font-semibold mt-2'>Printer ${i}${status}</div>"
                input "p${i}_name", "text", title: "Name", required: false, width: 6
                input "p${i}_ip", "text", title: "IP address", description: "e.g. 192.168.1.60", required: false, width: 6
                input "p${i}_serial", "text", title: "Serial number", required: false, width: 6
                input "p${i}_code", "text", title: "LAN access code", description: "8 characters, shown on the printer", required: false, width: 6
                if (s && !validSerial(s)) {
                    paragraph rawHtml: true, warningMessageHtml("That doesn't look like a serial number (letters and numbers only, like 00M09A123456789). " +
                        "If your browser filled in your email here, clear it and type the serial from the printer.")
                }
                input "p${i}_mode", "enum", title: "Mode", options: MODES, defaultValue: "monitor", submitOnChange: true, width: 12
                if (settings["p${i}_mode"] == "control") {
                    paragraph rawHtml: true, warningMessageHtml("<b>Needs Developer Mode on the printer</b> (Settings, LAN Only, Developer Mode), " +
                        "which disconnects it from Bambu Cloud: no remote printing or monitoring from the Bambu Handy app. Bambu Studio still works on your network. " +
                        "<b>Stop ends the print for good</b>, so keep it out of rules and voice assistants you don't trust.")
                }
            }
            paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>On the printer: <b>Settings, WLAN</b> (or <b>LAN Only</b>) shows the IP " +
                "address and access code; <b>Settings, Device</b> shows the serial. A DHCP reservation keeps the IP from changing.</div>"
            if (printerDevices()) {
                Map eq = equipmentCounts()
                href name: "toEquipment", title: "<i class='fa-solid fa-cube mr-2' aria-hidden='true'></i>Equipment",
                    description: eq.total ? "${eq.on} of ${eq.total} AMS units and lights included. Choose which devices Hubitat creates." :
                        "Appears once a printer connects and reports its light and AMS.",
                    page: "equipmentPage", width: 12, style: "margin:8px;"
            }
            paragraph rawHtml: true, columnHeader("Options")
            input "updateInterval", "enum", title: "Update busy printers every", options: INTERVALS, defaultValue: "5", width: 6,
                description: "X1 and H2 printers report every second while printing. State changes always go through right away."
            input "tempStep", "enum", title: "Report temperature changes of", options: TEMP_STEPS, defaultValue: "1", width: 6,
                description: "Smaller changes are skipped to keep hub load down"
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
                "Hubitat Community", "Questions, feedback, and release notes"), width: 6
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
                "Buy Me a Coffee", "Support development"), width: 6
        }
        versionFooterSection()
    }
}

def equipmentPage() {
    dynamicPage(name: "equipmentPage", title: "Equipment", install: false, uninstall: false) {
        section {
            paragraph rawHtml: true, "<style>${appPageSpacingCss()}</style>" +
                "<div class='text-color-secondary' style='font-size:14px;'>Turn off anything you don't want in Hubitat. " +
                "Its device is removed right away and won't come back. Turning it back on recreates it with the next report. " +
                "Deleting a device from the Devices page turns it off here too.</div>"
        }
        List devs = printerDevices()
        if (!devs) section { paragraph "No printers yet. Fill in a printer and tap Done first." }
        devs.sort { it.displayName }.each { dev ->
            Map eq = syncEquipment(dev)
            Map catalog = (eq.catalog ?: [:]) as Map
            section("<b>${esc(dev.displayName)}</b>") {
                if (!catalog) { paragraph "Nothing yet. The list fills in once the printer connects."; return }
                catalog.sort { a, b -> a.key <=> b.key }.each { key, v ->
                    input "inc_${dev.deviceNetworkId - 'bambu-'}_${key}", "bool", title: esc(v.n), defaultValue: true, submitOnChange: true, width: 6
                }
            }
        }
    }
}

// Printer exclusions (devices deleted from the Devices page) -> toggles, then toggles -> Printer.
private Map syncEquipment(dev) {
    Map catalog = [:]
    List devEx = []
    try {
        catalog = dev.getCatalog() ?: [:]
        devEx = dev.getExcluded() ?: []
    } catch (e) {
        logDbg "equipment list not available yet: ${e.message}"
        return [catalog: [:], excluded: []]
    }
    String key = dev.deviceNetworkId - "bambu-"
    Map pushedAll = (state.pushedExcluded ?: [:]) as Map
    List pushed = (pushedAll[key] ?: []) as List
    (devEx - pushed).each { k -> app.updateSetting("inc_${key}_${k}", [value: false, type: "bool"]) }
    List want = catalog.keySet().collect { it.toString() }.findAll { settings["inc_${key}_${it}"] == false }
    want += devEx.findAll { !catalog.containsKey(it) && !pushed.contains(it) }
    want = want.unique().sort()
    if (want != devEx.collect { it.toString() }.unique().sort()) {
        try { dev.setExcluded(want) } catch (e) { logErr "could not update ${dev.displayName}'s equipment list: ${e.message}" }
    }
    if (pushed != want) { pushedAll[key] = want; state.pushedExcluded = pushedAll }
    return [catalog: catalog, excluded: want]
}

private Map equipmentCounts() {
    int total = 0, on = 0
    printerDevices().each { dev ->
        try {
            Map c = dev.getCatalog() ?: [:]
            List x = dev.getExcluded() ?: []
            total += c.size()
            on += c.keySet().count { !x.contains(it) } as int
        } catch (e) { }
    }
    return [total: total, on: on]
}

private Map bannerState() {
    Map cfg = configuredPrinters()
    if (!cfg) return [ok: false, title: "Setup required", summary: "Enter a printer's IP address, serial number and LAN access code, then tap <b>Done</b>."]
    List devs = printerDevices()
    if (!devs) return [ok: false, title: "Not connected yet", summary: "Tap <b>Done</b> to connect."]
    List bad = devs.findAll { it.currentValue("connectionStatus") != "Connected" }
    String counts = countText(devs.size(), "printer")
    if (!bad) {
        List printing = devs.findAll { it.currentValue("printState") == "Printing" }
        return [ok: true, title: "Integration ready", summary: "${counts}${printing ? ' &middot; ' + printing.size() + ' printing' : ''}"]
    }
    if (bad.any { it.currentValue("connectionStatus") == "Access code rejected" }) {
        return [ok: false, title: "Access code rejected", summary: "Check the LAN access code on the printer, update it below and tap <b>Done</b>."]
    }
    return [ok: false, title: bad.size() == 1 ? "${esc(bad[0].displayName)} offline" : "${bad.size()} printers offline",
            summary: "${counts} &middot; reconnecting on its own. Check the printer is on and on the network."]
}

// ============================================================================
// Lifecycle
// ============================================================================

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
    unschedule()
    state.remove("fullLogArmed")   // unschedule() cleared its timer
    armLogRevert()
    Map cfg = configuredPrinters()
    Map shared = [interval: ((updateInterval ?: "5") as String).toInteger(), tempStep: ((tempStep ?: "1") as String).toBigDecimal()]
    printerDevices().each { dev ->
        String s = dev.deviceNetworkId - "bambu-"
        if (!cfg.containsKey(s)) {
            String label = dev.displayName
            try { dev.removeAllChildren(); deleteChildDevice(dev.deviceNetworkId); logInfo "removed ${label} (no longer in the app)" }
            catch (e) { logErr "could not remove ${label}: ${e.message}" }
        }
    }
    cfg.each { s, p ->
        def dev = getChildDevice(printerDni(s as String))
        if (!dev) {
            try {
                dev = addChildDevice("jdthomas24", "Bambu Lab Printer", printerDni(s as String), [label: p.name, isComponent: false])
                logInfo "created ${dev.displayName}"
            } catch (e) {
                String why = "${e}".contains("UnknownDeviceType") ? "the Bambu Lab Printer driver isn't installed (update with HPM)" : e.message
                logErr "could not create ${p.name}: ${why}"
                return
            }
        }
        dev.configure([serial: p.serial, name: p.name, ip: p.ip], shared + [control: p.control])
        dev.setLogLevel(currentLogLevel())
    }
    state.pushedLogLevel = currentLogLevel()
    runIn(2, "startPrinters")
}

def startPrinters() {
    printerDevices().each { dev -> try { dev.initialize() } catch (e) { logErr "could not start ${dev.displayName}: ${e.message}" } }
}

def uninstalled() {
    printerDevices().each { dev ->
        try { dev.removeAllChildren() } catch (e) { }
        try { deleteChildDevice(dev.deviceNetworkId) } catch (e) { log.warn "${APP_NAME}: could not delete ${dev.displayName}: ${e.message}" }
    }
}

// For the Printers, at connect time.
String getAccessCode(String serial) {
    Integer i = (1..slotCount()).find { settings["p${it}_serial"]?.toString()?.trim()?.toUpperCase() == serial }
    return i ? settings["p${i}_code"]?.toString()?.trim() : null
}

// ============================================================================
// Logging
// ============================================================================

private String currentLogLevel() { LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only" }

private void pushLogLevel() {
    printerDevices().each { try { it.setLogLevel(currentLogLevel()) } catch (e) { } }
    state.pushedLogLevel = currentLogLevel()
}

private void logErr(String msg)  { log.warn "${APP_NAME}: ${msg}" }
private void logInfo(String msg) { if (currentLogLevel() != "Errors Only") log.info "${APP_NAME}: ${msg}" }
private void logDbg(String msg)  { if (currentLogLevel() == "Full") log.debug "${APP_NAME}: ${msg}" }

// Called by the Printers for themselves and their light and AMS devices. They already filter by level.
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
    <div class='flex-shrink-0'><i class='fa-solid fa-cube text-2xl' aria-hidden='true'></i></div>
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
<div class='bl-dialog text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>One setting for the app, every printer and its light and AMS devices.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only, such as a rejected access code, a printer staying unreachable, printer errors, or a refused command.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus connections, print state changes, commands, filament changes, and devices added or removed.", current)}
  ${logLevelDetailHtml("Full", "Everything, including every report from the printer. Very busy while printing. Turns off after 30 minutes.", current)}
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
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .hub-text-link { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .hub-text-success { color: var(--hubitat-success-color, #356700) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .hub-text-warning { color: var(--hubitat-warning-color, #8a5a00) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .hub-text-danger { color: var(--hubitat-validation-color, #b71c1c) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-links .hrefElem { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-links .hrefElem > .state-incomplete-text,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-links .hrefElem > .state-complete-text { color: var(--text-color-secondary, #616161); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button { background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 1px solid transparent; border-radius: 4px; box-shadow: 0 2px 2px rgba(0,0,0,.14); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button:enabled:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button-primary,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button-primary:enabled:hover { background: var(--hubitat-brand-background, #81bc00); color: var(--hubitat-on-brand, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button-secondary { background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border-color: var(--hubitat-control-border, #b0bec5); box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button-danger,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button-danger:enabled:hover { background: var(--hubitat-danger-background, #d32f2f); color: var(--hubitat-on-danger, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button:enabled:active { box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button:disabled { background: var(--hubitat-control-background, #eee); color: var(--text-color-secondary, #616161); opacity: .6; cursor: not-allowed; box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-navigation .hrefElem { background: transparent; color: var(--hubitat-link-color, #1565c0) !important; border: 0; border-left: 3px solid transparent; border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-navigation .hrefElem::before { display: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-navigation .hrefElem:not(:disabled):not(:has(.app-ui-navigation-selected)):hover { background: var(--surface-hover, #f3f6fa); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-navigation .hrefElem:has(.app-ui-navigation-selected) { background: var(--hubitat-selection-background, #eaf2fc); border-left-color: var(--hubitat-link-color, #1565c0); font-weight: 600; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-navigation .hrefElem:disabled { opacity: .6; cursor: not-allowed; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) button.mdl-button.app-ui-button:focus-visible,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .bl-dialog) .app-ui-navigation .hrefElem:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
"""
}

private String tileSecondaryTextCss() {
    legacyLightThemeCss() + "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

