/**
 * Pentair IntelliCenter
 * Version: 1.6.4
 *
 * Local WebSocket integration for Pentair IntelliCenter: pool/spa bodies, pumps, circuits,
 * sensors, and chlorinator.
 *
 * v1.6.4 -- UI refresh (Reolink/Battery Monitor pattern): status card with a Connected /
 * Disconnected / Setup required tag, equipment counts, side-by-side IP and port, Help & Support
 * cards, and version footer. No changes to connection or device behavior.
 *
 * Author: jdthomas24
 */

definition(
    name: "Pentair IntelliCenter",
    namespace: "intellicenter",
    author: "jdthomas24",
    description: "Pentair IntelliCenter local integration for Hubitat",
    version: "1.6.4",
    category: "Convenience",
    menu: "Integrations",
    iconUrl: "",
    iconX2Url: ""
)

import groovy.transform.Field

@Field static final String APP_VERSION   = "1.6.4"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/release-pentair-intellicenter-controller-beta/162876/31"
@Field static final String COFFEE_URL    = "https://paypal.me/jdthomas24?locale.x=en_US&country.x=US"

preferences {
    page(name: "mainPage")
}

private String bridgeDni() { "intellicenter-bridge-${app.id}" }

def mainPage() {
    def bridge = getChildDevice(bridgeDni())
    dynamicPage(name: "mainPage", title: "", install: true, uninstall: true) {
        section {
            paragraph rawHtml: true, icStatusCardHtml(bridge)
        }

        section("<b>Controller</b>") {
            input "intellicenterIP", "text", title: "IntelliCenter IP address",
                  description: "e.g. 192.168.1.50", required: true, width: 8
            input "intellicenterPort", "number", title: "Port",
                  description: "6680 for IntelliCenter 1, try 6681 for IC2", defaultValue: 6680, required: true, width: 4
        }

        if (bridge) {
            section("<b>Equipment</b>") {
                paragraph rawHtml: true, icEquipmentHtml(bridge)
            }
        }

        section(title: "<b>Help & Support</b>") {
            paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
                "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 6
            paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
                "Buy Me a Coffee", "Support development"), width: 6
        }

        section {
            label title: "App name", required: false
            input "debugMode", "bool", title: "Debug logging <span style='font-size:13px;color:#6b7280;'>· passed to the bridge, turns off after 60 minutes</span>",
                  defaultValue: false
        }

        section {
            paragraph rawHtml: true, "<div style='text-align:center;font-size:12px;color:#6b7280;margin-top:4px;'>Pentair IntelliCenter v${APP_VERSION}</div>"
        }
    }
}

/** Reolink-style status card: controller on the left, colored connection tag on the right. */
private String icStatusCardHtml(bridge) {
    def ip     = bmEsc(intellicenterIP ?: "")
    def port   = intellicenterPort ?: 6680
    def status = bridge?.currentValue("connectionStatus")?.toString()
    int n      = bridge ? (bridge.getChildDevices()?.size() ?: 0) : 0
    def tag, tone, sub
    if (!intellicenterIP || status?.startsWith("Not Configured")) {
        tag = "Setup required"; tone = "amber"
        sub = "Enter your controller's IP address below, then tap <b>Done</b>."
    } else if (!bridge) {
        tag = "Not connected yet"; tone = "amber"
        sub = "${ip}:${port} &middot; tap <b>Done</b> to connect."
    } else if (status == "Connected") {
        tag = "● Connected"; tone = "green"
        sub = "${ip}:${port} &middot; ${countText(n, 'device')}"
    } else {
        tag = "● Disconnected"; tone = "red"
        sub = "Can't reach ${ip}:${port}. The bridge retries every 2 minutes."
    }
    def accent = [green: "#22a045", red: "#d93025", amber: "#e08a00"][tone]
    """
${bmPageCss()}
<style>
  .ic-card { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap;
             background: #fff; border: 1px solid #e3e6ea; border-left: 4px solid ${accent}; border-radius: 6px; padding: 12px 16px; }
  .ic-title { font-size: 16px; font-weight: 600; color: #1f2937; }
  .ic-sub { font-size: 14px; color: #6b7280; margin-top: 2px; }
  .ic-right { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
  .ic-right .bm-pill { font-size: 13px; padding: 4px 12px; margin-left: 0; }
  .ic-right a { font-size: 14px; font-weight: 600; color: #1565c0; text-decoration: none; white-space: nowrap; }
</style>
<div class='ic-card'>
  <div style='display:flex;align-items:center;gap:12px;min-width:0;'>
    <i class='fa-solid fa-water-ladder' style='font-size:22px;color:${accent};' aria-hidden='true'></i>
    <div style='min-width:0;'><div class='ic-title'>Pentair IntelliCenter</div><div class='ic-sub'>${sub}</div></div>
  </div>
  <div class='ic-right'>${bmPill(tag, tone)}
    <a href='/logs?tab=past&amp;appId=${app.id}' target='_blank'>View logs <i class='fa-regular fa-external-link'></i></a></div>
</div>
"""
}

private String icEquipmentHtml(bridge) {
    def kids  = bridge.getChildDevices() ?: []
    def count = { String prefix -> kids.count { it.deviceNetworkId?.startsWith(prefix) } }
    def tiles = [
        ["Pool and spa", count("intellicenter-body-")],
        ["Pumps",        count("intellicenter-pump-")],
        ["Circuits",     count("intellicenter-circuit-")],
        ["Sensors",      count("intellicenter-sensor-")],
        ["Chlorinator",  count("intellicenter-chem-")]
    ].findAll { it[0] != "Chlorinator" || it[1] > 0 }
    def html = "<div class='bm-stats' style='grid-template-columns:repeat(auto-fit,minmax(110px,1fr));'>" +
        tiles.collect { t -> "<div class='bm-stat'><div class='bm-stat-label'>${t[0]}</div><div class='bm-stat-num'>${t[1]}</div></div>" }.join("") +
        "</div>"
    if (!kids) html += "<div class='bm-hint' style='margin-top:8px;'>Equipment appears here once the bridge connects and reads your controller.</div>"
    html += "<div class='bm-hint' style='margin-top:8px;'>Bridge device: ${hubLink("/device/edit/${bridge.id}", bmEsc(bridge.displayName))}</div>" + hubLinkScript()
    return html
}

// ============================================================
// ===================== MAPPINGS ============================
// ============================================================
mappings {
    path("/body/:dni/on")                         { action: [GET: "endpointOn"] }
    path("/body/:dni/off")                        { action: [GET: "endpointOff"] }
    path("/body/:dni/heatoff")                    { action: [GET: "endpointHeatOff"] }
    path("/body/:dni/setpoint/:temp")             { action: [GET: "endpointSetPoint"] }
    path("/body/:dni/heatsource/:source")         { action: [GET: "endpointHeatSource"] }
    path("/body/:dni/setpointup")                 { action: [GET: "endpointSetPointUp"] }
    path("/body/:dni/setpointdown")               { action: [GET: "endpointSetPointDown"] }
    path("/body/:dni/heatandstart/:temp")         { action: [GET: "endpointHeatAndStart"] }
    path("/body/:dni/heatandstart/:temp/:source") { action: [GET: "endpointHeatAndStartWithSource"] }
}

def endpointOn() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.on()
    render status: 200, data: "OK"
}

def endpointHeatAndStart() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def temp = params.temp?.toInteger()
    if (temp) {
        child.setHeatingSetpoint(temp)
        setBodySetPoint(params.dni, temp)
    }
    child.on()
    render status: 200, data: "OK"
}

def endpointHeatAndStartWithSource() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def dni = params.dni
    def temp = params.temp?.toInteger()
    if (temp) {
        child.setHeatingSetpoint(temp)
        setBodySetPoint(dni, temp)
    }
    def source = params.source?.replaceAll("_"," ")?.split(" ")?.collect{it.capitalize()}?.join(" ")
    if (source && source != "Off") {
        child.setHeatSource(source)
        setBodyHeatSource(params.dni, source)
    }
    child.on()
    render status: 200, data: "OK"
}

def endpointOff() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.off()
    render status: 200, data: "OK"
}

def endpointHeatOff() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child."⚙ Stop Heat - Keep Pump On"()
    render status: 200, data: "OK"
}

def endpointSetPoint() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def temp = params.temp.toInteger()
    child.setHeatingSetpoint(temp)
    setBodySetPoint(params.dni, temp)
    render status: 200, data: "OK"
}

def endpointSetPointUp() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.adjustSetPointUp()
    render status: 200, data: "OK"
}

def endpointSetPointDown() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    child.adjustSetPointDown()
    render status: 200, data: "OK"
}

def endpointHeatSource() {
    def child = getChildDevice(params.dni)
    if (!child) { render status: 404, data: "Device not found"; return }
    def source = params.source?.replaceAll("_", " ")
                                ?.split(" ")
                                ?.collect { it.capitalize() }
                                ?.join(" ")
    child.setHeatSource(source)
    setBodyHeatSource(params.dni, source)
    render status: 200, data: "OK"
}

// ============================================================
// ===================== LIFECYCLE ===========================
// ============================================================
def installed() {
    log.info "IntelliCenter app installed"
    initialize()
}

def updated() {
    log.info "IntelliCenter app updated"
    initialize()
}

def initialize() {
    def bridgeDni = "intellicenter-bridge-${app.id}"
    def bridge    = getChildDevice(bridgeDni)

    if (!bridge) {
        log.info "Creating IntelliCenter bridge device"
        bridge = addChildDevice(
            "intellicenter",
            "Pentair IntelliCenter Bridge",
            bridgeDni,
            [label: "IntelliCenter Bridge", isComponent: false]
        )
    }

    def hubIP        = location.hubs[0].localIP
    def endpointBase = "http://${hubIP}:8080/apps/api/${app.id}"

    bridge.updateSetting("ipAddress",    [value: intellicenterIP,           type: "text"])
    bridge.updateSetting("portNumber",   [value: intellicenterPort ?: 6680, type: "number"])
    bridge.updateSetting("debugMode",    [value: debugMode ?: false,        type: "bool"])
    bridge.updateSetting("endpointBase", [value: endpointBase,              type: "text"])

    runIn(2, "initBridge")
}

def initBridge() {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.initialize()
}

def uninstalled() {
    log.info "IntelliCenter app uninstalled"
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.getChildDevices()?.each {
        try { bridge.deleteChildDevice(it.deviceNetworkId) }
        catch (e) { log.warn "Could not delete bridge child ${it.deviceNetworkId}: ${e.message}" }
    }
    getChildDevices().each {
        try { deleteChildDevice(it.deviceNetworkId) }
        catch (e) { log.warn "Could not delete ${it.deviceNetworkId}: ${e.message}" }
    }
}

// ============================================================
// ===================== BODY COMMAND RELAY ==================
// ============================================================
def setBodyStatus(String bodyDni, String status) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.setBodyStatus(bodyDni, status)
}

def setBodySetPoint(String bodyDni, Integer temp) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.setBodySetPoint(bodyDni, temp)
}

def setBodyHeatSource(String bodyDni, String source) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.setBodyHeatSource(bodyDni, source)
}

def componentRefresh(child) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.componentRefresh(child)
}

// ============================================================
// ===================== CIRCUIT COMMAND RELAY ===============
// ============================================================
def childOn(String dni) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.circuitOn(dni)
}

def childOff(String dni) {
    def bridge = getChildDevice("intellicenter-bridge-${app.id}")
    bridge?.circuitOff(dni)
}

// ============================================================
// ===================== UI HELPERS ==========================
// ============================================================
private String countText(int count, String singular) {
    "${count} ${singular}${count == 1 ? '' : 's'}"
}

private String supportLinkHtml(String url, String iconClass, String title, String subtitle) {
    """
<a href='${url}' target='_blank' rel='noopener noreferrer'
   class='flex align-items-center gap-3 border-1 border-gray-200 border-round px-3 py-2 text-color no-underline'>
  <i class='${iconClass} text-blue-700 text-xl flex-shrink-0'></i>
  <span class='min-w-0'>
    <span class='block text-blue-700 font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
</a>
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

private String hubLink(String path, String inner) {
    "<a href='${path}' class='bm-hublink' data-path='${path}' target='_blank'>${inner}</a>"
}

private String hubLinkScript() {
    """
<script>
(function(){
  var p = location.pathname, i = p.indexOf('/installedapp/');
  var pre = i > 0 ? p.substring(0, i) : '';
  [].forEach.call(document.querySelectorAll('a.bm-hublink'), function(a){
    a.setAttribute('href', pre + a.getAttribute('data-path'));
  });
})();
</script>
"""
}

private String bmEsc(v) {
    (v == null ? "" : v.toString()).replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace('"', "&quot;").replace("'", "&#39;")
}

private String bmPill(String text, String tone) {
    def t = tone in ["red", "green", "amber", "gray", "blue"] ? tone : "gray"
    "<span class='bm-pill bm-t-${t}'>${text}</span>"
}

private String bmToneCss() {
    """
  .bm-t-red   { background: #fdecec; color: #b42318; }
  .bm-t-green { background: #e7f6ec; color: #1e7b34; }
  .bm-t-amber { background: #fff4e0; color: #9a5b00; }
  .bm-t-gray  { background: #f1f3f5; color: #4b5563; }
  .bm-t-blue  { background: #e8f0fe; color: #1a56c4; }
  .bm-c-red   { color: #b42318; }
  .bm-c-amber { color: #9a5b00; }
  .bm-c-green { color: #1e7b34; }
"""
}

private String bmCardsCss() {
    """
  .bm-cards button.hrefElem {
    position: relative; background: #fff; border: 1px solid #e0e0e0; border-radius: 6px; box-shadow: none;
    min-height: 62px; padding: 8px 28px 8px 48px; font-family: inherit; text-align: left;
  }
  .bm-cards button.hrefElem > span:first-child { color: #1565c0; font-weight: 600; font-size: 16px !important; }
  .bm-cards button.hrefElem > .state-incomplete-text, .bm-cards button.hrefElem > .state-complete-text { color: #6b7280; font-size: 14px !important; }
  .bm-cards button.hrefElem i { position: absolute; left: 16px; top: 50%; transform: translateY(-50%); color: #1565c0; font-size: 18px; }
  .bm-cards button.hrefElem::before { color: #1565c0; }
  .bm-cards-primary button.hrefElem { background: #eef4fd; border-color: #c7dafc; }
  .bm-cards-primary button.hrefElem:hover { background: #e3edfc; }
  .bm-settings > .mdl-grid { border: 1px solid #e3e6ea; border-radius: 6px; overflow: hidden; padding: 0 !important; margin: 0 8px 8px; }
  .bm-settings .mdl-cell { margin: 0 !important; width: 100% !important; max-width: 100%; box-sizing: border-box; }
  .bm-settings button.hrefElem {
    display: flex; align-items: center; width: 100%; box-sizing: border-box; background: #fff; border: 0; border-bottom: 1px solid #eef0f3;
    border-radius: 0; box-shadow: none; min-height: 0; padding: 11px 44px 11px 14px; font-family: inherit; text-align: left;
  }
  .bm-settings .mdl-cell:last-child button.hrefElem { border-bottom: 0; }
  .bm-settings button.hrefElem:hover { background: #f7f9fb; }
  .bm-settings button.hrefElem > br { display: none; }
  .bm-settings button.hrefElem > span:first-child { color: #1f2937; font-weight: 500; font-size: 15px !important; }
  .bm-settings button.hrefElem > .state-incomplete-text,
  .bm-settings button.hrefElem > .state-complete-text { margin-left: auto; padding-left: 16px; color: #6b7280; font-size: 14px !important; text-align: right; white-space: nowrap; }
  .bm-settings button.hrefElem i { color: #6b7280; width: 18px; text-align: center; margin-right: 10px; }
  @media (max-width: 600px) {
    .bm-settings button.hrefElem { flex-wrap: wrap; }
    .bm-settings button.hrefElem > .state-incomplete-text,
    .bm-settings button.hrefElem > .state-complete-text {
      width: 100%; margin-left: 28px; padding-left: 0; text-align: left; white-space: normal; margin-top: 2px;
    }
  }
  .bm-helprow { text-align: center; font-size: 14px; color: #6b7280; margin-top: 4px; }
  .bm-helprow a { color: #1565c0; text-decoration: none; margin: 0 10px; white-space: nowrap; }
  .bm-helprow a:hover { text-decoration: underline; }
  .bm-helprow i { margin-right: 5px; }
"""
}

private String bmPageCss() {
    """
<style>
  ${tileSecondaryTextCss()}
  ${tipsCardCss()}
  ${bmCardsCss()}
  .bm-pill { display: inline-block; font-size: 11px; font-weight: 600; padding: 2px 9px; border-radius: 999px; white-space: nowrap; margin-left: 4px; }
  ${bmToneCss()}
  .bm-h { font-size: 15px; font-weight: 600; margin: 0 0 2px; }
  .bm-muted { color: #6b7280; }
  .bm-hint { font-size: 13px; color: #6b7280; }
  .bm-msg { border-radius: 6px; padding: 8px 12px; font-size: 14px; }
  .bm-msg-ok { background: #e7f6ec; color: #1e7b34; }
  .bm-msg-warn { background: #fff4e0; color: #9a5b00; }
  .bm-msg-err { background: #fdecec; color: #b42318; }
  .bm-msg-info { background: #e8f0fe; color: #1a56c4; }
  .bm-msg-muted { background: #f5f7fa; color: #6b7280; }
  .bm-stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; margin-bottom: 10px; }
  .bm-stat { background: #f5f7fa; border-radius: 6px; padding: 6px 12px; }
  .bm-stat-label { font-size: 12px; color: #6b7280; }
  .bm-stat-num { font-size: 16px; font-weight: 600; }
  .bm-btn button {
    min-height: 36px; width: 100%; padding: 0 12px; border-radius: 4px; font-family: inherit; font-size: 14px;
    box-shadow: none; background: #fff; color: #1a56c4; border: 1px solid #cfd6de;
  }
  .bm-btn button:hover { background: #f3f6fa; }
  .bm-btn-primary button { background: #1a73e8 !important; color: #fff !important; border-color: #1a73e8 !important; }
  .bm-btn-danger button { color: #b42318 !important; }
  .bm-table { width: 100%; border-collapse: collapse; font-size: 14px; }
  .bm-table th { font-size: 12px; font-weight: 600; color: #6b7280; text-align: left; padding: 6px 8px; border-bottom: 1px solid #e3e6ea; }
  .bm-table td { padding: 6px 8px; border-bottom: 1px solid #eef0f3; vertical-align: middle; }
  @media (max-width: 600px) {
    .bm-stat { padding: 6px 8px; }
    .bm-stat-num { font-size: 13px; }
    .bm-quick { width: calc(50% - 16px) !important; display: inline-block; }
  }
</style>
"""
}

