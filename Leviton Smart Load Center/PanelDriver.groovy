/*
    Leviton Smart Load Center - Panel Driver
    Copyright 2026 JDThomas. All Rights Reserved
    Breaker, CT and leg mapping ported from rwoldberg/ldata-ha (Home Assistant).

    Notes:
     -Created by the app, one per panel (LWHEM 2nd gen "WHEMS" or LDATA 1st gen). Holds the WebSocket to the
      Leviton cloud and creates the Breaker and CT devices under itself.
     -Sign-in lives in the app: parent.getAuth() gives the token, getAuth(true) signs in again (rate limited by
      the app). Used on a WebSocket auth error and on HTTP 401, so an expired token (60 days) recovers itself.
     -Startup: REST read (panel, breakers, CTs), then WebSocket. On "ready" it subscribes the residence, the
      panel and every breaker and CT, 10 per second (a burst trips the hub's load protection).
     -REST poll refreshes energy counters and covers missed pushes. WHEMS: /IotWhems/{id}/...; LDATA: the
      residence's residentialBreakerPanels with breakers included (LDATA has no CTs).
     -Leviton streams live power only while bandwidth is 1, so it is re-sent every minute (Live power updates).
     -Live values stay in memory (RUNTIME), never state: a busy panel sends several messages a second. A driver
      save clears them; the next poll rebuilds them. state holds the catalog (written on change), exclusions,
      options and connection status.
     -Leg 2 positions swap power/power2 (and current, voltage). 2-pole current is the leg average, voltage the sum.
     -Small changes are not reported (power threshold from the app), which keeps event volume down.
     -Hub load (2.0.1): the Panel remembers what it last sent each device (mem().sent, its own values in mem().pv)
      and only calls a device with a real change, so devices never read their values back. Whole-panel messages
      and the REST poll store values at once and send device updates 8 every 250 ms (a full panel at once tripped
      the hub's load protection after a reboot).
     -Hub load (2.0.2): the token is cached in memory; the app is asked again only every 6 hours, after an
      HTTP 401 or a refused WebSocket sign-in (before, 4 app calls a minute). The REST poll is async, one request
      at a time, so a slow Leviton cloud never blocks the hub. On startup the WebSocket connects after that poll.
     -Exclusions as in IntelliCenter: deleting a device from the Devices page excludes it; the app's Equipment
      page turns it back on.
     -Reconnect backoff 5 s to 5 min. A drop logs at Normal; still down after 4 tries warns once; recovery logs.
      No message for 90 s (15 min with live updates off) reconnects.
     -Breaker On/Off only when the app allows it. The switch changes only after Leviton accepts the command.
*/

metadata {
    definition(name: "Leviton Smart Load Center Panel", namespace: "jdthomas24", author: "jdthomas24") {
        capability "Initialize"
        capability "Refresh"
        capability "PowerMeter"
        capability "VoltageMeasurement"
        capability "Sensor"

        attribute "frequency",        "number"
        attribute "firmware",         "string"
        attribute "panelStatus",      "string"
        attribute "connectionStatus", "string"
        attribute "lastUpdate",       "string"
    }

    preferences {
        input name: "helpInfo", type: "hidden", title: fmtHelpInfo()
    }
}

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.Field

@Field static final String VERSION     = "2.0.2"
@Field static final String COMM_LINK   = "https://community.hubitat.com/t/release-leviton-smart-load-center/163950"
@Field static final String DONATE_LINK = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final List LOG_LEVELS    = ["Errors Only", "Normal", "Full"]
@Field static final String API         = "https://my.leviton.com/api"
@Field static final String WS_URI      = "wss://socket.cloud.leviton.com/"
@Field static final String ORIGIN      = "https://myapp.leviton.com"
@Field static final List LEG1 = [1, 2, 5, 6, 9, 10, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30,
                                 33, 34, 37, 38, 41, 42, 45, 46, 49, 50, 53, 54, 57, 58, 61, 62, 65, 66]
@Field static final List BACKOFF = [5, 15, 30, 60, 120, 300]
@Field static final long AUTH_CACHE_MS = 6L * 3600000L
@Field static final Map DRIVER = [breaker: "Leviton Smart Load Center Breaker", ct: "Leviton Smart Load Center CT"]
// Per-device working data: live values, last message time, subscription queue. Lost on a driver save, which is safe.
@Field static final java.util.concurrent.ConcurrentHashMap RUNTIME = new java.util.concurrent.ConcurrentHashMap()

String fmtHelpInfo() {
    String info     = "Leviton Smart Load Center v${VERSION}"
    String btnStyle = "style='font-size:14px;padding:4px 12px;border:2px solid Crimson;border-radius:6px;text-decoration:none;display:inline-block;margin:4px;'"
    String commLink = "<a ${btnStyle} href='${COMM_LINK}' target='_blank'>💬 Community<br><div style='font-size:11px;'>${info}</div></a>"
    String payLink  = "<a ${btnStyle} href='${DONATE_LINK}' target='_blank'>☕ Buy Me a Coffee<br><div style='font-size:11px;'>Support Development</div></a>"
    return "<div style='text-align:center;padding:8px 0;'>${commLink}${payLink}</div>"
}

private Map mem() {
    String k = device.id.toString()
    Map m = RUNTIME.get(k) as Map
    if (m == null) {
        m = new java.util.concurrent.ConcurrentHashMap()
        m.br = new java.util.concurrent.ConcurrentHashMap()
        m.ct = new java.util.concurrent.ConcurrentHashMap()
        m.panel = new java.util.concurrent.ConcurrentHashMap()
        m.sent = new java.util.concurrent.ConcurrentHashMap()
        m.pv = new java.util.concurrent.ConcurrentHashMap()
        m.pending = java.util.concurrent.ConcurrentHashMap.newKeySet()
        RUNTIME.put(k, m)
    }
    return m
}

// ============================================================
// Lifecycle
// ============================================================

def installed() { }

def updated() { initialize() }

def initialize() {
    unschedule()
    Map m = mem()
    m.attempt = 0
    m.reconnectPending = false
    m.remove("opts")
    m.remove("cat")
    m.remove("skip")
    m.remove("auth")
    m.remove("restBusy")
    if (state.connected) state.connected = false
    if (!state.panel?.id) {
        setStatus("Not Configured")
        logE "not set up. Open the Leviton Smart Load Center app and tap Done."
        return
    }
    schedule("15 * * * * ?", "everyMinute")
    schedulePoll()
    runIn(1, "startup")
}

def startup() {
    if (opt("live")) bandwidthToggle()
    restRefresh([connect: true])   // connect once the catalog is current
}

def refresh() { restRefresh() }

def poll() { restRefresh() }

def uninstalled() {
    mem().closingAt = now()
    try { interfaces.webSocket.close() } catch (e) { }
    RUNTIME.remove(device.id.toString())
}

// From the app on Done: panel identity and options.
void configure(Map panel, Map opts) {
    if (state.panel != panel) state.panel = panel
    if (state.opts != opts) state.opts = opts
    mem().remove("opts")
}

private schedulePoll() {
    int s = (opt("pollSec") ?: 60) as int
    String cron = s <= 30 ? "5/30 * * * * ?" : s <= 60 ? "45 * * * * ?" : s <= 120 ? "45 0/2 * * * ?" : "45 0/5 * * * ?"
    schedule(cron, "poll")
}

private opt(String k) {
    Map m = mem()
    Map o = m.opts as Map
    if (o == null) { o = (state.opts ?: [:]) as Map; m.opts = o }
    return o[k]
}

// Every minute: keep Leviton streaming, reconnect a silent or dropped socket.
def everyMinute() {
    Map m = mem()
    if (opt("live")) sendBandwidth()
    if (state.connected) {
        if (m.lastRx == null) { m.lastRx = now(); return }   // memory cleared by a driver save
        long quiet = now() - (m.lastRx as long)
        long limit = (opt("live") ? 90 : 900) * 1000L
        if (quiet > limit) {
            markDown("no message for ${(quiet / 1000) as int}s")
            scheduleReconnect()
        }
    } else if (!m.reconnectPending && now() - ((m.connectAt ?: 0L) as long) > 60000L) {
        scheduleReconnect()
    }
}

// ============================================================
// Connection
// ============================================================

def connect() {
    Map m = mem()
    m.reconnectPending = false
    m.connectAt = now()
    m.ready = false
    if (!state.panel?.id) return
    if (!token()) {
        setStatus("Sign-in failed")
        scheduleReconnect()
        return
    }
    m.closingAt = now()   // the previous socket's close is expected
    try { interfaces.webSocket.close() } catch (e) { }
    setStatus(state.downSince != null ? "Reconnecting" : "Connecting")
    logF "connecting to ${WS_URI}"
    try {
        interfaces.webSocket.connect(WS_URI, headers: ["Origin": ORIGIN, "Cache-Control": "no-cache", "Pragma": "no-cache"])
    } catch (e) {
        markDown("connect failed: ${e.message}")
        scheduleReconnect()
    }
}

def webSocketStatus(String s) {
    logF "WebSocket ${s}"
    Map m = mem()
    if (s.startsWith("status: open")) {
        m.closingAt = 0L
        m.lastRx = now()
        setStatus("Signing in")
        Map a = auth(false)
        if (!a) { markDown("no sign-in"); scheduleReconnect(); return }
        sendWs([token: a])
    } else if (s.contains("clos") || s.contains("failure") || s.contains("error")) {
        if (s.contains("clos") && now() - ((m.closingAt ?: 0L) as long) < 10000L) return
        markDown(s.replace("status: ", ""))
        scheduleReconnect()
    }
}

private void markDown(String why) {
    if (state.connected) state.connected = false
    mem().ready = false
    setStatus(device.currentValue("connectionStatus") == "Sign-in failed" ? "Sign-in failed" : "Reconnecting")
    if (state.downSince == null) {
        state.downSince = now()
        logN "Leviton cloud connection dropped (${why}). Reconnecting."
    } else {
        logF "still offline: ${why}"
    }
}

private void scheduleReconnect() {
    Map m = mem()
    if (m.reconnectPending) return
    int a = (m.attempt ?: 0) as int
    int secs = BACKOFF[Math.min(a, BACKOFF.size() - 1)] as int
    m.attempt = a + 1
    if (a == 3 && !state.downWarned) {
        state.downWarned = true
        logE "can't reach the Leviton cloud. Retrying on its own, at most every 5 minutes."
    }
    m.reconnectPending = true
    runIn(secs, "connect")
}

private void onReady() {
    Map m = mem()
    if (m.ready) return
    m.ready = true
    m.attempt = 0
    state.connected = true
    setStatus("Connected")
    if (state.downSince != null) {
        int mins = ((now() - (state.downSince as long)) / 60000) as int
        if (state.downWarned) slog("info", "Leviton cloud back online after ${mins} min")
        else logN "reconnected"
        state.remove("downSince")
        state.remove("downWarned")
    } else {
        logN "connected to the Leviton cloud"
    }
    queueSubscriptions()
}

private void onWsError(err) {
    String why = (err instanceof Map) ? (err.message ?: err.name ?: err.toString()) : err.toString()
    Map m = mem()
    if (m.ready) { logF "Leviton message error: ${why}"; return }   // after sign-in: a rejected subscription, not auth
    logN "Leviton refused the sign-in (${why}). Signing in again."
    m.closingAt = now()
    try { interfaces.webSocket.close() } catch (e) { }
    if (state.connected) state.connected = false
    if (!token(true)) setStatus("Sign-in failed")
    scheduleReconnect()   // backoff, so a token Leviton keeps refusing can't loop
}

private void queueSubscriptions() {
    Map p = state.panel
    List subs = []
    if (p.residenceId) subs << sub("Residence", p.residenceId)
    subs << sub(p.type == "LDATA" ? "ResidentialBreakerPanel" : "IotWhem", p.id)
    catalog().each { id, c -> subs << sub(c.k == "ct" ? "IotCt" : "ResidentialBreaker", id) }
    mem().subQueue = subs
    logF "subscribing to ${subs.size()} items"
    sendSubBatch()
}

private String sub(String model, id) {
    JsonOutput.toJson([type: "subscribe", subscription: [modelName: model, modelId: id.toString()]])
}

def sendSubBatch() {
    Map m = mem()
    List q = (m.subQueue ?: []) as List
    if (!q) return
    m.subQueue = q.drop(10)
    q.take(10).each { sendWsRaw(it as String) }
    if (m.subQueue) runIn(1, "sendSubBatch")
}

private void sendWs(Map msg) { sendWsRaw(JsonOutput.toJson(msg)) }

private void sendWsRaw(String s) {
    try { interfaces.webSocket.sendMessage(s) } catch (e) { logF "send failed: ${e.message}" }
}

def parse(String message) {
    Map m = mem()
    m.lastRx = now()
    if (lvl() >= 2) logF "received ${message.length() > 400 ? message.take(400) + '...' : message}"
    def p
    try { p = new JsonSlurper().parseText(message) } catch (e) { logF "unreadable message: ${message.take(120)}"; return }
    if (!(p instanceof Map)) return
    if (p.type == "status") {
        if (p.status == "ready") onReady()
        return
    }
    if (p.error) { onWsError(p.error); return }
    if (p.type == "notification" && p.notification instanceof Map) {
        handleNotification(p.notification as Map)
        stampUpdate()
    }
}

private void handleNotification(Map n) {
    if (!(n.data instanceof Map)) return
    Map d = n.data as Map
    switch (n.modelName) {
        case "ResidentialBreaker":
            breakerUpdate(d, n.modelId)
            pushTotal()
            break
        case "IotCt":
            ctUpdate(d, n.modelId)
            break
        case "IotWhem":
        case "ResidentialBreakerPanel":
            def brs = d.ResidentialBreaker ?: d.residentialBreakers
            if (brs instanceof List) brs.each { if (it instanceof Map) breakerUpdate(it as Map, it.id, true) }
            def cts = d.IotCt ?: d.iotCts
            if (cts instanceof List) cts.each { if (it instanceof Map) ctUpdate(it as Map, it.id, true) }
            panelUpdate(d)
            break
        default:
            logF "ignored ${n.modelName} update"
    }
}

// ============================================================
// REST (token from the app; one fresh sign-in on HTTP 401)
// ============================================================

private Map auth(boolean fresh) {
    Map m = mem()
    if (!fresh && m.auth && now() - ((m.authAt ?: 0L) as long) < AUTH_CACHE_MS) return m.auth as Map
    Map a
    try { a = parent?.getAuth(fresh) as Map } catch (e) { logE "could not get the sign-in from the app: ${e.message}"; return null }
    if (a) { m.auth = a; m.authAt = now() } else m.remove("auth")
    return a
}

private String token(boolean fresh = false) { auth(fresh)?.id?.toString() }

private Map headers(String tok) {
    ["Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.5", "Content-Type": "application/json",
     "cache-control": "no-cache", "pragma": "no-cache",
     "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0",
     "Origin": ORIGIN, "Referer": "${ORIGIN}/", "authorization": tok ?: ""]
}

private boolean apiPut(String path, Map body, Map extra = [:]) {
    for (int i = 0; i < 2; i++) {
        String tok = token(i == 1)
        if (!tok) return false
        try {
            boolean ok = false
            httpPutJson([uri: "${API}${path}", headers: headers(tok) + extra, body: body, timeout: 15]) { resp -> ok = resp.status == 200 }
            return ok
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e.statusCode == 401 && i == 0) continue
            logE "Leviton rejected the command (HTTP ${e.statusCode})"
            return false
        } catch (e) {
            logE "could not send the command: ${e.message}"
            return false
        }
    }
    return false
}

// REST failures warn once per outage; recovery logs once.
private void restDown(String why) {
    Map m = mem()
    if (!m.restDown) { m.restDown = true; logE "Leviton cloud request failed (${why}). Will keep trying." }
    else logF "Leviton cloud request failed: ${why}"
}

private void restUp() {
    Map m = mem()
    if (m.restDown) { m.restDown = false; slog("info", "Leviton cloud requests working again") }
}

private String panelPath() {
    state.panel?.type == "LDATA" ? "/ResidentialBreakerPanels/${state.panel.id}" : "/IotWhems/${state.panel.id}"
}

// Same wake-up the My Leviton app sends; the panel then streams live values.
private void bandwidthToggle() {
    String tok = token()
    if (!tok) return
    [1, 0, 1].each { v ->
        try { httpPutJson([uri: "${API}${panelPath()}", headers: headers(tok), body: [bandwidth: v], timeout: 5]) { } }
        catch (e) { logF "bandwidth ${v}: ${e.message}" }
    }
}

private void sendBandwidth() {
    String tok = token()
    if (!tok) return
    try {
        asynchttpPut("bandwidthDone", [uri: "${API}${panelPath()}", headers: headers(tok), requestContentType: "application/json",
                                        contentType: "application/json", body: JsonOutput.toJson([bandwidth: 1]), timeout: 10])
    } catch (e) { logF "bandwidth: ${e.message}" }
}

def bandwidthDone(resp, data) {
    if (resp.hasError()) logF "bandwidth keepalive: HTTP ${resp.status}"
}

def restRefresh(Map opts = null) {
    Map m = mem()
    Map p = state.panel
    boolean thenConnect = opts?.connect == true
    if (!p?.id || (m.restBusy && now() - (m.restBusy as long) < 60000L)) { if (thenConnect) connect(); return }
    List reqs = p.type == "LDATA" ?
        [[k: "ldata", path: "/Residences/${p.residenceId}/residentialBreakerPanels", extra: [filter: '{"include":["residentialBreakers"]}']]] :
        [[k: "panel", path: "/Residences/${p.residenceId}/iotWhems"],
         [k: "breakers", path: "/IotWhems/${p.id}/residentialBreakers", extra: [filter: "{}"]],
         [k: "cts", path: "/IotWhems/${p.id}/iotCts", extra: [filter: "{}"]]]
    m.restBusy = now()
    m.restConnect = thenConnect
    m.restQueue = reqs.collect { Map r -> [k: r.k, path: r.path.toString(), extra: r.extra ?: [:]] }
    restNext()
}

// One request at a time, so replies never write the catalog at the same moment.
private void restNext() {
    Map m = mem()
    List q = (m.restQueue ?: []) as List
    if (q) {
        m.restQueue = q.drop(1)
        String tok = token()
        if (tok) { restGet(q[0] as Map, tok, false); return }
        restDown("not signed in")
        m.restQueue = []
    }
    m.remove("restBusy")
    if (!m.draining) pushTotal()
    stampUpdate()
    m.polled = true
    if (m.restConnect) { m.restConnect = false; connect() }
}

private void restGet(Map r, String tok, boolean retry) {
    try {
        asynchttpGet("restDone", [uri: "${API}${r.path}", contentType: "application/json",
                                  headers: headers(tok) + (r.extra as Map), timeout: 15], r + [retry: retry])
    } catch (e) {
        restDown(e.message)
        restNext()
    }
}

def restDone(resp, Map r) {
    int status = (resp.status ?: 0) as int
    if (status == 401 && !r.retry) {
        logN "Leviton sign-in expired. Signing in again."
        String tok = token(true)
        if (tok) { restGet(r, tok, true); return }
    }
    if (status != 200) {
        restDown(status ? "HTTP ${status}" : (resp.errorMessage ?: "no reply"))
        restNext()
        return
    }
    try {
        def data = resp.getJson()
        restUp()
        String pid = state.panel?.id?.toString()
        switch (r.k) {
            case "ldata":
                Map raw = (data instanceof List) ? data.find { it.id?.toString() == pid } as Map : null
                if (raw) {
                    panelUpdate(raw, true)
                    if (raw.residentialBreakers instanceof List) breakersSnapshot(raw.residentialBreakers as List)
                }
                break
            case "panel":
                Map pr = (data instanceof List) ? data.find { it.id?.toString() == pid } as Map : null
                if (pr) panelUpdate(pr, true)
                break
            case "breakers": if (data instanceof List) breakersSnapshot(data as List); break
            case "cts": if (data instanceof List) ctsSnapshot(data as List); break
        }
    } catch (e) {
        restDown("could not read the reply: ${e.message}")
    }
    restNext()
}

// ============================================================
// Panel
// ============================================================

private void panelUpdate(Map d, boolean full = false) {
    Map lp = mem().panel as Map
    def v1 = first(d, ["rmsVoltageA", "rmsVoltage"]), v2 = first(d, ["rmsVoltageB", "rmsVoltage2"])
    if (v1 != null) lp.v1 = num(v1)
    if (v2 != null) lp.v2 = num(v2)
    if (v1 != null || v2 != null) report("voltage", avg([lp.v1, lp.v2], 1), "V", 0.5)
    if (d.frequencyA != null) lp.f1 = num(d.frequencyA)
    if (d.frequencyB != null) lp.f2 = num(d.frequencyB)
    if (d.frequencyA != null || d.frequencyB != null) report("frequency", avg([lp.f1, lp.f2], 2), "Hz", 0.05)
    def online = null
    if (d.connected != null) online = d.connected as boolean
    if (d.model == "DAU" && d.status == "READY") online = true
    if (online != null) report("panelStatus", online ? "online" : "offline")
    if (full) {
        def fw = d.version ?: d.updateVersion
        if (fw) report("firmware", fw.toString())
    }
}

private void pushTotal() {
    BigDecimal total = 0
    (mem().br as Map).each { id, lb -> total += (lb.power ?: 0) as BigDecimal }
    report("power", rnd(total, 1), "W", powerDelta())
}

private void stampUpdate() {
    Map m = mem()
    if (now() - ((m.stamped ?: 0L) as long) < 60000L) return
    m.stamped = now()
    sendEvent(name: "lastUpdate", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// ============================================================
// Breakers and CTs
// ============================================================

private void breakersSnapshot(List list) {
    list.each { raw ->
        if (!(raw instanceof Map)) return
        String model = raw.model?.toString()
        if (!model || model.startsWith("NONE")) return
        String id = raw.id?.toString()
        if (!id) return
        int pos = toInt(raw.position) ?: 0
        catalogPut(id, [k: "breaker", n: (raw.name ?: "Breaker ${pos}").toString(), pos: pos, leg: LEG1.contains(pos) ? 1 : 2,
                        poles: toInt(raw.poles) ?: 1, rating: raw.currentRating, model: model,
                        canOn: raw.canRemoteOn ? true : false])
        breakerUpdate(raw as Map, id, true)
    }
}

private void ctsSnapshot(List list) {
    list.each { raw ->
        if (!(raw instanceof Map)) return
        if (!raw.usageType || raw.usageType == "NOT_USED") return
        String id = raw.id?.toString()
        if (!id) return
        catalogPut(id, [k: "ct", n: prettyUsage(raw.usageType.toString()), ch: raw.channel?.toString() ?: "?"])
        ctUpdate(raw as Map, id, true)
    }
}

private void breakerUpdate(Map d, modelId, boolean defer = false) {
    String id = (d.id ?: modelId)?.toString()
    Map c = catalog()[id] as Map
    if (!c || c.k != "breaker") return
    Map br = mem().br as Map
    Map lb = br[id] as Map
    if (lb == null) { lb = new java.util.concurrent.ConcurrentHashMap(); br[id] = lb }
    applyBreaker(lb, d, (c.leg ?: 1) as int, (c.poles ?: 1) as int)
    if (defer) queue("breaker", id) else dispatch("breaker", id, c, breakerEvents(lb))
}

// Leg 2 positions report power/power2 (and current, voltage) the other way round.
private void applyBreaker(Map lb, Map d, int leg, int poles) {
    if (d.containsKey("power") || d.containsKey("power2")) {
        legs(lb, "p", d, "power", "power2", leg)
        lb.power = (lb.p1 ?: 0) + (lb.p2 ?: 0)
    }
    if (d.containsKey("rmsCurrent") || d.containsKey("rmsCurrent2")) {
        legs(lb, "c", d, "rmsCurrent", "rmsCurrent2", leg)
        BigDecimal c1 = (lb.c1 ?: 0) as BigDecimal, c2 = (lb.c2 ?: 0) as BigDecimal
        lb.current = poles == 2 ? (c1 + c2) / 2 : c1 + c2
    }
    if (d.containsKey("rmsVoltage") || d.containsKey("rmsVoltage2")) {
        legs(lb, "v", d, "rmsVoltage", "rmsVoltage2", leg)
        lb.voltage = (lb.v1 ?: 0) + (lb.v2 ?: 0)
    }
    if (d.containsKey("lineFrequency") || d.containsKey("lineFrequency2")) {
        if (d.containsKey("lineFrequency")) lb.f1 = num(d.lineFrequency)
        if (d.containsKey("lineFrequency2")) lb.f2 = num(d.lineFrequency2)
        lb.frequency = poles == 2 ? (((lb.f1 ?: 0) + (lb.f2 ?: 0)) / 2) : (lb.f1 ?: 0)
    }
    if (d.containsKey("energyConsumption") || d.containsKey("energyConsumption2")) {
        if (d.containsKey("energyConsumption")) lb.e1 = num(d.energyConsumption)
        if (d.containsKey("energyConsumption2")) lb.e2 = num(d.energyConsumption2)
        lb.energy = (lb.e1 ?: 0) + (lb.e2 ?: 0)
    }
    if (d.containsKey("energyImport") || d.containsKey("energyImport2")) {
        if (d.containsKey("energyImport")) lb.i1 = num(d.energyImport)
        if (d.containsKey("energyImport2")) lb.i2 = num(d.energyImport2)
        lb.importEnergy = (lb.i1 ?: 0) + (lb.i2 ?: 0)
    }
    if (d.currentState) lb.state = d.currentState.toString()
    if (d.containsKey("remoteState")) lb.remote = d.remoteState ? d.remoteState.toString() : "RemoteON"
}

private void legs(Map lb, String k, Map d, String a, String b, int leg) {
    def ra = d.containsKey(a) ? num(d[a]) : null
    def rb = d.containsKey(b) ? num(d[b]) : null
    if (leg == 1) { if (ra != null) lb[k + "1"] = ra; if (rb != null) lb[k + "2"] = rb }
    else          { if (ra != null) lb[k + "2"] = ra; if (rb != null) lb[k + "1"] = rb }
}

private List breakerEvents(Map lb) {
    String st = lb.state ?: "ManualON"
    String rs = lb.remote ?: "RemoteON"
    BigDecimal pd = powerDelta()
    List ev = [[name: "switch", value: (st == "ManualON" && rs == "RemoteON") ? "on" : "off"],
               [name: "breakerState", value: st], [name: "remoteState", value: rs]]
    if (lb.power != null)        ev << [name: "power", value: rnd(lb.power, 1), unit: "W", delta: pd]
    if (lb.current != null)      ev << [name: "amperage", value: rnd(lb.current, 2), unit: "A", delta: ampDelta(pd)]
    if (lb.voltage != null)      ev << [name: "voltage", value: rnd(lb.voltage, 1), unit: "V", delta: 0.5]
    if (lb.frequency != null)    ev << [name: "frequency", value: rnd(lb.frequency, 2), unit: "Hz", delta: 0.05]
    if (lb.energy != null)       ev << [name: "energy", value: rnd(lb.energy, 3), unit: "kWh"]
    if (lb.importEnergy != null) ev << [name: "energyImport", value: rnd(lb.importEnergy, 3), unit: "kWh"]
    return ev
}

private void ctUpdate(Map d, modelId, boolean defer = false) {
    String id = (d.id ?: modelId)?.toString()
    Map c = catalog()[id] as Map
    if (!c || c.k != "ct") return
    Map cts = mem().ct as Map
    Map lc = cts[id] as Map
    if (lc == null) { lc = new java.util.concurrent.ConcurrentHashMap(); cts[id] = lc }
    if (d.containsKey("activePower") || d.containsKey("activePower2")) {
        if (d.containsKey("activePower")) lc.p1 = num(d.activePower)
        if (d.containsKey("activePower2")) lc.p2 = num(d.activePower2)
        lc.power = (lc.p1 ?: 0) + (lc.p2 ?: 0)
    }
    if (d.containsKey("rmsCurrent") || d.containsKey("rmsCurrent2")) {
        if (d.containsKey("rmsCurrent")) lc.c1 = num(d.rmsCurrent)
        if (d.containsKey("rmsCurrent2")) lc.c2 = num(d.rmsCurrent2)
        lc.current = ((lc.c1 ?: 0) + (lc.c2 ?: 0)) / 2
    }
    if (d.containsKey("energyConsumption") || d.containsKey("energyConsumption2")) {
        if (d.containsKey("energyConsumption")) lc.e1 = num(d.energyConsumption)
        if (d.containsKey("energyConsumption2")) lc.e2 = num(d.energyConsumption2)
        lc.energy = (lc.e1 ?: 0) + (lc.e2 ?: 0)
    }
    if (d.containsKey("energyImport") || d.containsKey("energyImport2")) {
        if (d.containsKey("energyImport")) lc.i1 = num(d.energyImport)
        if (d.containsKey("energyImport2")) lc.i2 = num(d.energyImport2)
        lc.importEnergy = (lc.i1 ?: 0) + (lc.i2 ?: 0)
    }
    if (defer) queue("ct", id) else dispatch("ct", id, c, ctEvents(lc))
}

private List ctEvents(Map lc) {
    BigDecimal pd = powerDelta()
    List ev = []
    if (lc.power != null)        ev << [name: "power", value: rnd(lc.power, 1), unit: "W", delta: pd]
    if (lc.current != null)      ev << [name: "amperage", value: rnd(lc.current, 2), unit: "A", delta: ampDelta(pd)]
    if (lc.energy != null)       ev << [name: "energy", value: rnd(lc.energy, 3), unit: "kWh"]
    if (lc.importEnergy != null) ev << [name: "energyImport", value: rnd(lc.importEnergy, 3), unit: "kWh"]
    return ev
}

// Only values that moved past their threshold since the last send reach the device; unchanged devices aren't called.
private void dispatch(String kind, String id, Map c, List ev) {
    Map sent = mem().sent as Map
    Map last = sent[id] as Map
    if (last == null) { last = new java.util.concurrent.ConcurrentHashMap(); sent[id] = last }
    List out = ev.findAll { e -> moved(last[e.name], e.value, e.delta) }
    if (!out) return
    def child = childFor(kind, id, c)
    if (!child) return
    child.parse(out.collect { e -> Map x = [name: e.name, value: e.value]; if (e.unit) x.unit = e.unit; x })
    out.each { e -> last[e.name] = e.value }
}

// Whole-panel and REST updates: values are already stored; device updates go out 8 every 250 ms.
private void queue(String kind, String id) {
    Map m = mem()
    (m.pending as Set).add("${kind}:${id}".toString())
    if (!m.draining) { m.draining = true; runInMillis(250, "drainPending") }
}

def drainPending() {
    Map m = mem()
    Set p = m.pending as Set
    List batch = []
    for (k in p) { if (batch.size() >= 8) break; batch << k }
    batch.each { String key ->
        p.remove(key)
        String kind = key.startsWith("ct:") ? "ct" : "breaker"
        String id = key.substring(kind.length() + 1)
        Map c = catalog()[id] as Map
        Map live = ((kind == "ct" ? m.ct : m.br) as Map)[id] as Map
        if (c && live) {
            try { dispatch(kind, id, c, kind == "ct" ? ctEvents(live) : breakerEvents(live)) }
            catch (e) { logF "update for ${id} failed: ${e.message}" }
        }
    }
    if (p) runInMillis(250, "drainPending")
    else { m.draining = false; pushTotal() }
}

private BigDecimal powerDelta() { (opt("powerDelta") ?: 0) as BigDecimal }

private BigDecimal ampDelta(BigDecimal pd) { pd > 0 ? Math.max(0.01, (pd / 120).doubleValue()) as BigDecimal : 0 }

private String prettyUsage(String u) {
    u.toLowerCase().split("_").collect { it ? it[0].toUpperCase() + it.substring(1) : it }.join(" ")
}

// ============================================================
// Catalog, devices and exclusions
// ============================================================

private Map catalog() {
    Map m = mem()
    Map c = m.cat as Map
    if (c == null) { c = (state.catalog ?: [:]) as Map; m.cat = c }
    return c
}

private void catalogPut(String id, Map e) {
    Map c = (state.catalog ?: [:]) as Map
    if (c[id] != e) {
        c[id] = e
        state.catalog = c
        mem().remove("cat")
    }
}

private String dniFor(String kind, String id) { "leviton-${kind}-${id}" }

private String childLabel(String kind, Map c) {
    kind == "ct" ? "${device.displayName} - ${c.n} CT ${c.ch}" : "${device.displayName} - ${c.n}"
}

// Existing device, a new one, or null when excluded (by the user, or deleted from the Devices page).
private childFor(String kind, String id, Map c) {
    String dni = dniFor(kind, id)
    def child = getChildDevice(dni)
    if (child) return child
    Map m = mem()
    Set skip = (m.skip ?: (m.skip = java.util.concurrent.ConcurrentHashMap.newKeySet())) as Set
    if (skip.contains(id)) return null
    String label = childLabel(kind, c)
    if (((state.excluded ?: []) as List).contains(id)) { skip.add(id); return null }
    if (((state.known ?: []) as List).contains(id)) {
        exclude(id)
        skip.add(id)
        logN "${label} was deleted from the Devices page, so it won't be recreated. Turn it back on in the app's Equipment page."
        return null
    }
    try {
        child = addChildDevice("jdthomas24", DRIVER[kind], dni, [label: label, isComponent: false])
        state.known = ((state.known ?: []) as List) + id
        logN "created ${label}"
        try { child.setLogLevel(state.logLevel ?: "Errors Only") } catch (e) { }
        if (kind == "breaker") {
            child.updateDataValue("position", "${c.pos}")
            child.updateDataValue("poles", "${c.poles}")
            if (c.rating != null) child.updateDataValue("ampRating", "${c.rating}")
            child.updateDataValue("model", "${c.model}")
            child.updateDataValue("remoteOn", c.canOn ? "supported" : "not supported")
        } else {
            child.updateDataValue("channel", "${c.ch}")
        }
    } catch (e) {
        skip.add(id)
        String why = "${e}".contains("UnknownDeviceType") ? "the ${DRIVER[kind]} driver isn't installed (update with HPM)" : e.message
        logE "could not create ${label}: ${why}"
        return null
    }
    return child
}

private void exclude(String id) {
    List x = (state.excluded ?: []) as List
    if (!x.contains(id)) state.excluded = x + id
    state.known = ((state.known ?: []) as List) - id
}

// For the app's Equipment page.
Map getCatalog() { (state.catalog ?: [:]) as Map }
List getExcluded() { (state.excluded ?: []) as List }

// From the app's Equipment page: removes newly excluded devices, re-reads to create re-included ones.
void setExcluded(List list) {
    List old = (state.excluded ?: []) as List
    List want = (list ?: []).collect { it.toString() }.unique()
    state.excluded = want
    state.known = ((state.known ?: []) as List) - (old - want) - (want - old)
    mem().remove("skip")
    (old - want).each { id -> (mem().sent as Map).remove(id) }
    (want - old).each { id ->
        def child = getChildDevices().find { it.deviceNetworkId.endsWith("-${id}") }
        if (child) {
            String label = child.displayName
            try { deleteChildDevice(child.deviceNetworkId); logN "removed ${label} (turned off in Equipment)" }
            catch (e) { logE "could not remove ${label}: ${e.message}" }
        }
    }
    if (old - want) runIn(1, "restRefresh")
}

void removeAllChildren() {
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) } catch (e) { logE "could not remove ${it.displayName}: ${e.message}" }
    }
}

// ============================================================
// Breaker control (from the Breaker device)
// ============================================================

boolean breakerCommand(String id, boolean on) {
    Map c = catalog()[id] as Map
    String name = c ? childLabel("breaker", c) : "breaker ${id}"
    if (!opt("control")) {
        logE "${name}: breaker control is off. Turn on Allow breaker control in the Leviton app to use On and Off."
        return false
    }
    if (on && c && !c.canOn) {
        logE "${name} can't be switched on remotely. Reset it at the panel."
        return false
    }
    boolean ok = apiPut("/ResidentialBreakers/${id}", on ? [remoteOn: true] : [remoteTrip: true],
                        ["Referer": "https://my.leviton.com/home/residential-breakers/${id}/settings"])
    if (ok) logN "${name} ${on ? 'switched on' : 'tripped'} from Hubitat"
    return ok
}

// ============================================================
// Events and helpers
// ============================================================

private void report(String name, value, String unit = null, BigDecimal delta = 0) {
    Map pv = mem().pv as Map
    if (!moved(pv[name], value, delta)) return
    Map e = [name: name, value: value]
    if (unit) e.unit = unit
    sendEvent(e)
    pv[name] = value
}

private boolean moved(cur, val, delta) {
    if (cur == null) return true
    if (val instanceof Number) {
        BigDecimal c
        try { c = new BigDecimal(cur.toString()) } catch (e) { return true }
        BigDecimal v = val as BigDecimal
        if (v == c) return false
        if (v == 0) return true
        return (v - c).abs() >= ((delta ?: 0) as BigDecimal)
    }
    return cur.toString() != val.toString()
}

private void setStatus(String v) {
    if (device.currentValue("connectionStatus") != v) sendEvent(name: "connectionStatus", value: v)
}

private first(Map d, List keys) {
    for (k in keys) { if (d[k] != null) return d[k] }
    return null
}

private BigDecimal num(v) {
    if (v == null) return 0
    try { return new BigDecimal(v.toString()) } catch (e) { return 0 }
}

private BigDecimal rnd(v, int places) {
    (v == null ? 0 : v as BigDecimal).setScale(places, java.math.RoundingMode.HALF_UP)
}

private BigDecimal avg(List vals, int places) {
    List nz = vals.findAll { it != null && (it as BigDecimal) != 0 }
    if (!nz) return rnd(0, places)
    return rnd(nz.sum() / nz.size(), places)
}

private Integer toInt(v) {
    if (v == null) return null
    try { return v.toString().toBigDecimal().intValue() } catch (e) { return null }
}

// ============================================================
// Logging (level comes from the app)
// ============================================================

void setLogLevel(String level) {
    state.logLevel = LOG_LEVELS.contains(level) ? level : "Errors Only"
    mem().lvl = LOG_LEVELS.indexOf(state.logLevel)
    getChildDevices().each { try { it.setLogLevel(state.logLevel) } catch (e) { } }
}

// Breaker and CT devices log through here up to the app.
void childLog(String level, String source, String msg) {
    try { parent.childLog(level, source, msg) } catch (e) { localLog(level, "${source}: ${msg}") }
}

private int lvl() {
    Map m = mem()
    if (m.lvl == null) m.lvl = LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only")
    return m.lvl as int
}

private void logE(String s) { slog("warn", s) }
private void logN(String s) { if (lvl() >= 1) slog("info", s) }
private void logF(String s) { if (lvl() >= 2) slog("debug", s) }

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
