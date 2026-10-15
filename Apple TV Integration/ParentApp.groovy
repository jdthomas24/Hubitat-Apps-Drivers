/**
 * Apple TV Integration
 * Version: 1.0.0
 * Notes: Finds Apple TVs over Bonjour (_companion-link._tcp, Apple TV models only), reads each one's own
 * name from AirPlay /info, pairs each with the PIN shown on the TV and creates one "Apple TV" device per TV
 * (Switch: on = wake, off = standby; live power state). Devices are keyed by the Apple TV's permanent
 * route ID, so renames and IP changes never create duplicates. Pairing keys stay in this app; devices load
 * them into memory. All logging for the integration shows here, controlled by Log level.
 * Credits: protocol knowledge from pyatv (postlund, MIT).
 */
import groovy.transform.Field

@Field static final String APP_VERSION = "1.0.0"
@Field static final String SERVICE = "_companion-link._tcp"
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]
@Field static final Map MODELS = ["AppleTV5,3": "Apple TV HD", "AppleTV6,2": "Apple TV 4K (1st gen)",
                                  "AppleTV11,1": "Apple TV 4K (2nd gen)", "AppleTV14,1": "Apple TV 4K (3rd gen)"]

definition(
    name: "Apple TV Integration",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Local on/off and live power state for Apple TVs. No bridge, no Homebridge, no Home Assistant.",
    category: "Integrations",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true,
    installOnOpen: true
)

preferences {
    page(name: "mainPage")
    page(name: "tipsPage")
}

// ---------- lifecycle ----------

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    unsubscribe()
    subscribe(location, "systemStart", "systemStartHandler")
    startListener(true)
    pushLogLevel()
}

def systemStartHandler(evt) { startListener(true) }

def uninstalled() {
    getChildDevices().each { try { deleteChildDevice(it.deviceNetworkId) } catch (e) { } }
    try { unregisterMDNSListener(SERVICE) } catch (e) { }
}

private void startListener(boolean force) {
    if (!force && state.listening) return
    try {
        registerMDNSListener(SERVICE)
        state.listening = true
    } catch (e) {
        logError("App", "could not start Bonjour discovery: ${e.message}")
    }
}

private void pushLogLevel() {
    String lv = settings.logLevel ?: "Errors Only"
    getChildDevices().each { it.componentSetLogLevel(lv) }
    state.pushedLevel = lv
}

// ---------- discovery ----------

private List discovered() {
    List raw
    try {
        def e = getMDNSEntries(SERVICE)
        raw = e == null ? [] : (e instanceof Map ? [e] : e as List)
    } catch (ex) {
        logFull("App", "Bonjour lookup failed: ${ex.message}")
        return []
    }
    Map byKey = [:]
    raw.each { e ->
        String md = e?.rpMd?.toString()
        String id = e?.rpMRtID?.toString()
        if (!md?.startsWith("AppleTV") || !id) return
        String ip = e?.ip4Addresses?.toString()?.split(",")?.collect { it.trim() }?.find { it }
        if (!ip) return
        Integer port = e?.port ? (e.port.toString() as Integer) : 49153
        String key = id.replaceAll("[^A-Za-z0-9]", "").toUpperCase()
        Map rec = byKey[key] ?: [key: key, model: md, host: hostName(e?.server?.toString()), addresses: []]
        if (!rec.addresses.any { it.ip == ip && it.port == port }) rec.addresses << [ip: ip, port: port]
        byKey[key] = rec
    }
    return byKey.values() as List
}

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

private String fetchInfoName(String ip) {
    String name = null
    try {
        httpGet([uri: "http://${ip}:7000", path: "/info", contentType: "application/octet-stream",
                 headers: ["Accept": "*/*", "User-Agent": "AirPlay/550.10"], timeout: 4]) { resp ->
            byte[] body = toBytes(resp.data)
            if (body && body.length > 8 && new String(slice(body, 0, 8), "US-ASCII") == "bplist00") {
                def info = bplistParse(body)
                if (info instanceof Map && info.name) name = info.name.toString()
            }
        }
    } catch (e) {
        logFull("App", "could not read the name from ${ip}: ${e.message}")
    }
    return name
}

// ---------- child interface ----------

Map componentGetCredentials(String dni) { (state.creds ?: [:])[dni] as Map }

void componentPairingComplete(String dni, Map c) {
    Map all = state.creds ?: [:]
    all[dni] = c
    state.creds = all
    def d = getChildDevice(dni)
    logNormal(d?.displayName ?: dni, "paired with '${c.name}' (${modelName(c.model as String)})")
}

List componentLookupAddress(String dni) {
    String key = dni - "ATV-"
    Map rec = discovered().find { it.key == key }
    return rec ? (rec.addresses as List).reverse() : []
}

// ---------- logging ----------

private int lvl() { LOG_LEVELS.indexOf(settings.logLevel ?: "Errors Only") }

void logError(String src, String msg) { log.warn "${src}: ${msg}" }
void logNotice(String src, String msg) { log.info "${src}: ${msg}" }
void logNormal(String src, String msg) { if (lvl() >= 1) log.info "${src}: ${msg}" }
void logFull(String src, String msg) { if (lvl() >= 2) log.debug "${src}: ${msg}" }

// ---------- buttons ----------

def appButtonHandler(String btn) {
    int i = btn.indexOf("_")
    String action = i > 0 ? btn.substring(0, i) : btn
    String key = i > 0 ? btn.substring(i + 1) : null
    switch (action) {
        case "refresh": state.atvNames = [:]; break
        case "pair": startPair(key); break
        case "submitpin": submitPin(key); break
        case "cancel": cancelPair(key); break
        case "remove": state.confirmRemove = key; break
        case "confirmremove": removeTv(key); break
        case "keep": state.remove("confirmRemove"); break
    }
}

private void startPair(String key) {
    Map rec = discovered().find { it.key == key }
    if (!rec) { state.pageNote = "That Apple TV is not announcing itself right now. Click Refresh and try again."; return }
    String dni = "ATV-${key}"
    String tvName = atvName(rec)
    String label = (settings["name_${key}"] ?: "Apple TV - ${tvName}").toString().trim()
    def child = getChildDevice(dni)
    if (!child) {
        try {
            child = addChildDevice("jdthomas24", "Apple TV", dni, [name: "Apple TV", label: label, isComponent: false])
        } catch (e) {
            String why = "${e}".contains("UnknownDeviceType") ?
                "The Apple TV driver is not installed. Add it in Drivers Code (or install with HPM)." : e.message
            state.pageNote = "Could not create ${label}: ${why}"
            logError("App", "could not create ${label}: ${why}")
            return
        }
    } else if (child.label != label) child.setLabel(label)
    child.componentSetLogLevel(settings.logLevel ?: "Errors Only")
    Map a = rec.addresses.last() as Map
    child.componentSetAddress(a.ip as String, a.port as Integer, rec.model as String, tvName)
    child.componentStartPairing()
    pauseExecution(1500)
}

private void submitPin(String key) {
    def child = getChildDevice("ATV-${key}")
    if (!child) return
    child.componentSubmitPin(settings["pin_${key}"]?.toString())
    app.removeSetting("pin_${key}")
    pauseExecution(3000)
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
    app.removeSetting("name_${key}")
    state.remove("confirmRemove")
    logNormal("App", "removed ${label}")
}

// ---------- pages ----------

def mainPage() {
    startListener(false)
    if (state.pushedLevel != (settings.logLevel ?: "Errors Only")) pushLogLevel()
    List found = discovered()
    Map creds = state.creds ?: [:]
    List kids = getChildDevices().sort { it.displayName }
    List paired = kids.findAll { creds[it.deviceNetworkId] }
    paired.each { d ->
        Map rec = found.find { "ATV-${it.key}" == d.deviceNetworkId }
        if (rec && !rec.addresses.any { it.ip == d.getDataValue("ip") }) {
            Map a = rec.addresses.last() as Map
            d.componentSetAddress(a.ip as String, a.port as Integer)
        }
    }
    List unpaired = found.findAll { !creds["ATV-${it.key}"] }.sort { atvName(it) }

    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section { paragraph bannerHtml(paired, found) }
        if (state.pageNote) {
            section { paragraph "<div style='padding:8px 12px;border-left:4px solid #c62828;background:#fdecea'>${state.pageNote}</div>" }
            state.remove("pageNote")
        }
        if (paired) {
            section(headerHtml("Your Apple TVs")) {
                paired.each { d ->
                    String key = d.deviceNetworkId - "ATV-"
                    paragraph cardHtml(d)
                    if (state.confirmRemove == key) {
                        paragraph "<span style='color:#c62828'>Remove ${d.displayName}? Rules and dashboards that use it will stop working.</span>"
                        input "confirmremove_${key}", "button", title: "Yes, remove", width: 3
                        input "keep_${key}", "button", title: "Keep it", width: 3
                    } else {
                        input "remove_${key}", "button", title: "Remove", width: 2
                    }
                }
            }
        }
        section(headerHtml("Found on your network")) {
            if (!unpaired) {
                paragraph found ? "Every Apple TV on your network is paired." :
                    "Searching. Apple TVs announce themselves within about a minute. Click Refresh if nothing shows up."
            }
            unpaired.each { rec -> pairRow(rec) }
            input "refresh", "button", title: "Refresh", width: 2
        }
        section(headerHtml("Settings")) {
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS, defaultValue: "Errors Only",
                required: false, submitOnChange: true, width: 4
            href "tipsPage", title: "Tips and setup help", description: "Requirements, pairing, naming, turning the TV on too, troubleshooting"
        }
        section { paragraph "<div style='color:#888;font-size:12px;text-align:center'>Apple TV Integration v${APP_VERSION}</div>" }
    }
}

private void pairRow(Map rec) {
    String key = rec.key
    def child = getChildDevice("ATV-${key}")
    String tvName = atvName(rec)
    String stage = child?.componentPairingStage()
    String status = child?.componentPairingStatus()
    paragraph foundHtml(rec, tvName)
    if (stage == "PIN") {
        input "pin_${key}", "text", title: "PIN shown on the TV", required: false, width: 4
        input "submitpin_${key}", "button", title: "Submit PIN", width: 3
        input "cancel_${key}", "button", title: "Cancel", width: 2
    } else {
        input "name_${key}", "text", title: "Device name in Hubitat", defaultValue: child?.label ?: "Apple TV - ${tvName}",
            required: false, width: 6
        input "pair_${key}", "button", title: stage == "FAILED" ? "Try again" : "Pair", width: 2
        if (child) input "cancel_${key}", "button", title: "Cancel", width: 2
    }
    if (status && stage != "DONE") {
        String color = stage == "FAILED" ? "#c62828" : "#1565c0"
        paragraph "<span style='color:${color}'>${status}</span>"
    }
}

def tipsPage() {
    dynamicPage(name: "tipsPage", title: "Tips and setup help", install: false) {
        section(headerHtml("Requirements")) {
            paragraph "An Apple TV HD or Apple TV 4K on a current tvOS, on the same network as the hub (Bonjour/mDNS must reach the hub, so no VLAN separation without an mDNS repeater). Everything stays local. No cloud, no extra hardware."
            paragraph "Only Apple TVs are listed. iPhones, iPads and Macs announce the same service and are filtered out. HomePods and announcements stay with Hubitat's built-in AirPlay integration."
        }
        section(headerHtml("Pairing")) {
            paragraph "1. Pick a device name (the default is \"Apple TV - <name>\" so it never collides with lights or speakers named after the room).<br>2. Click Pair. A 4-digit PIN appears on the TV within a few seconds.<br>3. Type it in and click Submit PIN. Pairing is one time only and survives reboots and IP changes."
            paragraph "The Apple TV then lists \"Hubitat\" under Settings, Remotes and Devices, Remote App and Devices. Removing it there unpairs the hub, and the device will show offline until you pair again."
            paragraph "No PIN on screen? Make sure the Apple TV is awake and that its AirPlay access setting (Allow Access) lets devices on your network connect. A \"back off\" error means too many tries in a row. Wait a minute and try again."
        }
        section(headerHtml("Using it")) {
            paragraph "Each Apple TV is a standard Switch: on wakes it, off puts it to sleep. Scenes, Rule Machine, dashboards and voice assistants work with it directly. The power attribute shows asleep, screensaver or awake, and updates live, including when someone uses the Siri Remote. Screensaver counts as on."
            paragraph "To turn the TV on as well, enable HDMI-CEC: on the Apple TV, Settings, Remotes and Devices, Control TVs and Receivers. Waking the Apple TV then wakes the TV and switches its input."
        }
        section(headerHtml("Names")) {
            paragraph "The name in the Found list comes from the Apple TV itself (Settings, General, About, Name). Your Hubitat device name is separate and never overwritten, and devices are tracked by the Apple TV's permanent ID, so renaming either side never creates a duplicate."
        }
        section(headerHtml("Connection and logs")) {
            paragraph "Each Apple TV keeps one encrypted connection open, so commands and power changes are instant. It reconnects on its own after reboots, network drops and IP changes, and checks the connection every 5 minutes. Status shows connected, reconnecting or offline. Offline is only reported after several failed attempts."
            paragraph "All logs for the integration show under this app. Errors Only (default) logs real problems. Normal adds connections and power changes. Full adds protocol detail for troubleshooting."
        }
    }
}

// ---------- HTML ----------

private String headerHtml(String t) { "<div style='font-weight:600;font-size:15px;border-bottom:2px solid #1565c0;padding-bottom:3px'>${t}</div>" }

private String bannerHtml(List paired, List found) {
    int total = paired.size()
    int up = paired.count { it.currentValue("connection") == "connected" } as int
    String color, text
    if (!total) { color = "#1565c0"; text = found ? "${found.size()} Apple TV${found.size() == 1 ? '' : 's'} found. Pair one below to get started." : "Looking for Apple TVs on your network." }
    else if (up == total) { color = "#2e7d32"; text = "${total} Apple TV${total == 1 ? '' : 's'} paired, all connected" }
    else { color = paired.any { it.currentValue("connection") == "offline" } ? "#c62828" : "#f9a825"; text = "${up} of ${total} Apple TVs connected" }
    return "<div style='padding:10px 14px;border-radius:8px;background:${color};color:#fff;font-weight:600'>${text}</div>"
}

private String pill(String text, String color) {
    "<span style='display:inline-block;margin-left:6px;padding:1px 8px;border-radius:10px;background:${color};color:#fff;font-size:12px'>${text}</span>"
}

private String cardHtml(d) {
    String conn = d.currentValue("connection") ?: "unknown"
    String power = d.currentValue("power") ?: "unknown"
    String cc = conn == "connected" ? "#2e7d32" : conn == "offline" ? "#c62828" : conn == "not paired" ? "#757575" : "#f9a825"
    String pc = power == "asleep" ? "#757575" : power == "unknown" ? "#9e9e9e" : "#1565c0"
    String sub = [d.getDataValue("appleTvName"), modelName(d.getDataValue("model")), d.getDataValue("ip")].findAll { it }.join(" · ")
    return "<div style='padding:8px 12px;border:1px solid #ddd;border-radius:8px'>" +
        "<a href='/device/edit/${d.id}' target='_blank' style='font-weight:600;text-decoration:none;cursor:pointer'>${d.displayName} &#8599;</a>" +
        pill(conn, cc) + pill(power, pc) +
        "<div style='color:#666;font-size:12px;margin-top:3px'>${sub}</div></div>"
}

private String foundHtml(Map rec, String tvName) {
    String sub = [modelName(rec.model as String), (rec.addresses.last() as Map).ip].join(" · ")
    return "<div style='margin-top:6px'><b>${tvName}</b><span style='color:#666;font-size:12px;margin-left:8px'>${sub}</span></div>"
}

// ---------- AirPlay /info (binary plist) ----------

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
