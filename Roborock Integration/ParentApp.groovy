/*
    Roborock Integration - Parent App
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One-time Roborock sign-in (email PIN) brings in the account's vacuums with their local keys and room names.
      After that each vacuum runs locally (Roborock Vacuum driver); the cloud is only used for Rescan and scenes.
     -Sign-in and HomeData calls follow bloodtick's Roborock driver (Hawk auth, getUrlByEmail region lookup).
     -HomeData has no IP address, so each vacuum's IP is entered here (use a DHCP reservation).
     -IP recovery (0.2.0): when a vacuum can't be reached past its grace period, the hidden Roborock Cloud Helper
      asks Roborock's cloud for its current IP (get_network_info). A matching MAC updates the IP setting and the
      driver reconnects locally. The MAC is learned locally on every connect. Needs a sign-in; signed out = no lookups.
     -Log level is the only logging control; children log through childLog(). Full turns off after 3 hours (beta).
*/

import groovy.transform.Field
import groovy.json.JsonSlurper
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

@Field static final String APP_VERSION = "0.2.3"
@Field static final String APP_NAME = "Roborock Integration"
@Field static final String DRIVER = "Roborock Vacuum"
@Field static final String CLOUD_DRIVER = "Roborock Cloud Helper"
@Field static final String CLOUD_DNI = "RR-CLOUD"
@Field static final long IP_LOOKUP_GAP_MS = 600000L   // one cloud IP lookup per vacuum per 10 min
@Field static final String NS = "jdthomas24"
@Field static final String COFFEE_URL = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final Map REGIONS = ["https://usiot.roborock.com": "US", "https://euiot.roborock.com": "EU",
                                   "https://cniot.roborock.com": "CN", "https://ruiot.roborock.com": "RU"]
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]
@Field static final int FULL_LOG_SEC = 10800   // 3 hours while in beta

definition(
    name: "Roborock Integration",
    namespace: NS,
    author: "jdthomas24",
    importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Roborock%20Integration/ParentApp.groovy",
    description: "Local control of Roborock vacuums. Sign in once to bring in your vacuums.",
    category: "Convenience",
    menu: "Integrations",
    installOnOpen: true,
    iconUrl: "", iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
    page(name: "tipsPage")
}

def mainPage() {
    List vacs = (state.vacuums ?: []) as List
    Map banner = bannerState(vacs)
    String msg = state.msg
    state.remove("msg")
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section(sectionClass: "app-main-overview") {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
            if (msg) paragraph rawHtml: true, "<div class='text-color-secondary' style='font-size:14px;'>${msg}</div>"
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("Roborock account")
            if (!state.auth) {
                input "email", "email", title: "Roborock account email", required: false, submitOnChange: true, width: 8
                input "region", "enum", title: "Region", options: REGIONS, defaultValue: "https://usiot.roborock.com", width: 4
                input "sendCode", "button", title: "Email me a sign-in code", width: 6
                input "pin", "text", title: "Code from the email", required: false, width: 6
                input "verify", "button", title: "Sign in", width: 6
            } else {
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Signed in as ${esc(settings.email)}. Status and commands from Hubitat are local; the cloud is only used for sign-in, Rescan and scenes.</span>"
                input "rescan", "button", title: "Rescan vacuums and rooms", width: 6
                input "signOut", "button", title: "Sign out", width: 6
            }
            if (vacs) {
                paragraph rawHtml: true, columnHeader("Vacuums")
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Enter each vacuum's IP address (a DHCP reservation keeps it from changing), then tap <b>Done</b>.</span>"
                vacs.each { Map v ->
                    paragraph rawHtml: true, vacuumCardHtml(v)
                    input "inc_${v.duid}", "bool", title: "Include", defaultValue: true, width: 4
                    input "ip_${v.duid}", "text", title: "IP address", required: false, width: 8
                }
            }
        }
        section(sectionClass: "app-ui-links app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS, defaultValue: "Errors Only", submitOnChange: true, width: 12
            paragraph rawHtml: true, loggingDetailsPopupHtml()
            label title: "App name", required: false
        }
        section(title: "<b>Help & Support</b>", sectionClass: "app-ui-links app-main-support") {
            href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting", page: "tipsPage",
                description: "Local vs cloud, setup and diagnostics", width: 6, style: "margin:8px;"
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot", "Buy Me a Coffee", "Support development"), width: 6
        }
        versionFooterSection()
    }
}

private Map bannerState(List vacs) {
    if (!state.auth && !vacs) return [ok: false, title: "Setup required", summary: "Sign in to your Roborock account below to bring in your vacuums."]
    List kids = vacs.collect { getChildDevice(dniFor(it.duid)) }.findAll { it }
    if (!kids) return [ok: false, title: "Setup required", summary: "Enter each vacuum's IP address below, then tap <b>Done</b>."]
    List off = kids.findAll { it.currentValue("healthStatus") != "online" }
    if (off) return [ok: false, title: off.size() == kids.size() ? "Vacuum offline" : "Some vacuums offline",
        summary: "Can't reach ${off.collect { esc(it.displayName) }.join(', ')}. Retrying on its own."]
    String proto = kids.collect { it.currentValue("protocol") }.findAll { it }.unique().join("/")
    return [ok: true, title: "Integration ready", summary: "${countText(kids.size(), 'vacuum')} connected locally${proto ? ' &middot; ' + proto : ''}"]
}

private String vacuumCardHtml(Map v) {
    def child = getChildDevice(dniFor(v.duid))
    String tag = !child ? "<span class='p-tag p-tag-warning border-round-xl px-2 py-1 text-xs font-bold'>Not added</span>" :
        child.currentValue("healthStatus") == "online" ? "<span class='p-tag p-tag-success border-round-xl px-2 py-1 text-xs font-bold'>Connected</span>" :
        "<span class='p-tag p-tag-danger border-round-xl px-2 py-1 text-xs font-bold'>Offline</span>"
    String proto = child?.currentValue("protocol") ?: "not connected"
    String mac = state.macs?.get(v.duid) ? " &middot; MAC ${esc(state.macs[v.duid])}" : ""
    String link = child ? " &middot; <a href='/device/edit/${child.id}' target='_blank' class='hub-text-link no-underline'>device page " +
        "<i class='fa-regular fa-external-link' style='font-size:0.8em'></i></a>" : ""
    "<div class='flex align-items-center gap-2 mt-2'><span class='font-semibold'>${esc(v.name)}</span>${tag}</div>" +
        "<div class='text-color-secondary' style='font-size:14px;'>${esc(v.model ?: '')} &middot; local protocol ${esc(proto)}${mac}${link}</div>"
}

private String currentLogLevel() { settings.logLevel ?: "Errors Only" }

def installed() { initialize() }
def updated() { initialize() }
def uninstalled() { getChildDevices().each { deleteChildDevice(it.deviceNetworkId) } }

def initialize() {
    unschedule("fullLogsOff")
    if (settings.logLevel == "Full") runIn(FULL_LOG_SEC, "fullLogsOff")
    syncChildren()
}

def fullLogsOff() { app.updateSetting("logLevel", [type: "enum", value: "Normal"]); logN "Full logging turned off after 3 hours" }

// ---------- buttons ----------

def appButtonHandler(String btn) {
    state.remove("msg")
    try {
        switch (btn) {
            case "sendCode": requestCode(); break
            case "verify": verifyCode(); break
            case "rescan": scan(); break
            case "signOut":
                ["auth", "homeId", "rooms", "scenes", "base", "country", "countryCode", "lookupFor"].each { state.remove(it) }
                syncCloudHelper()
                state.msg = "Signed out. Vacuums keep running locally, but a changed IP won't be found automatically."
                break
        }
    } catch (e) {
        state.msg = "<span style='color:#c62828;'>${esc(e.message)}</span>"
        logE "${btn} failed: ${e}"
    }
}

// Sign-in follows python-roborock's current flow (v4 email code + user agreement version); v1 only if the
// account lookup returns no country. Error codes: 2018 bad code, 3006/3009 agreement, 3039 wrong region, 9002 too many codes.
private void requestCode() {
    if (!settings.email) { state.msg = "Enter your Roborock account email first."; return }
    lookupAccount()
    Map r
    if (state.country) {
        r = postForm(state.base, "/api/v4/email/code/send", [email: settings.email.toString(), type: "login", platform: ""],
            ["header_clientlang": "en"])
    } else {
        r = post(state.base, "/api/v1/sendEmailCode", "username=${enc(settings.email)}&type=auth")
    }
    int c = (r?.code ?: 0) as int
    state.msg = (c == 200 || r?.msg == "success") ? "Code sent. Check your email, enter the code, then tap <b>Sign in</b>." :
        err(c == 2008 ? "No Roborock account uses that email." : c == 9002 ? "Too many codes requested. Wait a few minutes and try again." :
            "Couldn't send a code (${r?.msg ?: 'no reply'}${c ? ', code ' + c : ''}).")
}

private void verifyCode() {
    if (!settings.pin) { state.msg = "Enter the code from the email."; return }
    lookupAccount()
    String pin = settings.pin.toString().trim()
    Map r
    if (state.country) {
        String ks = UUID.randomUUID().toString().replaceAll("-", "").take(16)
        Map sign = post(state.base, "/api/v3/key/sign", "s=${ks}")
        String k = sign?.data?.k
        if (!k) { state.msg = err("Roborock didn't accept the sign-in request (${sign?.msg ?: 'no reply'}). Try again in a minute."); return }
        r = postForm(state.base, "/api/v4/auth/email/login/code",
            [country: state.country, countryCode: state.countryCode, email: settings.email.toString(), code: pin] + agreementVersion(),
            ["x-mercy-ks": ks, "x-mercy-k": k, "header_clientlang": "en", "header_appversion": "4.54.02",
             "header_phonesystem": "iOS", "header_phonemodel": "iPhone16,1"])
    } else {
        r = post(state.base, "/api/v1/loginWithCode", "username=${enc(settings.email)}&verifycode=${enc(pin)}&verifycodetype=AUTH_EMAIL_CODE")
    }
    if (!r?.data?.rriot) {
        int c = (r?.code ?: 0) as int
        state.msg = err(c == 2018 ? "That code didn't work. Check it, or request a new one." :
            c == 3009 ? "Roborock needs you to accept its user agreement. Open the Roborock app, accept it, then sign in here again." :
            c == 3006 ? "Roborock needs you to accept its updated user agreement. Open the Roborock app (sign out and back in if no prompt shows), then try again." :
            c == 3039 ? "Roborock couldn't find that account in this region. Check the email and Region." :
            "Sign-in failed (${r?.msg ?: 'no reply'}${c ? ', code ' + c : ''}). Request a new code and try again.")
        logE "Roborock sign-in failed: code ${c}, ${r?.msg}"
        return
    }
    state.auth = [token: r.data.token, rriot: r.data.rriot]
    app.removeSetting("pin")
    logN "Signed in to Roborock"
    scan()
    syncCloudHelper()
}

private Map agreementVersion() {
    try {
        Map r = null
        httpGet([uri: state.base, path: "/api/v3/app/agreement/latest", query: [country: state.country],
                 headers: ["header_clientlang": "en"]]) { resp -> r = asMap(resp.data) }
        if (r?.code == 200 && r.data?.majorVersion != null) return [majorVersion: r.data.majorVersion, minorVersion: r.data.minorVersion]
    } catch (e) { logE "Couldn't read Roborock's agreement version: ${e.message}" }
    return [majorVersion: 14, minorVersion: 0]
}

private String err(String m) { "<span style='color:#c62828;'>${esc(m)}</span>" }

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
    Map prods = (home.products ?: []).collectEntries { [(it.id.toString()): it] }
    List devs = ((home.devices ?: []) + (home.receivedDevices ?: [])).findAll { it.localKey }
    state.vacuums = devs.collect { Map p = (prods[it.productId?.toString()] ?: [:]) as Map
        [duid: it.duid, name: it.name, model: p.name ?: p.model, modelCode: p.model, fw: it.fv, pv: it.pv] }
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

private void syncCloudHelper() {
    def h = getChildDevice(CLOUD_DNI)
    if (state.auth && !h) {
        try { addChildDevice(NS, CLOUD_DRIVER, CLOUD_DNI, [name: CLOUD_DRIVER, label: "Roborock Cloud Helper", isComponent: true]) }
        catch (e) { logE "Couldn't add the ${CLOUD_DRIVER}: ${e}. Update through HPM so the driver is installed." }
    } else if (!state.auth && h) deleteChildDevice(CLOUD_DNI)
}

// Called by a vacuum that can't be reached. Throttled per vacuum.
void componentIpLost(String dni) {
    Map v = ((state.vacuums ?: []) as List).find { dniFor(it.duid) == dni } as Map
    if (!v) return
    Map last = (state.ipLookups ?: [:]) as Map
    if (now() - ((last[v.duid] ?: 0L) as long) < IP_LOOKUP_GAP_MS) return
    last[v.duid] = now(); state.ipLookups = last
    def h = getChildDevice(CLOUD_DNI)
    if (!state.auth || !h) { log.warn "${v.name} can't be reached at ${settings["ip_${v.duid}"]}. Sign in to Roborock in the app so its new IP can be found automatically, or update the IP by hand."; return }
    logN "${v.name} can't be reached at ${settings["ip_${v.duid}"]}, asking Roborock's cloud for its current IP"
    h.lookupNetworkInfo(v.duid as String, [rriot: state.auth.rriot, localKey: state.keys?.get(v.duid)])
}

// From the vacuum (via: local, on each connect) or the Cloud Helper (via: cloud, after a lookup).
void componentNetworkInfo(String duid, Map info, String err, String via) {
    Map v = ((state.vacuums ?: []) as List).find { it.duid == duid } as Map
    if (!v) return
    if (err) { log.warn "${v.name}: couldn't find its current IP through Roborock's cloud (${err}). Will try again in 10 minutes."; return }
    String mac = info?.mac?.toString()?.toUpperCase()
    Map macs = (state.macs ?: [:]) as Map
    if (mac && macs[duid] && macs[duid] != mac) {
        log.warn "${v.name}: the cloud reported MAC ${mac}, but this vacuum is ${macs[duid]}. IP not changed."
        return
    }
    if (mac && macs[duid] != mac) { macs[duid] = mac; state.macs = macs }
    if (via != "cloud") return
    String ip = info?.ip?.toString()
    String cur = settings["ip_${duid}"]?.toString()?.trim()
    if (!ip) return
    if (ip == cur) { logN "${v.name}: Roborock's cloud says the IP is still ${ip}. It may be off or out of Wi-Fi range."; return }
    app.updateSetting("ip_${duid}", [type: "text", value: ip])
    log.info "${v.name}: IP changed from ${cur} to ${ip} (found through Roborock's cloud). Reconnecting locally. A DHCP reservation for MAC ${macs[duid] ?: mac ?: '?'} will keep it from changing."
    getChildDevice(dniFor(duid))?.configChanged()
}

private void syncChildren() {
    syncCloudHelper()
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
            modelCode: v.modelCode, fw: v.fw, pv: v.pv, appVersion: APP_VERSION, rooms: state.rooms ?: [:], scenes: state.scenes?.get(v.duid) ?: [:],
            tipsUrl: "/installedapp/configure/${app.id}/tipsPage"]
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
    else if (level == "diag") log.info line   // Diagnostics button: always shown
    else if (level == "info" && settings.logLevel in ["Normal", "Full"]) log.info line
    else if (level == "debug" && settings.logLevel == "Full") log.debug line
}

String componentLogLevel() { settings.logLevel ?: "Errors Only" }

// ---------- cloud helpers ----------

private String baseUrl() {
    if (!state.base) lookupAccount()
    return state.base ?: settings.region
}

// Region server plus the account's country, needed by the v4 sign-in.
private void lookupAccount() {
    if (state.base && state.lookupFor == settings.email) return
    Map r = post(settings.region ?: "https://usiot.roborock.com", "/api/v1/getUrlByEmail", "email=${enc(settings.email)}&needtwostepauth=false")
    state.base = r?.data?.url ?: settings.region
    state.country = r?.data?.country
    state.countryCode = r?.data?.countrycode
    state.lookupFor = settings.email
}

private Map postForm(String uri, String path, Map body, Map headers) {
    Map out = null
    httpPost([uri: uri, path: path, requestContentType: "application/x-www-form-urlencoded", body: body,
              headers: ["header_clientid": clientId()] + headers]) { resp -> out = asMap(resp.data) }
    return out
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
private String esc(v) { (v == null ? "" : v.toString()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;") }
private String dniFor(duid) { "RR-${duid}" }

private void logN(String m) { if (settings.logLevel in ["Normal", "Full"]) log.info m }
private void logE(String m) { log.error m }

// ---------- Tips & Troubleshooting ----------
@Field static final String DEFAULT_TIP_TOPIC = "local"

/** Tips topics. Fields: id, label, title, group, icon (PrimeIcons), body; optional checklist, warning, append. */
private List tipsTopics() {
    [
        [id: "local", label: "Local vs cloud", title: "What runs locally, and what needs the cloud", group: "How it works", icon: "pi-home",
            body: "<p>After the one-time sign-in, Hubitat talks straight to each vacuum on your network (TCP port 58867). Roborock's servers aren't involved for status or commands from Hubitat.</p>" +
                "<p><b>Local</b> (keeps working with your internet down):</p>",
            checklist: [
                [title: "Status", detail: "State, current room, clean area, time and percent, battery, errors, fan and mop modes, consumables."],
                [title: "Commands from Hubitat", detail: "Start, Dock, Pause, Resume, Vacuum Rooms, and fan and mop settings through Execute."]
            ],
            append: "cloud"],
        [id: "cloud", label: "Cloud only", title: "What still needs Roborock's cloud", group: "How it works", icon: "pi-cloud",
            body: "<p>These go through Roborock's servers and stop working if your internet or Roborock's cloud is down:</p>",
            checklist: [
                [title: "Sign-in and Rescan", detail: "Brings in your vacuums, their local keys, room names and scenes. Only needed at setup or after you rename rooms or add scenes."],
                [title: "IP recovery (automatic)", detail: "If a vacuum can't be reached for 90 seconds, the app asks Roborock's cloud for its current IP, checks the MAC matches, updates the IP and reconnects locally. At most once every 10 minutes per vacuum. Needs you to be signed in."],
                [title: "Vacuum Scene", detail: "Roborock scenes live in your Roborock account, so running one goes through the cloud. It's the only device command that does."],
                [title: "Jobs started from the Roborock app", detail: "The app sends its commands through Roborock's cloud. Hubitat still sees the job locally once it starts."]
            ],
            warning: "<b>Signing out doesn't stop local control.</b><br>Vacuums keep running locally. You only need to sign in again to Rescan or run scenes."],
        [id: "setup", label: "Setup", title: "Setting up a vacuum", group: "Getting started", icon: "pi-cog",
            body: "<p>Sign in with your Roborock email and the code they send you. Your vacuums appear on the main page.</p>" +
                "<p>Enter each vacuum's <b>IP address</b>. Roborock doesn't share it, so find it in your router or the Roborock app (Device info). Once connected, the vacuum's card shows its <b>MAC</b> address for a DHCP reservation in your router. If the IP changes anyway, the app finds the new one through Roborock's cloud while you're signed in.</p>" +
                "<p>Tap <b>Done</b>. Each vacuum gets a device and should show <b>healthStatus online</b> within a few seconds.</p>"],
        [id: "updates", label: "Status updates", title: "How status stays up to date", group: "Getting started", icon: "pi-refresh",
            body: "<p>The vacuum doesn't send updates on its own, so the driver checks it: every 20 seconds while it's cleaning, returning or busy at the dock, every 1, 2 or 5 minutes while docked (the <b>Docked poll</b> setting), and 3 seconds after any command.</p>" +
                "<p>The vacuum closes its connection now and then. The driver reconnects within seconds and only shows offline if it stays down for 90 seconds.</p>"],
        [id: "rooms", label: "Rooms and scenes", title: "Rooms and scenes", group: "Using it", icon: "pi-th-large",
            body: "<p><b>roomsList</b> on the device shows each room as <i>( Room name: ID )</i>. Use the IDs with <b>Vacuum Rooms</b>, for example <code>16, 17</code>.</p>" +
                "<p><b>scenesList</b> shows your Roborock scenes for <b>Vacuum Scene</b> (cloud).</p>" +
                "<p>Renamed a room or added a scene? Tap <b>Rescan vacuums and rooms</b> on the main page.</p>" +
                "<p><b>mopMode</b> and <b>mopWaterMode</b> show the vacuum's saved settings. A one-time choice in the Roborock app (like a Deep+ route for one run) isn't saved, so it won't show here. " +
                "Vacuums with a water flow slider (like the Saros) also show the slider number in <b>mopWaterLevel</b>.</p>"],
        [id: "execute", label: "Execute (advanced)", title: "Sending a raw command with Execute", group: "Using it", icon: "pi-code",
            body: "<p><b>Execute</b> sends a Roborock command straight to the vacuum. Type the command, and any parameters as a JSON list (leave blank if none). " +
                "It also works from a Rule Machine custom action, for example to set fan power before a room clean.</p>",
            checklist: [
                [title: "find_me", detail: "No parameters. The vacuum announces itself."],
                [title: "set_custom_mode", detail: "Fan power: [101] Quiet, [102] Balanced, [103] Turbo, [104] Max, [108] Max+."],
                [title: "set_mop_mode", detail: "Mop route: [300] Standard, [301] Deep, [303] Deep+, [304] Fast."],
                [title: "set_water_box_custom_mode", detail: "Water flow on older models: [200] Off, [201] Low, [202] Medium, [203] High. Not tested on slider models like the Saros yet."],
                [title: "get_status", detail: "No parameters. With Log level on Full, the vacuum's raw reply shows in the logs."]
            ],
            warning: "Nothing is checked before sending. A wrong command or parameter is rejected by the vacuum and the reason shows in the logs."],
        [id: "diag", label: "Diagnostics (beta)", title: "Sending a diagnostics report", group: "Troubleshooting", icon: "pi-file",
            body: "<p>Tap <b>Diagnostics</b> on the vacuum device. One block appears in the logs from <b>DIAGNOSTICS</b> to <b>END</b> with your model, firmware, protocol and the raw status from the vacuum. Copy all of it. It never includes your vacuum's local key.</p>" +
                "<p>For a problem, set Log level to <b>Full</b> (stays on for 3 hours during the beta), repeat it, and send those lines too.</p>"]
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
  .app-tip-card { border: 1px solid var(--surface-border, rgba(0,0,0,.12)); border-radius: 4px; overflow: hidden; }
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

// ---------- UI template (HubitatAppUiTemplate, from Pentair IntelliCenter) ----------

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "p-message p-message-success app-message" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle hub-text-success" : "fa-solid fa-exclamation-triangle hub-text-warning"
    """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
  .app-main-support button.hrefElem[name^='_action_href_tips'] { min-height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box; }
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
    <div class='flex-shrink-0'><i class='fa-solid fa-robot text-xl' aria-hidden='true'></i></div>
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
<div class='ic-dialog text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>One setting for the app and every vacuum.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only, such as a vacuum going offline or a command being rejected.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus connections, commands, state changes (cleaning, returning, charged) and vacuums added or removed.", current)}
  ${logLevelDetailHtml("Full", "Everything, including every request to the vacuum. Use while troubleshooting. Turns off after 3 hours.", current)}
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

private String logLevelPill(String level) {
    def tones = ["Errors Only": "p-tag p-tag-danger", "Normal": "surface-ground text-color-secondary", "Full": "p-tag p-tag-warning"]
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
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-link { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-success { color: var(--hubitat-success-color, #356700) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-warning { color: var(--hubitat-warning-color, #8a5a00) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .hub-text-danger { color: var(--hubitat-validation-color, #b71c1c) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-links .hrefElem { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-links .hrefElem > .state-incomplete-text,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-links .hrefElem > .state-complete-text { color: var(--text-color-secondary, #616161); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button { background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 1px solid transparent; border-radius: 4px; box-shadow: 0 2px 2px rgba(0,0,0,.14); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:enabled:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-primary,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-primary:enabled:hover { background: var(--hubitat-brand-background, #81bc00); color: var(--hubitat-on-brand, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-secondary { background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border-color: var(--hubitat-control-border, #b0bec5); box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-danger,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button-danger:enabled:hover { background: var(--hubitat-danger-background, #d32f2f); color: var(--hubitat-on-danger, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:enabled:active { box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:disabled { background: var(--hubitat-control-background, #eee); color: var(--text-color-secondary, #616161); opacity: .6; cursor: not-allowed; box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem { background: transparent; color: var(--hubitat-link-color, #1565c0) !important; border: 0; border-left: 3px solid transparent; border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem::before { display: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:not(:disabled):not(:has(.app-ui-navigation-selected)):hover { background: var(--surface-hover, #f3f6fa); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:has(.app-ui-navigation-selected) { background: var(--hubitat-selection-background, #eaf2fc); border-left-color: var(--hubitat-link-color, #1565c0); font-weight: 600; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:disabled { opacity: .6; cursor: not-allowed; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) button.mdl-button.app-ui-button:focus-visible,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .ic-dialog) .app-ui-navigation .hrefElem:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
"""
}

private String tileSecondaryTextCss() {
    legacyLightThemeCss() + "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}
