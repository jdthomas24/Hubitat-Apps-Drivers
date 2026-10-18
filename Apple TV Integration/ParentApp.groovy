/*
    Apple TV Integration - Parent App
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: protocol knowledge from the pyatv project (Pierre Ståhl, MIT).

    Notes:
     -Setup only. The Companion session, pairing crypto, wake/standby and power state live in
      the Apple TV driver (raw sockets only work in drivers). One child device per Apple TV.
     -Discovery: Bonjour _companion-link._tcp, kept to rpMd AppleTV* (iPhones, iPads, Macs are
      filtered out). DNI = "ATV-" + rpMRtID (permanent), so renames, stale Bonjour entries and
      IP changes never create duplicates. Names come from AirPlay /info (binary plist), cached
      in state.atvNames. Refresh clears the cache and re-registers the Bonjour listener.
     -Also listens on _raop._tcp (AirPlay audio, TXT am=AppleTV*), since the hub's Companion list can
      go stale and drop Apple TVs while RAOP still has them. A RAOP-only Apple TV is keyed "IP"+hex and
      paired like Add by IP (Companion port 49153). Listeners can't be restarted from an app.
     -The listener is left registered on uninstall, so a reinstall doesn't start from an empty list.
     -Add by IP: fallback when Bonjour is slow or blocked. /info confirms it's an Apple TV (model
      AppleTV*). Kept in state.manualTvs under key "IP"+hex IP until paired; on pairing the device's
      DNI moves to the permanent "ATV-" + identity (the same key Bonjour uses), so it never duplicates.
     -A Bonjour entry missing its TXT details (rpMd/rpMRtID, seen right after the listener starts) is
      checked through /info like Add by IP (result cached per IP in state.ipModels, cleared by Refresh)
      and keyed "IP"+hex until paired.
     -Full logging lists every raw Bonjour entry and why it was kept or skipped, on each Refresh.
     -Pairing: Pair creates the child (only on an explicit click) and starts pair-setup; the TV
      shows a PIN; Submit PIN finishes it. The child hands the keys back via
      componentPairingComplete(); they live in state.creds and children load them into memory.
     -Device names default to "Apple TV - <name>" and are editable before pairing. Names set
      in Hubitat are never overwritten.
     -Buttons pause 1.5s (Pair) or 3s (Submit PIN) so the page redraws after the TV replies.
     -The PIN box submits on change, and a saved PIN is sent as soon as the page redraws, since a
      text box isn't always saved when a button is clicked right after typing (seen over remote access).
      It's sent at the top of mainPage, before any list is built, so the redraw shows the paired TV.
     -While a paired TV is in its first "connecting" step (a few seconds after pairing or startup), the
      main page refreshes itself every 3s. "reconnecting"/"offline" don't, so typing is never interrupted.
     -Log level is the only logging control. It's pushed to every Apple TV device, and each
      device sends its lines back to childLog(), so all logging appears under this app,
      labeled by device. Full turns itself off after 30 minutes.
     -UI follows HubitatAppUiTemplate (Reolink v1.6.1, UI design by gopher.ny).
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Apple TV Integration"
@Field static final String APP_VERSION = "1.1.3"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/166801"
@Field static final String COFFEE_URL = "https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String DEFAULT_TIP_TOPIC = "start"
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]
@Field static final String SERVICE = "_companion-link._tcp"
@Field static final String RAOP = "_raop._tcp"
@Field static final Map MODELS = ["AppleTV5,3": "Apple TV HD", "AppleTV6,2": "Apple TV 4K (1st gen)",
                                  "AppleTV11,1": "Apple TV 4K (2nd gen)", "AppleTV14,1": "Apple TV 4K (3rd gen)"]

@Field static final String HUBITAT_PILL = "<span style='background:#1976d2;color:#fff;border-radius:8px;padding:1px 9px;font-size:0.75em;font-weight:bold'>HUBITAT</span>"
@Field static final String APPLETV_PILL = "<span style='background:#424242;color:#fff;border-radius:8px;padding:1px 9px;font-size:0.75em;font-weight:bold'>APPLE TV</span>"

definition(
    name: "Apple TV Integration",
    namespace: "jdthomas24",
    author: "Jason Thomas",
    description: "Local on/off and live power state for Apple TVs. No bridge, no extra hardware.",
    category: "Convenience",
    menu: "Integrations",
    singleInstance: true,
    installOnOpen: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Apple%20TV%20Integration/ParentApp.groovy"
)

preferences {
    page(name: "mainPage")
    page(name: "tipsPage")
}

def installed() { initialize() }

def updated() { initialize() }

def uninstalled() {
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) } catch (e) { logErr "failed to remove ${it.displayName} on uninstall: ${e.message}" }
    }
    // Listener stays registered: unregistering can leave a reinstall with an empty list until the TVs re-announce.
}

def initialize() {
    unsubscribe()
    subscribe(location, "systemStart", "systemStartHandler")
    startListener(true)
    pushLogLevel()
}

def systemStartHandler(evt) { startListener(true) }

private void startListener(boolean force) {
    if (!force && state.listening && state.listeningRaop) return
    try {
        registerMDNSListener(SERVICE)
        state.listening = true
    } catch (e) {
        logErr "could not start Bonjour discovery: ${e.message}"
    }
    try {
        registerMDNSListener(RAOP)
        state.listeningRaop = true
    } catch (e) {
        logDbg "could not start AirPlay discovery: ${e.message}"
    }
}

private void pushLogLevel() {
    String level = currentLogLevel()
    getChildDevices().each { try { it.setLogLevel(level) } catch (e) { } }
    state.pushedLogLevel = level
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

// Called by every Apple TV device. Devices already filter by level.
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
// Discovery
// ============================================================================

private List discovered() {
    List raw
    try {
        def e = getMDNSEntries(SERVICE)
        raw = e == null ? [] : (e instanceof Map ? [e] : e as List)
    } catch (ex) {
        logDbg "Bonjour lookup failed: ${ex.message}"
        return []
    }
    boolean detail = state.logBonjourDetail
    state.remove("logBonjourDetail")
    Map byKey = [:]
    List probed = []
    raw.each { e ->
        String md = e?.rpMd?.toString()
        String id = e?.rpMRtID?.toString()
        String ip = e?.ip4Addresses?.toString()?.split(",")?.collect { it.trim() }?.find { it }
        Integer port = e?.port ? (e.port.toString() as Integer) : 49153
        String verdict
        if (!ip) {
            verdict = "skipped, no IPv4 address"
        } else if (md && !md.startsWith("AppleTV")) {
            verdict = "skipped, not an Apple TV"
        } else if (md && id) {
            String key = id.replaceAll("[^A-Za-z0-9]", "").toUpperCase()
            Map rec = byKey[key] ?: [key: key, model: md, host: hostName(e?.server?.toString()), addresses: []]
            if (!rec.addresses.any { it.ip == ip && it.port == port }) rec.addresses << [ip: ip, port: port]
            byKey[key] = rec
            verdict = "Apple TV"
        } else {
            String model = probeModel(ip)
            if (model?.startsWith("AppleTV")) {
                probed << [key: ipKey(ip), model: model, host: hostName(e?.server?.toString()), addresses: [[ip: ip, port: port]]]
                verdict = "Apple TV (no Bonjour details, confirmed by /info)"
            } else {
                verdict = "skipped, no Bonjour details and /info says ${model ?: 'no answer'}"
            }
        }
        if (detail) logDbg "Bonjour entry: ${e?.server ?: '?'} ${ip ?: ''}:${port} model=${md ?: 'none'} id=${id ? 'yes' : 'none'} -> ${verdict}"
    }
    List txtIps = byKey.values().collectMany { it.addresses*.ip }
    List recs = (byKey.values() as List) + probed.findAll { !(it.addresses[0].ip in txtIps) }.unique { it.key }
    List raop = raopRecords(detail == true).findAll { r -> !recs.any { it.addresses.any { a -> a.ip == r.addresses[0].ip } } }
    recs += raop
    String count = "${raw.size()}/${recs.size()}/${raop.size()}"
    if (detail || state.lastBonjourCount != count) {
        state.lastBonjourCount = count
        logDbg "Bonjour: ${raw.size()} Companion entr${raw.size() == 1 ? 'y' : 'ies'}, ${countText(recs.size(), 'Apple TV')}" +
            (raop ? " (${raop.size()} found only through AirPlay)" : "")
    }
    return recs
}

// Apple TVs from _raop._tcp (model in TXT "am"), keyed by IP like Add by IP.
private List raopRecords(boolean detail) {
    List raw
    try {
        def e = getMDNSEntries(RAOP)
        raw = e == null ? [] : (e instanceof Map ? [e] : e as List)
    } catch (ex) {
        logDbg "AirPlay lookup failed: ${ex.message}"
        return []
    }
    List out = []
    raw.each { e ->
        String md = e?.am?.toString()
        String ip = e?.ip4Addresses?.toString()?.split(",")?.collect { it.trim() }?.find { it }
        boolean atv = ip && md?.startsWith("AppleTV")
        if (detail) logDbg "AirPlay entry: ${e?.server ?: '?'} ${ip ?: ''} model=${md ?: 'none'} -> ${atv ? 'Apple TV' : 'skipped'}"
        if (atv && !out.any { it.addresses[0].ip == ip }) {
            out << [key: ipKey(ip), model: md, host: hostName(e?.server?.toString()), addresses: [[ip: ip, port: 49153]]]
        }
    }
    return out
}

private String ipKey(String ip) {
    "IP" + ip.split("\\.").collect { Integer.toHexString(it as int).padLeft(2, "0") }.join().toUpperCase()
}

// /info model for a Bonjour entry without TXT details, cached per IP until Refresh.
private String probeModel(String ip) {
    Map cache = state.ipModels ?: [:]
    if (cache.containsKey(ip)) return cache[ip] ?: null
    Map info = fetchInfo(ip)
    cache[ip] = info?.model ?: ""
    state.ipModels = cache
    if (info?.name && info.model?.startsWith("AppleTV")) {
        Map names = state.atvNames ?: [:]
        names[ipKey(ip)] = info.name
        state.atvNames = names
    }
    return info?.model
}

// Bonjour records plus Add by IP records, without showing the same Apple TV twice.
private List allRecords() {
    List found = discovered()
    List manual = manualRecords()
    List ipsInProgress = manual.findAll { getChildDevice("ATV-${it.key}") }.collect { it.addresses[0].ip }
    found = found.findAll { r -> !r.addresses.any { it.ip in ipsInProgress } }
    List foundIps = found.collectMany { it.addresses*.ip }
    return found + manual.findAll { !(it.addresses[0].ip in foundIps) }
}

private List manualRecords() {
    (state.manualTvs ?: [:]).values().collect { m ->
        [key: m.key, model: m.model, host: m.name, addresses: [[ip: m.ip, port: m.port as Integer]], manual: true]
    }
}

private Map findRecord(String key) { allRecords().find { it.key == key } }

private String hostName(String s) {
    String h = (s ?: "Apple TV").replaceAll(/\.local\.?$/, "").replaceAll(/-[0-9A-Fa-f]{12}$/, "")
    return h.replace("-", " ")
}

private String atvName(Map rec) {
    Map names = state.atvNames ?: [:]
    if (names[rec.key]) return names[rec.key]
    String n = fetchInfoName((rec.addresses.last() as Map).ip as String)
    if (n) { names[rec.key] = n; state.atvNames = names }
    return n ?: rec.host
}

private String modelName(String md) { MODELS[md] ?: md ?: "Apple TV" }

private String fetchInfoName(String ip) { fetchInfo(ip)?.name }

private Map fetchInfo(String ip) {
    Map out = null
    try {
        httpGet([uri: "http://${ip}:7000", path: "/info", contentType: "application/octet-stream",
                 headers: ["Accept": "*/*", "User-Agent": "AirPlay/550.10"], timeout: 4]) { resp ->
            byte[] body = toBytes(resp.data)
            if (body && body.length > 8 && new String(slice(body, 0, 8), "US-ASCII") == "bplist00") {
                def info = bplistParse(body)
                if (info instanceof Map) out = [name: info.name?.toString(), model: info.model?.toString()]
            }
        }
    } catch (e) {
        logDbg "could not read /info from ${ip}: ${e.message}"
    }
    return out
}

private void addByIp() {
    String raw = (settings.manualIp ?: "").toString().trim()
    def m = raw =~ /^(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?$/
    if (!m.matches() || m.group(1).split("\\.").any { (it as int) > 255 }) {
        state.pageNote = "Enter the Apple TV's IP address, e.g. 192.168.1.120."
        return
    }
    String ip = m.group(1)
    Integer port = m.group(2) ? (m.group(2) as Integer) : 49153
    Map info = fetchInfo(ip)
    if (!info?.model?.startsWith("AppleTV")) {
        state.pageNote = info ? "${ip} answered, but it isn't an Apple TV (${info.model ?: 'unknown model'})." :
            "No Apple TV answered at ${ip}. Check the address and that the Apple TV is on."
        logInfo "Add by IP: ${state.pageNote}"
        return
    }
    String key = ipKey(ip)
    Map all = state.manualTvs ?: [:]
    all[key] = [key: key, ip: ip, port: port, model: info.model, name: info.name ?: ip]
    state.manualTvs = all
    Map names = state.atvNames ?: [:]
    names[key] = info.name ?: ip
    state.atvNames = names
    app.removeSetting("manualIp")
    logInfo "Add by IP: found '${info.name}' (${modelName(info.model)}) at ${ip}"
}

// ============================================================================
// Child interface
// ============================================================================

Map componentGetCredentials(String dni) { (state.creds ?: [:])[dni] as Map }

void componentPairingComplete(String dni, Map c) {
    def d = getChildDevice(dni)
    String target = dni
    if (dni.startsWith("ATV-IP")) {
        String permanent = "ATV-" + (c.atvId as String).replaceAll("[^A-Za-z0-9]", "").toUpperCase()
        Map manual = state.manualTvs ?: [:]
        manual.remove(dni - "ATV-")
        state.manualTvs = manual
        if (getChildDevice(permanent)) {
            logWarn "${d?.displayName ?: dni} is already paired as ${getChildDevice(permanent).displayName}. Keeping both; remove one."
        } else {
            try { d?.setDeviceNetworkId(permanent); target = permanent }
            catch (e) { logDbg "could not move ${dni} to ${permanent}: ${e.message}" }
        }
    }
    Map all = state.creds ?: [:]
    all[target] = c
    state.creds = all
    logInfo "${d?.displayName ?: target} paired with '${c.name}' (${modelName(c.model as String)})"
}

List componentLookupAddress(String dni) {
    Map rec = findRecord(dni - "ATV-")
    return rec ? (rec.addresses as List).reverse() : []
}

// ============================================================================
// Main page
// ============================================================================

def mainPage(params = null) {
    autoSubmitPins()
    startListener(false)
    armLogRevert()
    if (state.pushedLogLevel != currentLogLevel()) pushLogLevel()
    List found = allRecords()
    Map creds = state.creds ?: [:]
    List paired = getChildDevices().findAll { creds[it.deviceNetworkId] }.sort { it.displayName }
    paired.each { d ->
        Map rec = found.find { "ATV-${it.key}" == d.deviceNetworkId }
        if (rec && !rec.addresses.any { it.ip == d.getDataValue("ip") }) {
            Map a = rec.addresses.last() as Map
            d.componentSetAddress(a.ip as String, a.port as Integer)
        }
    }
    List pairedIps = paired.collect { it.getDataValue("ip") }.findAll { it }
    // IP-keyed records (Add by IP, or Bonjour without details) for a TV already paired under its permanent ID
    List unpaired = found.findAll { r -> !creds["ATV-${r.key}"] && !(r.key.startsWith("IP") && r.addresses.any { it.ip in pairedIps }) }
        .sort { atvName(it) }
    Map banner = bannerState(paired, found, unpaired)
    boolean settling = paired.any { it.currentValue("connection") == "connecting" }

    Map pageOpts = [name: "mainPage", install: true, uninstall: true]
    if (settling) pageOpts.refreshInterval = 3

    dynamicPage(pageOpts) {
        section {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
            if (state.pageNote) {
                paragraph rawHtml: true, warningMessageHtml(state.pageNote)
                state.remove("pageNote")
            }
        }
        section(sectionClass: "app-main-content") {
            paragraph rawHtml: true, columnHeader("Apple TVs")
            if (!paired) {
                paragraph "<span class='text-color-secondary'>No Apple TVs paired yet. Pair one from the list below.</span>"
            }
            paired.each { d ->
                String key = d.deviceNetworkId - "ATV-"
                paragraph rawHtml: true, atvCardHtml(d)
                if (state.confirmRemove == key) {
                    paragraph rawHtml: true, warningMessageHtml("Remove <b>${d.displayName}</b>? Rules and dashboards that use it will stop working.")
                    input name: "confirmRemove_${key}", type: "button", title: "Yes, remove", width: 3,
                        backgroundColor: "#c62828", textColor: "white"
                    input name: "keepTv_${key}", type: "button", title: "Keep it", width: 3
                } else {
                    input name: "removeTv_${key}", type: "button", title: "Remove", width: 3
                }
                paragraph rawHtml: true, "<div class='atv-end'></div>"
            }
            paragraph rawHtml: true, columnHeader("Found on your network")
            if (!unpaired) {
                paragraph "<span class='text-color-secondary'>" + (found ? "Every Apple TV on your network is paired." :
                    "Searching. Waking an Apple TV with its remote makes it announce itself. Tap Refresh, or add it by IP below.") + "</span>"
            }
            unpaired.each { rec -> pairRow(rec) }
            input name: "refreshTvs", type: "button", title: "Refresh", width: 3
            input name: "manualIp", type: "text", title: "Not listed? Add by IP", description: "e.g. 192.168.1.120",
                required: false, width: 5
            input name: "addByIp", type: "button", title: "Add", width: 2
            if (paired) {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Tap <b>Done</b> when finished. That's what finalizes the install.</span>"
            }
        }
        section(sectionClass: "app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
        }
        helpAndSupportSection()
        versionFooterSection()
    }
}

private void pairRow(Map rec) {
    String key = rec.key
    def child = getChildDevice("ATV-${key}")
    String tvName = atvName(rec)
    String stage = child?.componentPairingStage()
    String status = child?.componentPairingStatus()
    paragraph rawHtml: true, foundCardHtml(rec, tvName)
    if (stage == "PIN") {
        input name: "pin_${key}", type: "text", title: "PIN shown on the TV", required: false, width: 4, submitOnChange: true
        input name: "submitPin_${key}", type: "button", title: "Submit PIN", width: 3
        input name: "cancelPair_${key}", type: "button", title: "Cancel", width: 3
    } else {
        input name: "tvName_${key}", type: "text", title: "Device name in Hubitat", defaultValue: child?.label ?: "Apple TV - ${tvName}",
            required: false, width: 6
        input name: "pairTv_${key}", type: "button", title: stage == "FAILED" ? "Try Again" : "Pair", width: 3
        if (child) input name: "cancelPair_${key}", type: "button", title: "Cancel", width: 3
        else if (rec.manual) input name: "forgetIp_${key}", type: "button", title: "Forget", width: 3
    }
    if (status && stage != "DONE") {
        paragraph rawHtml: true, stage == "FAILED" ? warningMessageHtml(status) :
            "<div style='color:#1565c0;font-size:14px'><i class='pi pi-info-circle' style='margin-right:6px'></i>${status}</div>"
    }
    paragraph rawHtml: true, "<div class='atv-end'></div>"
}

// Green only when every paired Apple TV is connected.
private Map bannerState(List paired, List found, List unpaired) {
    if (!paired) {
        if (!found) return [ok: false, title: "Looking for Apple TVs", summary: "Nothing found yet. Give it a minute and tap Refresh. Tips &amp; Troubleshooting has the full walkthrough."]
        return [ok: false, title: "Ready to pair", summary: "${countText(found.size(), 'Apple TV')} found &middot; Pair one below to get started."]
    }
    int up = paired.count { it.currentValue("connection") == "connected" } as int
    int awake = paired.count { it.currentValue("switch") == "on" } as int
    String counts = "${countText(paired.size(), 'Apple TV')} &middot; ${up} connected &middot; ${awake} on" +
        (unpaired ? " &middot; ${unpaired.size()} not paired" : "")
    def offline = paired.findAll { it.currentValue("connection") in ["offline", "not paired"] }
    if (offline) {
        return [ok: false, title: "Apple TV offline", summary: "${counts} &middot; ${offline.collect { it.displayName }.join(', ')}. Check power and network (Tips, Offline or reconnecting)."]
    }
    if (up < paired.size()) {
        boolean settling = paired.any { it.currentValue("connection") == "connecting" }
        return [ok: false, title: "Connecting", summary: "${counts} &middot; " +
            (settling ? "This page updates on its own in a few seconds." : "Reload this page in a few seconds.")]
    }
    return [ok: true, title: "Integration ready", summary: counts]
}

private String atvCardHtml(d) {
    String conn = d.currentValue("connection") ?: "unknown"
    String power = d.currentValue("power") ?: "unknown"
    String cc = conn == "connected" ? "#2e9e5b" : conn in ["offline", "not paired"] ? "#c62828" : "#b26a00"
    String dot = "<span style='display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:6px;background:${d.currentValue('switch') == 'on' ? '#2e9e5b' : '#bbb'}'></span>"
    String sub = [d.getDataValue("appleTvName"), modelName(d.getDataValue("model")), d.getDataValue("ip")].findAll { it }.join(" &middot; ")
    StringBuilder card = new StringBuilder()
    card << "<div style='border:1px solid #ccc;border-radius:10px;padding:12px 14px;margin:4px 0 0;background:#fafafa'>"
    card << "<div><a href='/device/edit/${d.id}' target='_blank' class='atv-link'>${dot}${d.displayName}" +
        "<i class='fa-regular fa-external-link' style='margin-left:5px;font-size:0.8em'></i></a>"
    card << "<span style='margin-left:8px;background:${cc};color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em;font-weight:bold'>${conn}</span>"
    card << "<span style='margin-left:6px;background:#455a64;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>${power}</span></div>"
    card << "<div style='margin-top:6px;color:#888;font-size:0.8em'>${sub}</div></div>"
    return card.toString()
}

private String foundCardHtml(Map rec, String tvName) {
    String sub = [modelName(rec.model as String), (rec.addresses.last() as Map).ip].join(" &middot; ")
    String tag = rec.manual ? "<span style='margin-left:8px;background:#90a4ae;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Added by IP</span>" : ""
    return "<div style='border:1px dashed #ccc;border-radius:10px;padding:10px 14px;margin:4px 0 0'>" +
        "<b>${tvName}</b><span style='color:#888;font-size:0.8em;margin-left:8px'>${sub}</span>${tag}</div>"
}

// ============================================================================
// Buttons
// ============================================================================

def appButtonHandler(String btn) {
    logDbg "button: ${btn}"
    if (btn == "refreshTvs") {
        state.atvNames = [:]
        state.remove("ipModels")
        state.logBonjourDetail = true
        try { registerMDNSListener(SERVICE); registerMDNSListener(RAOP) } catch (e) { logDbg "re-registering Bonjour: ${e.message}" }
        pauseExecution(1500)   // give announcements a moment before the page redraws
        return
    }
    if (btn == "addByIp") { addByIp(); return }
    if (btn.startsWith("forgetIp_")) {
        Map all = state.manualTvs ?: [:]
        all.remove(btn - "forgetIp_")
        state.manualTvs = all
        return
    }
    if (btn.startsWith("pairTv_")) { startPair(btn - "pairTv_"); return }
    if (btn.startsWith("submitPin_")) { submitPin(btn - "submitPin_"); return }
    if (btn.startsWith("cancelPair_")) { cancelPair(btn - "cancelPair_"); return }
    if (btn.startsWith("removeTv_")) { state.confirmRemove = btn - "removeTv_"; return }
    if (btn.startsWith("confirmRemove_")) { removeTv(btn - "confirmRemove_"); return }
    if (btn.startsWith("keepTv_")) { state.remove("confirmRemove"); return }
}

private void startPair(String key) {
    Map rec = findRecord(key)
    if (!rec) { state.pageNote = "That Apple TV isn't announcing itself right now. Tap Refresh, or add it by IP."; return }
    String dni = "ATV-${key}"
    String tvName = atvName(rec)
    String label = (settings["tvName_${key}"] ?: "Apple TV - ${tvName}").toString().trim()
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("jdthomas24", "Apple TV", dni, [name: "Apple TV", label: label, isComponent: false])
        } catch (e) {
            String why = "${e}".contains("UnknownDeviceType") ?
                "The Apple TV driver isn't installed. Install it with HPM or add it in Drivers Code." : e.message
            state.pageNote = "Could not create ${label}: ${why}"
            logErr "could not create ${label}: ${why}"
            return
        }
    } else if (child.label != label) child.setLabel(label)
    child.setLogLevel(currentLogLevel())
    Map a = rec.addresses.last() as Map
    child.componentSetAddress(a.ip as String, a.port as Integer, rec.model as String, tvName)
    child.componentStartPairing()
    pauseExecution(1500)   // let the PIN request land before the page redraws
}

// A PIN saved by the box's submitOnChange is sent before the page builds its lists.
private void autoSubmitPins() {
    (settings.keySet().findAll { it.startsWith("pin_") } as List).each { String k ->
        String key = k - "pin_"
        if (pinDigits(key).length() >= 4 && getChildDevice("ATV-${key}")?.componentPairingStage() == "PIN") submitPin(key)
    }
}

private String pinDigits(String key) { (settings["pin_${key}"]?.toString() ?: "").replaceAll("[^0-9]", "") }

private void submitPin(String key) {
    def child = getChildDevice("ATV-${key}")
    if (!child) return
    String pin = pinDigits(key)
    if (!pin) {
        state.pageNote = "Type the PIN shown on the TV, then tap Submit PIN."
        logInfo "Submit PIN tapped with no PIN entered"
        return
    }
    app.removeSetting("pin_${key}")
    logDbg "submitting a ${pin.length()}-digit PIN"
    child.componentSubmitPin(pin)
    pauseExecution(3000)   // pairing finishes in about a second
}

private void cancelPair(String key) {
    String dni = "ATV-${key}"
    def child = getChildDevice(dni)
    if (!child) return
    child.componentCancelPairing()
    if (!componentGetCredentials(dni)) deleteChildDevice(dni)
}

private void removeTv(String key) {
    String dni = "ATV-${key}"
    def child = getChildDevice(dni)
    String label = child?.displayName ?: dni
    if (child) deleteChildDevice(dni)
    Map all = state.creds ?: [:]
    all.remove(dni)
    state.creds = all
    app.removeSetting("tvName_${key}")
    state.remove("confirmRemove")
    logInfo "removed ${label}"
}

// ============================================================================
// Tips & Troubleshooting content. The README links here.
// ============================================================================

private String exampleBoxHtml(String content) {
    "<div style='background:#f5f5f5;border-left:4px solid #5f8b6f;padding:10px 14px;margin-top:6px'>${content}</div>"
}

private List tipsTopics() {
    [
        [id: "start", label: "Before you begin", title: "Before you begin", group: "Getting started", icon: "pi-sitemap",
            body: "<p>This integration controls the Apple TV itself: on (wake), off (sleep), live power state, playback, " +
                "Siri Remote buttons and app launching. " +
                "HomePods and announcements stay with Hubitat's built-in AirPlay integration.</p>" +
                "<p>Setup moves between two places, so each step is tagged:</p>" +
                "<p>${HUBITAT_PILL}<br>This Hubitat app or a device page.</p>" +
                "<p>${APPLETV_PILL}<br>The Apple TV's Settings, on the TV.</p>",
            checklist: [[title: "Apple TV HD or Apple TV 4K", detail: "On a current tvOS. Older Apple TVs (3rd generation and earlier) use a different protocol and aren't supported."],
                        [title: "Same network as the hub", detail: "Bonjour (mDNS) must reach the hub. Networks split into VLANs need an mDNS repeater."],
                        [title: "The TV turned on", detail: "Pairing shows a PIN on screen, so you'll need to see the TV."]]],

        [id: "pair", label: "1. Pair an Apple TV", title: "Pair an Apple TV", group: "Setup", icon: "pi-link",
            body: "<p>${HUBITAT_PILL}<br>Your Apple TVs appear under <b>Found on your network</b>, named the way they're named on the TV. " +
                "If nothing shows up, give it a minute and tap <b>Refresh</b>.</p>" +
                "<p>${HUBITAT_PILL}<br>Change the <b>Device name in Hubitat</b> if you like, then tap <b>Pair</b>.</p>" +
                "<p>${APPLETV_PILL}<br>A 4-digit PIN appears on the TV within a few seconds.</p>" +
                "<p>${HUBITAT_PILL}<br>Type it in and tap <b>Submit PIN</b>. The Apple TV moves up to <b>Apple TVs</b> and connects. " +
                "Repeat for each Apple TV, then tap <b>Done</b>.</p>" +
                exampleBoxHtml("<b>Why \"Apple TV - Living Room\"?</b><br>Lights and speakers are often named after the room too. " +
                    "The prefix keeps \"turn off Living Room\" from catching the Apple TV, and groups your Apple TVs together in device lists."),
            warning: "Pairing is one time only. It survives hub reboots, Apple TV reboots and IP changes."],
        [id: "cec", label: "2. Turn the TV on too", title: "Turn the TV on too (optional)", group: "Setup", icon: "pi-desktop",
            body: "<p>${APPLETV_PILL}<br><b>Settings &rarr; Remotes and Devices &rarr; Control TVs and Receivers</b>, turn it on.</p>" +
                "<p>Waking the Apple TV from Hubitat then wakes the TV and switches it to the Apple TV's input (HDMI-CEC). " +
                "On some TVs CEC is also a TV setting, with a brand name such as SimpLink (LG) or Anynet+ (Samsung).</p>"],

        [id: "using", label: "Scenes and rules", title: "Use it in scenes and rules", group: "Using it", icon: "pi-play",
            body: "<p>Each Apple TV is a standard <b>Switch</b>, so scenes, Rule Machine, dashboards and voice assistants work with it directly.</p>" +
                "<table style='width:100%;border-collapse:collapse;font-size:14px;margin-bottom:16px'>" +
                "<tr style='background:#f5f7fa'><th style='text-align:left;padding:8px'>Attribute or command</th><th style='text-align:left;padding:8px'>What it does</th></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>on</b></td><td style='padding:8px'>Wakes the Apple TV</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>off</b></td><td style='padding:8px'>Puts it to sleep. Skipped if it's already asleep.</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>switch</b></td><td style='padding:8px'>on when awake or on the screensaver, off when asleep</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>power</b></td><td style='padding:8px'>asleep, screensaver or awake</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>connection</b></td><td style='padding:8px'>connected, connecting, reconnecting, offline or not paired</td></tr>" +
                "<tr style='border-top:1px solid #e0e0e0'><td style='padding:8px'><b>refresh</b></td><td style='padding:8px'>Re-reads the power state</td></tr>" +
                "</table>" +
                "<p>Power changes made with the Siri Remote show up in Hubitat right away, so rules can react when someone turns the Apple TV on or off.</p>"],
        [id: "remote", label: "Remote buttons", title: "Home Screen vs TV button", group: "Using it", icon: "pi-mobile",
            body: "<p><b>homeScreen</b> always takes the Apple TV to the Home Screen, no matter what's on screen. " +
                "Use it in rules when you just want to go home.</p>" +
                "<p><b>tvButton</b> presses the TV button on the Siri Remote (the one with the TV icon). One press does whatever your " +
                "Apple TV's TV Button setting says, the Home Screen or the Apple TV app (${APPLETV_PILL} <b>Settings &rarr; Remotes and Devices " +
                "&rarr; TV Button</b>). Two presses (<b>times</b> 2) open the App Switcher.</p>" +
                exampleBoxHtml("<b>Not sure which to use?</b> For rules, <b>homeScreen</b> is the safe choice."),
            warning: "home is a shortcut for homeScreen, and tv for tvButton, if you type button names in a rule."],

        [id: "notfound", label: "Apple TV not found", title: "Apple TV not found", group: "Troubleshooting", icon: "pi-search",
            body: "<p>Apple TVs announce themselves over Bonjour, but a sleeping Apple TV can take a while. " +
                "${APPLETV_PILL}<br>Wake it with its remote, then ${HUBITAT_PILL} tap <b>Refresh</b>.</p>" +
                "<p><b>Still not listed?</b> Type its IP address under <b>Not listed? Add by IP</b> and tap <b>Add</b>. " +
                "The app checks it's an Apple TV, then you pair it the same way. You'll find the IP on the Apple TV under " +
                "<b>Settings &rarr; Network</b>. A DHCP reservation on your router keeps it from changing.</p>" +
                "<p>If your network separates devices into VLANs, Bonjour has to reach the hub's network. Most routers call this an mDNS repeater or reflector.</p>"],
        [id: "pairing", label: "Pairing problems", title: "Pairing problems", group: "Troubleshooting", icon: "pi-exclamation-triangle",
            body: "<p><b>No PIN on screen:</b> wake the Apple TV first, then tap Pair again. Also check its AirPlay access setting " +
                "(<b>Allow Access</b>) lets devices on your network connect.</p>" +
                "<p><b>Wrong PIN:</b> tap <b>Try Again</b>. The TV shows a new PIN each time.</p>" +
                "<p><b>\"Back off, try later\":</b> too many attempts in a row. Wait a minute, then try again.</p>" +
                "<p><b>No reply from the Apple TV:</b> the PIN screen timed out. Tap <b>Try Again</b> and enter the PIN a little sooner.</p>"],
        [id: "offline", label: "Offline or reconnecting", title: "Offline or reconnecting", group: "Troubleshooting", icon: "pi-wifi",
            body: "<p>Each Apple TV keeps one encrypted connection open, so commands are instant. It reconnects on its own after hub reboots, " +
                "Apple TV reboots, network drops and IP changes, and checks the connection every 5 minutes.</p>" +
                "<p><b>reconnecting</b> is normal for a few seconds after a drop. <b>offline</b> is reported only after several failed attempts. " +
                "It keeps retrying every 5 minutes and logs <b>back online</b> when it recovers.</p>" +
                "<p>If the log says to pair again, the pairing was removed on the Apple TV side. Tap <b>Remove</b> on the main page, then pair it again.</p>"],
        [id: "faq", label: "Common questions", title: "Common questions", group: "Troubleshooting", icon: "pi-question-circle",
            body: "<p><b>Where does the name in the Found list come from?</b><br>The Apple TV itself: ${APPLETV_PILL} " +
                "<b>Settings &rarr; General &rarr; About &rarr; Name</b>. Your Hubitat device name is separate and never overwritten.</p>" +
                "<p><b>What's \"Hubitat\" on my Apple TV?</b><br>After pairing, the Apple TV lists Hubitat under " +
                "<b>Settings &rarr; Remotes and Devices &rarr; Remote App and Devices</b>. Removing it there unpairs the hub.</p>" +
                "<p><b>Why don't my iPad or Mac show up?</b><br>They announce the same service, but only Apple TVs are listed.</p>" +
                "<p><b>What about HomePods?</b><br>Keep them on Hubitat's AirPlay integration for audio and announcements.</p>" +
                "<p><b>Which app is on screen?</b><br>The Apple TV doesn't report it over this connection. " +
                "<b>Last Launch From Hubitat</b> in State Variables shows the last app opened from Hubitat.</p>"],

        [id: "removal", label: "Removing Apple TVs", title: "Remove Apple TVs safely", group: "Devices", icon: "pi-trash",
            body: "<p>Use <b>Remove</b> on the main page, not Hubitat's Devices page. That also clears the saved pairing.</p>" +
                "<p>${APPLETV_PILL}<br>To tidy up the Apple TV too, remove Hubitat under <b>Settings &rarr; Remotes and Devices &rarr; Remote App and Devices</b>.</p>"],

        [id: "logging", label: "Log levels", title: "Log levels", group: "Logging", icon: "pi-file",
            body: "<p>The <b>Log level</b> on the main page is the only logging setting. It applies to this app and every Apple TV device.</p>" +
                "<p>" + logLevelPill("Errors Only") + " Default. Warnings and errors only, such as an Apple TV going offline.</p>" +
                "<p>" + logLevelPill("Normal") + " Errors, plus connections, commands, power changes, and Apple TVs paired or removed.</p>" +
                "<p>" + logLevelPill("Full") + " Everything, including protocol detail. Use while troubleshooting. Turns itself off after 30 minutes.</p>" +
                "<p>Every log line appears under <b>Apple TV Integration</b> on Hubitat's <b>Logs</b> page, labeled by device, " +
                "e.g. <code>[Apple TV - Living Room]</code>. <b>View logs</b> in the banner shows all of it in one place.</p>"]
    ]
}

// ============================================================================
// UI template (HubitatAppUiTemplate, from Reolink v1.6.1)
// ============================================================================

private void helpAndSupportSection() {
    section(title: "<b>Help & Support</b>", sectionClass: "app-main-support") {
        href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
            page: "tipsPage", description: "Setup, scenes and troubleshooting", width: 4, style: "margin:8px;"
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
  a.atv-link { color: #1565c0; text-decoration: none; font-weight: 600; }
  a.atv-link:hover { text-decoration: underline; }
  .app-main-content :is([name*='removeTv_'],[name*='confirmRemove_'],[name*='keepTv_'],[name*='pairTv_'],[name*='submitPin_'],[name*='cancelPair_'],[name='refreshTvs'],
    [id*='removeTv_'],[id*='confirmRemove_'],[id*='keepTv_'],[id*='pairTv_'],[id*='submitPin_'],[id*='cancelPair_'],[id='refreshTvs']) {
    font-size: 13px !important; padding: 3px 12px !important; min-height: 0 !important; height: auto !important;
    line-height: 1.5 !important; margin: 2px 0 !important; box-shadow: none !important; border: 1px solid #d0d7de !important;
    white-space: nowrap; }
  .atv-end { border-bottom: 1px solid #e0e0e0; margin: 4px 0 18px; }
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
  <div class='text-color-secondary text-base mb-3'>One setting for the app and every Apple TV device.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only, such as an Apple TV going offline.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus connections, commands, power changes, and Apple TVs paired or removed.", current)}
  ${logLevelDetailHtml("Full", "Everything, including protocol detail. Use while troubleshooting. Turns off after 30 minutes.", current)}
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

// ============================================================================
// AirPlay /info (binary plist), for Apple TV names before pairing
// ============================================================================

private byte[] toBytes(data) {
    if (data == null) return new byte[0]
    if (data instanceof byte[]) return (byte[]) data
    if (data instanceof String) return ((String) data).getBytes("ISO-8859-1")
    def out = new java.io.ByteArrayOutputStream()
    byte[] buf = new byte[4096]
    int n
    while ((n = data.read(buf)) > 0) out.write(buf, 0, n)
    return out.toByteArray()
}

private byte[] slice(byte[] b, int from, int to) {
    byte[] out = new byte[to - from]
    for (int i = from; i < to; i++) out[i - from] = b[i]
    return out
}

def bplistParse(byte[] d) {
    int n = d.length
    int offSize = d[n - 26] & 0xFF
    int refSize = d[n - 25] & 0xFF
    long count = bpUInt(d, n - 24, 8)
    long top = bpUInt(d, n - 16, 8)
    long tableOff = bpUInt(d, n - 8, 8)
    long[] offsets = new long[(int) count]
    for (int i = 0; i < count; i++) offsets[i] = bpUInt(d, (int) (tableOff + i * offSize), offSize)
    return bpObj(d, offsets, refSize, (int) top, 0)
}

private bpObj(byte[] d, long[] offsets, int refSize, int idx, int depth) {
    if (depth > 64) throw new IllegalStateException("plist nested too deeply")
    int p = (int) offsets[idx]
    int marker = d[p] & 0xFF
    int hi = marker >> 4, lo = marker & 0xF
    switch (hi) {
        case 0x0: return lo == 0x8 ? false : lo == 0x9 ? true : null
        case 0x1: return bpUInt(d, p + 1, 1 << lo)
        case 0x2: return lo == 2 ? (double) Float.intBitsToFloat((int) bpUInt(d, p + 1, 4)) : Double.longBitsToDouble(bpUInt(d, p + 1, 8))
        case 0x3: return Double.longBitsToDouble(bpUInt(d, p + 1, 8))
    }
    def (int len, int start) = bpLen(d, p, lo)
    switch (hi) {
        case 0x4: return slice(d, start, start + len)
        case 0x5: return new String(slice(d, start, start + len), "US-ASCII")
        case 0x6: return new String(slice(d, start, start + len * 2), "UTF-16BE")
        case 0x8: return bpUInt(d, p + 1, lo + 1)
        case 0xA:
            List l = []
            for (int i = 0; i < len; i++) l << bpObj(d, offsets, refSize, (int) bpUInt(d, start + i * refSize, refSize), depth + 1)
            return l
        case 0xD:
            Map m = [:]
            for (int i = 0; i < len; i++) {
                def k = bpObj(d, offsets, refSize, (int) bpUInt(d, start + i * refSize, refSize), depth + 1)
                m[k] = bpObj(d, offsets, refSize, (int) bpUInt(d, start + (len + i) * refSize, refSize), depth + 1)
            }
            return m
        default: return null
    }
}

private List bpLen(byte[] d, int p, int lo) {
    if (lo != 0xF) return [lo, p + 1]
    int size = 1 << ((d[p + 1] & 0xFF) & 0xF)
    return [(int) bpUInt(d, p + 2, size), p + 2 + size]
}

private long bpUInt(byte[] d, int p, int size) {
    long v = 0
    for (int i = 0; i < size; i++) v = (v << 8) | (d[p + i] & 0xFF)
    return v
}
