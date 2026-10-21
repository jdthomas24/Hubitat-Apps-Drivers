/*
    Leviton Smart Load Center - App
    Copyright 2026 JDThomas. All Rights Reserved
    Breaker, CT and leg mapping ported from rwoldberg/ldata-ha (Home Assistant).

    Notes:
     -Signs in to My Leviton, finds the account's panels (LWHEM "WHEMS" and LDATA) and creates one Panel
      device per included panel. Each Panel holds its own WebSocket and creates its Breaker and CT devices.
     -The email and password stay in this app's settings and are never logged or passed to devices. The
      token (Leviton issues 60-day tokens) is in state.auth; getAuth() hands it to the Panels.
     -getAuth(true) signs in again after Leviton refuses the token. Rate limited: a sign-in from the last
      5 minutes is reused, a failed one waits 10 minutes, and a rejected password stops retries until the
      next Done. A daily check signs in again 3 days before the token expires.
     -Done signs in again only when the email or password changed (SHA-256 in state.credKey) or the last
      sign-in failed, then re-reads the panel list and restarts the Panels.
     -Equipment page as in IntelliCenter, one section per panel; state.pushedExcluded is per panel.
     -Log level is the only logging control. App -> Panel -> Breaker/CT; every line comes back to childLog().
      Full turns off after 30 minutes.
     -UI follows HubitatAppUiTemplate (Reolink, UI design by gopher.ny). Dark mode uses the hub's theme
      classes and var() tokens with fallbacks; legacyLightThemeCss() covers pre-2.5.3 firmware (pattern by
      vurvantsev, Reolink 1.6.13).
*/

definition(
    name: "Leviton Smart Load Center",
    namespace: "jdthomas24",
    author: "jdthomas24",
    description: "Leviton LWHEM and LDATA smart panels: breaker and CT power, energy and optional breaker control",
    category: "Convenience",
    menu: "Integrations",
    installOnOpen: true,
    iconUrl: "",
    iconX2Url: ""
)

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String APP_NAME      = "Leviton Smart Load Center"
@Field static final String APP_VERSION   = "2.0.2"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/release-leviton-smart-load-center/163950"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String API           = "https://my.leviton.com/api"
@Field static final String ORIGIN        = "https://myapp.leviton.com"
@Field static final List LOG_LEVELS      = ["Errors Only", "Normal", "Full"]
@Field static final Map POLL_OPTIONS     = ["30": "30 seconds", "60": "1 minute", "120": "2 minutes", "300": "5 minutes"]
@Field static final Map POWER_OPTIONS    = ["0": "Any change", "1": "1 W", "5": "5 W", "10": "10 W", "25": "25 W"]
@Field static final Map TYPE_NAMES       = [WHEMS: "LWHEM (2nd gen)", LDATA: "LDATA (1st gen)"]

preferences {
    page(name: "mainPage")
    page(name: "equipmentPage")
}

private String panelDni(String id) { "leviton-panel-${id}" }
private List panelDevices() { getChildDevices().findAll { it.deviceNetworkId.startsWith("leviton-panel-") } }
private boolean panelIncluded(String id) { settings["pan_" + id] != false }

// ============================================================================
// Pages
// ============================================================================

def mainPage() {
    armLogRevert()
    if (state.pushedLogLevel != currentLogLevel()) pushLogLevel()
    Map banner = bannerState()
    Map panels = (state.panels ?: [:]) as Map
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, statusBannerHtml(banner.ok, banner.title, banner.summary)
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("My Leviton account")
            input "email", "text", title: "Email", required: true, width: 6
            input "password", "password", title: "Password", required: true, width: 6
            if (email && password) {
                input "btnFind", "button", title: panels ? "Find panels again" : "Sign in and find panels", inputClass: "app-ui-button", width: 12
            }
            if (panels) {
                paragraph rawHtml: true, columnHeader("Panels")
                panels.sort { a, b -> a.value.name <=> b.value.name }.each { id, p ->
                    def dev = getChildDevice(panelDni(id as String))
                    String link = dev ? " &middot; <a href='/device/edit/${dev.id}' target='_blank' class='hub-text-link no-underline'>device " +
                        "<i class='fa-regular fa-external-link' style='font-size:0.8em'></i></a>" : ""
                    input "pan_${id}", "bool", title: "${esc(p.name)} <span class='text-color-secondary' style='font-size:13px;'>" +
                        "${TYPE_NAMES[p.type] ?: p.type}${link}</span>", defaultValue: true, submitOnChange: true, width: 12
                }
                if (panelDevices()) {
                    Map eq = equipmentCounts()
                    href name: "toEquipment", title: "<i class='fa-solid fa-bolt mr-2' aria-hidden='true'></i>Equipment",
                        description: eq.total ? "${eq.on} of ${eq.total} breakers and CTs included. Choose which devices Hubitat creates." :
                            "Appears once a panel connects and reads its breakers.",
                        page: "equipmentPage", width: 12, style: "margin:8px;"
                }
            }
            paragraph rawHtml: true, columnHeader("Options")
            input "pollInterval", "enum", title: "Refresh interval", options: POLL_OPTIONS, defaultValue: "60", width: 6,
                description: "Energy counters and a backup read of every breaker"
            input "powerThreshold", "enum", title: "Report power changes of", options: POWER_OPTIONS, defaultValue: "5", width: 6,
                description: "Smaller changes are skipped to keep hub load down"
            input "liveUpdates", "bool", title: "Live power updates (every few seconds)", defaultValue: true, width: 12,
                description: "Off: power updates on the refresh interval only, much lighter on the hub"
            input "allowControl", "bool", title: "Allow breaker control", defaultValue: false, submitOnChange: true, width: 12,
                description: "Lets each breaker's Off trip it and On reset it"
            if (allowControl) {
                paragraph rawHtml: true, warningMessageHtml("<b>Off cuts power to that circuit.</b> Any rule, dashboard, voice command or " +
                    "&ldquo;all switches off&rdquo; that includes a breaker device can trip it. Keep breakers out of switch groups and voice " +
                    "assistants. 1st gen breakers can be tripped but must be reset at the panel.")
            }
        }
        section(sectionClass: "app-ui-links app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
            label title: "App name", required: false
        }
        section(title: "<b>Help & Support</b>", sectionClass: "app-ui-links app-main-support") {
            paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
                "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 6
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
                "Buy Me a Coffee", "Support development"), width: 6
        }
        versionFooterSection()
    }
}

def equipmentPage() {
    dynamicPage(name: "equipmentPage", title: "Equipment", install: false, uninstall: false) {
        section {
            paragraph rawHtml: true, "<style>${appPageSpacingCss()}</style>" +
                "<div class='text-color-secondary' style='font-size:14px;'>Turn off anything you don't want in Hubitat. " +
                "Its device is removed right away and won't come back. Turning it back on recreates it in a few seconds. " +
                "Deleting a device from the Devices page turns it off here too. Panel total power always counts every breaker.</div>"
        }
        List devs = panelDevices()
        if (!devs) section { paragraph "No panels yet. Sign in and tap Done first." }
        devs.sort { it.displayName }.each { dev ->
            Map eq = syncEquipment(dev)
            Map catalog = (eq.catalog ?: [:]) as Map
            if (!catalog) {
                section("<b>${esc(dev.displayName)}</b>") { paragraph "Nothing yet. The list fills in once the panel connects." }
                return
            }
            [[k: "breaker", label: "Breakers"], [k: "ct", label: "CT clamps"]].each { kind ->
                Map items = catalog.findAll { k, v -> v.k == kind.k }
                if (!items) return
                int on = items.count { k, v -> included(k as String) } as int
                section("<b>${esc(dev.displayName)}: ${kind.label}</b> <span class='text-color-secondary' style='font-weight:400;font-size:14px;'>${on} of ${items.size()} included</span>") {
                    items.sort { a, b -> (a.value.pos ?: 0) <=> (b.value.pos ?: 0) ?: a.value.n <=> b.value.n }.each { id, v ->
                        String sub = kind.k == "ct" ? "channel ${v.ch}" : "slot ${v.pos}${v.poles == 2 ? ', 2-pole' : ''}${v.rating ? ', ' + v.rating + ' A' : ''}"
                        input "inc_${id}", "bool", title: "${esc(v.n)} <span class='text-color-secondary' style='font-size:13px;'>${sub}</span>",
                            defaultValue: true, submitOnChange: true, width: 6
                    }
                }
            }
        }
    }
}

// Panel exclusions (devices deleted from the Devices page) -> toggles, then toggles -> Panel.
private Map syncEquipment(dev) {
    Map catalog = [:]
    List panelEx = []
    try {
        catalog = dev.getCatalog() ?: [:]
        panelEx = dev.getExcluded() ?: []
    } catch (e) {
        logDbg "equipment list not available yet: ${e.message}"
        return [catalog: [:], excluded: []]
    }
    Map pushedAll = (state.pushedExcluded ?: [:]) as Map
    String key = dev.deviceNetworkId
    List pushed = (pushedAll[key] ?: []) as List
    (panelEx - pushed).each { id -> app.updateSetting("inc_${id}", [value: false, type: "bool"]) }
    List want = catalog.keySet().collect { it.toString() }.findAll { !included(it) }
    want += panelEx.findAll { !catalog.containsKey(it) && !pushed.contains(it) }
    want = want.unique().sort()
    if (want != panelEx.collect { it.toString() }.unique().sort()) {
        try { dev.setExcluded(want) } catch (e) { logErr "could not update ${dev.displayName}'s equipment list: ${e.message}" }
    }
    if (pushed != want) { pushedAll[key] = want; state.pushedExcluded = pushedAll }
    return [catalog: catalog, excluded: want]
}

private boolean included(String id) { settings["inc_" + id] != false }

private Map equipmentCounts() {
    int total = 0, on = 0
    panelDevices().each { dev ->
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
    if (!email || !password) return [ok: false, title: "Setup required", summary: "Enter your My Leviton email and password, then tap <b>Done</b>."]
    switch (state.authError) {
        case "credentials": return [ok: false, title: "Sign-in failed", summary: "My Leviton didn't accept the email or password. Fix them and tap <b>Done</b>."]
        case "twofactor": return [ok: false, title: "Two-step verification is on", summary: "This integration can't sign in to an account with two-step verification yet. Turn it off in the My Leviton app, then tap <b>Done</b>."]
        case "network": return [ok: false, title: "Can't reach My Leviton", summary: "The sign-in didn't go through. It retries on its own, or tap <b>Done</b> to try now."]
    }
    if (!state.auth) return [ok: false, title: "Not signed in yet", summary: "Tap <b>Done</b> to sign in and find your panels."]
    Map panels = (state.panels ?: [:]) as Map
    if (!panels) return [ok: false, title: "No panels found", summary: "Signed in, but no LWHEM or LDATA panel was found on this account. Tap <b>Find panels again</b> after adding one in the My Leviton app."]
    List devs = panelDevices()
    if (!devs) return [ok: false, title: "No panels included", summary: "Turn on a panel below, then tap <b>Done</b>."]
    int up = devs.count { it.currentValue("connectionStatus") == "Connected" } as int
    int kids = devs.sum { it.getChildDevices()?.size() ?: 0 } as int
    String counts = "${countText(devs.size(), 'panel')} &middot; ${countText(kids, 'device')}"
    if (up == devs.size()) return [ok: true, title: "Integration ready", summary: counts]
    return [ok: false, title: devs.size() == 1 ? "Panel offline" : "${devs.size() - up} of ${devs.size()} panels offline",
            summary: "${counts} &middot; reconnecting on its own. Check the Leviton cloud status in the My Leviton app."]
}

def appButtonHandler(String btn) {
    if (btn == "btnFind") {
        if (needsSignIn() && !signIn()) return
        discover()
    }
}

// ============================================================================
// Lifecycle
// ============================================================================

def installed() { initialize() }

def updated() { initialize() }

def initialize() {
    unschedule()
    state.remove("fullLogArmed")   // unschedule() cleared its timer
    armLogRevert()
    if (!email || !password) return
    if (needsSignIn() && !signIn()) {
        pushToPanels()   // keep options and log level current even while signed out
        return
    }
    discover()
    syncPanels()
    schedule("0 17 3 * * ?", "tokenCheck")
}

private boolean needsSignIn() { !state.auth || state.authError || state.credKey != credKey() }

private String credKey() {
    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256")
    md.digest("${email?.trim()?.toLowerCase()}\n${password}".getBytes("UTF-8")).encodeHex().toString()
}

private Map panelOptions() {
    [pollSec: ((pollInterval ?: "60") as String).toInteger(), live: liveUpdates != false, control: allowControl == true,
     powerDelta: ((powerThreshold ?: "5") as String).toInteger()]
}

// Discovered panels -> Panel devices (create, configure, remove the ones turned off).
private void syncPanels() {
    Map opts = panelOptions()
    ((state.panels ?: [:]) as Map).each { id, p ->
        String dni = panelDni(id as String)
        def dev = getChildDevice(dni)
        if (!panelIncluded(id as String)) {
            if (dev) {
                String label = dev.displayName
                try { dev.removeAllChildren(); deleteChildDevice(dni); logInfo "removed ${label} and its devices (panel turned off)" }
                catch (e) { logErr "could not remove ${label}: ${e.message}" }
            }
            return
        }
        if (!dev) {
            try {
                dev = addChildDevice("jdthomas24", "Leviton Smart Load Center Panel", dni, [label: p.name ?: "Leviton Panel", isComponent: false])
                logInfo "created ${dev.displayName}"
            } catch (e) {
                String why = "${e}".contains("UnknownDeviceType") ? "the Leviton Smart Load Center Panel driver isn't installed (update with HPM)" : e.message
                logErr "could not create panel ${p.name}: ${why}"
                return
            }
        }
        dev.configure(p as Map, opts)
        dev.setLogLevel(currentLogLevel())
    }
    state.pushedLogLevel = currentLogLevel()
    runIn(2, "startPanels")
}

private void pushToPanels() {
    Map opts = panelOptions()
    panelDevices().each { dev ->
        Map p = (state.panels ?: [:])[dev.deviceNetworkId - "leviton-panel-"] as Map
        if (p) try { dev.configure(p, opts) } catch (e) { }
    }
    pushLogLevel()
}

def startPanels() {
    panelDevices().each { dev -> try { dev.initialize() } catch (e) { logErr "could not start ${dev.displayName}: ${e.message}" } }
}

def uninstalled() {
    panelDevices().each { dev ->
        try { dev.removeAllChildren() } catch (e) { }
        try { deleteChildDevice(dev.deviceNetworkId) } catch (e) { log.warn "${APP_NAME}: could not delete ${dev.displayName}: ${e.message}" }
    }
}

// ============================================================================
// My Leviton sign-in and panel list
// ============================================================================

// For the Panels. fresh = Leviton refused the token.
Map getAuth(boolean fresh = false) {
    Map a = state.auth as Map
    if (a && !fresh && !expiring(a)) return a
    if (state.authError == "credentials" || state.authError == "twofactor") return null
    if (fresh && a && now() - ((state.lastSignIn ?: 0L) as long) < 300000L) return a   // just signed in, nothing newer to offer
    if (state.authError && now() - ((state.lastAttempt ?: 0L) as long) < 600000L) return fresh ? null : a
    return signIn() ? state.auth as Map : (fresh ? null : a)
}

def tokenCheck() {
    Map a = state.auth as Map
    if (a && expiring(a)) {
        logInfo "Leviton sign-in expires soon. Signing in again."
        signIn()
    }
}

private boolean expiring(Map a) {
    long ttl = ((a.ttl ?: 5184000) as long) * 1000L
    long created = (state.lastSignIn ?: 0L) as long
    try { if (a.created) created = toDateTime(a.created.toString()).time } catch (e) { }
    return now() > created + ttl - 3L * 86400000L
}

private boolean signIn() {
    state.lastAttempt = now()
    try {
        def data = null
        httpPost([uri: "${API}/Person/login?include=user", contentType: "application/json", requestContentType: "application/json",
                  headers: headers(null), body: JsonOutput.toJson([email: email?.trim(), password: password]), timeout: 20]) { resp ->
            if (resp.status == 200) data = resp.data
        }
        if (data?.id) {
            state.auth = data
            state.lastSignIn = now()
            state.credKey = credKey()
            state.remove("authError")
            logInfo "signed in to My Leviton"
            return true
        }
        state.authError = "network"
        logErr "My Leviton sign-in returned no token. Retrying in 10 minutes."
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 406) {
            state.authError = "twofactor"
            logErr "My Leviton sign-in needs a two-step verification code, which this integration doesn't support yet."
        } else if (e.statusCode in [400, 401, 403]) {
            state.authError = "credentials"
            state.credKey = credKey()
            logErr "My Leviton didn't accept the email or password (HTTP ${e.statusCode}). Fix them in the app and tap Done."
        } else {
            state.authError = "network"
            logErr "My Leviton sign-in failed (HTTP ${e.statusCode}). Retrying in 10 minutes."
        }
    } catch (e) {
        state.authError = "network"
        logErr "can't reach My Leviton (${e.message}). Retrying in 10 minutes."
    }
    return false
}

private Map headers(String tok) {
    Map h = ["Accept": "application/json, text/plain, */*", "Accept-Language": "en-US,en;q=0.5", "Content-Type": "application/json",
             "cache-control": "no-cache", "pragma": "no-cache",
             "user-agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:147.0) Gecko/20100101 Firefox/147.0",
             "Origin": ORIGIN, "Referer": "${ORIGIN}/"]
    if (tok) h.authorization = tok
    return h
}

private apiGet(String path) {
    for (int i = 0; i < 2; i++) {
        String tok = getAuth(i == 1)?.id?.toString()
        if (!tok) return null
        try {
            def out = null
            httpGet([uri: "${API}${path}", contentType: "application/json", headers: headers(tok), timeout: 20]) { resp ->
                if (resp.status == 200) out = resp.data
            }
            return out
        } catch (groovyx.net.http.HttpResponseException e) {
            if (e.statusCode == 401 && i == 0) continue
            logDbg "GET ${path}: HTTP ${e.statusCode}"
            return null
        } catch (e) {
            logDbg "GET ${path}: ${e.message}"
            return null
        }
    }
    return null
}

// Residences from the account and permissions, then each residence's LWHEM and LDATA panels.
private boolean discover() {
    String uid = (state.auth as Map)?.userId?.toString()
    if (!uid) return false
    List rids = []
    String acct = null
    def perms = apiGet("/Person/${uid}/residentialPermissions")
    if (perms == null) { logErr "could not read the panel list from My Leviton. Keeping the panels found before."; return false }
    (perms instanceof List ? perms : []).each { pm ->
        if (pm.residentialAccountId && !acct) acct = pm.residentialAccountId.toString()
        if (pm.residenceId) rids << pm.residenceId.toString()
    }
    if (acct) {
        def res = apiGet("/ResidentialAccounts/${acct}/residences")
        (res instanceof List ? res : []).each { r -> if (r.id) rids << r.id.toString() }
        if (!rids) {
            def ra = apiGet("/ResidentialAccounts/${acct}")
            if (ra?.primaryResidenceId) rids << ra.primaryResidenceId.toString()
        }
    }
    Map found = [:]
    rids.unique().each { rid ->
        def wh = apiGet("/Residences/${rid}/iotWhems")
        (wh instanceof List ? wh : []).each { p ->
            if (p.id) found[p.id.toString()] = [id: p.id.toString(), name: (p.name ?: "Leviton Panel").toString(), type: "WHEMS", residenceId: rid]
        }
        def ld = apiGet("/Residences/${rid}/residentialBreakerPanels")
        (ld instanceof List ? ld : []).each { p ->
            String id = p.id?.toString()
            if (id && !found[id]) found[id] = [id: id, name: (p.name ?: "Leviton Panel").toString(), type: "LDATA", residenceId: rid]
        }
    }
    if (found != state.panels) state.panels = found
    logInfo "found ${countText(found.size(), 'panel')}"
    return true
}

// ============================================================================
// Logging
// ============================================================================

private String currentLogLevel() { LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only" }

private void pushLogLevel() {
    panelDevices().each { try { it.setLogLevel(currentLogLevel()) } catch (e) { } }
    state.pushedLogLevel = currentLogLevel()
}

private void logErr(String msg)  { log.warn "${APP_NAME}: ${msg}" }
private void logInfo(String msg) { if (currentLogLevel() != "Errors Only") log.info "${APP_NAME}: ${msg}" }
private void logDbg(String msg)  { if (currentLogLevel() == "Full") log.debug "${APP_NAME}: ${msg}" }

// Called by the Panels for themselves and every Breaker and CT. They already filter by level.
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
  .app-main-content { float: left; width: calc(62% - 8px); }
  .app-main-settings { float: right; width: 38%; border-left: 1px solid var(--surface-border, rgba(0,0,0,.12)); padding-left: 8px; box-sizing: border-box; }
  .app-main-content > .mdl-grid, .app-main-settings > .mdl-grid { padding: 4px 0 !important; }
  .app-main-support { clear: both; }
  @media (max-width: 1000px) {
    .app-main-content, .app-main-settings { float: none; width: 100%; border-left: 0; padding-left: 0; }
  }
</style>
<div class='flex align-items-center justify-content-between gap-3 ${tone} border-1 border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='fa-solid fa-bolt text-2xl' aria-hidden='true'></i></div>
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
<div class='lv-dialog text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>One setting for the app, every panel, breaker and CT device.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only, such as a failed sign-in, the Leviton cloud staying unreachable, or a breaker command that was refused.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus connections and reconnects, breakers switching or tripping, commands, and devices added or removed.", current)}
  ${logLevelDetailHtml("Full", "Everything, including every message from the Leviton cloud. Very busy on a large panel. Turns off after 30 minutes.", current)}
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
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .hub-text-link { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .hub-text-success { color: var(--hubitat-success-color, #356700) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .hub-text-warning { color: var(--hubitat-warning-color, #8a5a00) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .hub-text-danger { color: var(--hubitat-validation-color, #b71c1c) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-links .hrefElem { color: var(--hubitat-link-color, #1565c0) !important; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-links .hrefElem > .state-incomplete-text,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-links .hrefElem > .state-complete-text { color: var(--text-color-secondary, #616161); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button { background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 1px solid transparent; border-radius: 4px; box-shadow: 0 2px 2px rgba(0,0,0,.14); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button:enabled:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button-primary,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button-primary:enabled:hover { background: var(--hubitat-brand-background, #81bc00); color: var(--hubitat-on-brand, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button-secondary { background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border-color: var(--hubitat-control-border, #b0bec5); box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button-danger,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button-danger:enabled:hover { background: var(--hubitat-danger-background, #d32f2f); color: var(--hubitat-on-danger, #fff); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button:enabled:active { box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button:disabled { background: var(--hubitat-control-background, #eee); color: var(--text-color-secondary, #616161); opacity: .6; cursor: not-allowed; box-shadow: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-navigation .hrefElem { background: transparent; color: var(--hubitat-link-color, #1565c0) !important; border: 0; border-left: 3px solid transparent; border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-navigation .hrefElem::before { display: none; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-navigation .hrefElem:not(:disabled):not(:has(.app-ui-navigation-selected)):hover { background: var(--surface-hover, #f3f6fa); }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-navigation .hrefElem:has(.app-ui-navigation-selected) { background: var(--hubitat-selection-background, #eaf2fc); border-left-color: var(--hubitat-link-color, #1565c0); font-weight: 600; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-navigation .hrefElem:disabled { opacity: .6; cursor: not-allowed; }
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) button.mdl-button.app-ui-button:focus-visible,
  :root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .lv-dialog) .app-ui-navigation .hrefElem:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
"""
}

private String tileSecondaryTextCss() {
    legacyLightThemeCss() + "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

