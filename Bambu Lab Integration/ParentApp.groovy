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
     -Find printers (0.2.20): M-SEARCH for urn:bambulab-com:device:3dprinter:1 over UDP to 1990 (multicast and
      broadcast) and 1900, plus the hub's SSDP discovery. Replies carry the IP (Location) and serial (USN).
      Any search moves a printer whose serial answers from a new IP. Printers ask for a search when lost
      (findLostPrinter); timing lives in the Printer driver, the limits in recoveryOpts().
     -Main page (0.2.22): printer cards, then any New printer box (Save printer creates and connects only that one,
      Cancel drops it; Add by hand hides while one is open), Find printers results, Equipment. Settings sit in Quick Settings.
     -Access codes (0.2.21): Enter by hand (default) or Sign in with Bambu. Sign-in on its own page (a password field
      on the main page makes browsers autofill the printer fields). Password never saved; token, email and the
      account's printers (serial, name, model, code) in state. Login posts with Bambu Studio headers, the printer
      list with the token only (Cloudflare blocks it with Studio headers, tested 0.2.20); each falls back to the
      other on a Cloudflare page. Codes fill in on Add and sign-in; a saved code is replaced only when the printer
      rejects it (accessCodeRejected from the driver, at most every 30 min per printer). Turning it off signs out.
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
@Field static final String APP_VERSION   = "0.2.22"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS      = ["Errors Only", "Normal", "Full"]
@Field static final String SSDP_ST       = "urn:bambulab-com:device:3dprinter:1"
@Field static final Map MODEL_NAMES      = ["3DPrinter-X1-Carbon": "X1 Carbon", "3DPrinter-X1": "X1", "BL-P001": "X1 Carbon", "BL-P002": "X1",
                                            "C11": "P1P", "C12": "P1S", "C13": "X1E", "N1": "A1 mini", "N2S": "A1", "O1D": "H2D", "O1S": "H2S"]
@Field static final Map CODE_SOURCES      = [hand: "Enter by hand (read from each printer)", cloud: "Sign in with Bambu (fills them in for you)"]
@Field static final String BBL_LOGIN    = "https://api.bambulab.com/v1/user-service/user/login"
@Field static final String BBL_EMAIL    = "https://api.bambulab.com/v1/user-service/user/sendemail/code"
@Field static final String BBL_BIND     = "https://api.bambulab.com/v1/iot-service/api/user/bind"
@Field static final String BBL_CSRF     = "https://bambulab.com/api/csrf"
@Field static final String BBL_TFA      = "https://bambulab.com/api/sign-in/tfa"
@Field static final Map STUDIO_HEADERS  = [
    "User-Agent": "bambu_network_agent/01.09.05.01", "X-BBL-Client-Name": "OrcaSlicer", "X-BBL-Client-Type": "slicer",
    "X-BBL-Client-Version": "01.09.05.51", "X-BBL-Language": "en-US", "X-BBL-OS-Type": "linux", "X-BBL-OS-Version": "6.2.0",
    "X-BBL-Agent-Version": "01.09.05.01", "X-BBL-Executable-info": "{}", "X-BBL-Agent-OS-Type": "linux", "accept": "application/json"]
@Field static final Map MODES            = [monitor: "Monitor only (works with Bambu Cloud and the Bambu apps)",
                                            control: "Monitor and control (needs Developer Mode, no Bambu Cloud)"]

preferences {
    page(name: "mainPage")
    page(name: "printerPage")
    page(name: "equipmentPage")
    page(name: "tipsPage")
    page(name: "cloudPage")
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
            List pending = sl.findAll { int i -> !getChildDevice(printerDni(i)) }
            if (!sl) paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>No printers yet. Tap <b>Find printers</b> to search your network.</div>"
            (sl - pending).each { int i -> printerCard(i) }
            pending.each { int i -> newPrinterCard(i) }
            input "findPrinters", "button", title: "Find printers", width: 4, inputClass: "app-ui-button"
            if (!pending) input "addPrinter", "button", title: "Add by hand", width: 4, inputClass: "app-ui-button"
            findResults()
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
            accessCodesBlock()
            input "findSecs", "enum", title: "Find printers searches for", options: ["5": "5 seconds", "10": "10 seconds", "20": "20 seconds", "30": "30 seconds"],
                defaultValue: "10", width: 12
            input "searchAfter", "enum", title: "Search for a lost printer after", options: ["2": "2 minutes", "5": "5 minutes", "10": "10 minutes", "15": "15 minutes"],
                defaultValue: "5", width: 12
            input "stopAfter", "enum", title: "Stop searching after", options: ["6": "6 hours", "24": "24 hours", "72": "3 days", "0": "Never"],
                defaultValue: "24", width: 12
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

private void printerCard(int i) {
    def dev = getChildDevice(printerDni(i))
    paragraph rawHtml: true, printerCardHtml(i, dev)
    href name: "editP_${i}", title: "Edit Printer", description: "", page: "printerPage", params: [slot: i], width: 3
    input "refreshP_${i}", "button", title: "Refresh", width: 3, inputClass: "app-ui-button"
    input "remove_${i}", "button", title: state.confirmRemove == i ? "Tap again to remove" : "Remove Printer", width: 3, inputClass: "app-ui-button"
    if ((dev.currentValue("connectionStatus") ?: "").toString().startsWith("Offline")) {
        input "searchP_${i}", "button", title: "Search now", width: 3, inputClass: "app-ui-button"
    }
    paragraph rawHtml: true, "<div class='bb-card-end'></div>"
}

// A printer not saved yet: its fields, then Save printer (creates and connects it) or Cancel.
private void newPrinterCard(int i) {
    String nm = settings["p${i}_name"]?.toString()?.trim()
    paragraph rawHtml: true, "<div class='bb-new-head'><span class='bb-chip'>New printer</span>" +
        (nm ? " <span style='font-weight:600;margin-left:6px'>${esc(nm)}</span>" : "") + "</div>"
    printerFields(i, true)
    if (!settings["p${i}_code"]) {
        paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>The access code is on the printer under <b>Settings, Network</b> " +
            "(<b>WLAN</b> on some models)${cloudOn() ? '' : ', or set Access codes to Sign in with Bambu to fill it in'}.</div>"
    }
    if (state.saveError == i) paragraph rawHtml: true, warningMessageHtml("Enter the IP address and access code, then tap <b>Save printer</b>.")
    input "saveP_${i}", "button", title: "Save printer", width: 4, inputClass: "app-ui-button app-ui-button-primary"
    input "cancelP_${i}", "button", title: "Cancel", width: 3, inputClass: "app-ui-button"
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
            if (!dev) { paragraph "That printer isn't set up. Go back and use Find printers or Add by hand."; return }
            paragraph rawHtml: true, "<div class='font-semibold'>${esc(dev.displayName)} <span class='text-color-secondary' style='font-weight:400;font-size:14px;'>" +
                "${esc(dev.currentValue('connectionStatus') ?: '')}</span></div>"
            printerFields(i)
            paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;margin-top:8px'>Tap <b>Done</b> to save. The printer reconnects with the new settings.</div>"
        }
    }
}

// live: a new printer's fields save as you leave each box, so Save printer sees them.
private void printerFields(int i, boolean live = false) {
    String s = settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase()
    input "p${i}_name", "text", title: "Name", required: false, width: 6, submitOnChange: live
    input "p${i}_ip", "text", title: "IP address", description: "e.g. 192.168.1.60", required: false, width: 6, submitOnChange: live
    input "p${i}_code", "text", title: "LAN access code", description: "8 characters, shown on the printer", required: false, width: 6, submitOnChange: live
    input "p${i}_serial", "text", title: "Serial number (optional)", description: "Found automatically", required: false, width: 6, submitOnChange: live
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
        page: "tipsPage", params: [topic: ctl ? "control" : "modes"], width: live ? 12 : 8, style: "margin:8px 0;"
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
    if (busy) line = conn == "Searching" ? "Searching the network for this printer&hellip;" : conn == "Connecting" ? "Connecting&hellip;" : "Reconnecting&hellip;"
    else if (conn == "Offline") line = "Offline &middot; not found on the network, still checking"
    else if (conn == "Offline, search stopped") line = "Offline &middot; search stopped. Tap Search now to look again"
    else if (!ok) line = conn == "Waiting for printer" ? "Connected, waiting for the printer's first status" : conn
    else if (ps in ["Printing", "Paused", "Preparing"]) {
        def pct = dev.currentValue("progress"), left = dev.currentValue("timeRemaining (min)")
        String stg = dev.currentValue("stage")
        String head = ps == "Paused" && stg && stg != "Paused" ? "<span class='hub-text-warning'>${esc(stg)}</span>" : ps
        line = head + (pct != null ? " &middot; ${pct}%" : "") + (left ? " &middot; ${hm(left as int)} left" : "") +
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
        else c << "<div class='hub-text-warning' style='margin-top:4px;font-size:0.85em'>&#9888; ${errs} printer error${errs == 1 ? '' : 's'}: ${esc(dev.currentValue('errorCodes') ?: '')}</div>"
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
            List lows = (k.currentValue("lowSlots") ?: "").toString().tokenize(", ")
            def idm = k.deviceNetworkId =~ /-ams(\d+)$/
            boolean ht = idm.find() && (idm.group(1) as int) >= 128
            String dots = (1..4).findAll { n -> k.currentValue("tray${n}") != null }.collect { n ->
                String t = k.currentValue("tray${n}").toString()
                String col = k.currentValue("tray${n}Color")
                def pm = t =~ /, (\d+)%$/
                String pct = pm.find() ? pm.group(1) + "%" : ""
                String dot = col && col != "none" ? "<span class='bb-dot' style='background:${esc(col)}'></span>" : "<span class='bb-dot' style='background:transparent'></span>"
                "<span class='bb-slot${pct ? ' has-pct' : ''}' title='${ht ? '' : 'Slot ' + n + ': '}${esc(t)}'>${ht ? '' : "<span class='bb-n'>${n}</span>"}${dot}" +
                    (pct ? "<span class='bb-pct${lows.contains(n.toString()) ? ' hub-text-warning' : ''}'>${pct}</span>" : "") + "</span>"
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

// "H2D #1 AMS A" on printer "H2D #1" shows as "AMS A".
private String shortName(k, dev) {
    String n = k.displayName ?: ""
    String p = dev.displayName ?: ""
    return n.startsWith(p + " ") ? n.substring(p.length() + 1) : n
}

private String hm(int mins) { mins >= 60 ? "${(mins / 60) as int}h ${mins % 60}m" : "${mins}m" }

def appButtonHandler(String btn) {
    if (btn.startsWith("cloud")) { cloudButton(btn); return }
    if (btn in ["findPrinters", "searchAgain"]) { state.remove("confirmRemove"); state.showFind = true; startSearch("button"); return }
    if (btn == "clearFind") { ["showFind", "findResults"].each { state.remove(it) }; return }
    def af = btn =~ /^addFound_([A-Z0-9]+)$/
    if (af.find()) { addFound(af.group(1)); return }
    def sp = btn =~ /^searchP_(\d+)$/
    if (sp.find()) {
        def d = getChildDevice(printerDni(sp.group(1).toInteger()))
        if (d) try { d.searchNow() } catch (e) { logErr "could not start a search for ${d.displayName}: ${e.message}" }
        return
    }
    def pm = btn =~ /^refreshP_(\d+)$/
    if (pm.find()) {
        state.remove("confirmRemove")
        def d = getChildDevice(printerDni(pm.group(1).toInteger()))
        if (d) try { d.refresh() } catch (e) { }
        return
    }
    if (btn == "addPrinter") { if (!slots().any { int i -> !getChildDevice(printerDni(i)) }) newSlot(); return }
    def sv = btn =~ /^saveP_(\d+)$/
    if (sv.find()) { savePrinter(sv.group(1).toInteger()); return }
    def cx = btn =~ /^cancelP_(\d+)$/
    if (cx.find()) {
        int i = cx.group(1).toInteger()
        if (!getChildDevice(printerDni(i))) { ["name", "ip", "code", "serial", "mode", "allowStop"].each { app.removeSetting("p${i}_${it}") }; state.slots = slots() - i }
        state.remove("saveError")
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

// ============================================================================
// Find printers
// ============================================================================

// Search results collect here while a search runs, then go to state once at the end.
@Field static final java.util.concurrent.ConcurrentHashMap APPMEM = new java.util.concurrent.ConcurrentHashMap()

private Map appMem() {
    String k = app.id.toString()
    Map m = APPMEM.get(k) as Map
    if (m == null) { m = new java.util.concurrent.ConcurrentHashMap(); APPMEM.put(k, m) }
    return m
}

private int findSecs() { ((settings.findSecs ?: "10") as String).toInteger() }

// For the Printers: when to search for a lost printer and when to stop (0 = never).
Map recoveryOpts() {
    [afterMin: ((settings.searchAfter ?: "5") as String).toInteger(), stopHours: ((settings.stopAfter ?: "24") as String).toInteger()]
}

// For the Printers: this one is lost, search and answer it with searchResult().
void findLostPrinter(String dni) {
    List req = (state.searchReq ?: []) as List
    if (!req.contains(dni)) state.searchReq = req + dni
    startSearch("auto")
}

private boolean searching() {
    Map s = (state.search ?: [:]) as Map
    return s.running && now() < ((s.until ?: 0L) as long) + 5000L
}

private void startSearch(String reason) {
    if (searching()) {
        if (reason == "button" && state.search.reason != "button") { Map s = state.search; s.reason = "button"; state.search = s }
        return
    }
    int secs = findSecs()
    Map m = appMem()
    m.found = new java.util.concurrent.ConcurrentHashMap()
    m.via = new java.util.concurrent.ConcurrentHashMap()   // used as a set
    m.remove("sample")
    state.search = [running: true, reason: reason, startedAt: now(), until: now() + secs * 1000L]
    logInfo "searching the network for printers for ${secs} s${reason == 'auto' ? ' (a printer is offline)' : ''}"
    sendDiscovery()
    if (secs >= 7) runIn(3, "searchBurst", [data: [n: 2]])
    runIn(secs, "searchDone")
}

def searchBurst(Map data) {
    if (!searching()) return
    sendDiscovery()
    int n = ((data?.n ?: 2) as int)
    if (n < 3 && now() + 3500L < (state.search.until as long)) runIn(3, "searchBurst", [data: [n: n + 1]])
}

// Bambu printers answer SSDP on 1990 (2021 for broadcasts), not the standard 1900, so ask every way the hub can.
private void sendDiscovery() {
    [["239.255.255.250:1990", "udpReplyMulticast"], ["255.255.255.255:1990", "udpReplyBroadcast"],
     ["255.255.255.255:2021", "udpReply2021"], ["239.255.255.250:1900", "udpReply1900"]].each { d ->
        String msg = "M-SEARCH * HTTP/1.1\r\nHOST: ${d[0]}\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ${SSDP_ST}\r\n\r\n"
        try {
            sendHubCommand(new hubitat.device.HubAction(msg, hubitat.device.Protocol.LAN,
                [type: hubitat.device.HubAction.Type.LAN_TYPE_UDPCLIENT, destinationAddress: d[0], callback: d[1], timeout: findSecs()]))
        } catch (e) { logDbg "search to ${d[0]} not sent: ${e.message}" }
    }
    try { sendHubCommand(new hubitat.device.HubAction("lan discovery ${SSDP_ST}", hubitat.device.Protocol.LAN)) }
    catch (e) { logDbg "hub SSDP search not sent: ${e.message}" }
}

def udpReplyMulticast(resp) { gotReply(resp, "UDP 1990 multicast") }
def udpReplyBroadcast(resp) { gotReply(resp, "UDP 1990 broadcast") }
def udpReply2021(resp)      { gotReply(resp, "UDP 2021 broadcast") }
def udpReply1900(resp)      { gotReply(resp, "UDP 1900") }
def ssdpEvent(evt)          { gotReply(evt?.description, "hub SSDP") }

private void gotReply(resp, String via) {
    String desc = null
    try { desc = resp instanceof String ? resp : resp?.description } catch (e) { }
    if (!desc) desc = resp?.toString()
    if (!desc) return
    Map lan = [:]
    try { lan = parseLanMessage(desc) ?: [:] } catch (e) { }
    Map r = parseBambuReply(desc, lan)
    Map m = appMem()
    if (m.sample == null) m.sample = "${via}: ${desc.take(300)}"
    if (!r) { logDbg "search reply via ${via} wasn't a Bambu printer: ${desc.take(200)}"; return }
    Map found = m.found as Map
    if (found == null) return   // late reply after the search ended
    if (!found.containsKey(r.serial)) logDbg "search found ${r.serial} at ${r.ip} via ${via}"
    found[r.serial] = r + [via: via]
    try { (m.via as Map)[via] = true } catch (e) { }
}

// Reply text (or the hub's parsed SSDP fields) -> [serial, ip, name, model], or null if it isn't a Bambu printer.
private Map parseBambuReply(String desc, Map lan) {
    String text = ""
    if (lan?.payload) text = hexText(lan.payload.toString())
    else if (lan?.body) text = lan.body.toString()
    if (!text && desc.toLowerCase().contains("bambulab")) text = desc
    Map h = [:]
    text.split(/\r?\n/).each { line ->
        int c = line.indexOf(":")
        if (c > 0) h[line.substring(0, c).trim().toLowerCase()] = line.substring(c + 1).trim()
    }
    String term = (h.st ?: h.nt ?: lan?.ssdpTerm ?: "").toString()
    if (!term.toLowerCase().contains("bambulab")) return null
    String usn = (h.usn ?: lan?.ssdpUSN ?: "").toString().replaceFirst(/(?i)^uuid:/, "").replaceFirst(/::.*$/, "").trim().toUpperCase()
    String ip = firstIp(h.location) ?: firstIp(lan?.ssdpPath) ?: hexIp(lan?.networkAddress) ?: firstIp(lan?.ip)
    if (!validSerial(usn) || !ip) return null
    String code = h["devmodel.bambu.com"]
    return [serial: usn, ip: ip, name: h["devname.bambu.com"] ?: null, model: code ? (MODEL_NAMES[code] ?: code) : null]
}

private String firstIp(v) {
    def mt = (v ?: "").toString() =~ /(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})/
    return mt.find() ? mt.group(1) : null
}

private String hexIp(v) {
    String h = (v ?: "").toString()
    if (!(h ==~ /[0-9A-Fa-f]{8}/)) return null
    return (0..3).collect { Integer.parseInt(h.substring(it * 2, it * 2 + 2), 16) }.join(".")
}

private String hexText(String hex) {
    if (!(hex ==~ /([0-9A-Fa-f]{2})+/)) return hex
    byte[] b = new byte[hex.length().intdiv(2) as int]
    for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16)
    return new String(b, "UTF-8")
}

def searchDone() {
    Map s = (state.search ?: [:]) as Map
    Map m = appMem()
    Map found = new LinkedHashMap((m.found ?: [:]) as Map)
    List via = ((m.via ?: [:]) as Map).keySet().collect { it.toString() }
    m.remove("found")
    s.running = false
    s.count = found.size()
    s.via = via
    state.search = s
    // Any search moves a printer that's not connected and answered from a new IP.
    Set moved = [] as Set
    slots().each { int i ->
        def dev = getChildDevice(printerDni(i))
        String sn = dev ? serialFor(i, dev) : null
        Map f = sn ? found[sn] as Map : null
        if (!f) return
        String cur = settings["p${i}_ip"]?.toString()?.trim()
        boolean up = dev.currentValue("connectionStatus") in ["Connected", "Waiting for printer"]
        if (f.ip != cur && !up) { movePrinter(i, dev, sn, f.ip as String); moved << sn }
    }
    ((state.searchReq ?: []) as List).each { dni ->
        def dev = getChildDevice(dni as String)
        String sn = null
        try { sn = dev?.getSerial() } catch (e) { }
        if (dev && !(sn in moved)) try { dev.searchResult(sn ? found[sn]?.ip : null) } catch (e) { }
    }
    state.remove("searchReq")
    if (s.reason == "button") {
        state.findResults = [at: now(), found: found.collectEntries { k, v -> [(k): (v as Map) + [moved: moved.contains(k)]] }]
    }
    if (found) logInfo "search found ${countText(found.size(), 'printer')} (${via.join(', ')})"
    else logInfo "no printers answered the search${m.sample ? '. First reply: ' + m.sample : ''}"
}

private String serialFor(int i, dev) {
    String sn = null
    try { sn = dev?.getSerial() } catch (e) { }
    if (!sn) sn = settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase()
    return sn && validSerial(sn) ? sn : null
}

private Integer slotForSerial(String sn) {
    slots().find { int i -> serialFor(i, getChildDevice(printerDni(i))) == sn }
}

// The search found a known printer at a new address: update the app and reconnect it there.
private void movePrinter(int i, dev, String sn, String newIp) {
    String old = settings["p${i}_ip"]?.toString()?.trim()
    app.updateSetting("p${i}_ip", [value: newIp, type: "text"])
    if (!validSerial(settings["p${i}_serial"]?.toString()?.trim()?.toUpperCase() ?: "")) app.updateSetting("p${i}_serial", [value: sn, type: "text"])
    boolean ctl = settings["p${i}_mode"] == "control"
    dev.configure([slot: i, serial: sn, name: settings["p${i}_name"]?.toString()?.trim() ?: "Bambu Printer ${i}", ip: newIp],
        [control: ctl, allowStop: ctl && settings["p${i}_allowStop"] == true])
    log.info "${APP_NAME}: ${dev.displayName} moved from ${old} to ${newIp}. Updated and reconnecting."
    try { dev.ipMoved(old, newIp) } catch (e) { logErr "could not reconnect ${dev.displayName}: ${e.message}" }
}

private int newSlot() {
    List sl = slots()
    int next = Math.max(((state.maxSlot ?: 0) as int), (sl ? sl.max() : 0)) + 1
    state.maxSlot = next
    state.slots = sl + next
    state.remove("confirmRemove")
    return next
}

private void addFound(String sn) {
    Map f = ((state.findResults?.found ?: [:]) as Map)[sn] as Map
    if (!f || slotForSerial(sn)) return
    int i = newSlot()
    state.remove("showFind")   // straight to its New printer box
    app.updateSetting("p${i}_name", [value: f.name ?: "Bambu ${f.model ?: 'Printer'}", type: "text"])
    app.updateSetting("p${i}_ip", [value: f.ip, type: "text"])
    app.updateSetting("p${i}_serial", [value: sn, type: "text"])
    String code = cloudOn() ? cloudCode(sn) : null
    if (code) app.updateSetting("p${i}_code", [value: code, type: "text"])
}

// Under the Find printers button: searching, what was found, or help when nothing answered.
private void findResults() {
    if (!state.showFind) return
    if (searching()) {
        long left = Math.max(1L, (((state.search.until as long) - now()) / 1000L) as long)
        paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;margin:6px 0'><i class='pi pi-spin pi-spinner mr-2' aria-hidden='true'></i>" +
            "Searching your network&hellip; about ${left} s</div><script>setTimeout(function(){location.reload()},${(left + 2) * 1000});</script>"
        input "checkFind", "button", title: "Show results", width: 4, inputClass: "app-ui-button"
        return
    }
    Map found = ((state.findResults?.found ?: [:]) as Map)
    if (!found) {
        paragraph rawHtml: true, warningMessageHtml("<b>No printers found.</b> Check that the printer is on and on the same network as your hub " +
            "(guest and IoT networks often block discovery), then tap <b>Search again</b>. You can also tap <b>Add by hand</b> and enter the IP. " +
            "It's on the printer screen under Settings, Network.")
    }
    found.sort { a, b -> (a.value.name ?: a.key) <=> (b.value.name ?: b.key) }.each { sn, f ->
        Integer slot = slotForSerial(sn as String)
        boolean made = slot && getChildDevice(printerDni(slot))
        String tag = !slot ? "" : made ? "<span class='bb-chip' style='margin-left:6px'>Already added</span>" +
            (f.moved ? " <span class='hub-text-success' style='font-size:0.85em'>IP updated</span>" : "") :
            "<span class='hub-text-warning' style='font-size:0.85em;margin-left:6px'>Added below. " +
            (settings["p${slot}_code"] || (cloudOn() && cloudCode(sn as String)) ? "Its access code is filled in from Bambu. Tap Save printer." : "Enter its access code and tap Save printer.") + "</span>"
        String chip = f.model ? "<span style='background:#00ae42;color:#fff;border-radius:8px;padding:2px 9px;font-size:0.9em;font-weight:bold;margin-right:6px'>${esc(f.model)}</span>" : ""
        paragraph rawHtml: true, "<div style='padding:6px 0'>${chip}<span style='font-weight:600'>${esc(f.name ?: 'Bambu printer')}</span> " +
            "<span class='text-color-secondary' style='font-size:0.8em'>${esc(sn)} &middot; ${esc(f.ip)}</span>${tag}</div>", width: slot ? 12 : 9
        if (!slot) input "addFound_${sn}", "button", title: "Add", width: 3, inputClass: "app-ui-button"
    }
    if (cloudOn() && state.cloudToken) {
        List missing = cloudPrinters().keySet().findAll { !found.containsKey(it) }.collect { cloudPrinters()[it].name ?: it }
        if (missing) paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:0.85em;padding:4px 0'>On your Bambu account but not found on this network: " +
            missing.collect { esc(it) }.join(", ") + "</div>"
    }
    input "searchAgain", "button", title: "Search again", width: 3, inputClass: "app-ui-button"
    input "clearFind", "button", title: "Close", width: 3, inputClass: "app-ui-button"
    href name: "findHelp", title: "How finding printers works", description: "", page: "tipsPage", params: [topic: "find"], width: 6, style: "margin:8px 0;"
    paragraph rawHtml: true, "<div class='bb-card-end'></div>"
}

// ============================================================================
// Access codes: Sign in with Bambu (optional)
// ============================================================================

private boolean cloudOn() { settings.codeSource == "cloud" }
private Map cloudPrinters() { (state.cloudPrinters ?: [:]) as Map }
private String cloudCode(String sn) { sn ? cloudPrinters()[sn]?.code?.toString() : null }

// Main page: the choice, and with Sign in with Bambu, the account line.
private void accessCodesBlock() {
    if (!cloudOn() && state.cloudToken) cloudSignOut("turned off")
    input "codeSource", "enum", title: "Access codes", options: CODE_SOURCES, defaultValue: "hand", submitOnChange: true, width: 12,
        style: "margin-left: 0.5em; margin-right: 0.5em; padding-right:0;"
    if (!cloudOn()) return
    String desc
    if (state.cloudToken) {
        int n = cloudPrinters().size()
        desc = "Signed in as ${esc(state.cloudEmail)} &middot; ${countText(n, 'printer')}"
    } else if (state.cloudExpired) {
        desc = "<span class='hub-text-warning'>The Bambu sign-in expired. Tap to sign in again.</span>"
    } else {
        desc = "Not signed in yet. Tap to sign in."
    }
    href name: "toCloud", title: "<i class='fa-solid fa-cloud mr-2' aria-hidden='true'></i>Bambu account", description: desc, page: "cloudPage", width: 12, style: "margin:4px 8px 8px;"
}

def cloudPage() {
    dynamicPage(name: "cloudPage", title: "Bambu account", install: false, uninstall: false) {
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, "<style>${appPageSpacingCss()}</style>" +
                "<div class='text-color-secondary' style='font-size:14px;'>Used only to read your printers' access codes. Your password isn't saved, " +
                "and the printers are still monitored on your own network.</div>"
            if (state.cloudToken) {
                paragraph rawHtml: true, "<div class='hub-text-success' style='margin-top:8px'>Signed in as ${esc(state.cloudEmail)}.</div>"
                input "cloudRefresh", "button", title: "Refresh from Bambu", width: 4, inputClass: "app-ui-button"
                input "cloudSignOut", "button", title: "Sign out", width: 4, inputClass: "app-ui-button"
            } else {
                input "cloudEmail", "text", title: "Bambu email", required: false, width: 6, submitOnChange: true
                input "cloudPassword", "password", title: "Bambu password", required: false, width: 6, submitOnChange: true
                input "cloudSignIn", "button", title: "Sign in", width: 4, inputClass: "app-ui-button"
                if (state.cloudStep == "code") {
                    paragraph rawHtml: true, "<div style='margin-top:8px'>Bambu emailed a 6-digit code to ${esc(state.cloudEmail)}. Enter it, tap outside the box, then <b>Submit code</b>.</div>"
                    input "cloudCode", "text", title: "Email code", required: false, width: 6, submitOnChange: true
                    input "cloudSendCode", "button", title: "Submit code", width: 4, inputClass: "app-ui-button"
                }
                if (state.cloudStep == "tfa") {
                    paragraph rawHtml: true, "<div style='margin-top:8px'>Two-step login is on. Enter the code from your authenticator app.</div>"
                    input "cloudTfa", "text", title: "Two-step code", required: false, width: 6, submitOnChange: true
                    input "cloudSendTfa", "button", title: "Submit code", width: 4, inputClass: "app-ui-button"
                }
                paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:13px;margin-top:8px'>Signed up with Google or Apple? " +
                    "Set a Bambu password first: in the Bambu Handy app, tap the person icon, then Account Security, Change Password.</div>"
            }
            if (state.cloudMsg) paragraph rawHtml: true, "<div class='${state.cloudMsgOk ? 'hub-text-success' : 'hub-text-warning'}' style='margin-top:8px'>${state.cloudMsg}</div>"
            Map cp = cloudPrinters()
            if (state.cloudToken && cp) {
                paragraph rawHtml: true, "<div class='app-heading font-semibold' style='margin-top:12px'>Printers on this account</div>"
                input "cloudShowCodes", "bool", title: "Show access codes", defaultValue: false, submitOnChange: true, width: 12
                cp.sort { a, b -> (a.value.name ?: a.key) <=> (b.value.name ?: b.key) }.each { sn, p ->
                    Integer slot = slotForSerial(sn as String)
                    String code = p.code ? (settings.cloudShowCodes ? esc(p.code) : "&bull;&bull;&bull;&bull;&bull;&bull;" + esc(last2(p.code.toString()))) : "not given"
                    paragraph rawHtml: true, "<div style='padding:4px 0'><b>${esc(p.name ?: 'Printer')}</b> " +
                        "<span class='text-color-secondary' style='font-size:0.85em'>${esc(p.model ?: '')} &middot; ${esc(sn)} &middot; ${p.online ? 'online' : 'offline'}" +
                        " &middot; code ${code}${slot ? ' &middot; in Hubitat' : ''}</span></div>"
                }
            }
        }
    }
}

private String last2(String s) { s.length() > 2 ? s.substring(s.length() - 2) : s }

private void cloudButton(String btn) {
    state.remove("cloudMsg")
    switch (btn) {
        case "cloudSignIn": cloudPasswordLogin(); break
        case "cloudSendCode": cloudCodeLogin(); break
        case "cloudSendTfa": cloudTfaLogin(); break
        case "cloudRefresh": cloudFetch(true); break
        case "cloudSignOut": cloudSignOut("signed out"); break
    }
}

private void cloudMsg(String m, boolean ok = false) { state.cloudMsg = m; state.cloudMsgOk = ok }

private void cloudPasswordLogin() {
    String em = settings.cloudEmail?.toString()?.trim()
    String pw = settings.cloudPassword?.toString()
    app.removeSetting("cloudPassword")
    if (!em || !pw) { cloudMsg("Enter your Bambu email and password first."); return }
    state.cloudEmail = em
    Map r = cloudSend("post", BBL_LOGIN, [account: em, password: pw, apiError: ""], true)
    if (r.status != 200 || !(r.data instanceof Map)) { cloudMsg(r.error ?: "Bambu didn't accept the sign-in. Check the email and password."); return }
    Map d = r.data as Map
    if (d.accessToken) { cloudGotToken(d.accessToken as String); return }
    if (d.loginType == "verifyCode") {
        state.cloudStep = "code"
        Map c = cloudSend("post", BBL_EMAIL, [email: em, type: "codeLogin"], true)
        cloudMsg(c.status == 200 ? "Code sent. Check your email." : (c.error ?: "Bambu couldn't send the email code."), c.status == 200)
    } else if (d.loginType == "tfa") {
        state.cloudStep = "tfa"
        state.cloudTfaKey = d.tfaKey
    } else {
        cloudMsg("Bambu answered in a way this version doesn't know (${esc(d.loginType ?: 'no sign-in type')}). Post it in the community thread.")
    }
}

private void cloudCodeLogin() {
    String c = settings.cloudCode?.toString()?.trim()
    app.removeSetting("cloudCode")
    if (!c) { cloudMsg("No code reached the app. Type it, tap outside the box, then Submit code."); return }
    Map r = cloudSend("post", BBL_LOGIN, [account: state.cloudEmail, code: c], true)
    if (r.status == 200 && r.data?.accessToken) cloudGotToken(r.data.accessToken as String)
    else if (r.status == 400 && r.data?.code == 1) cloudMsg("That code expired. Tap Sign in for a new one.")
    else if (r.status == 400) cloudMsg("That code didn't work. Check it and try again.")
    else cloudMsg(r.error ?: "Bambu didn't accept the code.")
}

private void cloudTfaLogin() {
    String c = settings.cloudTfa?.toString()?.trim()
    app.removeSetting("cloudTfa")
    if (!c) { cloudMsg("Enter the two-step code first."); return }
    String csrf = null, tok = null
    try { httpGet([uri: BBL_CSRF, headers: STUDIO_HEADERS, timeout: 20]) { resp -> csrf = cookieOf(resp, "bbl_csrf_token") } }
    catch (e) { logDbg "Bambu CSRF: ${e.message}" }
    if (!csrf) { cloudMsg("Bambu's two-step sign-in didn't start. Try again in a minute."); return }
    try {
        httpPost([uri: BBL_TFA, requestContentType: "application/json", contentType: "text/plain", timeout: 20,
                  headers: STUDIO_HEADERS + ["x-bbl-csrf-token": csrf, "Cookie": "bbl_csrf_token=" + csrf],
                  body: [tfaKey: state.cloudTfaKey, tfaCode: c]]) { resp -> tok = cookieOf(resp, "token") }
    } catch (e) { logDbg "Bambu two-step: ${e.message}" }
    if (tok) cloudGotToken(tok) else cloudMsg("That two-step code didn't work. Check it and try again.")
}

private void cloudGotToken(String t) {
    state.cloudToken = t
    ["cloudStep", "cloudTfaKey", "cloudExpired"].each { state.remove(it) }
    logInfo "signed in to Bambu as ${state.cloudEmail}"
    cloudFetch(true)
}

private void cloudSignOut(String why) {
    ["cloudToken", "cloudPrinters", "cloudStep", "cloudTfaKey", "cloudExpired", "cloudFetchedAt"].each { state.remove(it) }
    logInfo "Bambu sign-in removed (${why}). Access codes already filled in stay."
    if (why == "signed out") cloudMsg("Signed out. Access codes already filled in stay.", true)
}

// The account's printers. fill: put codes into printers that have none.
private boolean cloudFetch(boolean fill) {
    if (!state.cloudToken) return false
    Map r = cloudSend("get", BBL_BIND, null, false)
    if (r.status == 401) {
        ["cloudToken", "cloudPrinters"].each { state.remove(it) }
        state.cloudExpired = true
        cloudMsg("The Bambu sign-in expired. Sign in again.")
        logErr "the Bambu sign-in expired. Open the app, Bambu account, and sign in again."
        return false
    }
    if (r.status != 200 || !(r.data instanceof Map)) { cloudMsg(r.error ?: "Couldn't read your printers from Bambu."); return false }
    Map out = [:]
    ((r.data.devices ?: []) as List).each { d ->
        String sn = d.dev_id?.toString()?.toUpperCase()
        if (sn && validSerial(sn)) out[sn] = [name: d.name, model: d.dev_product_name ?: d.dev_model_name, code: d.dev_access_code, online: d.online == true]
    }
    state.cloudPrinters = out
    state.cloudFetchedAt = now()
    int filled = fill ? cloudFillCodes() : 0
    cloudMsg("${countText(out.size(), 'printer')} on your Bambu account${filled ? ', access code filled in for ' + countText(filled, 'printer') : ''}.", true)
    return true
}

// Printers in the app with a known serial and no access code get the account's code.
private int cloudFillCodes() {
    int n = 0
    slots().each { int i ->
        String sn = serialFor(i, getChildDevice(printerDni(i)))
        String code = cloudCode(sn)
        if (code && !settings["p${i}_code"]) { app.updateSetting("p${i}_code", [value: code, type: "text"]); n++ }
    }
    return n
}

// From a Printer when its access code is rejected: with Sign in with Bambu, fetch the current code.
void accessCodeRejected(String dni) {
    if (!cloudOn() || !state.cloudToken) return
    Map last = (state.codeFetchAt ?: [:]) as Map
    if (now() - ((last[dni] ?: 0L) as long) < 1800000L) return
    last[dni] = now()
    state.codeFetchAt = last
    runIn(2, "cloudRefreshCode", [data: [dni: dni]])
}

def cloudRefreshCode(Map data) {
    def mt = (data?.dni ?: "") =~ /^bambu-p(\d+)$/
    if (!mt.find()) return
    int i = mt.group(1).toInteger()
    def dev = getChildDevice(printerDni(i))
    if (!dev || !cloudFetch(false)) return
    String sn = serialFor(i, dev)
    String code = cloudCode(sn)
    if (!code) { logErr "${dev.displayName}: the printer rejected its access code and Bambu has none for it. Check the code on the printer."; return }
    if (code == settings["p${i}_code"]?.toString()?.trim()) {
        logErr "${dev.displayName}: the printer rejected its access code, and Bambu has the same one. Check the code on the printer."
        return
    }
    app.updateSetting("p${i}_code", [value: code, type: "text"])
    log.info "${APP_NAME}: ${dev.displayName}'s access code changed on the printer. Updated from your Bambu account and reconnecting."
    try { dev.codeUpdated() } catch (e) { logErr "could not reconnect ${dev.displayName}: ${e.message}" }
}

// Sign-in posts work with Bambu Studio headers, the printer list only with the token; each tries the other on a Cloudflare page.
private Map cloudSend(String method, String url, Map body, boolean studioFirst) {
    Map r = cloudOnce(method, url, body, studioFirst)
    if (r.blocked) {
        logDbg "Bambu ${method} ${url}: Cloudflare page, trying ${studioFirst ? 'plain' : 'Studio'} headers"
        Map r2 = cloudOnce(method, url, body, !studioFirst)
        if (r2.blocked) logErr "Bambu's Cloudflare protection blocked the hub (${url.replaceFirst('https://', '')}). Try again later, or enter the access code by hand."
        return r2
    }
    return r
}

private Map cloudOnce(String method, String url, Map body, boolean studio) {
    Map h = studio ? new LinkedHashMap(STUDIO_HEADERS) : ["accept": "application/json"]
    if (state.cloudToken && method == "get") h.Authorization = "Bearer " + state.cloudToken
    Map p = [uri: url, headers: h, requestContentType: "application/json", contentType: "text/plain", timeout: 20]
    if (body != null) p.body = body
    Map out = [status: null, data: null]
    String raw = null
    try {
        Closure c = { resp -> out.status = resp.status; raw = bodyText(resp.data) }
        if (method == "post") httpPost(p, c) else httpGet(p, c)
    } catch (e) {
        try { out.status = e.statusCode as Integer } catch (x) { }
        try { raw = bodyText(e.response?.data) } catch (x) { }
        if (out.status == null) { out.error = "Couldn't reach Bambu: " + esc(e.message); return out }
    }
    String txt = (raw ?: "").trim()
    if (txt.startsWith("{") || txt.startsWith("[")) { try { out.data = new groovy.json.JsonSlurper().parseText(txt) } catch (x) { } }
    out.blocked = txt.startsWith("<") || (out.status in [403, 429] && txt.toLowerCase().contains("cloudflare"))
    if (out.blocked) out.error = "Bambu's Cloudflare protection blocked the request. Try again later, or enter the access code by hand."
    logDbg "Bambu ${method} ${url.replaceFirst('https://', '')}: HTTP ${out.status}${studio ? '' : ' (token only)'}"
    return out
}

private String bodyText(d) {
    if (d == null) return null
    if (d instanceof String) return d
    if (d instanceof Map || d instanceof List) return groovy.json.JsonOutput.toJson(d)
    try { return d.text } catch (e) { return d.toString() }
}

private String cookieOf(resp, String name) {
    String found = null
    try {
        resp.headers.each { h ->
            if (h.name?.equalsIgnoreCase("Set-Cookie")) {
                String v = h.value ?: ""
                int a = v.indexOf(name + "=")
                if (a == 0 || (a > 0 && v.substring(a - 1, a) in [";", " "])) {
                    int b = v.indexOf(";", a)
                    found = v.substring(a + name.length() + 1, b > 0 ? b : v.length())
                }
            }
        }
    } catch (e) { }
    return found
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
        if (!devs) section { paragraph "No printers yet. Add one on the main page first." }
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
    if (!cfg) return [ok: false, title: "Setup required", summary: "Tap <b>Find printers</b> to search your network, or <b>Add by hand</b>."]
    List devs = printerDevices()
    if (!devs) return [ok: false, title: "Not connected yet", summary: "Tap <b>Save printer</b> to connect."]
    List bad = devs.findAll { !(it.currentValue("connectionStatus") in ["Connected", "Waiting for printer"]) && !reconnecting(it) }
    String counts = countText(devs.size(), "printer")
    List busy = devs.findAll { reconnecting(it) }
    if (!bad && busy) return [ok: true, title: busy.size() == 1 ? "${esc(busy[0].displayName)} reconnecting" : "${busy.size()} printers reconnecting",
                              summary: "${counts} &middot; reconnecting on its own"]
    if (!bad) {
        List printing = devs.findAll { it.currentValue("printState") == "Printing" }
        return [ok: true, title: "Integration ready", summary: "${counts}${printing ? ' &middot; ' + printing.size() + ' printing' : ''}"]
    }
    if (bad.any { it.currentValue("connectionStatus") == "Access code rejected" }) {
        return [ok: false, title: "Access code rejected", summary: "Check the LAN access code on the printer, update it below and tap <b>Done</b>."]
    }
    return [ok: false, title: bad.size() == 1 ? "${esc(bad[0].displayName)} offline" : "${bad.size()} printers offline",
            summary: "${counts} &middot; still checking on its own. Check the printer is on and on the network, or tap <b>Search now</b>."]
}

// Connecting, Reconnecting and Searching are the printer recovering on its own, not an outage. The driver
// moves to Offline when its searches fail.
private boolean reconnecting(dev) { dev.currentValue("connectionStatus") in ["Connecting", "Reconnecting", "Searching"] }

// ============================================================================
// Lifecycle
// ============================================================================

def installed() { initialize() }

def updated() { ["editing", "editSlot", "editBefore", "applyEdits", "confirmRemove"].each { state.remove(it) }; initialize() }

def initialize() {
    unschedule()
    unsubscribe()
    subscribe(location, "ssdpTerm.${SSDP_ST}", "ssdpEvent")
    if (state.search?.running) { Map s = state.search; s.running = false; state.search = s }   // its timer was cleared
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
    cfg.each { dni, p -> setUpPrinter(dni as String, p as Map) }
    state.pushedLogLevel = currentLogLevel()
    state.remove("migrated")
    ["printerCount", "updateInterval", "tempStep", "tempUnit"].each { app.removeSetting(it) }
    runIn(2, "startPrinters")
}

// Create the Printer if needed and hand it its settings. Returns the device, or null.
private setUpPrinter(String dni, Map p) {
    def dev = getChildDevice(dni)
    if (!dev) {
        try {
            dev = addChildDevice("jdthomas24", "Bambu Lab Printer", dni, [label: p.name, isComponent: false])
            logInfo "created ${dev.displayName}"
        } catch (e) {
            String why = "${e}".contains("UnknownDeviceType") ? "the Bambu Lab Printer driver isn't installed (update with HPM)" : e.message
            logErr "could not create ${p.name}: ${why}"
            return null
        }
    }
    dev.configure([slot: p.slot, serial: p.serial, name: p.name, ip: p.ip], [control: p.control, allowStop: p.control && p.allowStop])
    dev.setLogLevel(currentLogLevel())
    return dev
}

// Save printer: create and connect just this one, without restarting the others.
private void savePrinter(int i) {
    Map p = configuredPrinters()[printerDni(i)] as Map
    if (!p) { state.saveError = i; return }
    state.remove("saveError")
    def dev = setUpPrinter(printerDni(i), p)
    if (dev) try { dev.initialize() } catch (e) { logErr "could not start ${dev.displayName}: ${e.message}" }
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
            body: "<p>Tap <b>Find printers</b>, then <b>Add</b> next to your printer. Its name, IP address and serial fill in. Enter the <b>LAN access code</b>: it's on the printer under <b>Settings, Network</b> (<b>WLAN</b> on some models). Just read it: you don't need to turn on LAN Only.</p>" +
                "<p>Not found? Tap <b>Add by hand</b> and enter the name, <b>IP address</b> and access code. Leave the serial blank: the integration reads it from the printer.</p>" +
                "<p>Tap <b>Save printer</b>. It connects within a few seconds and adds its AMS units.</p>"],
        [id: "find", label: "Finding printers", title: "Finding your printer", group: "Getting started", icon: "pi-search",
            body: "<p>Tap <b>Find printers</b> to search your network for about 10 seconds. Printers you've already added are marked <b>Already added</b>.</p>" +
                "<p><b>None found?</b> Check that the printer is on and on the same network as your hub (guest and IoT networks often block discovery), then tap <b>Search again</b>. " +
                "You can also enter the IP by hand. It's on the printer screen under Settings, Network.</p>" +
                "<p><b>IP changed?</b> No action needed. The integration finds the printer by its serial number and reconnects on its own.</p>",
            checklist: [
                [title: "Online", detail: "Working normally."],
                [title: "Reconnecting", detail: "Retrying, normal after a hub or printer restart."],
                [title: "Searching", detail: "Looking for the printer on the network."],
                [title: "Offline", detail: "Not found, still checking in the background."],
                [title: "Offline, search stopped", detail: "Not found for 24 hours. Tap Search now on the printer's card to try again."]
            ],
            warning: "Tip: reserving the printer's IP in your router keeps the address from changing."],
        [id: "cloud", label: "Sign in with Bambu", title: "Access codes from your Bambu account", group: "Getting started", icon: "pi-cloud",
            body: "<p>Optional. Set <b>Access codes</b> to <b>Sign in with Bambu</b>, open <b>Bambu account</b> and sign in. Bambu emails a code the first time.</p>" +
                "<p>After that, <b>Find printers</b> fills in each printer's access code too, so <b>Add</b> then <b>Done</b> is all it takes.</p>",
            checklist: [
                [title: "What it's used for", detail: "Only to read your printers' access codes. Monitoring stays on your own network, and Monitor only or control works the same."],
                [title: "Your password", detail: "Never saved. Only Bambu's sign-in token is kept, and Sign out removes it."],
                [title: "Code changed on the printer", detail: "If a printer rejects its code, the app gets the current one from Bambu and reconnects on its own."],
                [title: "Signed up with Google or Apple", detail: "Set a Bambu password first: in Bambu Handy, tap the person icon, then Account Security, Change Password."],
                [title: "Sign-in expired", detail: "Bambu's sign-in runs out after a while. Codes already filled in keep working; sign in again to fill in new ones or refresh a changed one."]
            ]],
        [id: "updates", label: "Status updates", title: "How status stays up to date", group: "How it works", icon: "pi-refresh",
            body: "<p>X1 and H2 printers send an 8 KB status every second while printing. Over a long print that adds up, so the integration stays connected but only listens for one status per check:</p>",
            checklist: [
                [title: "Printing", detail: "Every 30 seconds (the printer device's Preferences, from 15 seconds to 2 minutes). A finished print or an error shows up within that time."],
                [title: "Idle", detail: "Every 15 minutes, like a health check (5 minutes to 1 hour in Preferences). A print that starts shows up within that time."],
                [title: "After a command", detail: "Right away, to pick up the printer's answer."],
                [title: "Refresh", detail: "Tap Refresh on the printer device for a status now."],
                [title: "P1 and A1 series", detail: "These printers lag if asked for a full status too often, so they get one at most every 5 minutes. The checks in between listen for 5 seconds and pick up what changed, so progress and temperatures stay current, but a finished or paused print can take up to 5 minutes to show. Refresh always gets a full status. This switches on by itself from the model the printer reports."]
            ]],
        [id: "events", label: "Events and history", title: "Events and device history", group: "How it works", icon: "pi-chart-bar",
            body: "<p>Every change to an attribute is an event, and the hub keeps a history of them. To keep that history small, values that change " +
                "steadily during a print are reported in steps:</p>",
            checklist: [
                [title: "progress", detail: "Every 2%, plus 0 and 100 exactly."],
                [title: "timeRemaining", detail: "Every 10 minutes, then every 5 in the last hour."],
                [title: "ETA", detail: "Set when the print starts and moved only when the estimate shifts by 10 minutes or more, or in the last 10 minutes. It's checked on every status, so it stays right."],
                [title: "elapsedTime", detail: "Every 15 minutes."],
                [title: "currentLayer", detail: "Every 5 layers."],
                [title: "Temperatures", detail: "Only at temperature or off, in 5° steps (see Temperatures)."],
                [title: "wifiSignal", detail: "Changes of 10 dBm or more, at most every 15 minutes. Turn off Report Wi-Fi signal in the printer device's Preferences to stop it."],
                [title: "Tile", detail: "Redraws when what it shows changes. An idle heater shows off, so a cooling nozzle doesn't redraw it."]
            ],
            warning: "Print state, stage, door, errors, the active tray and heater status are always reported right away, so rules react immediately."],
        [id: "temps", label: "Temperatures", title: "Heater status and temperatures", group: "How it works", icon: "pi-sun",
            body: "<p>Each heater has a <b>status</b> and a <b>temperature</b>. The status is <i>Heating to 220°</i>, <i>At temp</i>, <i>Cooling</i> or <i>Off</i>. " +
                "The temperature only updates once the heater is at temperature or off, in 5° steps, so a warm-up doesn't send a burst of events.</p>" +
                "<p>Dual-nozzle printers (H2D) have <b>rightNozzle</b> and <b>leftNozzle</b> attributes, and the tile shows <b>R</b> and <b>L</b>. Other printers have <b>nozzle</b>.</p>" +
                "<p>Temperatures are in Celsius to match the printer. To follow the hub's units instead, open the printer device and change <b>Temperature units</b> under Preferences. The 5° step is there too.</p>"],
        [id: "filament", label: "AMS and filament", title: "AMS slots and filament left", group: "How it works", icon: "pi-palette",
            body: "<p>Each AMS slot on the printer card shows the filament color and, when the AMS knows it, how much is left. " +
                "The amount comes from the AMS itself. Hubitat shows what the printer reports.</p>",
            checklist: [
                [title: "How the AMS knows", detail: "It reads the spool's Bambu RFID tag and measures how far the spool turns. That needs Remaining Filament Estimation (Update Remaining Capacity on some firmware) turned on in the AMS settings on the printer. A newly read spool starts at a full 1 kg and settles as it's used."],
                [title: "No amount shown", detail: "The spool has no Bambu tag (most third-party spools), or the AMS hasn't estimated it yet. Bambu Handy may still show a dial for these, but it doesn't come from the AMS."],
                [title: "0% in orange", detail: "A Bambu spool that's nearly out. The AMS reports 0% well before the spool is completely empty."],
                [title: "Getting an estimate", detail: "On the printer screen, unload the slot, tap the spool and choose Re-read. The AMS can't re-read while that filament is loaded into the nozzle or during a print."],
                [title: "Third-party spools", detail: "A tag taken from an empty Bambu spool works, and tells the printer that tag's filament type and color. Other RFID tags aren't read."],
                [title: "Empty slots", detail: "A slot is empty when the AMS reports no filament or its filament sensor says nothing is loaded, so a runout counts even if the tag is still read."],
                [title: "Names match the printer", detail: "AMS units are AMS A, AMS B and so on, and the circled number is the slot, so AMS A ③ is A3 on the printer screen. An AMS HT holds one spool, so it's AMS HT A with no slot number. activeTray uses the same names (AMS A2, AMS HT A)."],
                [title: "Letters out of order?", detail: "Bambu letters units by how they're connected, and the printer's Arrange option can change that. If Hubitat shows A where the screen shows B, post it in the community thread."]
            ],
            warning: "Low filament warning at (printer device Preferences, 10% by default) sets when a slot shows in orange and appears in lowSlots."],
        [id: "rules", label: "Rules and dashboards", title: "Rules and dashboards", group: "Using it", icon: "pi-bolt",
            body: "<p>Some ideas:</p>",
            checklist: [
                [title: "Planned pause", detail: "A pause added in the slicer (filament swap, magnets, inserts): pauseType becomes planned. pauseType is user when paused from an app or the screen, and problem for a runout or fault. stage says why, like Paused by G-code."],
                [title: "Print finished", detail: "printState becomes Finished: notify, or turn off the enclosure fan."],
                [title: "Print failed or error", detail: "printState becomes Failed, or errorCount above 0. errorText says what's wrong in plain English, and the card links each code to the Bambu Lab Wiki."],
                [title: "Door opened", detail: "door becomes open (H2D and printers with a door sensor). In Rule Machine it's under Custom Attribute. The printer's own \"front door is open\" notice isn't counted as an error, so errorCount rules don't fire when you check a print."],
                [title: "What printed last", detail: "lastPrint keeps the last finished or failed file. currentPrint is the job printing now, or Not printing."],
                [title: "AMS slot empty", detail: "On the AMS device, emptySlots changes from none: a spool was pulled or ran out. It lists the slots, like 2, 4."],
                [title: "Filament low", detail: "lowSlots changes from none: a slot is at or below Low filament warning (printer device Preferences, 10% by default). Needs a Bambu RFID tag on the spool."],
                [title: "Firmware update", detail: "firmwareUpdate becomes available. Update from the printer or the Bambu apps."],
                [title: "Ready to print", detail: "bedStatus becomes At temp."],
                [title: "Dashboard", detail: "Add the printer as an Attribute tile and pick tile: state, file, progress bar, layers, time left and temperatures."]
            ]],
        [id: "trouble", label: "Troubleshooting", title: "Troubleshooting", group: "Troubleshooting", icon: "pi-wrench",
            body: "<p>The printer's status line on the main page usually says what's wrong.</p>",
            checklist: [
                [title: "Access code rejected", detail: "The code changes when the printer's network settings are reset or the code is refreshed. With Sign in with Bambu, the app updates it on its own. Otherwise read it again on the printer, update it here and tap Done."],
                [title: "Waiting for printer", detail: "Connected, but no status yet. Some printers stay quiet while idle; start anything on the printer, or enter its serial (Settings, Device)."],
                [title: "Serial doesn't match", detail: "The integration uses the serial the printer reports and logs a warning. Clear the serial field."],
                [title: "Reconnecting or Offline", detail: "Check the printer is on and on the same network, and nothing blocks port 8883. It retries and searches for the printer on its own (see Finding printers)."],
                [title: "Command refused", detail: "In Monitor only that's on purpose. In Monitor and control, turn on Developer Mode on the printer. The light works either way."],
                [title: "Light came back on", detail: "The printer turns its light on when a print finishes. That's the printer, not Hubitat."],
                [title: "Error text", detail: "Looked up from this integration's GitHub folder the first time a code appears (not from Bambu). Plain-English text comes from the ha-bambulab project (MIT license)."],
                [title: "Other models", detail: "Tested on the H2D. X1, P1 and A1 should work but haven't been tested yet. If something looks off, tap Diagnostics on the printer device and post the report in the community thread."]
            ]],
        [id: "diag", label: "Diagnostics", title: "Sending a diagnostics report", group: "Troubleshooting", icon: "pi-file",
            body: "<p>Tap <b>Diagnostics</b> on the printer device. About 10 seconds later one block appears in the logs from <b>DIAGNOSTICS</b> to <b>END</b>, " +
                "whatever the log level. Copy all of it into your forum post.</p>" +
                "<p>It has the integration version and settings, the printer's model and firmware (with each AMS and module), the connection, " +
                "and the printer's raw status: print state, errors, every AMS slot, external spools, nozzles, lights and door. " +
                "It never includes your LAN access code, and serials are shortened. It doesn't change anything on the printer.</p>"]
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
        case "diag": log.info line; break   // Diagnostics button: always shown
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
  .bb-slot { display: inline-flex; align-items: center; gap: 4px; margin-left: 6px; padding: 2px 3px; border-radius: 999px; background: rgba(0,0,0,.28); border: 1px solid rgba(127,127,127,.35); }
  .bb-slot.has-pct { padding-right: 8px; }
  .bb-slot .bb-dot { margin-left: 0; }
  .bb-pct { font-size: 0.75em; opacity: 0.9; }
  .bb-n { display: inline-flex; align-items: center; justify-content: center; width: 15px; height: 15px; border-radius: 50%; flex: none;
    border: 1px solid currentColor; font-size: 0.6em; font-weight: 600; line-height: 1; opacity: 0.65; }
  .bb-slot { white-space: nowrap; flex: none; }
  a.bb-pill { flex-wrap: wrap; row-gap: 6px; }
  .app-main-content :is([name*='refreshP_'],[name*='remove_'],[name*='searchP_'],[name*='addFound_'],[id*='refreshP_'],[id*='remove_'],[id*='searchP_'],[id*='addFound_']) {
    font-size: 13px !important; padding: 3px 12px !important; min-height: 0 !important; height: auto !important;
    line-height: 1.5 !important; margin: 2px 0 !important; box-shadow: none !important; border: 1px solid var(--hubitat-control-border, #d0d7de) !important;
    white-space: nowrap; }
  .bb-card-end { border-bottom: 1px solid var(--surface-border, rgba(0,0,0,.12)); margin: 4px 0 18px; }
  .bb-new-head { display: flex; align-items: center; margin-top: 4px; }
  .app-main-content :is([name*='saveP_'],[name*='cancelP_'],[id*='saveP_'],[id*='cancelP_']) { white-space: nowrap; }
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

