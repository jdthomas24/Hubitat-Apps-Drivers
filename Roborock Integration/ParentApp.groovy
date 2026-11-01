/*
    Roborock Integration - Parent App
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One-time Roborock sign-in (email PIN) brings in the account's vacuums with their local keys and room names.
      After that each vacuum runs locally (Roborock Vacuum driver); the cloud is only used for Rescan and scenes.
     -Sign-in and HomeData calls follow bloodtick's Roborock driver (Hawk auth, getUrlByEmail region lookup).
     -HomeData has no IP address, so each vacuum's IP is entered here (use a DHCP reservation).
     -Log level is the only logging control; children log through childLog(). Full turns off after 30 minutes.
*/

import groovy.transform.Field
import groovy.json.JsonSlurper
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

@Field static final String APP_VERSION = "0.1.0"
@Field static final String DRIVER = "Roborock Vacuum"
@Field static final String NS = "jdthomas24"
@Field static final String COFFEE_URL = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final Map REGIONS = ["https://usiot.roborock.com": "US", "https://euiot.roborock.com": "EU",
                                   "https://cniot.roborock.com": "CN", "https://ruiot.roborock.com": "RU"]
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

definition(
    name: "Roborock Integration",
    namespace: NS,
    author: "jdthomas24",
    description: "Local control of Roborock vacuums. Sign in once to bring in your vacuums.",
    category: "Integrations",
    iconUrl: "", iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    List vacs = (state.vacuums ?: []) as List
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            String msg = state.msg ? "<div style='margin-top:6px;'>${state.msg}</div>" : ""
            String head = state.auth ? "<b style='color:#2e7d32;'>Signed in</b> as ${esc(settings.email)}. ${vacs.size()} vacuum${vacs.size() == 1 ? '' : 's'} found." :
                "<b style='color:#b26a00;'>Sign in required</b>. Your Roborock account is only used to bring in your vacuums."
            paragraph "<div style='padding:8px 12px;border-radius:8px;border:1px solid rgba(128,128,128,.35);'>${head}${msg}</div>"
        }
        section("<b>Roborock account</b>") {
            if (!state.auth) {
                input "email", "email", title: "Roborock account email", required: false, submitOnChange: true, width: 8
                input "region", "enum", title: "Region", options: REGIONS, defaultValue: "https://usiot.roborock.com", width: 4
                input "sendCode", "button", title: "Email me a sign-in code"
                input "pin", "text", title: "Code from the email", required: false, width: 6
                input "verify", "button", title: "Sign in"
            } else {
                input "rescan", "button", title: "Rescan vacuums and rooms"
                input "signOut", "button", title: "Sign out"
            }
        }
        if (vacs) {
            section("<b>Vacuums</b>") {
                paragraph "<span style='font-size:13px;'>Enter each vacuum's IP address (a DHCP reservation keeps it from changing), " +
                    "then tap <b>Done</b>. Turn a vacuum off to leave it out of Hubitat.</span>"
                vacs.each { Map v ->
                    def child = getChildDevice(dniFor(v.duid))
                    String link = child ? " · <a href='/device/edit/${child.id}' target='_blank'>device page</a>" : ""
                    paragraph "<b>${esc(v.name)}</b> <span style='font-size:13px;'>${esc(v.model ?: '')} · protocol ${v.pv ?: '?'}${link}</span>"
                    input "inc_${v.duid}", "bool", title: "Include", defaultValue: true, width: 3
                    input "ip_${v.duid}", "text", title: "IP address", required: false, width: 9
                }
            }
        }
        section("<b>Settings</b>") {
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS, defaultValue: "Errors Only", submitOnChange: true, width: 6
            label title: "App name", required: false, width: 6
        }
        section("<b>Help & Support</b>") {
            paragraph "<a href='${COFFEE_URL}' target='_blank'>Buy Me a Coffee</a> · Roborock Integration v${APP_VERSION} (beta)"
        }
    }
}

def installed() { initialize() }
def updated() { initialize() }
def uninstalled() { getChildDevices().each { deleteChildDevice(it.deviceNetworkId) } }

def initialize() {
    unschedule("fullLogsOff")
    if (settings.logLevel == "Full") runIn(1800, "fullLogsOff")
    syncChildren()
}

def fullLogsOff() { app.updateSetting("logLevel", [type: "enum", value: "Normal"]); logN "Full logging turned off after 30 minutes" }

// ---------- buttons ----------

def appButtonHandler(String btn) {
    state.remove("msg")
    try {
        switch (btn) {
            case "sendCode": requestCode(); break
            case "verify": verifyCode(); break
            case "rescan": scan(); break
            case "signOut":
                ["auth", "homeId", "rooms", "scenes"].each { state.remove(it) }
                state.msg = "Signed out. Vacuums keep running locally."
                break
        }
    } catch (e) {
        state.msg = "<span style='color:#c62828;'>${esc(e.message)}</span>"
        logE "${btn} failed: ${e}"
    }
}

private void requestCode() {
    if (!settings.email) { state.msg = "Enter your Roborock account email first."; return }
    Map r = post(baseUrl(), "/api/v1/sendEmailCode", "username=${enc(settings.email)}&type=auth")
    state.msg = r?.msg == "success" ? "Code sent. Check your email, enter the code, then tap <b>Sign in</b>." :
        "<span style='color:#c62828;'>Couldn't send a code (${esc(r?.msg ?: 'no reply')}).</span>"
}

private void verifyCode() {
    if (!settings.pin) { state.msg = "Enter the code from the email."; return }
    Map r = post(baseUrl(), "/api/v1/loginWithCode",
        "username=${enc(settings.email)}&verifycode=${enc(settings.pin.toString().trim())}&verifycodetype=AUTH_EMAIL_CODE")
    if (r?.msg != "success" || !r?.data?.rriot) {
        state.msg = "<span style='color:#c62828;'>Sign-in failed (${esc(r?.msg ?: 'no reply')}). Request a new code and try again.</span>"
        return
    }
    state.auth = [token: r.data.token, rriot: r.data.rriot]
    app.removeSetting("pin")
    logN "Signed in to Roborock"
    scan()
}

// Home detail -> HomeData (devices, products, rooms) -> scenes per vacuum.
private void scan() {
    Map auth = state.auth as Map
    if (!auth) { state.msg = "Sign in first."; return }
    Map detail = getJson(state.base ?: settings.region, "/api/v1/getHomeDetail",
        ["header_clientid": md5hex(settings.email).bytes.encodeBase64().toString(), "Authorization": auth.token])
    String homeId = detail?.data?.rrHomeId?.toString()
    if (!homeId) { state.msg = "<span style='color:#c62828;'>Couldn't read your Roborock home. Try signing in again.</span>"; return }
    state.homeId = homeId
    Map home = hawkGet("/v2/user/homes/${homeId}")?.result as Map
    if (!home) { state.msg = "<span style='color:#c62828;'>Couldn't read your vacuums.</span>"; return }
    Map models = (home.products ?: []).collectEntries { [(it.id.toString()): (it.name ?: it.model)] }
    List devs = ((home.devices ?: []) + (home.receivedDevices ?: [])).findAll { it.localKey }
    state.vacuums = devs.collect { [duid: it.duid, name: it.name, model: models[it.productId?.toString()], pv: it.pv] }
    Map keys = (state.keys ?: [:]) as Map
    devs.each { keys[it.duid] = it.localKey }
    state.keys = keys
    state.rooms = (home.rooms ?: []).collectEntries { [(it.id.toString()): it.name] }
    Map scenes = [:]
    devs.each { d ->
        List res = hawkGet("/user/scene/device/${d.duid}")?.result as List
        scenes[d.duid] = (res ?: []).collectEntries { [(it.id.toString()): it.name] }
    }
    state.scenes = scenes
    state.msg = "Found ${devs.size()} vacuum${devs.size() == 1 ? '' : 's'}. Enter the IP addresses below, then tap <b>Done</b>."
    logN "Scan found ${devs.collect { it.name }.join(', ') ?: 'no vacuums'}"
    getChildDevices().each { it.configChanged() }
}

// ---------- children ----------

private void syncChildren() {
    ((state.vacuums ?: []) as List).each { Map v ->
        String dni = dniFor(v.duid)
        def child = getChildDevice(dni)
        boolean want = settings["inc_${v.duid}"] != false && settings["ip_${v.duid}"]
        if (want && !child) {
            try {
                child = addChildDevice(NS, DRIVER, dni, [name: "Roborock ${v.model ?: 'Vacuum'}", label: v.name, isComponent: false])
                logN "Added ${v.name}"
            } catch (e) { logE "Couldn't add ${v.name}: ${e}. Is the ${DRIVER} driver installed?" }
        } else if (!want && child && settings["inc_${v.duid}"] == false) {
            deleteChildDevice(dni)
            logN "Removed ${v.name}"
        } else if (child) {
            child.configChanged()
        }
    }
}

Map componentConfig(String dni) {
    Map v = ((state.vacuums ?: []) as List).find { dniFor(it.duid) == dni } as Map
    if (!v) return [:]
    return [duid: v.duid, ip: settings["ip_${v.duid}"]?.toString()?.trim(), localKey: state.keys?.get(v.duid), model: v.model,
            rooms: state.rooms ?: [:], scenes: state.scenes?.get(v.duid) ?: [:]]
}

void componentScene(String dni, String sceneId) {
    if (!state.auth) { logE "Scenes need a Roborock sign-in. Sign in again in the app."; return }
    String path = "/user/scene/${sceneId}/execute"
    Map rriot = state.auth.rriot as Map
    asynchttpPost("sceneDone", [uri: rriot.r.a, path: path, contentType: "application/json", body: [sceneId: sceneId],
        headers: ["Authorization": hawk(path)]], [sceneId: sceneId])
}

def sceneDone(resp, data) {
    if (resp.status == 200) logN "Scene ${data.sceneId} started"
    else logE "Scene ${data.sceneId} failed (HTTP ${resp.status})"
}

void childLog(String level, String source, String msg) {
    String line = "${source}: ${msg}"
    if (level == "error") log.error line
    else if (level == "warn") log.warn line
    else if (level == "info" && settings.logLevel in ["Normal", "Full"]) log.info line
    else if (level == "debug" && settings.logLevel == "Full") log.debug line
}

String componentLogLevel() { settings.logLevel ?: "Errors Only" }

// ---------- cloud helpers ----------

private String baseUrl() {
    if (state.base) return state.base
    Map r = post(settings.region ?: "https://usiot.roborock.com", "/api/v1/getUrlByEmail", "email=${enc(settings.email)}")
    if (r?.data?.url) state.base = r.data.url
    return state.base ?: settings.region
}

private Map post(String uri, String path, String query) {
    Map out = null
    httpPostJson([uri: uri, path: path, queryString: query, headers: ["header_clientid": clientId()]]) { resp -> out = asMap(resp.data) }
    return out
}

private Map getJson(String uri, String path, Map headers) {
    Map out = null
    httpGet([uri: uri, path: path, headers: headers]) { resp -> out = asMap(resp.data) }
    return out
}

private Map hawkGet(String path) {
    Map rriot = state.auth?.rriot as Map
    try { return getJson(rriot.r.a, path, ["Authorization": hawk(path)]) }
    catch (e) { logE "Roborock request ${path} failed: ${e}"; return null }
}

private String hawk(String path) {
    Map rriot = state.auth.rriot as Map
    long ts = (now() / 1000L) as long
    String nonce = UUID.randomUUID().toString().replaceAll('-', '').take(8)
    String pre = "${rriot.u}:${rriot.s}:${nonce}:${ts}:${md5hex(path)}::"
    Mac mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(rriot.h.toString().getBytes("UTF-8"), "HmacSHA256"))
    String sig = mac.doFinal(pre.getBytes("UTF-8")).encodeBase64().toString()
    return "Hawk id=\"${rriot.u}\", s=\"${rriot.s}\", ts=\"${ts}\", nonce=\"${nonce}\", mac=\"${sig}\""
}

private String clientId() {
    MessageDigest md = MessageDigest.getInstance("MD5")
    md.update(settings.email.toString().bytes)
    md.update("hubitat-${app.id}".toString().bytes)
    return md.digest().encodeBase64().toString()
}

private Map asMap(data) {
    if (data instanceof Map) return data as Map
    if (data == null) return null
    def txt = data instanceof String ? data : data.text
    return new JsonSlurper().parseText(txt.toString()) as Map
}

private String md5hex(String s) { MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).encodeHex().toString() }
private String enc(s) { URLEncoder.encode(s.toString(), "UTF-8") }
private String esc(s) { s == null ? "" : s.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;") }
private String dniFor(duid) { "RR-${duid}" }

private void logN(String m) { if (settings.logLevel in ["Normal", "Full"]) log.info m }
private void logE(String m) { log.error m }
