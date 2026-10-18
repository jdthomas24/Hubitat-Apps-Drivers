/*
    Apple TV Now Playing (Beta) - App
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: protocol knowledge from the pyatv project (Pierre Ståhl, MIT).

    Notes:
     -Standalone beta, separate from Apple TV Integration. Setup only: sessions, pairing and tracking live
      in the driver (raw sockets only work in drivers). One device per Apple TV.
     -Discovery: Bonjour _raop._tcp (TXT am=AppleTV*). Names and identity from AirPlay /info (cached until
      Refresh). Add by IP is the fallback. Unpaired TVs are keyed "IP"+hex; on pairing the device's DNI
      moves to "ATVNP-" + the Apple TV's AirPlay identity, so an IP change never creates a duplicate.
     -Keys live in state.creds; the driver loads them into memory only. Remove deletes the device and keys.
     -Address follow: when a device can't connect, it asks componentLookupAddress(); /info psi/pi on each
      Apple TV found is matched to the paired identity.
     -PIN box submits on change and is sent at the top of mainPage (text boxes aren't always saved when a
      button is clicked right after typing). Buttons pause so the page redraws after the TV replies.
     -Cards update live from the hub's /eventsocket feed (no page reload, so typing is never interrupted).
     -Log level is pushed to every device; their lines appear under this app. Full turns off after 30 minutes.
     -UI follows HubitatAppUiTemplate (as Apple TV Integration 1.1.1).
*/

import groovy.transform.Field

@Field static final String APP_NAME = "Apple TV Now Playing (Beta)"
@Field static final String APP_VERSION = "1.2.0.5"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/166801"
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]
@Field static final String RAOP = "_raop._tcp"
@Field static final String DRIVER = "Apple TV Now Playing (Beta)"
@Field static final Map MODELS = ["AppleTV5,3": "Apple TV HD", "AppleTV6,2": "Apple TV 4K (1st gen)",
                                  "AppleTV11,1": "Apple TV 4K (2nd gen)", "AppleTV14,1": "Apple TV 4K (3rd gen)"]

definition(
    name: "Apple TV Now Playing (Beta)",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "BETA: which app is playing on your Apple TVs, and whether it's playing or paused. Separate from Apple TV Integration.",
    category: "Convenience",
    menu: "Integrations",
    singleInstance: true,
    installOnOpen: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Tests%20-%20Groovy%20RAW/Apple%20TV%20Integration%20BETA/AppleTVNowPlayingApp.groovy"
)

preferences {
    page(name: "mainPage")
}

def installed() { initialize() }

def updated() { initialize() }

def uninstalled() {
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) } catch (e) { logErr "failed to remove ${it.displayName}: ${e.message}" }
    }
}

def initialize() {
    unsubscribe()
    subscribe(location, "systemStart", "systemStartHandler")
    startListener(true)
    pushLogLevel()
}

def systemStartHandler(evt) { startListener(true) }

private void startListener(boolean force) {
    if (!force && state.listening) return
    try { registerMDNSListener(RAOP); state.listening = true }
    catch (e) { logErr "could not start Bonjour discovery: ${e.message}" }
}

private void pushLogLevel() {
    String level = currentLogLevel()
    getChildDevices().each { try { it.setLogLevel(level) } catch (e) { } }
    state.pushedLogLevel = level
}

// ============================================================================
// Logging
// ============================================================================

private String currentLogLevel() { LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only" }

private void logErr(String msg) { log.error "${APP_NAME}: ${msg}" }
private void logInfo(String msg) { if (currentLogLevel() != "Errors Only") log.info "${APP_NAME}: ${msg}" }
private void logDbg(String msg) { if (currentLogLevel() == "Full") log.debug "${APP_NAME}: ${msg}" }

void childLog(String level, String source, String msg) {
    String line = "${APP_NAME} [${source}]: ${msg}"
    switch (level) {
        case "error": log.error line; break
        case "warn": log.warn line; break
        case "info": log.info line; break
        default: log.debug line
    }
}

private void armLogRevert() {
    if (currentLogLevel() == "Full") {
        if (!state.fullLogArmed) { runIn(1800, "revertLogLevel"); state.fullLogArmed = true }
    } else state.remove("fullLogArmed")
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
        def e = getMDNSEntries(RAOP)
        raw = e == null ? [] : (e instanceof Map ? [e] : e as List)
    } catch (ex) {
        logDbg "Bonjour lookup failed: ${ex.message}"
        return []
    }
    boolean detail = state.logBonjourDetail
    state.remove("logBonjourDetail")
    List out = []
    raw.each { e ->
        String md = e?.am?.toString()
        String ip = e?.ip4Addresses?.toString()?.split(",")?.collect { it.trim() }?.find { it }
        boolean atv = ip && md?.startsWith("AppleTV")
        if (detail) logDbg "Bonjour entry: ${e?.server ?: '?'} ${ip ?: ''} model=${md ?: 'none'} -> ${atv ? 'Apple TV' : 'skipped'}"
        if (atv && !out.any { it.ip == ip }) out << [key: ipKey(ip), ip: ip, model: md, host: hostName(e?.server?.toString())]
    }
    return out
}

private List allRecords() {
    List found = discovered()
    List foundIps = found*.ip
    List manual = (state.manualTvs ?: [:]).values().findAll { !(it.ip in foundIps) }.collect { it + [manual: true] }
    return found + manual
}

private Map findRecord(String key) { allRecords().find { it.key == key } }

private String ipKey(String ip) {
    "IP" + ip.split("\\.").collect { Integer.toHexString(it as int).padLeft(2, "0") }.join().toUpperCase()
}

private String hostName(String s) {
    (s ?: "Apple TV").replaceAll(/\.local\.?$/, "").replaceAll(/-[0-9A-Fa-f]{12}$/, "").replace("-", " ")
}

private String atvName(Map rec) {
    Map names = state.atvNames ?: [:]
    if (names[rec.ip]) return names[rec.ip]
    String n = fetchInfo(rec.ip as String)?.name
    if (n) { names[rec.ip] = n; state.atvNames = names }
    return n ?: rec.host
}

private String modelName(String md) { MODELS[md] ?: md ?: "Apple TV" }

private Map fetchInfo(String ip) {
    Map out = null
    try {
        httpGet([uri: "http://${ip}:7000", path: "/info", contentType: "application/octet-stream",
                 headers: ["Accept": "*/*", "User-Agent": "AirPlay/550.10"], timeout: 4]) { resp ->
            byte[] body = toBytes(resp.data)
            if (body && body.length > 8 && new String(slice(body, 0, 8), "US-ASCII") == "bplist00") {
                def info = bplistParse(body)
                if (info instanceof Map) out = [name: info.name?.toString(), model: info.model?.toString(),
                                                ids: [info.psi, info.pi].findAll { it }.collect { it.toString().toUpperCase() }]
            }
        }
    } catch (e) {
        logDbg "could not read /info from ${ip}: ${e.message}"
    }
    return out
}

private void addByIp() {
    String ip = (settings.manualIp ?: "").toString().trim()
    if (!(ip ==~ /\d{1,3}(\.\d{1,3}){3}/) || ip.split("\\.").any { (it as int) > 255 }) {
        state.pageNote = "Enter the Apple TV's IP address, e.g. 192.168.1.120."
        return
    }
    Map info = fetchInfo(ip)
    if (!info?.model?.startsWith("AppleTV")) {
        state.pageNote = info ? "${ip} answered, but it isn't an Apple TV (${info.model ?: 'unknown model'})." :
            "No Apple TV answered at ${ip}. Check the address and that the Apple TV is on."
        return
    }
    String key = ipKey(ip)
    Map all = state.manualTvs ?: [:]
    all[key] = [key: key, ip: ip, model: info.model, host: info.name ?: ip]
    state.manualTvs = all
    Map names = state.atvNames ?: [:]
    names[ip] = info.name ?: ip
    state.atvNames = names
    app.removeSetting("manualIp")
    logInfo "Add by IP: found '${info.name}' (${modelName(info.model)}) at ${ip}"
}

// ============================================================================
// Device interface
// ============================================================================

Map componentGetCredentials(String dni) { (state.creds ?: [:])[dni] as Map }

void componentPairingComplete(String dni, Map c) {
    def d = getChildDevice(dni)
    String target = dni
    String permanent = "ATVNP-" + (c.atvId as String).replaceAll("[^A-Za-z0-9]", "").toUpperCase()
    if (dni != permanent) {
        if (getChildDevice(permanent)) {
            logErr "${d?.displayName ?: dni} is the same Apple TV as ${getChildDevice(permanent).displayName}. Remove one."
        } else {
            try { d?.setDeviceNetworkId(permanent); target = permanent }
            catch (e) { logDbg "could not move ${dni} to ${permanent}: ${e.message}" }
        }
    }
    Map manual = state.manualTvs ?: [:]
    if (manual.remove(dni - "ATVNP-") != null) state.manualTvs = manual
    Map all = state.creds ?: [:]
    all.remove(dni)
    all[target] = c
    state.creds = all
    logInfo "${d?.displayName ?: target} paired"
}

// Called by a device that can't connect: another IP whose /info identity matches its pairing, or null.
String componentLookupAddress(String dni, String current) {
    String id = ((state.creds ?: [:])[dni] as Map)?.atvId?.toString()?.toUpperCase()
    if (!id) return null
    for (Map rec in discovered()) {
        if (rec.ip == current) continue
        if (fetchInfo(rec.ip as String)?.ids?.contains(id)) {
            logInfo "${getChildDevice(dni)?.displayName ?: dni} moved to ${rec.ip}"
            return rec.ip
        }
    }
    return null
}

// ============================================================================
// Main page
// ============================================================================

def mainPage(params = null) {
    autoSubmitPins()
    startListener(false)
    armLogRevert()
    if (state.pushedLogLevel != currentLogLevel()) pushLogLevel()
    Map creds = state.creds ?: [:]
    List paired = getChildDevices().findAll { creds[it.deviceNetworkId] }.sort { it.displayName }
    List pairedIps = paired.collect { it.getDataValue("ip") }.findAll { it }
    List found = allRecords()
    List unpaired = found.findAll { r -> !(r.ip in pairedIps) && !creds["ATVNP-${r.key}"] }.sort { atvName(it) }
    boolean settling = paired.any { it.currentValue("connection") == "connecting" } ||
        getChildDevices().any { it.componentPairingStage() in ["PINSTART", "M2", "M4", "M6"] }

    Map pageOpts = [name: "mainPage", install: true, uninstall: true]
    if (settling) pageOpts.refreshInterval = 3

    dynamicPage(pageOpts) {
        section {
            Map b = bannerState(paired, found, unpaired)
            paragraph rawHtml: true, statusBannerHtml(b.ok, b.title, b.summary)
            paragraph rawHtml: true, betaNoteHtml()
            if (paired) paragraph rawHtml: true, liveUpdateHtml()
            if (state.pageNote) {
                paragraph rawHtml: true, warningMessageHtml(state.pageNote)
                state.remove("pageNote")
            }
        }
        section(sectionClass: "app-main-content") {
            paragraph rawHtml: true, columnHeader("Apple TVs")
            if (!paired) paragraph "<span class='text-color-secondary'>No Apple TVs paired yet. Pair one from the list below.</span>"
            paired.each { d -> pairedRow(d) }
            paragraph rawHtml: true, columnHeader("Found on your network")
            if (!unpaired) {
                paragraph "<span class='text-color-secondary'>" + (found ? "Every Apple TV on your network is paired." :
                    "Searching. Waking an Apple TV with its remote makes it announce itself. Tap Refresh, or add it by IP below.") + "</span>"
            }
            unpaired.each { rec -> pairRow(rec) }
            input name: "refreshTvs", type: "button", title: "Refresh", width: 3
            input name: "manualIp", type: "text", title: "Not listed? Add by IP", description: "e.g. 192.168.1.120", required: false, width: 5
            input name: "addByIp", type: "button", title: "Add", width: 2
            if (paired) paragraph "<span class='text-color-secondary' style='font-size:14px;'>Tap <b>Done</b> when finished. That's what finalizes the install.</span>"
        }
        section(sectionClass: "app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS, defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph "<span class='text-color-secondary' style='font-size:14px;'><b>Normal</b> logs every app change and connection. " +
                "<b>Full</b> adds protocol detail for troubleshooting and turns itself off after 30 minutes.</span>"
            paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments", "Hubitat Community", "Apple TV Integration thread")
        }
        section(sectionClass: "app-main-footer") {
            paragraph "<div class='text-center text-color-secondary text-xs mt-2'>${APP_NAME} v${APP_VERSION}</div>"
        }
    }
}

private void pairedRow(d) {
    String dni = d.deviceNetworkId
    String stage = d.componentPairingStage()
    paragraph rawHtml: true, atvCardHtml(d)
    if (stage == "PIN") {
        input name: "pin_${dni}", type: "text", title: "PIN shown on the TV", required: false, width: 4, submitOnChange: true
        input name: "submitPin_${dni}", type: "button", title: "Submit PIN", width: 3
        input name: "cancelPair_${dni}", type: "button", title: "Cancel", width: 3
    } else if (state.confirmRemove == dni) {
        paragraph rawHtml: true, warningMessageHtml("Remove <b>${d.displayName}</b>? Rules that use it will stop working.")
        input name: "confirmRemove_${dni}", type: "button", title: "Yes, remove", width: 3, backgroundColor: "#c62828", textColor: "white"
        input name: "keepTv_${dni}", type: "button", title: "Keep it", width: 3
    } else {
        input name: "removeTv_${dni}", type: "button", title: "Remove", width: 3
        input name: "repair_${dni}", type: "button", title: "Pair again", width: 3
    }
    pairStatusLine(d)
    paragraph rawHtml: true, "<div class='atv-end'></div>"
}

private void pairRow(Map rec) {
    String dni = "ATVNP-${rec.key}"
    def child = getChildDevice(dni)
    String tvName = atvName(rec)
    String stage = child?.componentPairingStage()
    paragraph rawHtml: true, foundCardHtml(rec, tvName)
    if (stage == "PIN") {
        input name: "pin_${dni}", type: "text", title: "PIN shown on the TV", required: false, width: 4, submitOnChange: true
        input name: "submitPin_${dni}", type: "button", title: "Submit PIN", width: 3
        input name: "cancelPair_${dni}", type: "button", title: "Cancel", width: 3
    } else {
        input name: "tvName_${rec.key}", type: "text", title: "Device name in Hubitat", defaultValue: child?.label ?: "${tvName} Now Playing",
            required: false, width: 6
        input name: "pairTv_${rec.key}", type: "button", title: stage == "FAILED" ? "Try Again" : "Pair", width: 3
        if (child) input name: "cancelPair_${dni}", type: "button", title: "Cancel", width: 3
        else if (rec.manual) input name: "forgetIp_${rec.key}", type: "button", title: "Forget", width: 3
    }
    if (child) pairStatusLine(child)
    paragraph rawHtml: true, "<div class='atv-end'></div>"
}

private void pairStatusLine(d) {
    String stage = d.componentPairingStage()
    String status = d.componentPairingStatus()
    if (!status || stage == null || stage == "DONE") return
    paragraph rawHtml: true, stage == "FAILED" ? warningMessageHtml(status) :
        "<div style='color:#1565c0;font-size:14px'><i class='pi pi-info-circle' style='margin-right:6px'></i>${status}</div>"
}

private Map bannerState(List paired, List found, List unpaired) {
    if (!paired) {
        if (!found) return [ok: false, title: "Looking for Apple TVs", summary: "Nothing found yet. Wake an Apple TV, give it a minute and tap Refresh."]
        return [ok: false, title: "Ready to pair", summary: "${countText(found.size(), 'Apple TV')} found &middot; Pair one below to get started."]
    }
    int up = paired.count { it.currentValue("connection") == "connected" } as int
    String counts = "${countText(paired.size(), 'Apple TV')} &middot; ${up} connected" + (unpaired ? " &middot; ${unpaired.size()} not paired" : "")
    List off = paired.findAll { it.currentValue("connection") in ["offline", "disconnected"] }
    if (off) return [ok: false, title: "Apple TV not connected", summary: "${counts} &middot; ${off.collect { it.displayName }.join(', ')}"]
    if (up < paired.size()) return [ok: false, title: "Connecting", summary: "${counts} &middot; This page updates on its own in a few seconds."]
    return [ok: true, title: "Watching", summary: counts]
}

private String atvCardHtml(d) {
    String conn = d.currentValue("connection") ?: "unknown"
    String tv = d.currentValue("tvState") ?: "unknown"
    String app = d.currentValue("currentApp") ?: "none"
    String ps = d.currentValue("playbackState") ?: "idle"
    String title = d.currentValue("title") ?: "none"
    String now = app == "none" ? "Nothing playing" :
        "<b>${esc(app)}</b> &middot; ${esc(ps)}" + (title != "none" ? " &middot; ${esc(title)}" : "")
    String sub = [d.getDataValue("appleTvName"), modelName(d.getDataValue("model")), d.getDataValue("ip")].findAll { it }.join(" &middot; ")
    StringBuilder card = new StringBuilder()
    card << "<div class='np-card' data-dev='${d.id}' data-app='${esc(app)}' data-ps='${esc(ps)}' data-title='${esc(title)}' data-conn='${esc(conn)}' " +
        "style='border:1px solid #ccc;border-radius:10px;padding:12px 14px;margin:4px 0 0;background:#fafafa'>"
    card << "<div><a href='/device/edit/${d.id}' target='_blank' class='atv-link'>${d.displayName}" +
        "<i class='fa-regular fa-external-link' style='margin-left:5px;font-size:0.8em'></i></a>"
    card << "<span class='np-conn' style='margin-left:8px;background:${connColor(conn)};color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em;font-weight:bold'>${esc(conn)}</span>"
    card << "<span class='np-tv' style='margin-left:6px;background:#455a64;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>${esc(tv)}</span></div>"
    card << "<div class='np-now' style='margin-top:6px;font-size:0.9em${conn == "connected" ? "" : ";display:none"}'>${now}</div>"
    card << "<div style='margin-top:4px;color:#888;font-size:0.8em'>${sub}</div></div>"
    return card.toString()
}

private String connColor(String c) { c == "connected" ? "#2e9e5b" : c in ["offline", "not paired", "disconnected"] ? "#c62828" : "#b26a00" }

// Updates the cards in place from the hub's live event feed (/eventsocket), so the page never reloads while
// someone is typing. Started once per browser page by an image that fails to load (paragraphs don't run <script>).
private String liveUpdateHtml() {
    String js = '''(function(){if(window.__npLive)return;window.__npLive=1;
var col=function(c){return c=='connected'?'#2e9e5b':(['offline','not paired','disconnected'].indexOf(c)>=0?'#c62828':'#b26a00')};
var esc=function(v){var d=document.createElement('div');d.textContent=v;return d.innerHTML};
var draw=function(c){var a=c.dataset.app||'none',p=c.dataset.ps||'idle',t=c.dataset.title||'none',n=c.querySelector('.np-now');
if(!n)return;if(c.dataset.conn!='connected'){n.style.display='none';return}
n.style.display='';n.innerHTML=a=='none'?'Nothing playing':'<b>'+esc(a)+'</b> \\u00b7 '+esc(p)+(t=='none'?'':' \\u00b7 '+esc(t))};
var keys={currentApp:'app',playbackState:'ps',title:'title',connection:'conn',tvState:'tv'};
var ws=new WebSocket((location.protocol=='https:'?'wss://':'ws://')+location.host+'/eventsocket');
ws.onmessage=function(e){var cards=document.querySelectorAll('.np-card');if(!cards.length){ws.close();return}
var m;try{m=JSON.parse(e.data)}catch(x){return}
var k=keys[m.name];if(m.source!='DEVICE'||!k)return;
cards.forEach(function(c){if(c.dataset.dev!=String(m.deviceId))return;c.dataset[k]=m.value;
if(k=='conn'){var s=c.querySelector('.np-conn');s.textContent=m.value;s.style.background=col(m.value)}
if(k=='tv')c.querySelector('.np-tv').textContent=m.value;draw(c)})};
ws.onclose=function(){window.__npLive=0}})()'''
    return "<img src='data:,x' alt='' style='display:none' onerror=\"" + js.replace("\n", "") + "\">"
}

private String foundCardHtml(Map rec, String tvName) {
    String sub = [modelName(rec.model as String), rec.ip].join(" &middot; ")
    String tag = rec.manual ? "<span style='margin-left:8px;background:#90a4ae;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Added by IP</span>" : ""
    return "<div style='border:1px dashed #ccc;border-radius:10px;padding:10px 14px;margin:4px 0 0'>" +
        "<b>${esc(tvName)}</b><span style='color:#888;font-size:0.8em;margin-left:8px'>${sub}</span>${tag}</div>"
}

private String betaNoteHtml() {
    "<div style='font-size:14px;color:#555;margin-top:6px'><b>Beta.</b> Separate from Apple TV Integration and doesn't change it. " +
        "Pairing here adds \"Hubitat\" to the Apple TV's Remotes and Devices list. For rules, use <b>currentApp</b> together with " +
        "<b>playbackState</b> (e.g. Plex + playing). Leaving an app or sleeping the TV pauses it.</div>"
}

private String esc(v) { (v ?: "").toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace('"', "&quot;") }

// ============================================================================
// Buttons
// ============================================================================

def appButtonHandler(String btn) {
    logDbg "button: ${btn}"
    if (btn == "refreshTvs") {
        state.atvNames = [:]
        state.logBonjourDetail = true
        try { registerMDNSListener(RAOP) } catch (e) { logDbg "re-registering Bonjour: ${e.message}" }
        pauseExecution(1500)
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
    if (btn.startsWith("repair_")) { repair(btn - "repair_"); return }
    if (btn.startsWith("submitPin_")) { submitPin(btn - "submitPin_"); return }
    if (btn.startsWith("cancelPair_")) { cancelPair(btn - "cancelPair_"); return }
    if (btn.startsWith("removeTv_")) { state.confirmRemove = btn - "removeTv_"; return }
    if (btn.startsWith("confirmRemove_")) { removeTv(btn - "confirmRemove_"); return }
    if (btn.startsWith("keepTv_")) { state.remove("confirmRemove"); return }
}

private void startPair(String key) {
    Map rec = findRecord(key)
    if (!rec) { state.pageNote = "That Apple TV isn't announcing itself right now. Tap Refresh, or add it by IP."; return }
    String dni = "ATVNP-${key}"
    String tvName = atvName(rec)
    String label = (settings["tvName_${key}"] ?: "${tvName} Now Playing").toString().trim()
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("jdthomas24", DRIVER, dni, [name: DRIVER, label: label, isComponent: false])
        } catch (e) {
            String why = "${e}".contains("UnknownDeviceType") ?
                "The \"${DRIVER}\" driver isn't installed. Add it in Drivers Code (Import, then paste the driver link)." : e.message
            state.pageNote = "Could not create ${label}: ${why}"
            logErr "could not create ${label}: ${why}"
            return
        }
    } else if (child.label != label) child.setLabel(label)
    child.setLogLevel(currentLogLevel())
    child.componentSetAddress(rec.ip as String, rec.model as String, tvName)
    child.componentStartPairing()
    pauseExecution(1500)
}

private void repair(String dni) {
    def child = getChildDevice(dni)
    if (!child) return
    child.setLogLevel(currentLogLevel())
    child.componentStartPairing()
    pauseExecution(1500)
}

private void autoSubmitPins() {
    (settings.keySet().findAll { it.startsWith("pin_") } as List).each { String k ->
        String dni = k - "pin_"
        if (pinDigits(dni).length() >= 4 && getChildDevice(dni)?.componentPairingStage() == "PIN") submitPin(dni)
    }
}

private String pinDigits(String dni) { (settings["pin_${dni}"]?.toString() ?: "").replaceAll("[^0-9]", "") }

private void submitPin(String dni) {
    def child = getChildDevice(dni)
    if (!child) return
    String pin = pinDigits(dni)
    if (!pin) { state.pageNote = "Type the PIN shown on the TV, then tap Submit PIN."; return }
    app.removeSetting("pin_${dni}")
    child.componentSubmitPin(pin)
    pauseExecution(3000)   // pairing finishes in about a second
}

private void cancelPair(String dni) {
    def child = getChildDevice(dni)
    if (!child) return
    child.componentCancelPairing()
    if (!componentGetCredentials(dni)) deleteChildDevice(dni)
}

private void removeTv(String dni) {
    def child = getChildDevice(dni)
    String label = child?.displayName ?: dni
    if (child) deleteChildDevice(dni)
    Map all = state.creds ?: [:]
    all.remove(dni)
    state.creds = all
    state.remove("confirmRemove")
    logInfo "removed ${label}. To clear it on the Apple TV too: Settings > Remotes and Devices (or AirPlay), remove \"Hubitat\"."
}

// ============================================================================
// UI helpers (HubitatAppUiTemplate)
// ============================================================================

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "bg-green-50 border-green-200" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle text-green-700" : "fa-solid fa-exclamation-triangle text-yellow-700"
    """
<style>
  .app-heading { font-size: 16px; }
  .p-message.app-message { padding: 0.75rem !important; margin: 0; border: 0 !important; }
  .app-message .text-color-secondary, .app-message .text-blue-700, .app-message .text-yellow-700 { color: inherit !important; }
  div.panel-body { padding: 0 !important; margin-left: -0.5em; margin-right: -0.5em; }
  fieldset#fieldsetAppButtons { margin-left: 0.5em; margin-right: 0.5em; }
  .app-main-content { float: left; width: calc(62% - 8px); }
  .app-main-settings { float: right; width: 38%; border-left: 1px solid #e0e0e0; padding-left: 8px; box-sizing: border-box; }
  .app-main-content > .mdl-grid, .app-main-settings > .mdl-grid { padding: 4px 0 !important; }
  a.atv-link { color: #1565c0; text-decoration: none; font-weight: 600; }
  a.atv-link:hover { text-decoration: underline; }
  .app-main-content :is([name*='removeTv_'],[name*='confirmRemove_'],[name*='keepTv_'],[name*='pairTv_'],[name*='repair_'],[name*='submitPin_'],[name*='cancelPair_'],[name*='forgetIp_'],[name='refreshTvs'],
    [id*='removeTv_'],[id*='confirmRemove_'],[id*='keepTv_'],[id*='pairTv_'],[id*='repair_'],[id*='submitPin_'],[id*='cancelPair_'],[id*='forgetIp_'],[id='refreshTvs']) {
    font-size: 13px !important; padding: 3px 12px !important; min-height: 0 !important; height: auto !important;
    line-height: 1.5 !important; margin: 2px 0 !important; box-shadow: none !important; border: 1px solid #d0d7de !important;
    white-space: nowrap; }
  .atv-end { border-bottom: 1px solid #e0e0e0; margin: 4px 0 18px; }
  .app-main-footer { clear: both; }
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

private String warningMessageHtml(String content) {
    "<div class='p-message p-message-warn app-message flex align-items-center gap-2'>" +
        "<div class='flex-shrink-0'><i class='fa-solid fa-exclamation-triangle text-xl' aria-hidden='true'></i></div>" +
        "<div class='min-w-0 flex-1'>${content}</div></div>"
}

private String columnHeader(String title) { "<div class='app-heading font-semibold'>${title}</div>" }

private String countText(int count, String singular) { "${count} ${count == 1 ? singular : singular + 's'}" }

// ============================================================================
// AirPlay /info (binary plist)
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
