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

@Field static final String APP_VERSION = "0.1.3"
@Field static final String APP_NAME = "Roborock Integration"
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
    category: "Convenience",
    menu: "Integrations",
    installOnOpen: true,
    iconUrl: "", iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
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
                paragraph "<span class='text-color-secondary' style='font-size:14px;'>Signed in as ${esc(settings.email)}. Only used to bring in vacuums, rooms and scenes.</span>"
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
    String link = child ? " &middot; <a href='/device/edit/${child.id}' target='_blank' class='hub-text-link no-underline'>device page " +
        "<i class='fa-regular fa-external-link' style='font-size:0.8em'></i></a>" : ""
    "<div class='flex align-items-center gap-2 mt-2'><span class='font-semibold'>${esc(v.name)}</span>${tag}</div>" +
        "<div class='text-color-secondary' style='font-size:14px;'>${esc(v.model ?: '')} &middot; local protocol ${esc(proto)}${link}</div>"
}

private String currentLogLevel() { settings.logLevel ?: "Errors Only" }

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
private String esc(v) { (v == null ? "" : v.toString()).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;") }
private String dniFor(duid) { "RR-${duid}" }

private void logN(String m) { if (settings.logLevel in ["Normal", "Full"]) log.info m }
private void logE(String m) { log.error m }

// ---------- UI template (HubitatAppUiTemplate, from Pentair IntelliCenter) ----------

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "p-message p-message-success app-message" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle hub-text-success" : "fa-solid fa-exclamation-triangle hub-text-warning"
    """
<style>
  ${appPageSpacingCss()}
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
  ${logLevelDetailHtml("Full", "Everything, including every request to the vacuum. Use while troubleshooting. Turns off after 30 minutes.", current)}
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
