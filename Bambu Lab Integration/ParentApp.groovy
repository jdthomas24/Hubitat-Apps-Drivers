/*
    Bambu Lab Integration - App
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Local only. Each printer is a slot (state.slots, numbers never reused) with name, IP, LAN access code,
      optional serial and mode. Done creates one Printer per slot (DNI bambu-pN). Existing printers show as cards;
      Edit Printer opens printerPage, and back on the main page any change is applied. New slots edit inline.
     -Access codes stay in this app's settings, never logged or stored on a device; getAccessCode() hands one
      over at connect time. Plain text input on purpose: password fields make browsers autofill the next field.
     -Mode per printer: Monitor only (default) or Monitor and control (Developer Mode, pN_allowStop off by default).
      Check intervals, temperature step and units are printer device preferences.
     -Equipment page as IntelliCenter, one section per printer; state.pushedExcluded is per printer.
     -One log level for everything; children log through childLog(). Full turns off after 30 minutes.
     -UI per HubitatAppUiTemplate (Reolink, gopher.ny). legacyLightThemeCss() covers pre-2.5.3 firmware.
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
@Field static final String APP_VERSION   = "0.2.2"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS      = ["Errors Only", "Normal", "Full"]
@Field static final Map MODES            = [monitor: "Monitor only (works with Bambu Cloud and the Bambu apps)",
                                            control: "Monitor and control (needs Developer Mode, no Bambu Cloud)"]

preferences {
    page(name: "mainPage")
    page(name: "printerPage")
    page(name: "equipmentPage")
    page(name: "tipsPage")
}

private String printerDni(int slot) { "bambu-p${slot}" }
private List printerDevices() { getChildDevices().findAll { it.deviceNetworkId.startsWith("bambu-") } }
private List slots() {
    if (state.slots == null) state.slots = []
    return (state.slots as List).collect { it as int }
}
private boolean validSerial(String s) { s ==~ /[A-Z0-9]{8,20}/ }

// Slots with an IP and access code: DNI -> [slot, name, ip, serial (optional), control]
private Map configuredPrinters() {
    Map out = [:]
    slots().each { i ->
        String s = settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase()
        String ip = settings["p${i}_ip"]?.toString()?.trim()
        if (ip && settings["p${i}_code"]) out[printerDni(i)] = [slot: i, name: (settings["p${i}_name"]?.toString()?.trim() ?: "Bambu Printer ${i}"), ip: ip,
                                                serial: (s && validSerial(s)) ? s : null, control: settings["p${i}_mode"] == "control",
                                                allowStop: settings["p${i}_allowStop"] == true]
    }
    return out
}

// ============================================================================
// Pages
// ============================================================================

def mainPage() {
    if (state.applyEdits) {   // back from Edit Printer: reconnect only if something changed
        boolean changed = state.editBefore != groovy.json.JsonOutput.toJson(configuredPrinters())
        ["applyEdits", "editSlot", "editBefore"].each { state.remove(it) }
        if (changed) initialize()
    }
    armLogRevert()
    if (state.pushedLogLevel != currentLogLevel()) pushLogLevel()
    Map banner = bannerState()
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section(sectionClass: "app-main-overview") {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("Printers")
            List sl = slots()
            if (!sl) paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>No printers yet. Tap <b>Add printer</b>.</div>"
            sl.eachWithIndex { int i, int n -> printerCard(i, n + 1) }
            input "addPrinter", "button", title: "Add printer", width: 4, inputClass: "app-ui-button"
            if (!printerDevices() || printerDevices().any { d -> String sn = null; try { sn = d.getSerial() } catch (e) { }; !sn }) {
                paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>On the printer, <b>Settings, Network</b> (<b>WLAN</b> on some models) " +
                    "shows the IP address and access code. Just read them there: Monitor only doesn't need LAN Only or Developer Mode. " +
                    "A DHCP reservation in your router keeps the IP from changing.</div>"
            }
            if (printerDevices()) {
                Map eq = equipmentCounts()
                href name: "toEquipment", title: "<i class='fa-solid fa-cube mr-2' aria-hidden='true'></i>Equipment",
                    description: eq.total ? "${eq.on} of ${eq.total} AMS units and lights included. Choose which devices Hubitat creates." :
                        "Appears once a printer connects and reports its light and AMS.",
                    page: "equipmentPage", width: 12, style: "margin:8px;"
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
                description: "Monitor only or control, setup and rules", width: 6, style: "margin:8px;"
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
                "Buy Me a Coffee", "Support development"), width: 6
        }
        versionFooterSection()
    }
}

private void printerCard(int i, int pos) {
    def dev = getChildDevice(printerDni(i))
    if (dev) {
        paragraph rawHtml: true, printerCardHtml(i, dev)
        href name: "editP_${i}", title: "Edit Printer", description: "", page: "printerPage", params: [slot: i], width: 3
        input "refreshP_${i}", "button", title: "Refresh", width: 3, inputClass: "app-ui-button"
        input "remove_${i}", "button", title: state.confirmRemove == i ? "Tap again to remove" : "Remove Printer", width: 3, inputClass: "app-ui-button"
        paragraph rawHtml: true, "<div class='bb-card-end'></div>"
        return
    }
    // New printer: fields inline until Done creates its device.
    paragraph rawHtml: true, "<div class='font-semibold mt-3'>${esc(settings["p${i}_name"]?.toString()?.trim() ?: "Printer ${pos}")}</div>"
    printerFields(i)
    input "remove_${i}", "button", title: state.confirmRemove == i ? "Tap again to remove" : "Remove Printer", width: 3, inputClass: "app-ui-button"
    paragraph rawHtml: true, "<div class='bb-card-end'></div>"
}

// Edit an existing printer on its own page. Back on the main page, initialize() applies it.
def printerPage(params = null) {
    if (params?.slot) {
        state.editSlot = params.slot as int
        state.editBefore = groovy.json.JsonOutput.toJson(configuredPrinters())
    }
    int i = (state.editSlot ?: 0) as int
    def dev = getChildDevice(printerDni(i))
    if (dev) state.applyEdits = true
    dynamicPage(name: "printerPage", title: "Edit Printer", install: false, uninstall: false) {
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, "<style>${appPageSpacingCss()}</style>"
            if (!dev) { paragraph "That printer isn't set up. Go back and use Add printer."; return }
            paragraph rawHtml: true, "<div class='font-semibold'>${esc(dev.displayName)} <span class='text-color-secondary' style='font-weight:400;font-size:14px;'>" +
                "${esc(dev.currentValue('connectionStatus') ?: '')}</span></div>"
            printerFields(i)
            paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;margin-top:8px'>Tap <b>Done</b> to save. The printer reconnects with the new settings.</div>"
        }
    }
}

private void printerFields(int i) {
    String s = settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase()
    input "p${i}_name", "text", title: "Name", required: false, width: 6
    input "p${i}_ip", "text", title: "IP address", description: "e.g. 192.168.1.60", required: false, width: 6
    input "p${i}_code", "text", title: "LAN access code", description: "8 characters, shown on the printer", required: false, width: 6
    input "p${i}_serial", "text", title: "Serial number (optional)", description: "Found automatically", required: false, width: 6
    if (s && !validSerial(s)) {
        paragraph rawHtml: true, warningMessageHtml("That doesn't look like a serial number (letters and numbers only). " +
            "If your browser filled in your email here, clear it. The serial is found automatically, so you can leave it blank.")
    }
    input "p${i}_mode", "enum", title: "Mode", options: MODES, defaultValue: "monitor", submitOnChange: true, width: 12
    boolean ctl = settings["p${i}_mode"] == "control"
    if (ctl) {
        paragraph rawHtml: true, warningMessageHtml("<b>Turn on Developer Mode on the printer</b> (Settings, LAN Only, then Developer Mode). " +
            "This takes the printer off Bambu Cloud: no Bambu Handy app and no printing from outside your home. Bambu Studio keeps working on your network.")
        input "p${i}_allowStop", "bool", title: "Allow Stop from Hubitat", defaultValue: false, width: 12,
            description: "Stop cancels the print and can't be undone. Off: Printer Control only pauses, resumes and changes speed."
        paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>Adds <b>Printer Control</b> (Pause, Resume, " +
            "${settings["p${i}_allowStop"] ? 'Stop, ' : ''}speed).</div>"
    } else {
        paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>Hubitat watches the printer and the Bambu Handy app keeps working. " +
            "The chamber light works from Hubitat; pause, stop and speed stay in the Bambu apps.</div>"
    }
    href name: "help_${i}", title: ctl ? "What changes with control" : "Monitor only or control?", description: "",
        page: "tipsPage", params: [topic: ctl ? "control" : "modes"], width: 8, style: "margin:8px 0;"
}

// Summary card, Sofabaton style: name, serial and IP; status line; mode, AMS and light chips.
private String printerCardHtml(int i, dev) {
    String serial = null
    try { serial = dev.getSerial() } catch (e) { }
    String conn = dev.currentValue("connectionStatus") ?: "Connecting"
    String ps = dev.currentValue("printState")
    boolean ok = conn == "Connected"
    boolean busy = reconnecting(dev)
    String line
    if (busy) line = "Reconnecting&hellip;"
    else if (!ok) line = conn == "Waiting for printer" ? "Connected, waiting for the printer's first status" : conn
    else if (ps in ["Printing", "Paused", "Preparing"]) {
        def pct = dev.currentValue("progress"), left = dev.currentValue("timeRemaining")
        line = ps + (pct != null ? " &middot; ${pct}%" : "") + (left ? " &middot; ${hm(left as int)} left" : "") +
            (dev.currentValue("ETA") && dev.currentValue("ETA") != "--" ? " &middot; done at ${dev.currentValue('ETA')}" : "")
    } else line = ps ?: "Connected"
    int errs = (dev.currentValue("errorCount") ?: 0) as int
    StringBuilder c = new StringBuilder()
    c << "<div style='border:1px solid var(--surface-border, rgba(0,0,0,.12));border-radius:10px;padding:12px 14px;margin:4px 0 0;background:var(--surface-card, #fff)'>"
    String model = dev.currentValue("model")
    c << "<div>" + (model ? "<span style='background:#00ae42;color:#fff;border-radius:8px;padding:2px 9px;font-size:0.9em;font-weight:bold;margin-right:6px'>${esc(model)}</span>" :
        "<i class='fa-solid fa-cube hub-text-link' aria-hidden='true' style='margin-right:6px'></i>")
    c << "<a href='/device/edit/${dev.id}' target='_blank' class='bb-link' style='font-weight:600'>${esc(dev.displayName)}" +
        "<i class='fa-regular fa-external-link' style='margin-left:5px;font-size:0.8em'></i></a> "
    c << "<span class='text-color-secondary' style='font-size:0.8em'>${serial ? esc(serial) + ' &middot; ' : ''}${esc(settings["p${i}_ip"] ?: '')}</span></div>"
    String tone = ok ? "hub-text-success" : busy ? "text-color-secondary" : "hub-text-warning"
    c << "<div class='${tone}' style='margin-top:6px;font-size:0.85em'>${esc(line).replace('&amp;middot;', '&middot;').replace('&amp;hellip;', '&hellip;')}</div>"
    if (errs > 0) {
        List det = []
        try { det = dev.getErrorDetails() ?: [] } catch (e) { }
        if (det) det.each { d ->
            c << "<div class='hub-text-warning' style='margin-top:4px;font-size:0.85em'>&#9888; ${d[1] ? esc(d[1]) + ' ' : ''}" +
                "<a href='${esc(d[2])}' target='_blank' class='bb-link'>${esc(d[0])}<i class='fa-regular fa-external-link' style='margin-left:4px;font-size:0.8em'></i></a></div>"
        }
        else c << "<div class='hub-text-warning' style='margin-top:4px;font-size:0.85em'>&#9888; ${errs} printer error${errs == 1 ? '' : 's'}: ${esc(dev.currentValue('errors') ?: '')}</div>"
    }
    if (dev.currentValue("door") == "open" || dev.currentValue("firmwareUpdate")?.toString()?.startsWith("available")) {
        List bits = []
        if (dev.currentValue("door") == "open") bits << "Door open"
        if (dev.currentValue("firmwareUpdate")?.toString()?.startsWith("available")) bits << "Firmware update available"
        c << "<div class='text-color-secondary' style='margin-top:4px;font-size:0.85em'>${bits.join(' &middot; ')}</div>"
    }
    c << "<div style='margin-top:10px;display:flex;flex-wrap:wrap;gap:8px'>"
    c << "<span class='bb-chip'>${settings["p${i}_mode"] == 'control' ? 'Monitor and control' : 'Monitor only'}</span>"
    List kids = []
    try { kids = dev.getChildDevices() ?: [] } catch (e) { }
    kids.sort { it.displayName }.each { k ->
        if (k.deviceNetworkId.contains("-ams")) {
            String dots = (1..4).collect { n ->
                String col = k.currentValue("tray${n}Color")
                col && col != "none" ? "<span class='bb-dot' style='background:${esc(col)}' title='${esc(k.currentValue("tray${n}") ?: '')}'></span>" :
                    "<span class='bb-dot' style='background:transparent' title='empty'></span>"
            }.join("")
            c << "<a href='/device/edit/${k.id}' target='_blank' class='bb-pill'>${esc(shortName(k, dev))}${dots}" +
                "<i class='fa-regular fa-external-link hub-text-link' style='margin-left:8px;font-size:0.75em'></i></a>"
        } else {
            boolean on = k.currentValue("switch") == "on"
            c << "<a href='/device/edit/${k.id}' target='_blank' class='bb-pill' title='${on ? 'On' : 'Off'}'>" + bulbSvg(on) +
                "${esc(shortName(k, dev))}<i class='fa-regular fa-external-link hub-text-link' style='margin-left:8px;font-size:0.75em'></i></a>"
        }
    }
    c << "</div></div>"
    return c.toString()
}

// Light pill icon: lit amber bulb when on, gray outline with a slash when off.
private String bulbSvg(boolean on) {
    String bulb = "M9 18h6M10 21h4M12 3a6 6 0 0 0-3.6 10.8c.6.5 1 1.2 1 2V16h5.2v-.2c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z"
    String fill = on ? "#f5b301" : "none"
    String stroke = on ? "#b37f00" : "var(--text-color-secondary, #999)"
    return "<svg viewBox='0 0 24 24' width='16' height='16' aria-hidden='true' style='vertical-align:-3px;margin-right:6px'>" +
        "<path d='${bulb}' fill='${fill}' stroke='${stroke}' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'/>" +
        (on ? "" : "<line x1='4' y1='3' x2='20' y2='21' stroke='${stroke}' stroke-width='2' stroke-linecap='round'/>") + "</svg>"
}

// "H2D #1 AMS 1" on printer "H2D #1" shows as "AMS 1".
private String shortName(k, dev) {
    String n = k.displayName ?: ""
    String p = dev.displayName ?: ""
    return n.startsWith(p + " ") ? n.substring(p.length() + 1) : n
}

private String hm(int mins) { mins >= 60 ? "${(mins / 60) as int}h ${mins % 60}m" : "${mins}m" }

def appButtonHandler(String btn) {
    def pm = btn =~ /^refreshP_(\d+)$/
    if (pm.find()) {
        state.remove("confirmRemove")
        def d = getChildDevice(printerDni(pm.group(1).toInteger()))
        if (d) try { d.refresh() } catch (e) { }
        return
    }
    if (btn == "addPrinter") {
        List sl = slots()
        int next = Math.max(((state.maxSlot ?: 0) as int), (sl ? sl.max() : 0)) + 1
        state.maxSlot = next
        state.slots = sl + next
        state.remove("confirmRemove")
        return
    }
    def mt = btn =~ /^remove_(\d+)$/
    if (!mt.find()) return
    int i = mt.group(1).toInteger()
    if (state.confirmRemove != i) { state.confirmRemove = i; return }
    state.remove("confirmRemove")
    def dev = getChildDevice(printerDni(i))
    if (dev) {
        String label = dev.displayName
        try { dev.removeAllChildren(); deleteChildDevice(dev.deviceNetworkId); logInfo "removed ${label}" }
        catch (e) { logErr "could not remove ${label}: ${e.message}" }
    }
    ["name", "ip", "code", "serial", "mode", "allowStop"].each { app.removeSetting("p${i}_${it}") }
    state.slots = slots() - i
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
    if (!cfg) return [ok: false, title: "Setup required", summary: "Enter a printer's IP address and LAN access code, then tap <b>Done</b>."]
    List devs = printerDevices()
    if (!devs) return [ok: false, title: "Not connected yet", summary: "Tap <b>Done</b> to connect."]
    List bad = devs.findAll { !(it.currentValue("connectionStatus") in ["Connected", "Waiting for printer"]) && !reconnecting(it) }
    String counts = countText(devs.size(), "printer")
    List busy = devs.findAll { reconnecting(it) }
    if (!bad && busy) return [ok: true, title: busy.size() == 1 ? "${esc(busy[0].displayName)} reconnecting" : "${busy.size()} printers reconnecting",
                              summary: "${counts} &middot; this takes a few seconds after an update"]
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

// Connecting or Reconnecting for under 2 minutes is normal (after an update or Done), not an outage.
private boolean reconnecting(dev) {
    if (!(dev.currentValue("connectionStatus") in ["Connecting", "Reconnecting"])) return false
    Long age = null
    try { age = dev.statusAge() } catch (e) { }
    return age == null || age < 120000L
}

// ============================================================================
// Lifecycle
// ============================================================================

def installed() { initialize() }

def updated() { ["editing", "editSlot", "editBefore", "applyEdits", "confirmRemove"].each { state.remove(it) }; initialize() }

def initialize() {
    unschedule()
    state.remove("fullLogArmed")   // unschedule() cleared its timer
    armLogRevert()
    Map cfg = configuredPrinters()
    printerDevices().each { dev ->
        if (!cfg.containsKey(dev.deviceNetworkId)) {
            String label = dev.displayName
            try { dev.removeAllChildren(); deleteChildDevice(dev.deviceNetworkId); logInfo "removed ${label} (no longer in the app)" }
            catch (e) { logErr "could not remove ${label}: ${e.message}" }
        }
    }
    cfg.each { dni, p ->
        def dev = getChildDevice(dni as String)
        if (!dev) {
            try {
                dev = addChildDevice("jdthomas24", "Bambu Lab Printer", dni as String, [label: p.name, isComponent: false])
                logInfo "created ${dev.displayName}"
            } catch (e) {
                String why = "${e}".contains("UnknownDeviceType") ? "the Bambu Lab Printer driver isn't installed (update with HPM)" : e.message
                logErr "could not create ${p.name}: ${why}"
                return
            }
        }
        dev.configure([slot: p.slot, serial: p.serial, name: p.name, ip: p.ip], [control: p.control, allowStop: p.control && p.allowStop])
        dev.setLogLevel(currentLogLevel())
    }
    state.pushedLogLevel = currentLogLevel()
    state.remove("migrated")
    ["printerCount", "updateInterval", "tempStep", "tempUnit"].each { app.removeSetting(it) }
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

// For the Printers, at connect time. Takes the Printer's DNI (bambu-pN).
String getAccessCode(String dni) {
    def mt = (dni ?: "") =~ /^bambu-p(\d+)$/
    Integer i = mt.find() ? mt.group(1).toInteger() : null
    return (i && slots().contains(i)) ? settings["p${i}_code"]?.toString()?.trim() : null
}

// ============================================================================
// Tips & Troubleshooting
// ============================================================================

@Field static final String DEFAULT_TIP_TOPIC = "modes"

/** Fields: id, label, title, group, icon (PrimeIcons), body; optional checklist, warning, append. */
private List tipsTopics() {
    [
        [id: "modes", label: "Monitor or control", title: "Monitor only or Monitor and control?", group: "Getting started", icon: "pi-sliders-h",
            body: "<p>Each printer has its own mode. Most people want <b>Monitor only</b>: Hubitat sees everything, and you keep printing and controlling from the Bambu apps as usual.</p>",
            checklist: [
                [title: "Monitor only (default)", detail: "Print state, progress, time left, temperatures, AMS, door, errors in plain English, a dashboard tile, and the chamber light switch. Bambu Cloud, Bambu Handy and printing from anywhere keep working. Nothing to change on the printer."],
                [title: "Monitor and control", detail: "Everything above, plus Pause, Resume, speed and optional Stop from Hubitat. Needs Developer Mode, which takes the printer off Bambu Cloud."],
                [title: "Not sure?", detail: "Start with Monitor only. You can switch any time in the app; nothing else needs redoing."]
            ],
            append: "control"],
        [id: "control", label: "Control mode", title: "What changes with Monitor and control", group: "Getting started", icon: "pi-sliders-v",
            body: "<p>Bambu's current firmware only accepts commands signed by Bambu's own software, unless <b>Developer Mode</b> is on. Developer Mode is under <b>Settings, LAN Only</b> on the printer.</p>",
            checklist: [
                [title: "You lose", detail: "Bambu Cloud: the Bambu Handy app, remote printing and monitoring away from home, and cloud print history."],
                [title: "You keep", detail: "Bambu Studio and OrcaSlicer on your home network, and printing from the printer's screen."],
                [title: "Hubitat gets", detail: "Printer Control (Pause, Resume, speed, and Stop if you allow it). The chamber light works in both modes."],
                [title: "Allow Stop", detail: "Off by default. Stop cancels the print for good, so only turn it on if you trust every rule, dashboard and voice assistant that can reach the printer."]
            ],
            warning: "If Developer Mode is off, the printer refuses Printer Control. After each command the log says whether it took, and monitoring and the light keep working."],
        [id: "setup", label: "Setup", title: "Adding a printer", group: "Getting started", icon: "pi-cog",
            body: "<p>Tap <b>Add printer</b>. Enter a name, the <b>IP address</b> and the <b>LAN access code</b>. Both are on the printer under <b>Settings, Network</b> (<b>WLAN</b> on some models). Just read them: you don't need to turn on LAN Only.</p>" +
                "<p>Leave the serial blank. The integration reads it from the printer and shows it next to the printer's name once connected.</p>" +
                "<p>Tap <b>Done</b>. Each printer connects within a few seconds and adds its AMS units. Give the printer a <b>DHCP reservation</b> in your router so its IP doesn't change.</p>"],
        [id: "updates", label: "Status updates", title: "How status stays up to date", group: "How it works", icon: "pi-refresh",
            body: "<p>X1 and H2 printers send an 8 KB status every second while printing. Over a long print that adds up, so the integration stays connected but only listens for one status per check:</p>",
            checklist: [
                [title: "Printing", detail: "Every 30 seconds (the printer device's Preferences, from 15 seconds to 2 minutes). A finished print or an error shows up within that time."],
                [title: "Idle", detail: "Every 15 minutes, like a health check (5 minutes to 1 hour in Preferences). A print that starts shows up within that time."],
                [title: "After a command", detail: "Right away, to pick up the printer's answer."],
                [title: "Refresh", detail: "Tap Refresh on the printer device for a status now."]
            ]],
        [id: "temps", label: "Temperatures", title: "Heater status and temperatures", group: "How it works", icon: "pi-sun",
            body: "<p>Each heater has a <b>status</b> and a <b>temperature</b>. The status is <i>Heating to 220°</i>, <i>At temp</i>, <i>Cooling</i> or <i>Off</i>. " +
                "The temperature only updates once the heater is at temperature or off, in 5° steps, so a warm-up doesn't send a burst of events.</p>" +
                "<p>Temperatures are in Celsius to match the printer. To follow the hub's units instead, open the printer device and change <b>Temperature units</b> under Preferences. The 5° step is there too.</p>"],
        [id: "rules", label: "Rules and dashboards", title: "Rules and dashboards", group: "Using it", icon: "pi-bolt",
            body: "<p>Some ideas:</p>",
            checklist: [
                [title: "Print finished", detail: "printState becomes Finished: notify, or turn off the enclosure fan."],
                [title: "Print failed or error", detail: "printState becomes Failed, or errorCount above 0. errorText says what's wrong in plain English, and the card links each code to the Bambu Lab Wiki."],
                [title: "Door opened", detail: "door becomes open (H2D and printers with a door sensor). In Rule Machine it's under Custom Attribute."],
                [title: "What printed last", detail: "lastPrint keeps the last finished or failed file. fileName says Not printing between prints."],
                [title: "Firmware update", detail: "firmwareUpdate becomes available. Update from the printer or the Bambu apps."],
                [title: "Ready to print", detail: "bedStatus becomes At temp."],
                [title: "Dashboard", detail: "Add the printer as an Attribute tile and pick tile: state, file, progress bar, layers, time left and temperatures."]
            ]],
        [id: "trouble", label: "Troubleshooting", title: "Troubleshooting", group: "Troubleshooting", icon: "pi-wrench",
            body: "<p>The printer's status line on the main page usually says what's wrong.</p>",
            checklist: [
                [title: "Access code rejected", detail: "The code changes when the printer's network settings are reset. Read it again on the printer, update it here and tap Done."],
                [title: "Waiting for printer", detail: "Connected, but no status yet. Some printers stay quiet while idle; start anything on the printer, or enter its serial (Settings, Device)."],
                [title: "Serial doesn't match", detail: "The integration uses the serial the printer reports and logs a warning. Clear the serial field."],
                [title: "Reconnecting", detail: "Check the printer is on and on the same network, and nothing blocks port 8883. It retries on its own."],
                [title: "Command refused", detail: "In Monitor only that's on purpose. In Monitor and control, turn on Developer Mode on the printer. The light works either way."],
                [title: "Light came back on", detail: "The printer turns its light on when a print finishes. That's the printer, not Hubitat."],
                [title: "Error text", detail: "Looked up from this integration's GitHub folder the first time a code appears (not from Bambu). Plain-English text comes from the ha-bambulab project (MIT license)."]
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
            paragraph rawHtml: true, tipsStylesHtml()
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
  ${tipsCardCss()}
  .app-main-support button.hrefElem[name^='_action_href_tips'] { min-height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box; }
  a.bb-pill { display: inline-flex; align-items: center; background: var(--hubitat-selection-background, #e3eaf3); border-radius: 20px; padding: 6px 14px;
    font-size: 0.9em; color: inherit; text-decoration: none; transition: background 0.15s; }
  a.bb-pill:hover { background: var(--surface-hover, #cfdcec); }
  span.bb-chip { display: inline-flex; align-items: center; border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 20px; padding: 5px 12px; font-size: 0.85em; }
  a.bb-link { color: var(--hubitat-link-color, #1565c0); text-decoration: none; }
  a.bb-link:hover { text-decoration: underline; }
  .bb-dot { display: inline-block; width: 11px; height: 11px; border-radius: 50%; margin-left: 4px; border: 1px solid rgba(127,127,127,.5); }
  .app-main-content :is([name*='refreshP_'],[name*='remove_'],[id*='refreshP_'],[id*='remove_']) {
    font-size: 13px !important; padding: 3px 12px !important; min-height: 0 !important; height: auto !important;
    line-height: 1.5 !important; margin: 2px 0 !important; box-shadow: none !important; border: 1px solid var(--hubitat-control-border, #d0d7de) !important;
    white-space: nowrap; }
  .bb-card-end { border-bottom: 1px solid var(--surface-border, rgba(0,0,0,.12)); margin: 4px 0 18px; }
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
    <div class='flex-shrink-0'><i class='fa-solid fa-cube text-xl' aria-hidden='true'></i></div>
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

