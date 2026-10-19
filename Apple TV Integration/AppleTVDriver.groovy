/*
    Apple TV Integration - Apple TV Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Credits: protocol knowledge and test vectors from the pyatv project (Pierre Ståhl, MIT).

    Notes:
     -One device per Apple TV, created by the app. Holds an always-open Companion session
      (pair-verify, ChaCha20-Poly1305). Switch on = wake (HID 13), off = standby (HID 12).
      power = asleep/screensaver/awake from TVSystemStatus events; screensaver counts as on.
     -Pairing (PIN) runs here because raw sockets only work in drivers. Keys go back to the app
      and are loaded into memory only, so they never show in State Variables.
     -Reconnects with backoff (5s to 5 min). "offline" only after 4 failed attempts, then
      "back online" logs at every level. Follows IP changes through the app's Bonjour list.
     -Health check every 5 min (FetchAttentionState, 20s timeout) catches half-open sockets.
      on/off with no reply in 5s reconnects and retries once. Initialize reconnects after reboots.
     -Playback: play/pause/next/previous/skip via _mcc (MediaControl), buttons via HID press+release.
      transportStatus comes from the allowed-controls flags (_iMC _mcF or MediaControlStatus
      MediaControlFlags): Pause allowed = playing, Play allowed = paused, neither = stopped.
      Flags of 0 are seen briefly while waking, so they only count after 3s.
     -Buttons: back (HID 5, short press), homeScreen (Back held 1s, always the Home Screen, like the
      Siri Remote), tvButton (HID 7, the TV button: one press follows the Apple TV's TV Button setting,
      two back to back open the App Switcher, as pyatv's double tap; max 2 so a triple press never
      triggers the accessibility shortcut). Aliases: menu = back; home = homeScreen; tv = tvButton.
      Closing an app in the App Switcher needs Media Remote (MRP) key events; Companion Up and touch swipes
      only scroll it (tested), so closeApp waits for MRP in 1.2.0.
     -Right after connecting the Apple TV reports its controls twice in a few ms (256, then the real
      value), so flags in the first 2s are applied once, after 2s, to avoid a false "stopped".
     -remoteButton(times) and volumeUp/Down(steps) repeat with a 150/250ms gap (max 10/20).
     -Play from the screensaver presses Play Pause first (dismisses it, like the Siri Remote), from
      sleep wakes first; the real play follows 2-3s later.
     -nowPlaying = title from NowPlayingInfo (an NSKeyedArchiver plist; values are UID refs into
      $objects). Only full updates carry metadata; partial play/pause updates leave the title alone.
      Some apps (Netflix) never send one. Cleared when playback stops or the TV sleeps.
     -launchApp: _launchApp with _bundleID (or _urlS for a URL; YouTube web links are converted to
      youtube://, since tvOS rejects https links, and the Apple TV asks to confirm URL opens). The installed list (FetchLaunchable-
      ApplicationsEvent, bundle ID -> name) is fetched on connect and Refresh and kept in memory;
      State Variables only shows installedApps (names) for typing in Other.
      Dropdown picks try their known spellings (APP_NAMES) exactly first. Typed names match exactly,
      then by prefix, then by contains (must be unique). A bundle ID not in the list is sent as is,
      since the list may omit some apps. Wakes a sleeping TV first.
     -state.lastLaunchFromHubitat = "<app> (<status>)": launching, then launched (Apple TV acknowledged)
      or "failed: <reason>". Only launches sent from Hubitat; the Apple TV never reports the app on
      screen, so this is a State Variable, not an attribute. Failures also log at Errors Only.
      Kept in memory and re-written to state at the end of every parse(): overlapping runs each save
      their own copy of state, so a run that started before the launch would otherwise erase it.
      Reasons: not installed, ambiguous, the Apple TV's error, or no reply (10s after sending, 30s if
      waiting to connect, wake or fetch the app list).
     -Every other event is logged at Full with its decoded content (binary plists decoded), once per
      distinct payload, to learn what NowPlayingInfo carries.
     -The session map is a ConcurrentHashMap: clear keys with remove(), never "= null".
     -Log level comes from the app. Log lines go up to childLog() and appear under the app,
      labeled with this device's name. Falls back to local logging.
*/

import groovy.transform.Field
import java.security.MessageDigest

def version() { return "1.1.4" }

metadata {
    definition(name: "Apple TV", namespace: "jdthomas24", author: "Jason Thomas", importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Apple%20TV%20Integration/AppleTVDriver.groovy") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"
        capability "Initialize"
        capability "MediaTransport"
        attribute "power", "string"
        attribute "connection", "string"
        attribute "nowPlaying", "string"
        command "playPause"
        command "nextTrack"
        command "previousTrack"
        command "skipForward"
        command "skipBackward"
        command "volumeUp", [[name: "steps", type: "NUMBER", description: "Steps (blank = 1, max 20)"]]
        command "volumeDown", [[name: "steps", type: "NUMBER", description: "Steps (blank = 1, max 20)"]]
        command "remoteButton", [[name: "button", type: "ENUM", description: "Siri Remote button",
            constraints: ["up", "down", "left", "right", "select", "back", "homeScreen", "tvButton", "playPause"]],
            [name: "times", type: "NUMBER", description: "Presses (blank = 1, max 10). tvButton x2 opens the App Switcher"]]
        command "launchApp", [[name: "app", type: "ENUM", description: "App (Other = use the box below)",
            constraints: ["Netflix", "YouTube", "YouTube TV", "Prime Video", "Disney+", "Hulu", "HBO Max", "Peacock",
                          "Paramount+", "Plex", "Apple TV", "Music", "Spotify", "Other"]],
            [name: "other", type: "STRING", description: "Other: app name, bundle ID or app link (installed apps are in State Variables). Links open only in apps that support them, and the Apple TV may ask to confirm."]]
    }
    preferences {
        input name: "aboutInfo", type: "paragraph", title: "Pairing, names and logging are managed in the Apple TV Integration app."
    }
}

@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> MEM = new java.util.concurrent.ConcurrentHashMap()
@Field static final Map POWER_NAMES = [1: "asleep", 2: "screensaver", 3: "awake", 4: "idle"]
@Field static final List RETRY_SEC = [5, 15, 30, 60, 120, 300]
@Field static final int OFFLINE_AFTER = 4
@Field static final List CONNECTING = ["OPEN", "PV2", "PV4", "SETUP"]
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]
@Field static final Map MC_CMD = [play: 1, pause: 2, nextTrack: 3, previousTrack: 4, skipForward: 7, skipBackward: 7]
@Field static final Map HID_BTN = [up: 1, down: 2, left: 3, right: 4, back: 5, select: 6,
                                   volumeUp: 8, volumeDown: 9, playPause: 14, homeScreen: 5, tvButton: 7]
@Field static final Map BTN_ALIAS = [menu: "back", home: "homeScreen", tv: "tvButton", tvbutton: "tvButton",
                                     enter: "select", ok: "select", homescreen: "homeScreen", mainmenu: "homeScreen"]
// Dropdown pick -> names the Apple TV may use for it, tried exactly, in order.
@Field static final Map APP_NAMES = ["YouTube TV": ["YouTube TV"], "Prime Video": ["Prime Video", "Amazon Prime Video"],
                                     "Disney+": ["Disney+", "Disney Plus"], "HBO Max": ["HBO Max", "Max"],
                                     "Paramount+": ["Paramount+", "Paramount Plus"], "Apple TV": ["TV", "Apple TV"],
                                     "Music": ["Music", "Apple Music"], "Peacock": ["Peacock", "Peacock TV"]]

// ---------- lifecycle ----------

def installed() {
    state.version = version()
    sendIfChanged("connection", "not paired")
    initialize()
}

def updated() { initialize() }

def initialize() {
    state.version = version()
    unschedule()
    runEvery5Minutes("healthCheck")
    Map m = mem()
    if (m.mode == "pair") return
    closeSocket()
    m.stage = "CLOSED"
    m.failCount = 0
    runIn(2, "connect")
}

def uninstalled() {
    closeSocket()
    MEM.remove(device.id.toString())
}

private Map mem() {
    String k = device.id.toString()
    Map m = MEM.get(k)
    if (m == null) { m = new java.util.concurrent.ConcurrentHashMap(); MEM.put(k, m) }
    return m
}

// ---------- commands ----------

def on() { hid(13, "wake") }

def off() {
    if (device.currentValue("power") == "asleep" && mem().stage == "READY") { logN("already asleep"); return }
    hid(12, "standby")
}

def refresh() {
    Map m = mem()
    if (m.stage == "READY") { request("FetchAttentionState", [:], "attention"); request("FetchLaunchableApplicationsEvent", [:], "apps") }
    else queue("refresh")
}

// ---------- apps ----------

// app is a dropdown pick, "Other" (use other) or, from Rule Machine, any name/bundle ID/URL.
def launchApp(String app, String other = null) {
    String a = (app ?: "").trim()
    if (!a || a == "Other") a = (other ?: "").trim()
    if (!a) { logE("launchApp needs an app. Pick one, or choose Other and type a name, bundle ID or URL."); return }
    Map m = mem()
    if (m.launching != a && m.launchName != a) launchStatus(a, "launching")
    m.launching = a
    if (m.stage != "READY") { queue("app:" + a); runIn(30, "launchTimeout"); return }
    if (device.currentValue("power") == "asleep") { m.launchAfterWake = a; hid(13, "wake"); runIn(3, "launchAfterWake"); runIn(30, "launchTimeout"); return }
    if (a.contains("://")) { String u = appUrl(a); sendLaunch(["_urlS": u], u, "opening ${u}"); return }
    Map apps = (m.apps ?: [:]) as Map
    if (!apps) { m.pendingLaunch = a; request("FetchLaunchableApplicationsEvent", [:], "apps"); runIn(30, "launchTimeout"); return }
    def hit = findApp(apps, a)
    if (hit instanceof List && !hit && a ==~ /[A-Za-z0-9-]+(\.[A-Za-z0-9-]+){2,}/) {
        sendLaunch(["_bundleID": a], a, "launching ${a} (not in the app list, sent as is)")
        return
    }
    if (hit instanceof List) {
        launchFailed(hit ? "'${a}' matches more than one app: ${hit.join(', ')}" : "no app named '${a}'. Installed apps are listed in State Variables.",
            hit ? "more than one match" : "not installed")
        return
    }
    String name = apps[hit] ?: hit
    sendLaunch(["_bundleID": hit], name, "launching ${name}")
}

// tvOS won't open https web links; YouTube links become youtube:// (tvOS asks to confirm).
private String appUrl(String a) {
    def w = a =~ /(?i)^https?:\/\/(?:www\.|m\.)?youtube\.com\/watch\?(?:.*&)?v=([\w-]+)/
    if (w.find()) return "youtube://www.youtube.com/watch?v=${w.group(1)}".toString()
    def s = a =~ /(?i)^https?:\/\/youtu\.be\/([\w-]+)/
    if (s.find()) return "youtube://www.youtube.com/watch?v=${s.group(1)}".toString()
    return a
}

private void sendLaunch(Map content, String name, String msg) {
    Map m = mem()
    m.launchName = name
    if (m.lastLaunchName != name) launchStatus(name, "launching")
    request("_launchApp", content, "launch")
    logN(msg)
    runIn(10, "launchTimeout")
}

private void launchStatus(String name, String status) {
    Map m = mem()
    m.lastLaunchName = name
    m.lastLaunchText = "${name} (${status})".toString()
    syncLaunch()
}

private void syncLaunch() {
    String v = mem().lastLaunchText
    if (v && state.lastLaunchFromHubitat != v) state.lastLaunchFromHubitat = v
}

private void launchDone(String status) {
    Map m = mem()
    String name = m.launchName ?: m.launching ?: m.lastLaunchName
    ["launching", "launchName", "launchAfterWake", "pendingLaunch"].each { m.remove(it) }
    unschedule("launchTimeout")
    launchStatus(name, status)
}

private void launchFailed(String logMsg, String reason) {
    logE("could not launch: ${logMsg}")
    launchDone("failed: ${reason}")
}

def launchTimeout() {
    Map m = mem()
    if (!m.launching) return
    launchFailed("${m.launchName ?: m.launching} got no reply from the Apple TV", "no reply")
}

def launchAfterWake() {
    Map m = mem()
    String a = m.launchAfterWake
    m.remove("launchAfterWake")
    if (a) launchApp(a)
}

// Bundle ID (String) on a unique match; otherwise a List of candidate names (empty = none).
private findApp(Map apps, String a) {
    if (apps.containsKey(a)) return a
    def id = apps.keySet().find { it.equalsIgnoreCase(a) }
    if (id) return id
    for (String n in (APP_NAMES[a] ?: [])) {
        id = apps.find { k, v -> v.toString().equalsIgnoreCase(n) }?.key
        if (id) return id
    }
    String q = a.toLowerCase()
    for (Closure test in [{ String n -> n == q }, { String n -> n.startsWith(q) }, { String n -> n.contains(q) }]) {
        List ids = apps.findAll { k, v -> test(v.toString().toLowerCase()) }.collect { it.key }
        if (ids.size() == 1) return ids[0]
        if (ids.size() > 1) return ids.collect { apps[it] }.sort()
    }
    return []
}

private void appList(c) {
    if (!(c instanceof Map)) return
    Map apps = new TreeMap()
    c.each { k, v -> if (k && v) apps[k.toString()] = v.toString() }
    if (!apps) return
    Map m = mem()
    m.apps = apps
    String names = apps.values().sort(false) { it.toLowerCase() }.join(", ")
    if (state.installedApps != names) { logN("${apps.size()} apps installed"); state.installedApps = names }
    state.remove("apps"); state.remove("appNames")   // pre-1.1.0 test builds
    ["lastApp", "lastAppStatus"].each { try { device.deleteCurrentState(it) } catch (e) { } }   // earlier test builds
    if (state.lastLaunchFromHubitat == null && !mem().lastLaunchText) state.lastLaunchFromHubitat = "none"
    String p = m.pendingLaunch
    m.remove("pendingLaunch")
    if (p) launchApp(p)
}

// ---------- playback and buttons ----------

// Play only reaches a "now playing" app, so from the screensaver press Play Pause first (like the
// Siri Remote), and from sleep wake first; the real play follows once the TV is up. Play is idempotent.
def play() {
    String p = device.currentValue("power")
    if (mem().stage == "READY" && p == "screensaver") { press("playPause", 1); runIn(2, "playAfterWake"); return }
    if (mem().stage == "READY" && p == "asleep") { hid(13, "wake"); runIn(3, "playAfterWake"); return }
    media("play")
}

def playAfterWake() { media("play") }
def pause() { media("pause") }
def stop() { media("pause") }   // Apple TV has no stop; pause is the closest
def nextTrack() { media("nextTrack") }
def previousTrack() { media("previousTrack") }
def skipForward() { media("skipForward") }
def skipBackward() { media("skipBackward") }
def playPause() { press("playPause", 1) }
// Counts are untyped: Rule Machine passes NUMBER as BigDecimal. Blank = 1.
def volumeUp(steps = null) { press("volumeUp", count(steps, 20)) }
def volumeDown(steps = null) { press("volumeDown", count(steps, 20)) }

def remoteButton(String button, times = null) {
    String key = (button ?: "").trim().toLowerCase()
    key = BTN_ALIAS[key] ?: key
    String b = HID_BTN.keySet().find { it.equalsIgnoreCase(key) }
    if (!b) { logE("unknown button '${button}'. Use up, down, left, right, select, back, homeScreen, tvButton or playPause."); return }
    press(b, count(times, b == "tvButton" ? 2 : 10))
}

private int count(raw, int max) {
    if (raw == null || raw.toString().trim() == "") return 1
    try { return Math.max(1, Math.min(max, new BigDecimal(raw.toString().trim()).intValue())) } catch (e) { return 1 }
}

private void media(String name) {
    Map m = mem()
    if (m.stage != "READY") { queue("mc:" + name); return }
    Map c = ["_mcc": MC_CMD[name]]
    if (name == "skipForward") c["_skpS"] = 10.0d
    if (name == "skipBackward") c["_skpS"] = -10.0d
    request("_mcc", c, name)
    logN("sent ${name}")
}

// Repeats get a short gap so the Apple TV (and a receiver over CEC, for volume) registers each one.
private void press(String name, int times = 1) {
    Map m = mem()
    if (m.stage != "READY") { queue("btn:${name}:${times}"); return }
    int code = HID_BTN[name] as int
    int gap = name.startsWith("volume") ? 250 : name == "tvButton" ? 0 : 150   // tvButton x2 = double tap, back to back
    int hold = name == "homeScreen" ? 1000 : 0   // homeScreen = hold Back, like the Siri Remote
    for (int i = 0; i < times; i++) {
        if (i > 0 && gap) pauseExecution(gap)
        request("_hidC", ["_hBtS": 1, "_hidC": code], name)
        if (hold) pauseExecution(hold)
        request("_hidC", ["_hBtS": 2, "_hidC": code], name)
    }
    logN("pressed ${name}${times > 1 ? " x${times}" : ""}")
}

// Allowed-controls flags -> transportStatus. 0 is seen briefly while waking, so it waits 3s.
private void mediaFlags(def raw) {
    if (raw == null) return
    Map m = mem()
    long f = raw as long
    m.flags = f
    if (f == 0L) runIn(3, "applyFlags")
    else if (m.readyAt && now() - (m.readyAt as long) < 2000) runIn(2, "applyFlags")
    else applyFlags()
}

def applyFlags() {
    Map m = mem()
    long f = (m.flags ?: 0L) as long
    String t = device.currentValue("power") == "asleep" ? "stopped" : (f & 2L) ? "playing" : (f & 1L) ? "paused" : "stopped"
    setTransport(t)
}

// Both _iMC and MediaControlStatus report each change, so compare against memory, not the
// attribute (which can lag within one execution) to log once.
private void setTransport(String t) {
    Map m = mem()
    if (m.transport != t) { m.transport = t; logN("playback ${t}") }
    sendIfChanged("transportStatus", t)
    if (t == "stopped") setNowPlaying("")
}

private void nowPlayingInfo(raw) {
    if (!(raw instanceof byte[])) return
    Map info
    try { info = keyedArchive((byte[]) raw) } catch (e) { logF("could not decode NowPlayingInfo: ${e.message}"); return }
    if (!(info?.metadata instanceof Map)) return   // partial update
    setNowPlaying((info.metadata.title ?: "").toString().trim())
}

private void setNowPlaying(String title) {
    Map m = mem()
    if ((m.nowPlaying ?: "") != title) { m.nowPlaying = title; logN(title ? "now playing: ${title}" : "now playing cleared") }
    sendIfChanged("nowPlaying", title ?: "none")
}

// NSKeyedArchiver plist -> plain Map: follows UID refs from $top.root into $objects, drops $class.
private Map keyedArchive(byte[] b) {
    def p = bplistParse(b)
    List objs = p?.'$objects' as List
    def root = p?.'$top'?.root
    if (!objs || root == null) return null
    return nsResolve(root, objs, 0) as Map
}

private nsResolve(v, List objs, int depth) {
    if (depth > 20) return null
    if (v instanceof Map && v.containsKey('$uid')) {
        int i = v['$uid'] as int
        return (i <= 0 || i >= objs.size()) ? null : nsResolve(objs[i], objs, depth + 1)
    }
    if (v instanceof Map) {
        Map out = [:]
        v.each { k, x -> if (k != '$class') out[k] = nsResolve(x, objs, depth + 1) }
        return out
    }
    if (v instanceof List) return v.collect { nsResolve(it, objs, depth + 1) }
    if (v == '$null') return null
    return v
}

private void hid(int button, String label) {
    Map m = mem()
    if (m.stage != "READY") { queue(label); return }
    def (frame, xid) = companionHid(m.ss as Map, button)
    (m.waiting as Map)[xid] = label
    m.pendingCmd = label
    send(frame)
    logN("sent ${label}")
    runIn(5, "commandTimeout")
}

private void queue(String action) {
    Map m = mem()
    if (!creds()) { logE("not paired yet. Pair it in the Apple TV Integration app."); return }
    List q = (m.after ?: []) as List
    if (!q.contains(action)) q << action
    m.after = q
    logF("${action} queued until connected")
    if (!(m.stage in CONNECTING)) connect()
}

private void runAction(String a) {
    if (a == "wake") hid(13, "wake")
    else if (a == "standby") hid(12, "standby")
    else if (a == "refresh") request("FetchAttentionState", [:], "attention")
    else if (a.startsWith("mc:")) media(a - "mc:")
    else if (a.startsWith("btn:")) { List p = a.split(":") as List; press(p[1], p.size() > 2 ? (p[2] as int) : 1) }
    else if (a.startsWith("app:")) launchApp(a.substring(4))
}

def commandTimeout() {
    Map m = mem()
    String label = m.pendingCmd
    if (!label) return
    m.remove("pendingCmd")
    if (m.retriedCmd == label) { m.remove("retriedCmd"); logE("no reply to ${label} after reconnecting"); return }
    m.retriedCmd = label
    logN("no reply to ${label}, reconnecting and retrying")
    m.after = [label]
    forceReconnect()
}

// ---------- app interface ----------

void setLogLevel(String level) { if (state.logLevel != level) state.logLevel = level }

def componentSetAddress(String ip, Integer port, String model = null, String tvName = null) {
    if (ip && (state.ip != ip || state.port != (port ?: 49153))) {
        if (state.ip) logN("address changed to ${ip}:${port ?: 49153}")
        state.ip = ip
        state.port = port ?: 49153
        updateDataValue("ip", ip)
        updateDataValue("port", "${port ?: 49153}")
    }
    if (model && getDataValue("model") != model) updateDataValue("model", model)
    if (tvName && getDataValue("appleTvName") != tvName) updateDataValue("appleTvName", tvName)
}

def componentStartPairing() {
    unschedule("connect")
    unschedule("connectTimeout")
    closeSocket()
    MEM.remove(device.id.toString())
    Map m = mem()
    m.mode = "pair"
    m.pairStage = "M2"
    m.st = [:]
    m.rx = ""
    m.pairStatus = "Waiting for the Apple TV to show a PIN"
    sendIfChanged("connection", "pairing")
    if (!openSocket(state.ip as String, state.port as Integer)) { pairFailed("could not connect to ${state.ip}"); return }
    send(pairingM1(m.st as Map))
    logN("pairing started")
    runIn(30, "pairTimeout")
}

def componentSubmitPin(String pin) {
    Map m = mem()
    if (m.mode != "pair" || m.pairStage != "PIN") {
        m.pairStatus = "Not waiting for a PIN. Tap Pair again."
        logN("PIN ignored, not waiting for one (stage ${m.pairStage ?: 'none'})")
        return
    }
    String digits = (pin ?: "").replaceAll("[^0-9]", "")
    if (!digits) { m.pairStatus = "Enter the PIN shown on the TV"; logN("no PIN entered"); return }
    m.pairStage = "M4"
    m.pairStatus = "Checking PIN"
    send(pairingM3(m.st as Map, digits))
    logN("PIN sent, waiting for the Apple TV")
    runIn(30, "pairTimeout")
}

def componentCancelPairing() {
    unschedule("pairTimeout")
    closeSocket()
    MEM.remove(device.id.toString())
    logN("pairing cancelled")
    if (creds()) runIn(1, "connect") else sendIfChanged("connection", "not paired")
}

String componentPairingStage() { mem().pairStage }
String componentPairingStatus() { mem().pairStatus }

// ---------- pairing ----------

def pairTimeout() {
    Map m = mem()
    if (m.mode == "pair" && m.pairStage in ["M2", "M4", "M6"]) pairFailed("no reply from the Apple TV")
}

private void pairFailed(String why) {
    Map m = mem()
    unschedule("pairTimeout")
    m.pairStage = "FAILED"
    m.pairStatus = "Pairing failed: ${why}"
    m.remove("mode")
    closeSocket()
    logE("pairing failed: ${why}")
    if (creds()) runIn(2, "connect") else sendIfChanged("connection", "not paired")
}

private void handlePairFrame(Map m, int type, byte[] payload) {
    if (type != FRAME_PS_NEXT) { logF("ignoring frame type ${type} while pairing"); return }
    switch (m.pairStage) {
        case "M2":
            pairingHandleM2(m.st as Map, payload)
            unschedule("pairTimeout")
            m.pairStage = "PIN"
            m.pairStatus = "Enter the PIN shown on the TV"
            logN("Apple TV is showing a PIN")
            break
        case "M4":
            pairingHandleM4(m.st as Map, payload)
            m.pairStage = "M6"
            m.pairStatus = "PIN accepted, finishing"
            send(pairingM5(m.st as Map, "Hubitat"))
            break
        case "M6":
            unschedule("pairTimeout")
            Map c = pairingHandleM6(m.st as Map, payload)
            if (!c.signatureOk) throw new IllegalStateException("Apple TV signature did not verify")
            Map saved = [atvId: new String(c.atvId as byte[], "UTF-8"), atvLtpk: toHex(c.atvLtpk as byte[]),
                         seed: toHex(c.seed as byte[]), pairingId: new String(c.pairingId as byte[], "UTF-8"),
                         ourId: toHex(randomBytes(6)), name: c.name, model: c.model]
            parent?.componentPairingComplete(device.deviceNetworkId, saved)
            m.remove("creds")
            m.pairStage = "DONE"
            m.pairStatus = "Paired"
            m.remove("mode")
            state.pairing = "Paired with '${c.name}' on ${fmtNow()}"
            closeSocket()
            logN("paired with '${c.name}' (${c.model})")
            runIn(2, "connect")
            break
    }
}

private Map creds() {
    Map m = mem()
    if (m.creds) return m.creds as Map
    Map c = parent?.componentGetCredentials(device.deviceNetworkId)
    if (!c?.seed) return null
    m.creds = [atvId: (c.atvId as String).getBytes("UTF-8"), atvLtpk: fromHex(c.atvLtpk as String), seed: fromHex(c.seed as String),
               pairingId: (c.pairingId as String).getBytes("UTF-8"), ourId: c.ourId ?: toHex(randomBytes(6)), pairingIdStr: c.pairingId]
    return m.creds as Map
}

// ---------- connection ----------

def connect() {
    Map m = mem()
    if (m.mode == "pair") return
    if (m.stage == "READY" || m.stage in CONNECTING) return
    if (!creds()) { sendIfChanged("connection", "not paired"); return }
    if (!state.ip) refreshAddress()
    if (!state.ip) { scheduleRetry("no address yet"); return }
    m.rx = ""
    m.mode = "session"
    m.stage = "OPEN"
    m.ss = [:]
    m.waiting = [:]
    sendIfChanged("connection", (m.failCount ?: 0) ? (device.currentValue("connection") == "offline" ? "offline" : "reconnecting") : "connecting")
    if (!openSocket(state.ip as String, state.port as Integer)) { m.stage = "CLOSED"; scheduleRetry("could not connect to ${state.ip}"); return }
    m.stage = "PV2"
    send(pvStart(m.ss as Map))
    logF("connecting to ${state.ip}:${state.port}")
    runIn(15, "connectTimeout")
}

def connectTimeout() {
    Map m = mem()
    String at = m.stage
    if (!(at in CONNECTING)) return
    closeSocket()
    m.stage = "CLOSED"
    scheduleRetry("no reply at ${at}")
}

private void scheduleRetry(String reason) {
    Map m = mem()
    int n = ((m.failCount ?: 0) as int) + 1
    m.failCount = n
    if (n == OFFLINE_AFTER) {
        sendIfChanged("connection", "offline")
        logE("offline (${reason}). Retrying every 5 minutes. Check that the Apple TV has power and is on the network.")
    } else if (n < OFFLINE_AFTER) sendIfChanged("connection", "reconnecting")
    if (n >= 2) refreshAddress()
    int delay = RETRY_SEC[Math.min(n - 1, RETRY_SEC.size() - 1)] as int
    logF("retry ${n} in ${delay}s (${reason})")
    runIn(delay, "connect")
}

private void forceReconnect() {
    Map m = mem()
    closeSocket()
    m.stage = "CLOSED"
    runIn(1, "connect")
}

private void refreshAddress() {
    List addrs = parent?.componentLookupAddress(device.deviceNetworkId) ?: []
    if (!addrs) return
    int n = (mem().failCount ?: 0) as int
    Map a = addrs[n % addrs.size()] as Map
    componentSetAddress(a.ip as String, a.port as Integer)
}

def healthCheck() {
    try {
        Map m = mem()
        if (m.mode == "pair" || !creds()) return
        if (m.stage == "READY") {
            m.probeAt = now()
            request("FetchAttentionState", [:], "health")
            runIn(20, "healthTimeout")
        } else if (!(m.stage in CONNECTING)) connect()
    } catch (e) {
        logE("health check failed: ${e.message}")
    }
}

def healthTimeout() {
    Map m = mem()
    if (m.probeAt && m.stage == "READY") {
        m.remove("probeAt")
        logF("no reply to health check, reconnecting")
        forceReconnect()
    }
}

def socketStatus(String s) {
    Map m = mem()
    logF("socket status: ${s}")
    String l = s?.toLowerCase() ?: ""
    if (!(l.contains("error") || l.contains("closed") || l.contains("disconnect"))) return
    if (m.stage != "READY" && m.closingAt && now() - (m.closingAt as long) < 3000) return
    if (m.mode == "pair") {
        if (!(m.pairStage in ["DONE", "FAILED"])) pairFailed("the Apple TV closed the connection")
        return
    }
    if (m.stage == "CLOSED") return
    boolean wasReady = m.stage == "READY"
    m.stage = "CLOSED"
    unschedule("connectTimeout")
    if (wasReady) logN("connection lost, reconnecting")
    scheduleRetry("socket ${s}")
}

private boolean openSocket(String ip, Integer port) {
    closeSocket()
    try {
        interfaces.rawSocket.connect(ip, (port ?: 49153) as int, byteInterface: true)
        return true
    } catch (e) {
        logF("connect to ${ip}:${port} failed: ${e.message}")
        return false
    }
}

private void closeSocket() {
    mem().closingAt = now()
    try { interfaces.rawSocket.close() } catch (e) { }
}

def parse(String hex) {
    try { parseFrames(hex) } finally { syncLaunch() }
}

private void parseFrames(String hex) {
    Map m = mem()
    m.rx = (m.rx ?: "") + hex
    while ((m.rx as String).length() >= 8) {
        String buf = m.rx as String
        int len = Integer.parseInt(buf.substring(2, 8), 16)
        if (buf.length() < (4 + len) * 2) return
        byte[] header = fromHex(buf.substring(0, 8))
        byte[] payload = fromHex(buf.substring(8, (4 + len) * 2))
        m.rx = buf.substring((4 + len) * 2)
        try {
            if (m.mode == "pair") handlePairFrame(m, header[0] & 0xFF, payload)
            else handleSessionFrame(m, header, payload)
        } catch (e) {
            if (m.mode == "pair") { pairFailed(e.message); return }
            unschedule("connectTimeout")
            if (m.stage in CONNECTING && e.message?.contains("identity")) {
                m.stage = "CLOSED"
                closeSocket()
                sendIfChanged("connection", "offline")
                logE("${e.message}. Pair this Apple TV again in the app.")
                return
            }
            logN("session error (${e.message}), reconnecting")
            m.rx = ""
            forceReconnect()
            return
        }
    }
}

private void handleSessionFrame(Map m, byte[] header, byte[] payload) {
    int type = header[0] & 0xFF
    Map c = creds()
    if (m.stage == "PV2") {
        if (type != FRAME_PV_NEXT) return
        send(pvHandleM2(m.ss as Map, c, payload))
        m.stage = "PV4"
        return
    }
    if (m.stage == "PV4") {
        if (type != FRAME_PV_NEXT) return
        pvHandleM4(m.ss as Map, payload)
        m.stage = "SETUP"
        String ourId = c.ourId as String
        request("_systemInfo", ["_bf": 0, "_cf": 512, "_clFl": 128, "_i": ourId, "_idsID": c.pairingIdStr,
            "_pubID": ourId.toUpperCase().replaceAll(/(..)(?!$)/, '$1:'), "_sf": 256, "_sv": "170.18",
            "model": "Hubitat", "name": "Hubitat"], "systemInfo")
        return
    }
    if (type != FRAME_E_OPACK) { logF("ignoring frame type ${type}"); return }
    Map msg = opackUnpack(sessionOpen(m.ss as Map, header, payload))[0] as Map
    if (msg._t == 1) {
        if (msg._i in ["SystemStatus", "TVSystemStatus"] && msg._c instanceof Map && msg._c.state != null) setPower(msg._c.state as int, "event")
        else if (msg._i == "_iMC" && msg._c instanceof Map) { logEvent(msg); mediaFlags(msg._c._mcF) }
        else if (msg._i == "MediaControlStatus" && msg._c instanceof Map) { logEvent(msg); mediaFlags(msg._c.MediaControlFlags) }
        else if (msg._i == "NowPlayingInfo" && msg._c instanceof Map) { logEvent(msg); nowPlayingInfo(msg._c.NowPlayingInfoKey) }
        else logEvent(msg)
        return
    }
    if (msg._t == 3) {
        String label = (m.waiting as Map).remove(msg._x) ?: "unknown"
        if (msg._em) {
            if (label == "tvrc") { finishSetup(m); return }
            if (label == "launch") { launchFailed("${m.launchName ?: 'app'}: ${msg._em}", msg._em.toString().take(60)); return }
            if (label == "apps") {
                logN("could not read the app list: ${msg._em}")
                if (m.pendingLaunch) launchFailed("${m.pendingLaunch}: the app list couldn't be read", "app list unavailable")
                return
            }
            logN("${label} failed: ${msg._em}")
            return
        }
        switch (label) {
            case "systemInfo":
                request("_sessionStart", ["_srvT": "com.apple.tvremoteservices", "_sid": new java.security.SecureRandom().nextInt(0x7FFFFFFF)], "sessionStart")
                break
            case "sessionStart":
                request("TVRCSessionStart", ["ProtocolVersionKey": "1.2"], "tvrc")
                break
            case "tvrc":
                finishSetup(m)
                break
            case "attention":
            case "health":
                if (label == "health") { m.remove("probeAt"); unschedule("healthTimeout") }
                if (msg._c instanceof Map && msg._c.state != null) setPower(msg._c.state as int, label == "health" ? "check" : "query")
                break
            case "apps":
                appList(msg._c)
                break
            case "launch":
                logF("${m.launchName ?: 'launch'} acknowledged")
                launchDone("launched")
                break
            case "wake":
            case "standby":
                if (m.pendingCmd == label) { m.remove("pendingCmd"); unschedule("commandTimeout") }
                if (m.retriedCmd == label) m.remove("retriedCmd")
                logF("${label} acknowledged")
                break
            default:
                logF("reply to ${label}")
        }
        return
    }
    logF("message type ${msg._t}")
}

private void finishSetup(Map m) {
    if (m.stage == "READY") return
    send(companionEvent(m.ss as Map, "_interest", ["_regEvents": ["SystemStatus"]]))
    send(companionEvent(m.ss as Map, "_interest", ["_regEvents": ["TVSystemStatus"]]))
    send(companionEvent(m.ss as Map, "_interest", ["_regEvents": ["_iMC"]]))
    m.stage = "READY"
    m.readyAt = now()
    unschedule("connectTimeout")
    boolean wasOffline = device.currentValue("connection") == "offline"
    m.failCount = 0
    sendIfChanged("connection", "connected")
    state.lastConnected = fmtNow()
    if (wasOffline) slog("info", "back online")
    else logN("connected")
    request("FetchAttentionState", [:], "attention")
    request("FetchLaunchableApplicationsEvent", [:], "apps")
    List q = (m.after ?: []) as List
    m.after = []
    q.each { runAction(it as String) }
}

private void request(String id, Map content, String label) {
    Map m = mem()
    def (frame, xid) = companionRequest(m.ss as Map, id, content)
    (m.waiting as Map)[xid] = label
    send(frame)
}

private void setPower(int s, String source) {
    String v = POWER_NAMES[s]
    if (!v) { logF("unknown power state ${s}"); return }
    if (device.currentValue("power") != v) logN("power ${v} (${source})")
    sendIfChanged("power", v)
    sendIfChanged("switch", v == "asleep" ? "off" : "on")
    if (v == "asleep") setTransport("stopped")
}

// Full only, once per distinct payload, so NowPlayingInfo bursts don't flood the log.
private void logEvent(Map msg) {
    if (lvl() < 2) return
    Map m = mem()
    String text = describe(msg._c, 0)
    Map seen = (m.eventSeen ?: [:]) as Map
    int sig = text.hashCode()
    if (seen[msg._i] == sig) return
    seen[msg._i] = sig
    m.eventSeen = seen
    logF("event ${msg._i}: ${text.take(msg._i == 'NowPlayingInfo' ? 3000 : 600)}")
}

private String describe(def v, int depth) {
    if (depth > 8) return "..."
    if (v instanceof Map) return "{" + v.collect { k, x -> "${k}=${describe(x, depth + 1)}" }.join(", ") + "}"
    if (v instanceof List) return "[" + v.collect { describe(it, depth + 1) }.join(", ") + "]"
    if (v instanceof byte[]) {
        byte[] b = (byte[]) v
        if (b.length > 8 && new String(cdSlice(b, 0, 8), "US-ASCII") == "bplist00") {
            try { return "plist" + describe(bplistParse(b), depth + 1) } catch (e) { return "plist(unreadable ${b.length} bytes)" }
        }
        return "bytes(${b.length}):" + toHex(cdSlice(b, 0, Math.min(b.length, 48)))
    }
    if (v instanceof CharSequence) return "'" + v.toString().take(120) + "'"
    return String.valueOf(v)
}

// ---------- binary plist (bplist00) reader, for logging event payloads ----------

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
        case 0x4: return cdSlice(d, start, start + len)
        case 0x5: return new String(cdSlice(d, start, start + len), "US-ASCII")
        case 0x6: return new String(cdSlice(d, start, start + len * 2), "UTF-16BE")
        case 0x8: return ['$uid': bpUInt(d, p + 1, lo + 1)]
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

private void send(byte[] frame) {
    try {
        interfaces.rawSocket.sendMessage(toHex(frame))
    } catch (e) {
        Map m = mem()
        logF("send failed: ${e.message}")
        if (m.mode == "session" && m.stage == "READY") { m.stage = "CLOSED"; scheduleRetry("send failed") }
    }
}

private void sendIfChanged(String name, String value) {
    if (device.currentValue(name) == value) return
    String desc = name == "switch" ? "${device.displayName} switch is ${value}" : "${device.displayName} ${name} is ${value}"
    sendEvent(name: name, value: value, descriptionText: desc)
}

private String fmtNow() { new Date().format("MMM d, h:mm a", location.timeZone) }

// ---------- logging (routed through the app) ----------

private int lvl() { LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only") }

private void logE(String s) { slog("warn", s) }
private void logN(String s) { if (lvl() >= 1) slog("info", s) }
private void logF(String s) { if (lvl() >= 2) slog("debug", s) }

// All log lines go up to the app, so they show under the app labeled by device.
private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) } catch (e) { localLog(level, "${device.displayName}: ${msg}") }
}

private void localLog(String level, String line) {
    switch (level) {
        case "error": log.error line; break
        case "warn": log.warn line; break
        case "info": log.info line; break
        default: log.debug line
    }
}

// ---------- hex helpers ----------

private byte[] fromHex(String s) {
    byte[] out = new byte[(s ?: "").length() / 2]
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16)
    return out
}

private String toHex(byte[] b) {
    StringBuilder sb = new StringBuilder()
    b.each { sb.append(String.format("%02x", it & 0xFF)) }
    return sb.toString()
}

// ---------- crypto and codecs (unchanged from the tested pair driver) ----------

// ---------- Ed25519 (RFC 8032), pure Groovy ----------
// The hub's Java has no Ed25519 (added in Java 15). Used only for the Companion pairing
// handshake (a few operations per connection), so BigInteger speed is fine.
// Keys and signatures are byte[]: private seed 32, public 32, signature 64.

// Constants as literals: Hubitat cannot build one static @Field from another.
@Field static final BigInteger ED_P = new BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")
@Field static final BigInteger ED_L = new BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
@Field static final BigInteger ED_D = new BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")
@Field static final BigInteger ED_SQRT_M1 = new BigInteger("19681161376707505956807079304988542015446066515923890162744021073123829784752")
@Field static final BigInteger ED_GY = new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

/** Public key (32 bytes) for a 32-byte private seed. */
byte[] ed25519PublicKey(byte[] seed) {
    def (BigInteger a, byte[] prefix) = edExpand(seed)
    return edCompress(edMul(a, edBase()))
}

/** 64-byte signature of msg with a 32-byte private seed. */
byte[] ed25519Sign(byte[] seed, byte[] msg) {
    def (BigInteger a, byte[] prefix) = edExpand(seed)
    byte[] pub = edCompress(edMul(a, edBase()))
    BigInteger r = edLe(edSha512([prefix, msg])).mod(ED_L)
    byte[] rs = edCompress(edMul(r, edBase()))
    BigInteger h = edLe(edSha512([rs, pub, msg])).mod(ED_L)
    BigInteger s = r.add(h.multiply(a)).mod(ED_L)
    return edConcat(rs, edToLe(s, 32))
}

/** True if sig is a valid signature of msg by the 32-byte public key. */
boolean ed25519Verify(byte[] pub, byte[] msg, byte[] sig) {
    if (pub == null || sig == null || pub.length != 32 || sig.length != 64) return false
    List<BigInteger> A = edDecompress(pub)
    if (A == null) return false
    byte[] rs = edSlice(sig, 0, 32)
    List<BigInteger> R = edDecompress(rs)
    if (R == null) return false
    BigInteger s = edLe(edSlice(sig, 32, 64))
    if (s >= ED_L) return false
    BigInteger h = edLe(edSha512([rs, pub, msg])).mod(ED_L)
    return edEqual(edMul(s, edBase()), edAdd(R, edMul(h, A)))
}

// Points are extended coordinates [X, Y, Z, T] with x = X/Z, y = Y/Z, x*y = T/Z.
private List<BigInteger> edBase() {
    BigInteger x = edRecoverX(ED_GY, 0)
    return [x, ED_GY, BigInteger.ONE, x.multiply(ED_GY).mod(ED_P)]
}

private List<BigInteger> edAdd(List<BigInteger> P, List<BigInteger> Q) {
    BigInteger p = ED_P
    BigInteger A = P[1].subtract(P[0]).multiply(Q[1].subtract(Q[0])).mod(p)
    BigInteger B = P[1].add(P[0]).multiply(Q[1].add(Q[0])).mod(p)
    BigInteger C = BigInteger.TWO.multiply(P[3]).multiply(Q[3]).multiply(ED_D).mod(p)
    BigInteger D = BigInteger.TWO.multiply(P[2]).multiply(Q[2]).mod(p)
    BigInteger E = B.subtract(A), F = D.subtract(C), G = D.add(C), H = B.add(A)
    return [E.multiply(F).mod(p), G.multiply(H).mod(p), F.multiply(G).mod(p), E.multiply(H).mod(p)]
}

private List<BigInteger> edMul(BigInteger s, List<BigInteger> P) {
    List<BigInteger> Q = [BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO]
    List<BigInteger> R = P
    BigInteger k = s
    while (k.signum() > 0) {
        if (k.testBit(0)) Q = edAdd(Q, R)
        R = edAdd(R, R)
        k = k.shiftRight(1)
    }
    return Q
}

private boolean edEqual(List<BigInteger> P, List<BigInteger> Q) {
    BigInteger p = ED_P
    return P[0].multiply(Q[2]).subtract(Q[0].multiply(P[2])).mod(p).signum() == 0 &&
        P[1].multiply(Q[2]).subtract(Q[1].multiply(P[2])).mod(p).signum() == 0
}

private BigInteger edRecoverX(BigInteger y, int sign) {
    BigInteger p = ED_P
    if (y >= p) return null
    BigInteger x2 = y.multiply(y).subtract(BigInteger.ONE)
        .multiply(ED_D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(p)).mod(p)
    if (x2.signum() == 0) return sign ? null : BigInteger.ZERO
    BigInteger x = x2.modPow(p.add(BigInteger.valueOf(3)).shiftRight(3), p)
    if (x.multiply(x).subtract(x2).mod(p).signum() != 0) x = x.multiply(ED_SQRT_M1).mod(p)
    if (x.multiply(x).subtract(x2).mod(p).signum() != 0) return null
    if ((x.testBit(0) ? 1 : 0) != sign) x = p.subtract(x)
    return x
}

private byte[] edCompress(List<BigInteger> P) {
    BigInteger zinv = P[2].modInverse(ED_P)
    BigInteger x = P[0].multiply(zinv).mod(ED_P)
    BigInteger y = P[1].multiply(zinv).mod(ED_P)
    return edToLe(y.or(x.testBit(0) ? BigInteger.ONE.shiftLeft(255) : BigInteger.ZERO), 32)
}

private List<BigInteger> edDecompress(byte[] s) {
    BigInteger y = edLe(s)
    int sign = y.testBit(255) ? 1 : 0
    y = y.clearBit(255)
    BigInteger x = edRecoverX(y, sign)
    if (x == null) return null
    return [x, y, BigInteger.ONE, x.multiply(y).mod(ED_P)]
}

private List edExpand(byte[] seed) {
    byte[] h = edSha512([seed])
    BigInteger a = edLe(edSlice(h, 0, 32))
    a = a.and(BigInteger.ONE.shiftLeft(254).subtract(BigInteger.valueOf(8))).or(BigInteger.ONE.shiftLeft(254))
    return [a, edSlice(h, 32, 64)]
}

private byte[] edSha512(List<byte[]> parts) {
    def md = MessageDigest.getInstance("SHA-512")
    parts.each { md.update(it) }
    return md.digest()
}

private BigInteger edLe(byte[] b) {
    byte[] be = new byte[b.length]
    for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i]
    return new BigInteger(1, be)
}

private byte[] edToLe(BigInteger v, int len) {
    byte[] out = new byte[len]
    BigInteger x = v
    for (int i = 0; i < len; i++) {
        out[i] = (byte) x.and(BigInteger.valueOf(0xFF)).intValue()
        x = x.shiftRight(8)
    }
    return out
}

// java.util.Arrays is blocked by the Hubitat sandbox.
private byte[] edSlice(byte[] b, int from, int to) {
    byte[] out = new byte[to - from]
    for (int i = from; i < to; i++) out[i - from] = b[i]
    return out
}

private byte[] edConcat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length]
    for (int i = 0; i < a.length; i++) out[i] = a[i]
    for (int i = 0; i < b.length; i++) out[a.length + i] = b[i]
    return out
}

// ---------- X25519 (RFC 7748), pure Groovy ----------
// Raw 32-byte keys, so no Java key-spec classes are needed. One exchange per connection.

@Field static final BigInteger X_P = new BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")
@Field static final BigInteger X_A24 = new BigInteger("121665")

/** Public key (32 bytes) for a 32-byte private key. */
byte[] x25519PublicKey(byte[] priv) { x25519(priv, x9()) }

/** Shared secret (32 bytes). */
byte[] x25519Shared(byte[] priv, byte[] peerPublic) { x25519(priv, peerPublic) }

private byte[] x9() { byte[] b = new byte[32]; b[0] = 9; return b }

private byte[] x25519(byte[] scalar, byte[] uBytes) {
    byte[] k = new byte[32]
    for (int i = 0; i < 32; i++) k[i] = scalar[i]
    k[0] = (byte) (k[0] & 248)
    k[31] = (byte) ((k[31] & 127) | 64)
    byte[] u = new byte[32]
    for (int i = 0; i < 32; i++) u[i] = uBytes[i]
    u[31] = (byte) (u[31] & 127)
    BigInteger p = X_P
    BigInteger x1 = xLe(u).mod(p), x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE
    BigInteger kn = xLe(k)
    int swap = 0
    for (int t = 254; t >= 0; t--) {
        int kt = kn.testBit(t) ? 1 : 0
        swap ^= kt
        if (swap == 1) { def tx = x2; x2 = x3; x3 = tx; def tz = z2; z2 = z3; z3 = tz }
        swap = kt
        BigInteger A = x2.add(z2), AA = A.multiply(A).mod(p)
        BigInteger B = x2.subtract(z2), BB = B.multiply(B).mod(p)
        BigInteger E = AA.subtract(BB)
        BigInteger C = x3.add(z3), D = x3.subtract(z3)
        BigInteger DA = D.multiply(A).mod(p), CB = C.multiply(B).mod(p)
        x3 = DA.add(CB).pow(2).mod(p)
        z3 = x1.multiply(DA.subtract(CB).pow(2)).mod(p)
        x2 = AA.multiply(BB).mod(p)
        z2 = E.multiply(AA.add(X_A24.multiply(E))).mod(p)
    }
    if (swap == 1) { def tx = x2; x2 = x3; x3 = tx; def tz = z2; z2 = z3; z3 = tz }
    BigInteger r = x2.multiply(z2.modPow(p.subtract(BigInteger.TWO), p)).mod(p)
    byte[] out = new byte[32]
    for (int i = 0; i < 32; i++) out[i] = (byte) r.shiftRight(8 * i).and(BigInteger.valueOf(0xFF)).intValue()
    return out
}

private BigInteger xLe(byte[] b) {
    BigInteger v = BigInteger.ZERO
    for (int i = b.length - 1; i >= 0; i--) v = v.shiftLeft(8).or(BigInteger.valueOf(b[i] & 0xFF))
    return v
}

// ---------- SRP-6a client (HomeKit pair-setup: 3072-bit group, g = 5, SHA-512) ----------
// Mirrors srptools as used by pyatv: user "Pair-Setup", password = PIN. Values hashed unpadded
// unless noted (PAD = left-padded to 384 bytes). One run per pairing.

@Field static final BigInteger SRP_N = new BigInteger(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16)
@Field static final int SRP_N_LEN = 384

/**
 * Client side of pair-setup M3. seedA: 32-byte client private. pin: 8-digit PIN as shown on the TV.
 * salt, serverPublic: from the Apple TV's M2. Returns [A, M1, M2, K] (byte[]); A and M1 go to the TV,
 * M2 is what the TV must send back, K is the session key for later steps.
 */
Map srpClient(byte[] seedA, String pin, byte[] salt, byte[] serverPublic) {
    BigInteger N = SRP_N, g = BigInteger.valueOf(5)
    BigInteger a = new BigInteger(1, seedA)
    BigInteger A = g.modPow(a, N)
    BigInteger B = new BigInteger(1, serverPublic)
    if (B.mod(N).signum() == 0) throw new IllegalArgumentException("invalid server public key")
    BigInteger k = srpHashInt([srpBytes(N), srpPad(g)])
    BigInteger u = srpHashInt([srpPad(A), srpPad(B)])
    byte[] inner = srpHash(["Pair-Setup:${pin}".toString().getBytes("UTF-8")])
    BigInteger x = srpHashInt([salt, inner])
    BigInteger S = B.subtract(k.multiply(g.modPow(x, N))).modPow(a.add(u.multiply(x)), N)
    byte[] K = srpHash([srpBytes(S)])
    BigInteger hNxorG = srpHashInt([srpBytes(N)]).xor(srpHashInt([srpBytes(g)]))
    byte[] M1 = srpHash([srpBytes(hNxorG), srpHash(["Pair-Setup".getBytes("UTF-8")]), salt, srpBytes(A), srpBytes(B), K])
    byte[] M2 = srpHash([srpBytes(A), M1, K])
    return [A: srpBytes(A), M1: M1, M2: M2, K: K]
}

private byte[] srpHash(List<byte[]> parts) {
    def md = MessageDigest.getInstance("SHA-512")
    parts.each { md.update(it) }
    return md.digest()
}

private BigInteger srpHashInt(List<byte[]> parts) { new BigInteger(1, srpHash(parts)) }

/** Minimal big-endian bytes, no sign byte (srptools int_to_bytes). */
private byte[] srpBytes(BigInteger v) {
    byte[] b = v.toByteArray()
    if (b.length > 1 && b[0] == 0) {
        byte[] out = new byte[b.length - 1]
        for (int i = 1; i < b.length; i++) out[i - 1] = b[i]
        return out
    }
    return b
}

/** Left-padded to the size of N. */
private byte[] srpPad(BigInteger v) {
    byte[] b = srpBytes(v)
    byte[] out = new byte[SRP_N_LEN]
    int off = SRP_N_LEN - b.length
    for (int i = 0; i < b.length; i++) out[off + i] = b[i]
    return out
}

// ---------- TLV8 (HomeKit pairing messages) ----------
// Map<Integer, byte[]> in insertion order. Values over 255 bytes are split into repeated tags
// and joined again on read. Empty values are skipped on write (same as pyatv).

byte[] tlv8Write(Map data) {
    def out = new java.io.ByteArrayOutputStream()
    data.each { k, v ->
        byte[] val = v as byte[]
        int pos = 0
        while (pos < val.length) {
            int size = Math.min(val.length - pos, 255)
            out.write((k as Integer) & 0xFF)
            out.write(size)
            out.write(val, pos, size)
            pos += size
        }
    }
    return out.toByteArray()
}

Map tlv8Read(byte[] data) {
    Map result = [:]
    int pos = 0
    while (pos + 1 < data.length) {
        int tag = data[pos] & 0xFF
        int len = data[pos + 1] & 0xFF
        byte[] val = cdSlice(data, pos + 2, Math.min(pos + 2 + len, data.length))
        result[tag] = result.containsKey(tag) ? cdConcat(result[tag] as byte[], val) : val
        pos += 2 + len
    }
    return result
}

// ---------- OPACK (Apple Companion messages) ----------
// Groovy types: null, Boolean, Integer/Long/BigInteger, Double/Float, String, byte[], UUID,
// List, Map. Mirrors pyatv's opack.py, including back-references for repeated values.

byte[] opackPack(Object data) {
    return opPack(data, [])
}

/** Returns [value, nextOffset]. */
List opackUnpack(byte[] data, int offset = 0) {
    return opUnpack(data, offset, [])
}

private byte[] opPack(Object data, List<byte[]> seen) {
    def out = new java.io.ByteArrayOutputStream()
    if (data == null) {
        out.write(0x04)
    } else if (data instanceof Boolean) {
        out.write(data ? 1 : 2)
    } else if (data instanceof UUID) {
        out.write(0x05)
        cdWriteLong(out, ((UUID) data).mostSignificantBits, 8, true)
        cdWriteLong(out, ((UUID) data).leastSignificantBits, 8, true)
    } else if (data instanceof Integer || data instanceof Long || data instanceof BigInteger) {
        BigInteger v = data as BigInteger
        if (v.signum() < 0) throw new IllegalArgumentException("negative ints not supported")
        if (v < 0x28) {
            out.write(v.intValue() + 8)
        } else {
            int n = v <= 0xFF ? 1 : v <= 0xFFFF ? 2 : v <= 0xFFFFFFFFL ? 4 : 8
            out.write(n == 1 ? 0x30 : n == 2 ? 0x31 : n == 4 ? 0x32 : 0x33)
            cdWriteBig(out, v, n)
        }
    } else if (data instanceof Float || data instanceof Double || data instanceof BigDecimal) {
        out.write(0x36)
        cdWriteLong(out, Double.doubleToLongBits((data as Number).doubleValue()), 8, false)
    } else if (data instanceof CharSequence) {
        byte[] s = data.toString().getBytes("UTF-8")
        cdWriteSized(out, s.length, 0x40, 0x20, 0x61)
        out.write(s, 0, s.length)
    } else if (data instanceof byte[]) {
        byte[] b = (byte[]) data
        if (b.length <= 0x20) {
            out.write(0x70 + b.length)
        } else {
            int n = b.length <= 0xFF ? 1 : b.length <= 0xFFFF ? 2 : 4
            out.write(n == 1 ? 0x91 : n == 2 ? 0x92 : 0x93)
            cdWriteBig(out, BigInteger.valueOf(b.length), n)
        }
        out.write(b, 0, b.length)
    } else if (data instanceof List) {
        List l = (List) data
        out.write(0xD0 + Math.min(l.size(), 0xF))
        l.each { byte[] p = opPack(it, seen); out.write(p, 0, p.length) }
        if (l.size() >= 0xF) out.write(0x03)
    } else if (data instanceof Map) {
        Map m = (Map) data
        out.write(0xE0 + Math.min(m.size(), 0xF))
        m.each { k, v ->
            byte[] pk = opPack(k, seen); out.write(pk, 0, pk.length)
            byte[] pv = opPack(v, seen); out.write(pv, 0, pv.length)
        }
        if (m.size() >= 0xF) out.write(0x03)
    } else {
        throw new IllegalArgumentException("cannot pack value: ${data}")
    }
    byte[] packed = out.toByteArray()
    int idx = seen.findIndexOf { cdEq(it, packed) }
    if (idx >= 0) {
        def ref = new java.io.ByteArrayOutputStream()
        if (idx < 0x21) {
            ref.write(0xA0 + idx)
        } else {
            int n = idx <= 0xFF ? 1 : idx <= 0xFFFF ? 2 : 4
            ref.write(0xC0 + n)
            cdWriteBig(ref, BigInteger.valueOf(idx), n)
        }
        return ref.toByteArray()
    }
    if (packed.length > 1) seen << packed
    return packed
}

private List opUnpack(byte[] d, int p, List seen) {
    int t = d[p] & 0xFF
    Object value
    int next
    boolean remember = true
    if (t == 0x01 || t == 0x02) {
        value = (t == 0x01); next = p + 1; remember = false
    } else if (t == 0x04) {
        value = null; next = p + 1; remember = false
    } else if (t == 0x05) {
        value = new UUID(cdReadLong(d, p + 1, 8, true), cdReadLong(d, p + 9, 8, true)); next = p + 17
    } else if (t == 0x06) {
        value = cdReadBig(d, p + 1, 8); next = p + 9
    } else if (t >= 0x08 && t <= 0x2F) {
        value = (long) (t - 8); next = p + 1; remember = false
    } else if (t == 0x35) {
        value = Float.intBitsToFloat((int) cdReadLong(d, p + 1, 4, false)); next = p + 5
    } else if (t == 0x36) {
        value = Double.longBitsToDouble(cdReadLong(d, p + 1, 8, false)); next = p + 9
    } else if ((t & 0xF0) == 0x30) {
        int n = 1 << (t & 0xF)
        BigInteger v = cdReadBig(d, p + 1, n)
        value = v.bitLength() < 64 ? v.longValue() : v; next = p + 1 + n
    } else if (t >= 0x40 && t <= 0x60) {
        int len = t - 0x40
        value = new String(cdSlice(d, p + 1, p + 1 + len), "UTF-8"); next = p + 1 + len
    } else if (t > 0x60 && t <= 0x64) {
        int n = t & 0xF
        int len = cdReadBig(d, p + 1, n).intValue()
        value = new String(cdSlice(d, p + 1 + n, p + 1 + n + len), "UTF-8"); next = p + 1 + n + len
    } else if (t >= 0x70 && t <= 0x90) {
        int len = t - 0x70
        value = cdSlice(d, p + 1, p + 1 + len); next = p + 1 + len
    } else if (t >= 0x91 && t <= 0x94) {
        int n = 1 << ((t & 0xF) - 1)
        int len = cdReadBig(d, p + 1, n).intValue()
        value = cdSlice(d, p + 1 + n, p + 1 + n + len); next = p + 1 + n + len
    } else if ((t & 0xF0) == 0xD0) {
        int count = t & 0xF
        List l = []
        int q = p + 1
        if (count == 0xF) {
            while ((d[q] & 0xFF) != 0x03) { def r = opUnpack(d, q, seen); l << r[0]; q = r[1] as int }
            q++
        } else {
            for (int i = 0; i < count; i++) { def r = opUnpack(d, q, seen); l << r[0]; q = r[1] as int }
        }
        value = l; next = q; remember = false
    } else if ((t & 0xE0) == 0xE0) {
        int count = t & 0xF
        Map m = [:]
        int q = p + 1
        if (count == 0xF) {
            while ((d[q] & 0xFF) != 0x03) {
                def k = opUnpack(d, q, seen); def v = opUnpack(d, k[1] as int, seen)
                m[k[0]] = v[0]; q = v[1] as int
            }
            q++
        } else {
            for (int i = 0; i < count; i++) {
                def k = opUnpack(d, q, seen); def v = opUnpack(d, k[1] as int, seen)
                m[k[0]] = v[0]; q = v[1] as int
            }
        }
        value = m; next = q; remember = false
    } else if (t >= 0xA0 && t <= 0xC0) {
        value = seen[t - 0xA0]; next = p + 1
    } else if (t >= 0xC1 && t <= 0xC4) {
        int n = t - 0xC0
        value = seen[cdReadBig(d, p + 1, n).intValue()]; next = p + 1 + n
    } else {
        throw new IllegalArgumentException("unknown OPACK type 0x${Integer.toHexString(t)}")
    }
    if (remember && !seen.any { cdEq(it, value) }) seen << value
    return [value, next]
}

// ---------- shared byte helpers (java.util.Arrays is blocked by the Hubitat sandbox) ----------

private boolean cdEq(a, b) {
    if (a instanceof byte[] && b instanceof byte[]) {
        byte[] x = (byte[]) a, y = (byte[]) b
        if (x.length != y.length) return false
        for (int i = 0; i < x.length; i++) if (x[i] != y[i]) return false
        return true
    }
    if (a instanceof byte[] || b instanceof byte[]) return false
    if (a instanceof Number && b instanceof Number) return (a as BigDecimal).compareTo(b as BigDecimal) == 0
    return a == b
}

private byte[] cdSlice(byte[] b, int from, int to) {
    byte[] out = new byte[to - from]
    for (int i = from; i < to; i++) out[i - from] = b[i]
    return out
}

private byte[] cdConcat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length]
    for (int i = 0; i < a.length; i++) out[i] = a[i]
    for (int i = 0; i < b.length; i++) out[a.length + i] = b[i]
    return out
}

private void cdWriteBig(java.io.ByteArrayOutputStream out, BigInteger v, int n) {
    for (int i = 0; i < n; i++) out.write(v.shiftRight(8 * i).intValue() & 0xFF)
}

private void cdWriteLong(java.io.ByteArrayOutputStream out, long v, int n, boolean bigEndian) {
    for (int i = 0; i < n; i++) {
        int shift = bigEndian ? 8 * (n - 1 - i) : 8 * i
        out.write((int) ((v >>> shift) & 0xFF))
    }
}

private void cdWriteSized(java.io.ByteArrayOutputStream out, int len, int shortBase, int shortMax, int longBase) {
    if (len <= shortMax) {
        out.write(shortBase + len)
    } else {
        int n = len <= 0xFF ? 1 : len <= 0xFFFF ? 2 : len <= 0xFFFFFF ? 3 : 4
        out.write(longBase + n - 1)
        cdWriteBig(out, BigInteger.valueOf(len), n)
    }
}

private BigInteger cdReadBig(byte[] d, int p, int n) {
    BigInteger v = BigInteger.ZERO
    for (int i = n - 1; i >= 0; i--) v = v.shiftLeft(8).or(BigInteger.valueOf(d[p + i] & 0xFF))
    return v
}

private long cdReadLong(byte[] d, int p, int n, boolean bigEndian) {
    long v = 0
    for (int i = 0; i < n; i++) {
        int b = d[p + (bigEndian ? i : n - 1 - i)] & 0xFF
        v = (v << 8) | b
    }
    return v
}

// ---------- Companion pair-setup (PIN pairing), following pyatv ----------
// Socket-independent: each step takes/returns frame bytes; progress lives in a Map `st` (byte[] values).
// Frame: 1-byte type, 3-byte big-endian length, payload (OPACK).

@Field static final int FRAME_PS_START = 3
@Field static final int FRAME_PS_NEXT = 4
@Field static final int FRAME_PV_START = 5
@Field static final int FRAME_PV_NEXT = 6
@Field static final int FRAME_E_OPACK = 8

byte[] companionFrame(int type, byte[] payload) {
    byte[] out = new byte[4 + payload.length]
    out[0] = (byte) type
    out[1] = (byte) ((payload.length >> 16) & 0xFF)
    out[2] = (byte) ((payload.length >> 8) & 0xFF)
    out[3] = (byte) (payload.length & 0xFF)
    for (int i = 0; i < payload.length; i++) out[4 + i] = payload[i]
    return out
}

/** M1: ask the Apple TV to start pairing (it shows a PIN). Creates our long-term identity. */
byte[] pairingM1(Map st) {
    st.seed = randomBytes(32)
    st.ltpk = ed25519PublicKey(st.seed as byte[])
    st.pairingId = UUID.randomUUID().toString().getBytes("UTF-8")
    byte[] tlv = tlv8Write([0: [0x00] as byte[], 6: [0x01] as byte[]])
    return companionFrame(FRAME_PS_START, opackPack(["_pd": tlv, "_pwTy": 1]))
}

/** M2 from the Apple TV: salt and SRP public key. */
void pairingHandleM2(Map st, byte[] payload) {
    Map tlv = pairingData(payload)
    st.salt = tlv[2]
    st.atvSrpPublic = tlv[3]
    if (!st.salt || !st.atvSrpPublic) throw new IllegalStateException("pairing M2 missing salt or public key")
}

/** M3: our SRP proof for the PIN shown on the TV. */
byte[] pairingM3(Map st, String pin) {
    Map srp = srpClient(randomBytes(32), pin.trim(), st.salt as byte[], st.atvSrpPublic as byte[])
    st.srpK = srp.K
    st.expectM2 = srp.M2
    byte[] tlv = tlv8Write([6: [0x03] as byte[], 3: srp.A, 4: srp.M1])
    return companionFrame(FRAME_PS_NEXT, opackPack(["_pd": tlv, "_pwTy": 1]))
}

/** M4: the Apple TV's proof. A mismatch (or an error TLV) means a wrong PIN. */
void pairingHandleM4(Map st, byte[] payload) {
    Map tlv = pairingData(payload)
    if (!cdEq(tlv[4], st.expectM2)) throw new IllegalStateException("Apple TV rejected the PIN (proof mismatch)")
}

/** M5: our identity (pairing id, Ed25519 key, signature, display name), encrypted. */
byte[] pairingM5(Map st, String displayName) {
    byte[] K = st.srpK as byte[]
    byte[] signX = hkdfSha512("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", K)
    byte[] encKey = hkdfSha512("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", K)
    byte[] sig = ed25519Sign(st.seed as byte[], cdConcat(cdConcat(signX, st.pairingId as byte[]), st.ltpk as byte[]))
    Map inner = [1: st.pairingId, 3: st.ltpk, 10: sig]
    if (displayName) inner[0x11] = opackPack(["name": displayName])
    byte[] enc = chachaSeal(encKey, "PS-Msg05", tlv8Write(inner), null)
    byte[] tlv = tlv8Write([6: [0x05] as byte[], 5: enc])
    return companionFrame(FRAME_PS_NEXT, opackPack(["_pd": tlv, "_pwTy": 1]))
}

/**
 * M6: the Apple TV's identity, encrypted. Returns credentials to store:
 * [atvId, atvLtpk, seed, pairingId, name, model, signatureOk].
 */
Map pairingHandleM6(Map st, byte[] payload) {
    Map tlv = pairingData(payload)
    byte[] K = st.srpK as byte[]
    byte[] encKey = hkdfSha512("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", K)
    byte[] plain = chachaOpen(encKey, "PS-Msg06", tlv[5] as byte[], null)
    Map inner = tlv8Read(plain)
    byte[] atvId = inner[1], atvLtpk = inner[3], atvSig = inner[10]
    byte[] accX = hkdfSha512("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", K)
    boolean sigOk = atvSig && ed25519Verify(atvLtpk, cdConcat(cdConcat(accX, atvId), atvLtpk), atvSig)
    Map extra = [:]
    if (inner[0x11]) {
        try { extra = opackUnpack(inner[0x11] as byte[])[0] as Map } catch (e) { extra = [:] }
    }
    return [atvId: atvId, atvLtpk: atvLtpk, seed: st.seed, pairingId: st.pairingId,
            name: extra?.name, model: extra?.model, signatureOk: sigOk]
}

/** Reads `_pd` TLV from a pairing frame payload; an Error TLV (7) becomes an exception. */
private Map pairingData(byte[] payload) {
    Map msg = opackUnpack(payload)[0] as Map
    if (!(msg?._pd instanceof byte[])) throw new IllegalStateException("pairing reply has no _pd")
    Map tlv = tlv8Read(msg._pd as byte[])
    if (tlv[7]) {
        int code = (tlv[7] as byte[])[0] & 0xFF
        String why = [1: "unknown", 2: "authentication (wrong PIN)", 3: "back off, try later", 4: "max peers",
                      5: "max tries", 6: "unavailable", 7: "busy"][code] ?: "code ${code}"
        throw new IllegalStateException("Apple TV returned pairing error: ${why}")
    }
    return tlv
}

// ---------- crypto helpers ----------

byte[] hkdfSha512(String salt, String info, byte[] ikm) {
    def mac = javax.crypto.Mac.getInstance("HmacSHA512")
    // Empty salt = 64 zero bytes (RFC 5869); Java rejects an empty HMAC key.
    byte[] saltBytes = salt ? salt.getBytes("UTF-8") : new byte[64]
    mac.init(new javax.crypto.spec.SecretKeySpec(saltBytes, "HmacSHA512"))
    byte[] prk = mac.doFinal(ikm)
    mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA512"))
    mac.update(info.getBytes("UTF-8"))
    mac.update((byte) 1)
    return cdSlice(mac.doFinal(), 0, 32)
}

/** 12-byte nonce from an 8-byte label or counter: 4 zero bytes then the 8 bytes. */
private byte[] chachaNonce(nonce) {
    byte[] n8 = nonce instanceof String ? nonce.getBytes("UTF-8") : nonce as byte[]
    byte[] out = new byte[12]
    for (int i = 0; i < n8.length; i++) out[12 - n8.length + i] = n8[i]
    return out
}

byte[] chachaSeal(byte[] key, nonce, byte[] data, byte[] aad) {
    def c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
    c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
        new javax.crypto.spec.IvParameterSpec(chachaNonce(nonce)))
    if (aad) c.updateAAD(aad)
    return c.doFinal(data)
}

byte[] chachaOpen(byte[] key, nonce, byte[] data, byte[] aad) {
    def c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
    c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
        new javax.crypto.spec.IvParameterSpec(chachaNonce(nonce)))
    if (aad) c.updateAAD(aad)
    return c.doFinal(data)
}

byte[] randomBytes(int n) {
    byte[] b = new byte[n]
    new java.security.SecureRandom().nextBytes(b)
    return b
}

// ---------- Companion pair-verify and encrypted session, following pyatv ----------
// cred: [atvId, atvLtpk, seed, pairingId] as byte[] (from pairing). ss: per-connection Map.

/** PV M1: fresh X25519 key. */
byte[] pvStart(Map ss) {
    ss.xPriv = randomBytes(32)
    ss.xPub = x25519PublicKey(ss.xPriv as byte[])
    byte[] tlv = tlv8Write([6: [0x01] as byte[], 3: ss.xPub])
    return companionFrame(FRAME_PV_START, opackPack(["_pd": tlv, "_auTy": 4]))
}

/** PV M2 from the Apple TV: check its identity and signature; returns our PV M3. */
byte[] pvHandleM2(Map ss, Map cred, byte[] payload) {
    Map tlv = pairingData(payload)
    byte[] atvPub = tlv[3] as byte[]
    byte[] shared = x25519Shared(ss.xPriv as byte[], atvPub)
    ss.shared = shared
    byte[] key = hkdfSha512("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
    Map inner = tlv8Read(chachaOpen(key, "PV-Msg02", tlv[5] as byte[], null))
    if (!cdEq(inner[1], cred.atvId)) throw new IllegalStateException("Apple TV identity does not match the pairing (paired with a different Apple TV?)")
    if (!ed25519Verify(cred.atvLtpk as byte[], cdConcat(cdConcat(atvPub, inner[1] as byte[]), ss.xPub as byte[]), inner[10] as byte[]))
        throw new IllegalStateException("Apple TV signature did not verify")
    byte[] sig = ed25519Sign(cred.seed as byte[], cdConcat(cdConcat(ss.xPub as byte[], cred.pairingId as byte[]), atvPub))
    byte[] enc = chachaSeal(key, "PV-Msg03", tlv8Write([1: cred.pairingId, 10: sig]), null)
    return companionFrame(FRAME_PV_NEXT, opackPack(["_pd": tlv8Write([6: [0x03] as byte[], 5: enc])]))
}

/** PV M4: accepted unless it carries an error. Switches the session to encryption. */
void pvHandleM4(Map ss, byte[] payload) {
    pairingData(payload)
    ss.outKey = hkdfSha512("", "ClientEncrypt-main", ss.shared as byte[])
    ss.inKey = hkdfSha512("", "ServerEncrypt-main", ss.shared as byte[])
    ss.outCount = 0L
    ss.inCount = 0L
    ss.xid = (new java.security.SecureRandom().nextInt(0x7FFF) + 1) as long
}

/** Encrypted frame: payload sealed with a 12-byte little-endian counter nonce; the header is the AAD. */
byte[] sessionFrame(Map ss, int type, byte[] plain) {
    int len = plain.length + 16
    byte[] header = [type, (len >> 16) & 0xFF, (len >> 8) & 0xFF, len & 0xFF] as byte[]
    byte[] enc = chachaSeal(ss.outKey as byte[], counterNonce(ss.outCount as long), plain, header)
    ss.outCount = (ss.outCount as long) + 1
    return cdConcat(header, enc)
}

byte[] sessionOpen(Map ss, byte[] header, byte[] payload) {
    byte[] plain = chachaOpen(ss.inKey as byte[], counterNonce(ss.inCount as long), payload, header)
    ss.inCount = (ss.inCount as long) + 1
    return plain
}

/** Request (_t 2) with a new transaction id; returns [frame, xid]. */
List companionRequest(Map ss, String id, Map content) {
    long xid = ss.xid as long
    ss.xid = xid + 1
    return [sessionFrame(ss, FRAME_E_OPACK, opackPack(["_i": id, "_t": 2, "_c": content, "_x": xid])), xid]
}

/** Event (_t 1), e.g. _interest subscriptions. */
byte[] companionEvent(Map ss, String id, Map content) {
    long xid = ss.xid as long
    ss.xid = xid + 1
    return sessionFrame(ss, FRAME_E_OPACK, opackPack(["_i": id, "_t": 1, "_c": content, "_x": xid]))
}

/** HID button: Sleep 12, Wake 13 (pyatv sends only the release, _hBtS 2). */
List companionHid(Map ss, int button) { companionRequest(ss, "_hidC", ["_hBtS": 2, "_hidC": button]) }

private byte[] counterNonce(long n) {
    byte[] out = new byte[12]
    for (int i = 0; i < 8; i++) out[i] = (byte) ((n >>> (8 * i)) & 0xFF)
    return out
}
