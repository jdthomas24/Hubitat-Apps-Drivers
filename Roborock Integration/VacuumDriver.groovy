/*
    Roborock Integration - Vacuum Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -Local TCP 58867. Tries the last protocol that worked first: L01 (AES-256-GCM, newer models that report pv 1.0)
      or 1.0 (AES-128-ECB). Method from python-roborock and the Homebridge Roborock plugin (L01 fix, Oct 2026).
     -Frames: 4-byte length, version(3) seq(4) random(4) timestamp(4) protocol(2), [2-byte length + payload], CRC32.
      HELLO 0/1, PING 2/3, GENERAL 4/5. The vacuum answers a request with a header-only 5, then sends the
      result as its own 4 with dps "102" {id, result}; replies are matched by that id.
     -GCM is built from AES/ECB plus GHASH (the hub sandbox blocks GCMParameterSpec).
     -The vacuum doesn't push status locally, so the driver polls get_status: every 20 s while working, the
      Docked poll setting otherwise, 3 s after a command. Ping every 30 s keeps the socket open (vacuum drops at 60 s).
     -Attribute and command names follow bloodtick's Roborock driver so rules carry over. Scenes run through the app (cloud).
     -Log level comes from the app.
*/

import groovy.transform.Field
import groovy.json.JsonSlurper
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Field static final String VERSION = "0.1.1"
@Field static final int PORT = 58867
@Field static final String SALT = 'TXdfu$jyZ#TZHsg4'
@Field static final Map life = [main: 300, side: 200, filter: 150, sensor: 30, highSpeed: 300]
@Field static final List ACTIVE_STATES = [4, 5, 6, 7, 11, 15, 16, 17, 18, 22, 23, 25, 26, 29]
@Field static Map sess = [:]
@Field static Map rxBuf = [:]
@Field static Map pending = [:]

@Field static final Map errorCodes = [
    0: "No error", 1: "Laser sensor fault", 2: "Collision sensor fault", 3: "Wheel floating",
    4: "Cliff sensor fault", 5: "Main brush blocked", 6: "Side brush blocked", 7: "Wheel blocked",
    8: "Device stuck", 9: "Dust bin missing", 10: "Filter blocked", 11: "Magnetic field detected",
    12: "Low battery", 13: "Charging problem", 14: "Battery failure", 15: "Wall sensor fault",
    16: "Uneven surface", 17: "Side brush failure", 18: "Suction fan failure",
    19: "Unpowered charging station", 20: "Unknown Error", 21: "Laser pressure sensor problem",
    22: "Charge sensor problem", 23: "Dock problem", 24: "No-go zone or invisible wall detected",
    254: "Bin full", 255: "Internal error", 256: "Wifi Offline", 257: "Authorization error"]
@Field static final Map stateCodes = [
    0: "Unknown", 1: "Initiating", 2: "Sleeping", 3: "Idle", 4: "Remote Control",
    5: "Cleaning", 6: "Returning Dock", 7: "Manual Mode", 8: "Charging", 9: "Charging Error",
    10: "Paused", 11: "Spot Cleaning", 12: "In Error", 13: "Shutting Down", 14: "Updating",
    15: "Docking", 16: "Go To", 17: "Zone Clean", 18: "Room Clean", 22: "Emptying Dust Bin",
    23: "Washing the mop", 25: "Washing the mop", 26: "Going to wash the mop", 28: "In call", 29: "Mapping",
    100: "Charged"]
@Field static final Map fanPowerCodes = [101: "Quiet", 102: "Balanced", 103: "Turbo", 104: "Max", 105: "Off", 106: "Auto", 108: "Max+", 110: "Smart"]
@Field static final Map mopModeCodes = [300: "Standard", 301: "Deep", 302: "Custom", 303: "Deep+", 304: "Fast", 306: "Smart"]
@Field static final Map mopWaterModeCodes = [0: "Default", 200: "Off", 201: "Low", 202: "Medium", 203: "High", 204: "Auto", 207: "Custom", 209: "Smart"]
@Field static final Map dockErrorCodes = [
    0: "No error", 34: "Duct Blockage", 38: "Water Empty", 39: "Waste Water Tank Full",
    40: "Water Filter Not Installed", 42: "Check the Water Filter Has Been Correctly Installed",
    44: "Dirty Tank Latch Open", 46: "No Dust Bin", 53: "Cleaning Tank Full Blocked"]

metadata {
    definition(name: "Roborock Vacuum", namespace: "jdthomas24", author: "jdthomas24", importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Roborock%20Integration/VacuumDriver.groovy")
    {
        capability "Actuator"
        capability "Battery"
        capability "Initialize"
        capability "Refresh"
        capability "Switch"
        capability "PushableButton"

        command "vacuumStart"
        command "vacuumDock"
        command "vacuumPause"
        command "vacuumResume"
        command "vacuumRooms", [[name: "Room IDs*", type: "STRING", description: "Comma or space delimited Room IDs"],
                                [name: "MopWater",  type: "ENUM",   description: "Mop water mode. Optional.", constraints: mopWaterModeCodes.values().collect{ it.toUpperCase() }]]
        command "vacuumScene",  [[name: "Scene ID*", type: "STRING", description: "Single Scene ID"]]
        command "execute", [[name: "command*", type: "STRING", description: "Raw command"],[name: "params", type: "JSON_OBJECT", description: "Command parameters"]]

        attribute "dustCollection", "enum", ["off","on"]
        attribute "dockError", "enum", dockErrorCodes.values().collect{ it.toLowerCase() }
        attribute "name", "string"
        attribute "state", "enum", stateCodes.values().collect{ it.toLowerCase() }
        attribute "error", "enum", errorCodes.values().collect{ it.toLowerCase() }
        attribute "fanPower", "enum", fanPowerCodes.values().collect{ it.toLowerCase() }
        attribute "cleanTime (min)", "number"
        attribute "cleanArea (sq ft)", "number"
        attribute "cleanPercent (%)", "number"
        attribute "remainingFilter (%)", "number"
        attribute "remainingMainBrush (%)", "number"
        attribute "remainingSensors (%)", "number"
        attribute "remainingSideBrush (%)", "number"
        attribute "remainingHighSpeedMaintBrush (%)", "number"
        attribute "locating", "enum", ["true","false"]
        attribute "mopMode", "enum", mopModeCodes.values().collect{ it.toLowerCase() }
        attribute "mopWaterMode", "enum", mopWaterModeCodes.values().collect{ it.toLowerCase() }
        attribute "healthStatus", "enum", ["offline", "online"]
        attribute "roomsList", "string"
        attribute "scenesList", "string"
        attribute "ip", "string"
        attribute "protocol", "string"
        attribute "currentRoom", "string"
    }
}

preferences {
    input(name: "dockedPoll", type: "enum", title: "<b>Docked poll:</b>", options: ["60": "1 minute", "120": "2 minutes", "300": "5 minutes"], defaultValue: "120", required: true, width: 4,
        description: "<i>How often to check the vacuum while docked. Jobs started outside Hubitat show up within this time.</i>")
    input(name: "areaUnit", type: "enum", title: "<b>Device Area Unit:</b>", options: ["0": "Square Foot (ft²)", "1": "Square Meter (m²)"], defaultValue: "0", required: true, width: 4)
    input(name: "numberOfButtons", type: "number", title: "<b>Set Number of Buttons:</b>", range: "1...", defaultValue: 1, required: true, width: 4)
}

def installed() { initialize() }
def updated() { sendEvent(name: "numberOfButtons", value: (settings.numberOfButtons ?: 1) as int); initialize() }

def initialize() {
    unschedule()
    closeSocket()
    state.retry = 0
    connect()
}

// Called by the app when sign-in data or the IP changes.
void configChanged() {
    Map c = cfg()
    if (c.ip != state.cfgIp || c.localKey != state.cfgKey) initialize()
    else pushLists(c)
}

def refresh() { if (session()) poll() else initialize() }

// ---------- commands ----------

def on() { vacuumStart() }
def off() { vacuumDock() }
def push(button) { sendEvent(name: "pushed", value: button, isStateChange: true) }
def vacuumStart() { command("app_start") }
def vacuumDock() { command("app_charge") }
def vacuumPause() { command("app_pause") }
def vacuumResume() { command("resume_segment_clean") }

def vacuumRooms(String rooms, String mopWater = null) {
    List ids = rooms.split(/[,\s]+/).findAll { it ==~ /\d+/ }.collect { it as int }
    if (!ids) { logE "No room IDs in '${rooms}'. Use the numbers from roomsList."; return }
    if (mopWater && mopWater.toUpperCase() != "DEFAULT") {
        def code = mopWaterModeCodes.find { it.value.toUpperCase() == mopWater.toUpperCase() }?.key
        if (code != null) command("set_water_box_custom_mode", [code])
    }
    command("app_segment_clean", ids)
}

def vacuumScene(String sceneId) { parent?.componentScene(device.deviceNetworkId, sceneId) }

def execute(String cmd, params = null) {
    def p = params
    if (p instanceof String && p) p = new JsonSlurper().parseText(p)
    command(cmd, p == null ? [] : p)
}

private void command(String method, params = []) {
    if (!session()) { logE "Not connected, '${method}' not sent. Reconnecting."; initialize(); return }
    rpc(method, params)
    runIn(3, "poll")
}

// ---------- connection ----------

private Map cfg() { (parent?.componentConfig(device.deviceNetworkId) ?: [:]) as Map }

def connect() {
    Map c = cfg()
    state.cfgIp = c.ip
    state.cfgKey = c.localKey
    pushLists(c)
    if (!c.ip || !c.localKey) { setHealth(false, "Waiting for an IP address and sign-in in the app"); return }
    sendIf("ip", c.ip)
    String first = state.ver ?: "L01"
    hello(first, first == "L01" ? "1.0" : "L01")
}

private void hello(String ver, String next) {
    closeSocket()
    long cn = 10000 + new Random().nextInt(22767)
    sess[device.id] = [ver: ver, cn: cn, open: false, seq: 1L]
    try {
        interfaces.rawSocket.connect(state.cfgIp, PORT, byteInterface: true)
    } catch (e) {
        setHealth(false, "Couldn't connect to ${state.cfgIp}: ${e.message}")
        scheduleReconnect()
        return
    }
    logD "HELLO ${ver}"
    send(frame(ver, 1L, cn, nowSec(), 0, null))
    runIn(5, "helloTimeout", [data: [ver: ver, next: next]])
}

def helloTimeout(Map data) {
    if (session()) return
    if (data?.next) { logD "No answer to HELLO ${data.ver}, trying ${data.next}"; hello(data.next, null); return }
    setHealth(false, "The vacuum didn't answer on ${state.cfgIp}. Check the IP and that it's on the network.")
    scheduleReconnect()
}

private void scheduleReconnect() {
    closeSocket()
    int n = ((state.retry ?: 0) as int) + 1
    state.retry = n
    int delay = Math.min(300, 30 * n)
    logD "Reconnecting in ${delay} s"
    runIn(delay, "connect")
}

def ping() {
    Map s = session()
    if (!s) return
    long last = (s.lastRx ?: 0L) as long
    if (now() - last > 75000L) { logW "No reply from the vacuum for 75 s, reconnecting"; setHealth(false, null); scheduleReconnect(); return }
    s.seq = (s.seq as long) + 1
    send(frame(s.ver as String, s.seq as long, 10000 + new Random().nextInt(89999), nowSec(), 2, null))
    runIn(30, "ping")
}

def socketStatus(String msg) {
    logD "Socket: ${msg}"
    if (msg?.toLowerCase()?.contains("error") || msg?.toLowerCase()?.contains("disconnect")) {
        if (session()) logW "Connection lost (${msg})"
        sess.remove(device.id)
        setHealth(false, null)
        scheduleReconnect()
    }
}

private void closeSocket() {
    unschedule("ping")
    unschedule("helloTimeout")
    sess.remove(device.id)
    rxBuf[device.id] = ""
    try { interfaces.rawSocket.close() } catch (e) { }
}

private Map session() { Map s = sess[device.id] as Map; return s?.open ? s : null }

// ---------- requests ----------

def poll() {
    unschedule("poll")
    if (!session()) return
    rpc("get_status")
    long n = now()
    if (n - ((state.lastConsumables ?: 0L) as long) > 3600000L) { state.lastConsumables = n; rpc("get_consumable") }
    if (n - ((state.lastRooms ?: 0L) as long) > 21600000L) { state.lastRooms = n; rpc("get_room_mapping") }
}

private void rpc(String method, params = []) {
    Map s = session()
    if (!s) return
    long ts = nowSec()
    int id = 10000 + new Random().nextInt(80000)
    String inner = groovy.json.JsonOutput.toJson([id: id, method: method, params: params ?: []])
    byte[] pt = ('{"dps":{"101":' + jsonString(inner) + '},"t":' + ts + '}').getBytes("UTF-8")
    s.seq = (s.seq as long) + 1
    long rnd = 10000 + new Random().nextInt(89999)
    byte[] enc = s.ver == "L01" ? gcmEncrypt(pt, state.cfgKey, ts, s.seq as long, rnd, s.cn as long, s.an as Long) : ecbEncrypt(pt, state.cfgKey, ts)
    Map p = (pending[device.id] ?: [:]) as Map
    p.keySet().findAll { (n2(p[it].at) < now() - 30000L) }.each { p.remove(it) }
    p[id] = [m: method, at: now()]
    pending[device.id] = p
    logD "Sending ${method}"
    send(frame(s.ver as String, s.seq as long, rnd, ts, 4, enc))
}

private long n2(v) { (v ?: 0L) as long }

private void send(byte[] f) { interfaces.rawSocket.sendMessage(toHex(f)) }

// ---------- receive ----------

def parse(String hex) {
    Map s0 = sess[device.id] as Map
    if (s0) s0.lastRx = now()
    String buf = (rxBuf[device.id] ?: "") + hex
    while (buf.length() >= 8) {
        long len = Long.parseLong(buf.substring(0, 8), 16)
        if (len < 17 || len > 65536) { logW "Unexpected data from the vacuum, clearing buffer"; buf = ""; break }
        int n = (int) len
        if (buf.length() < 8 + n * 2) break
        try { handleFrame(fromHex(buf.substring(8, 8 + n * 2))) } catch (e) { logE "Couldn't read a message from the vacuum: ${e}" }
        buf = buf.substring(8 + n * 2)
    }
    rxBuf[device.id] = buf
}

private void handleFrame(byte[] m) {
    if (m.length < 17) return
    String ver = new String(m, 0, 3, "ISO-8859-1")
    long seq = u32(m, 3), rnd = u32(m, 7), ts = u32(m, 11)
    int proto = u16(m, 15)
    Map s = sess[device.id] as Map
    if (proto == 1) {
        if (!s) return
        unschedule("helloTimeout")
        s.an = rnd; s.open = true; s.lastRx = now()
        state.ver = ver
        state.retry = 0
        sendIf("protocol", ver)
        setHealth(true, null)
        logN "Connected locally (${ver})"
        runIn(30, "ping")
        state.lastConsumables = 0L
        state.lastRooms = 0L
        runIn(1, "poll")
        return
    }
    if (proto == 3 || m.length <= 21 || !s?.open) return
    int plen = u16(m, 17)
    byte[] payload = slice(m, 19, plen)
    byte[] pt = ver == "L01" ? gcmDecrypt(payload, state.cfgKey, ts, seq, rnd, s.cn as long, s.an as Long) : ecbDecrypt(payload, state.cfgKey, ts)
    Map msg = new JsonSlurper().parseText(new String(pt, "UTF-8")) as Map
    msg?.dps?.each { k, v ->
        if (k.toString() == "102") handleRpc(new JsonSlurper().parseText(v.toString()) as Map)
        else logD "Unhandled dps ${k}: ${v}"
    }
}

private void handleRpc(Map r) {
    Map p = (pending[device.id] ?: [:]) as Map
    Map req = p.remove(r?.id as int) as Map
    String method = req?.m
    if (r?.error) { logE "${method ?: 'Command'} rejected: ${r.error}"; return }
    def res = r?.result
    switch (method) {
        case "get_status": if (res instanceof List && res) processStatus(res[0] as Map); break
        case "get_consumable": if (res instanceof List && res) processConsumables(res[0] as Map); break
        case "get_room_mapping": processRooms(res as List); break
        case null: logD "Reply to an unknown request: ${r}"; break
        default:
            if (res == ["ok"] || res == ["OK"] || res == 0) logN "${method} accepted"
            else logN "${method}: ${res}"
    }
}

// ---------- status ----------

private void processStatus(Map r) {
    int st = (r.state ?: 0) as int
    if (st == 8 && ((r.battery ?: 0) as int) == 100) st = 100
    boolean working = ((r.in_cleaning ?: 0) as int) != 0 || ((r.is_locating ?: 0) as int) != 0 || ((r.is_exploring ?: 0) as int) != 0
    sendIf("switch", working ? "on" : "off")
    sendIf("state", (stateCodes[st] ?: st.toString()).toLowerCase(), "state is ${stateCodes[st] ?: st}")
    if (r.battery != null) sendIf("battery", r.battery as int, null, "%")
    if (r.error_code != null) {
        int e = r.error_code as int
        if (sendIf("error", (errorCodes[e] ?: e.toString()).toLowerCase()) && e != 0) logW "Error: ${errorCodes[e] ?: e}"
    }
    if (r.dock_error_status != null) {
        int d = r.dock_error_status as int
        if (sendIf("dockError", (dockErrorCodes[d] ?: d.toString()).toLowerCase()) && d != 0) logW "Dock error: ${dockErrorCodes[d] ?: d}"
    }
    codeAttr("fanPower", fanPowerCodes, r.fan_power)
    codeAttr("mopMode", mopModeCodes, r.mop_mode)
    codeAttr("mopWaterMode", mopWaterModeCodes, r.water_box_mode)
    if (r.dust_collection_status != null) sendIf("dustCollection", (r.dust_collection_status as int) == 0 ? "off" : "on")
    if (r.is_locating != null) sendIf("locating", (r.is_locating as int) == 0 ? "false" : "true")
    if (r.clean_time != null) sendIf("cleanTime (min)", Math.ceil((r.clean_time as int) / 60.0) as int)
    if (r.clean_area != null) {
        boolean ft = settings.areaUnit != "1"
        sendIf("cleanArea (sq ft)", (ft ? (r.clean_area as long) / 92903.04 : (r.clean_area as long) / 1000000.0) as int, null, ft ? "sq ft" : "m²")
    }
    if (r.clean_percent != null) {
        int pc = r.clean_percent as int
        if (pc == 0 && ((r.clean_area ?: 0) as long) > 1 && !working) pc = 100
        sendIf("cleanPercent (%)", pc, null, "%")
    }
    def seg = r.cleaning_info?.segment_id
    if (seg != null) sendIf("currentRoom", (seg as int) < 0 ? "none" : (roomName(seg as int) ?: seg.toString()))
    boolean active = working || ((r.in_returning ?: 0) as int) != 0 || (st in ACTIVE_STATES)
    runIn(active ? 20 : ((settings.dockedPoll ?: "120") as int), "poll")
}

private void codeAttr(String name, Map codes, v) {
    if (v == null) return
    int c = v as int
    sendIf(name, (codes[c] ?: c.toString()).toLowerCase())
}

private void processConsumables(Map r) {
    pct("remainingMainBrush (%)", r.main_brush_work_time, life.main * 3600)
    pct("remainingSideBrush (%)", r.side_brush_work_time, life.side * 3600)
    pct("remainingSensors (%)", r.sensor_dirty_time, life.sensor * 3600)
    pct("remainingFilter (%)", r.strainer_work_times, life.filter)
    pct("remainingHighSpeedMaintBrush (%)", r.cleaning_brush_work_times, life.highSpeed)
}

private void pct(String name, v, long full) {
    if (v == null) return
    int used = (int) Math.floor(((v as long) / (double) full) * 100)
    int left = Math.max(0, 100 - used)
    sendIf(name, left, null, "%")
}

private void processRooms(List mapping) {
    Map names = (cfg().rooms ?: [:]) as Map
    Map segs = [:]
    (mapping ?: []).each { m -> segs[m[0].toString()] = names[m[1].toString()] ?: "Room ${m[0]}" }
    state.segRooms = segs
    String v = segs.sort { a, b -> (a.key as int) <=> (b.key as int) }.collect { k, n -> "${k}: ${n}" }.join(" | ")
    sendIf("roomsList", v ?: "none")
}

private String roomName(int seg) { state.segRooms?.get(seg.toString()) as String }

private void pushLists(Map c) {
    if (c.model) sendIf("name", c.model as String)
    Map sc = (c.scenes ?: [:]) as Map
    sendIf("scenesList", sc ? sc.collect { k, n -> "${k}: ${n}" }.join(" | ") : "none")
}

private void setHealth(boolean ok, String why) {
    if (sendIf("healthStatus", ok ? "online" : "offline") && !ok && why) logW why
    else if (!ok && why) logD why
}

private boolean sendIf(String name, value, String desc = null, String unit = null) {
    if (device.currentValue(name)?.toString() == value?.toString()) return false
    Map e = [name: name, value: value, descriptionText: desc ?: "${device.displayName} ${name} is ${value}"]
    if (unit) e.unit = unit
    sendEvent(e)
    if (name in ["state", "switch", "healthStatus", "error", "dockError"]) logN e.descriptionText
    return true
}

// ---------- logging (routed through the app) ----------

private void logE(String m) { parent ? parent.childLog("error", device.displayName, m) : log.error(m) }
private void logW(String m) { parent ? parent.childLog("warn", device.displayName, m) : log.warn(m) }
private void logN(String m) { parent ? parent.childLog("info", device.displayName, m) : log.info(m) }
private void logD(String m) { parent ? parent.childLog("debug", device.displayName, m) : log.debug(m) }

// ---------- protocol helpers ----------

private byte[] frame(String ver, long seq, long rnd, long ts, int proto, byte[] payload) {
    byte[] m = cat([ver.getBytes("ISO-8859-1"), be32(seq), be32(rnd), be32(ts), be16(proto)])
    if (payload != null) m = cat([m, be16(payload.length), payload])
    m = cat([m, be32(crc32(m))])
    return cat([be32(m.length), m])
}

private String encodeTs(long t) {
    String h = String.format("%08x", t)
    return [5, 6, 3, 7, 1, 2, 0, 4].collect { h[it] }.join()
}

private byte[] sha256(byte[] b) { MessageDigest.getInstance("SHA-256").digest(b) }
private byte[] md5(byte[] b) { MessageDigest.getInstance("MD5").digest(b) }

private byte[] gcmKey(String lk, long ts) { sha256((encodeTs(ts) + lk + SALT).getBytes("UTF-8")) }
private byte[] gcmIv(long ts, long nonce, long seq) { slice(sha256(cat([be32(seq), be32(nonce), be32(ts)])), 0, 12) }
private byte[] gcmAad(long ts, long nonce, long seq, long cn, Long an) {
    an != null ? cat([be32(seq), be32(cn), be32(an), be32(nonce), be32(ts)]) : cat([be32(seq), be32(cn), be32(nonce), be32(ts)])
}

private byte[] gcmEncrypt(byte[] pt, String lk, long ts, long seq, long nonce, long cn, Long an) {
    byte[] k = gcmKey(lk, ts), iv = gcmIv(ts, nonce, seq)
    byte[] ct = gcmCtr(k, iv, pt)
    return cat([ct, gcmTag(k, iv, gcmAad(ts, nonce, seq, cn, an), ct)])
}

private byte[] gcmDecrypt(byte[] payload, String lk, long ts, long seq, long nonce, long cn, Long an) {
    if (payload.length < 16) throw new Exception("payload too short for GCM")
    byte[] k = gcmKey(lk, ts), iv = gcmIv(ts, nonce, seq)
    byte[] ct = slice(payload, 0, payload.length - 16)
    if (toHex(gcmTag(k, iv, gcmAad(ts, nonce, seq, cn, an), ct)) != toHex(slice(payload, payload.length - 16, 16)))
        throw new Exception("GCM tag check failed (wrong key or nonces)")
    return gcmCtr(k, iv, ct)
}

private byte[] aesBlock(byte[] k, byte[] block) {
    Cipher c = Cipher.getInstance("AES/ECB/NoPadding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"))
    return c.doFinal(block)
}

private byte[] gcmCtr(byte[] k, byte[] iv, byte[] input) {
    byte[] out = new byte[input.length]
    byte[] cb = new byte[16]
    for (int i = 0; i < 12; i++) cb[i] = iv[i]
    long ctr = 2
    for (int o = 0; o < input.length; o += 16) {
        byte[] c4 = be32(ctr)
        for (int i = 0; i < 4; i++) cb[12 + i] = c4[i]
        byte[] ks = aesBlock(k, cb)
        for (int i = 0; i < 16 && o + i < input.length; i++) out[o + i] = (byte) ((input[o + i] ^ ks[i]) & 0xFF)
        ctr++
    }
    return out
}

private byte[] gcmTag(byte[] k, byte[] iv, byte[] aad, byte[] ct) {
    byte[] h = aesBlock(k, new byte[16])
    long hh = getL(h, 0), hl = getL(h, 8)
    List x = [0L, 0L]
    x = ghashBlocks(x, aad, hh, hl)
    x = ghashBlocks(x, ct, hh, hl)
    x = gfMul((x[0] as long) ^ ((long) aad.length * 8L), (x[1] as long) ^ ((long) ct.length * 8L), hh, hl)
    byte[] j0 = new byte[16]
    for (int i = 0; i < 12; i++) j0[i] = iv[i]
    j0[15] = (byte) 1
    byte[] ek = aesBlock(k, j0)
    byte[] t = cat([be64(x[0] as long), be64(x[1] as long)])
    for (int i = 0; i < 16; i++) t[i] = (byte) ((t[i] ^ ek[i]) & 0xFF)
    return t
}

private List ghashBlocks(List x, byte[] data, long hh, long hl) {
    for (int o = 0; o < data.length; o += 16) {
        byte[] blk = new byte[16]
        for (int i = 0; i < 16 && o + i < data.length; i++) blk[i] = data[o + i]
        x = gfMul((x[0] as long) ^ getL(blk, 0), (x[1] as long) ^ getL(blk, 8), hh, hl)
    }
    return x
}

private List gfMul(long xh, long xl, long hh, long hl) {
    long zh = 0L, zl = 0L, vh = hh, vl = hl
    long r = 0xE1L << 56
    for (int i = 0; i < 128; i++) {
        long bit = i < 64 ? ((xh >>> (63 - i)) & 1L) : ((xl >>> (127 - i)) & 1L)
        if (bit == 1L) { zh ^= vh; zl ^= vl }
        boolean lsb = (vl & 1L) == 1L
        vl = (vl >>> 1) | (vh << 63)
        vh = vh >>> 1
        if (lsb) vh ^= r
    }
    return [zh, zl]
}

private long getL(byte[] b, int o) {
    long v = 0L
    for (int i = 0; i < 8; i++) v = (v << 8) | (b[o + i] & 0xFFL)
    return v
}

private byte[] be64(long v) { cat([be32((v >>> 32) & 0xFFFFFFFFL), be32(v & 0xFFFFFFFFL)]) }

private byte[] ecbKey(String lk, long ts) { md5((encodeTs(ts) + lk + SALT).getBytes("UTF-8")) }

private byte[] ecbEncrypt(byte[] pt, String lk, long ts) {
    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding")
    c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(ecbKey(lk, ts), "AES"))
    return c.doFinal(pt)
}

private byte[] ecbDecrypt(byte[] ct, String lk, long ts) {
    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding")
    c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(ecbKey(lk, ts), "AES"))
    return c.doFinal(ct)
}

// ---------- byte helpers ----------

private long crc32(byte[] b) {
    long crc = 0xFFFFFFFFL
    for (byte x : b) {
        crc ^= (x & 0xFF)
        for (int k = 0; k < 8; k++) crc = (crc & 1L) ? ((crc >>> 1) ^ 0xEDB88320L) : (crc >>> 1)
    }
    return crc ^ 0xFFFFFFFFL
}

private byte[] be32(long v) {
    byte[] o = new byte[4]
    o[0] = (byte) ((v >> 24) & 0xFF); o[1] = (byte) ((v >> 16) & 0xFF); o[2] = (byte) ((v >> 8) & 0xFF); o[3] = (byte) (v & 0xFF)
    return o
}

private byte[] be16(long v) {
    byte[] o = new byte[2]
    o[0] = (byte) ((v >> 8) & 0xFF); o[1] = (byte) (v & 0xFF)
    return o
}
private long u32(byte[] b, int o) { ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL) }
private int u16(byte[] b, int o) { ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF) }

private byte[] slice(byte[] b, int from, int len) {
    byte[] o = new byte[len]
    for (int i = 0; i < len; i++) o[i] = b[from + i]
    return o
}

private byte[] cat(List parts) {
    int n = 0
    for (part in parts) n += part.length
    byte[] o = new byte[n]
    int p = 0
    for (part in parts) { for (int i = 0; i < part.length; i++) o[p++] = part[i] }
    return o
}

private String toHex(byte[] b) { hubitat.helper.HexUtils.byteArrayToHexString(b).toLowerCase() }
private byte[] fromHex(String h) { hubitat.helper.HexUtils.hexStringToByteArray(h) }
private long nowSec() { (now() / 1000L) as long }
private String jsonString(String s) { groovy.json.JsonOutput.toJson(s) }
