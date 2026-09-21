/*
    Sofabaton Integration - Parent App
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: the X1/X1S local receive path (Sofabaton Remote driver) is a
    fork of Derek Osborn's (dJOS1475) Hubitat community driver, built on
    push-command building blocks originated by Mike Maxwell (mike.maxwell).

    2026-09-10 jdthomas24
        -Initial publication
    2026-09-16 jdthomas24
        -Fixed addHubPage: removed submitOnChange from newHubIp/newHubMac/
         newHubMqttHost, which was forcing a page postback (and required-
         field validation reset) on every keystroke into those fields.
        -Relabeled the X2 MAC field to "Sofabaton Hub MAC ID" and added
         guidance on where to find it (it's the X2's own MAC, read out of
         the activity_control_up topic name via a tool like MQTT Explorer --
         not the Hubitat hub's MAC, which is an easy mix-up).
        -Fixed getBridge() to lazily create the Bridge device if missing,
         instead of only creating it in initialize() (which only runs after
         the first "Done" on the main page). Previously, adding a hub before
         ever completing that first install would silently fail since there
         was no Bridge yet to attach the child device to.
        -Added a "Listen for Next Activity" button to the X2 branch of Add
         Activity, working with the Bridge driver's new learn-mode support:
         press Listen, press the activity's button on the physical remote,
         and the numeric Sofabaton Activity ID auto-fills, no more manual
         MQTT Explorer lookup required. Page auto-refreshes while listening.
        -Added a "Cancel and go back" link to both Add Hub and Add Activity,
         and mainPage() now clears any half-entered fields from those pages
         whenever it's reached, so a cancelled attempt doesn't leave stale
         values sitting in the form the next time it's opened.
        -Changed the Hub Model dropdown from "X1/X1S" to just "X1S", since
         the plain X1 isn't supported by the underlying driver this forks.
        -Main page now lists each hub's activities nested underneath it
         instead of just a count, so with multiple hubs it's clear which
         activity belongs to which hub.
        -Added Edit (name, and for X2 the broker host/port/user/pass) and
         Remove for hubs, and Remove for activities, right on the main
         page. Model, MAC, and X1S IP are intentionally not editable after
         creation (those change the device's DNI) -- remove and re-add if
         one of those needs to change. Editing relies on new mqttHost/
         mqttPort/mqttUser attributes published by the Remote driver.
        -FIXED A REAL BUG: neither Add Hub nor Add Activity had an actual
         "finish" button for fields with no submitOnChange (the X2 broker
         fields, the X1S webhook URL fields). The only clickable thing on
         the page was the "Cancel and go back" link, which jumps straight
         to mainPage() without ever running the creation logic in
         addHubPage()/addActivityPage() -- and since mainPage() clears
         those fields on entry, clicking Cancel (easy to do by mistake,
         since it looked like the way to finish) silently discarded
         everything with zero error, zero log output. Added explicit
         "Add This Hub" / "Add This Activity" buttons that actually submit
         the page and trigger creation.
        -FIXED ANOTHER REAL BUG: the Listen-mode methods (startActivityLearn,
         getActivityLearnResult, cancelActivityLearn, clearActivityLearnResult)
         live on the Bridge device, but addActivityPage() and
         appButtonHandler() were calling them on the individual hub
         (Remote) device instead -- those methods don't exist there, so
         every click of "Listen for Next Activity" would have errored out
         silently. Now calls bridge.* directly.
        -FIXED A THIRD REAL BUG: required:true on the page's inputs (Hub
         Name, Hub Model, etc.) triggers the browser's own HTML5 "please
         fill out this field" validation, which blocks submitting the form
         at all -- including clicking "Cancel and go back", since it's the
         same <form>. That made Cancel completely unusable on a partially
         filled page. Dropped required:true from every field on both
         pages; the app's own logic already gates real creation on those
         fields being present, so nothing is lost except the native red
         asterisk/validation nudge.
        -FIXED A FOURTH REAL BUG: Edit used an href with params to reach
         addHubPage(), but the main page also has a plain "Add a Hub" href
         to that same page name with no params -- Hubitat has a confirmed
         platform bug where multiple hrefs targeting the same page name
         with different params can mix up or drop those params. Replaced
         Edit (and Cancel, for consistency and to let it properly exit
         edit mode) with buttons that set/clear state directly instead,
         the same button+state pattern already proven reliable tonight
         for Remove and Listen mode.
        -FIXED A FIFTH REAL BUG: Edit's prefill step and its save-check ran
         in the same page pass -- the prefill echoed the hub's own current
         values into the form fields, and the very next lines immediately
         re-read those same fields as if the user had just submitted them,
         silently saving (a no-op, since it was the hub's own data) and
         returning to the main page before the form was ever shown. Added
         a justPrefilled guard so the save-check only fires on an actual
         later postback (the Save button), not the same pass as prefill.
        -Wrapped each hub's summary line in a bordered/pill-styled box
         (model shown as a small badge) instead of plain text, so multiple
         hubs read as distinct cards rather than a flat list.
        -Styled the model badge blue, capped the card's width instead of
         stretching full-page, and put Edit/Remove side by side (width: 6
         each) instead of stacked, using Hubitat's input width grid.
        -FIXED A SIXTH REAL BUG (same family as the Hub Edit one, caught
         proactively before it surfaced): if the Activity Name was typed
         before pressing Listen, the auto-refresh that captures the ID
         would read it as a real submission on that same pass and silently
         create the Activity before the user ever saw the captured ID.
         Added the same justCaptured-style guard so a captured ID only
         fills the field; creation still requires an explicit later click
         of "Add This Activity".
        -Main page now shows each X2 activity's Sofabaton Activity ID
         inline next to its name (e.g. "Watch TV (ID: 101)"), so it's
         visible without opening the device page.
        -FIXED A SEVENTH REAL BUG, confirmed by live logs this time: the
         page's refreshInterval (meant to auto-poll while Listening) was
         bouncing straight back to mainPage() every cycle instead of
         reloading addActivityPage() -- matches a known class of Hubitat
         auto-refresh bug (confirmed via community reports of the same
         "bounces back unexpectedly" behavior). Removed refreshInterval
         entirely rather than fight an unreliable platform mechanism;
         Listen mode now relies on state persisting across navigation
         instead -- press Listen, press the remote whenever, then simply
         reopen Add an Activity and the captured ID will already be there.
        -SUPERSEDED the justPrefilled/justCapturedLearn patches above with
         a more fundamental fix, after a live test showed the underlying
         bug family wasn't actually closed: any field becoming non-null on
         its own (Edit's prefill, Listen's capture) could still trigger a
         save on a LATER, unrelated page reload, not just the exact same
         pass -- because the save logic only ever checked "are the fields
         full", never "did the user actually just click Save". Both
         addHubPage() and addActivityPage() now gate all creation/update
         behind an explicit state.hubSaveRequested / state.
         activitySaveRequested flag, set only by the Save button's own
         click handler and consumed (cleared) the instant it's checked.
         Field values being present is no longer sufficient on its own,
         under any circumstance, to trigger a save. A saveError message
         now also shows on the page if Save is clicked with required
         fields still missing, instead of silently doing nothing.
        -FIXED: added a missing uninstalled() method. Without it, removing
         the app left the Bridge device (and everything under it) orphaned
         on the hub with nothing to clean it up, guaranteeing a DNI
         conflict on the next install. Now explicitly deletes the Bridge
         child, which triggers its own uninstalled() to tear down MQTT and
         its children in turn.
    2026-09-18 jdthomas24
        -Added optional webhook URL fields to the X2 branch of Add
         Activity, alongside the existing Sofabaton Activity ID field.
         Context: local MQTT command delivery on this platform currently
         appears to be broken at the interfaces.mqtt level (confirmed via
         a minimal isolated test driver -- connect and subscribe both
         succeed, but zero incoming messages are ever delivered, for any
         topic, from any publisher, on this platform version). Filed with
         Hubitat. Reads (state sync from the physical remote) are
         unaffected and still work over MQTT. Sofabaton's own app exposes
         a cloud webhook ("Turn on API") for starting/stopping an
         activity, and the Activity driver has always supported
         webhookUrlOn/webhookUrlOff -- this page just never offered those
         fields for X2 hubs before, since MQTT was assumed to be the whole
         story. Now an X2 activity can optionally take a webhook URL too:
         the Activity driver already checks webhookUrlOn/Off before
         falling back to MQTT, so filling one in gives a real, working
         command path today, and remains available afterward as a
         cloud-dependent alternative to local MQTT for anyone who wants
         it, once MQTT itself is working again. Sofabaton Activity ID
         remains required for X2 either way, since it's still what drives
         read-side state sync from the physical remote.
        -Added Edit for Activities (name, webhook URLs, and for X2 the
         Sofabaton Activity ID) -- previously only Remove existed, so
         fixing a typo'd webhook URL or ID meant going to the Activity
         device's own Preferences page directly. Same button+state pattern
         as Hub Edit (state.editingActivityKey = "<hubDni>|<actDni>"),
         same explicit-save-flag gating, same reasoning throughout.
        -Added a "don't forget to hit Done" reminder paragraph on the main
         page once at least one hub exists, since hitting Done (not just
         "Add This Hub"/"Add This Activity") is what actually finalizes
         the app install -- easy to miss on first use.
        -Swapped the visual emphasis on hub/activity listing rows: the
         model badge (X2/X1S) and the activity's Sofabaton Activity ID are
         now the larger, bolder elements (what you actually scan for),
         while the hub/activity's own name is smaller text beside them.
    2026-09-21 jdthomas24
        -Added an in-app Setup Guide (helpPage(), linked from the top of
         the main page) covering the full walkthrough for both hub
         models: static IP prerequisite, local HTTP setup (X1S) vs MQTT
         broker + MAC lookup (X2), the separate cloud "Turn on API"
         webhook step for both, an FAQ (what buttons 11-20 mean, how
         name matching works for X1S, where the webhook URL goes, the
         space-encoding behavior), and a Known Issues note about X2's
         current MQTT write limitation. Ships with the app itself rather
         than living only in an external README -- content mirrors
         SETUP-GUIDE.md; keep both in sync if either is updated.

    *OVERVIEW
     Parent app for the Sofabaton Integration. Manages one or more physical
     Sofabaton hubs (X1S/X2), each as its own "Sofabaton Remote" child device
     nested under a single "Sofabaton Integration Bridge" grouping device, and
     lets each hub have one or more "Sofabaton Activity" child devices nested
     under IT for the activities you want to control/observe.

     X1S hubs are identified by IP and use a local HTTP listener. X2 hubs are
     identified by MAC address and share one MQTT connection (held by the
     Bridge device) across however many X2 hubs you add -- confirmed against
     real hardware. Adding an X2 hub here also wires up that shared MQTT
     connection.

     Adding an Activity asks for different things depending on the hub's
     model: an X1S activity needs a Start (and optional Stop) webhook URL.
     An X2 activity needs its numeric Sofabaton Activity ID (drives
     read-side MQTT state sync from the physical remote), and can
     optionally also take a webhook URL for command delivery -- see the
     2026-09-18 note above for why that option exists alongside MQTT.

     This app only handles setup (adding/removing hubs and activities).
     Runtime behaviour -- local button routing, MQTT connect/subscribe/
     publish, cloud webhook calls, and Activity state sync when the
     physical remote is used -- lives entirely in the Bridge, Remote, and
     Activity drivers; this app does not subscribe to or process any device
     events itself.
*/

definition(
    name: "Sofabaton Integration",
    namespace: "jdthomas24",
    author: "Jason Thomas",
    description: "Manage one or more Sofabaton X Series hubs and their activities",
    category: "Convenience",
    menu: "Integrations",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: "",
    importUrl: ""
)

preferences {
    page(name: "mainPage")
    page(name: "addHubPage")
    page(name: "addActivityPage")
    page(name: "helpPage")
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

// Without this, removing the app leaves the Bridge (and everything under
// it) orphaned on the hub -- nothing would ever clean it up, and the next
// install would immediately hit the same DNI-conflict mess this whole
// project fought through tonight. Explicitly delete the Bridge child here;
// deleting it triggers the Bridge driver's own uninstalled(), which is
// responsible for tearing down MQTT and its own children in turn.
def uninstalled() {
    def bridge = getBridge()
    if (bridge) {
        try {
            deleteChildDevice(bridge.deviceNetworkId)
        } catch (e) {
            log.error "Sofabaton Integration: failed to remove Bridge device on uninstall: ${e.message}"
        }
    }
}

def initialize() {
    if (!getBridge()) {
        addChildDevice("jdthomas24", "Sofabaton Integration Bridge", bridgeDni(), [label: "Sofabaton Integration Bridge"])
    }
}

private String bridgeDni() {
    return "sofabaton-bridge-${app.id}"
}

def getBridge() {
    def bridge = getChildDevice(bridgeDni())
    if (!bridge) {
        // Lazily create the Bridge if it doesn't exist yet. installed()/
        // updated() only fire once the user hits "Done" on the main page,
        // but a first-run user can navigate straight into Add a Hub before
        // that happens -- without this, the hub-creation calls below would
        // silently fail with no Bridge to attach the child device to.
        log.debug "getBridge() -- Bridge not found, creating it now"
        bridge = addChildDevice("jdthomas24", "Sofabaton Integration Bridge", bridgeDni(), [label: "Sofabaton Integration Bridge"])
    }
    return bridge
}

def mainPage() {
    log.debug "mainPage() entered -- state.editingHubDni=${state.editingHubDni}, state.editingActivityKey=${state.editingActivityKey}"
    // Edit mode is entered by a button (editHub_<dni> / editAct_<hubDni>_<actDni>)
    // rather than an href with params -- Hubitat has a known bug where
    // multiple hrefs on one page targeting the SAME page name with
    // different params can mix up or drop those params. A button + state
    // sidesteps it entirely.
    if (state.editingHubDni) {
        return addHubPage()
    }
    if (state.editingActivityKey) {
        return addActivityPage()
    }

    // Wipe any half-entered Add Hub / Add Activity fields whenever we land
    // back here normally, so a cancelled attempt doesn't leave stale
    // values sitting in those forms the next time they're opened.
    clearHubSettings()
    clearActivitySettings()
    state.remove("learnStartedFor")
    state.remove("activitySaveRequested")

    dynamicPage(name: "mainPage", title: "Manage your hubs and activities", install: true, uninstall: true) {
        def bridge = getBridge()
        def hubs = bridge?.getChildDevices() ?: []

        section {
            href name: "toHelpPage", title: "📖 Setup Guide", description: "Full walkthrough: X1S vs X2, webhooks, button mapping, FAQ", page: "helpPage"
        }

        section {
            paragraph "<span style='background:#1976d2;color:#fff;border-radius:14px;padding:4px 14px;font-size:1.1em;font-weight:bold'>Hubs</span>"
            if (!hubs) {
                paragraph "No hubs added yet."
            } else {
                hubs.each { hub ->
                    def activities = hub.getChildDevices() ?: []
                    String model = hub.currentValue("hubModel") ?: "unknown model"
                    String idShown = model == "X2" ? (hub.currentValue("remoteMac") ?: "no MAC set") : (hub.currentValue("remoteIp") ?: "no IP set")
                    // 2026-09-18: activities are now full pills of their
                    // own (name + ID badge together, rounded, light fill)
                    // instead of plain indented text with a vertical
                    // border line -- the line felt like clutter once each
                    // row had its own visual weight. Section headers
                    // ("Hubs"/"Activities") are now blue pills too, same
                    // family as the model badge, for a consistent look.
                    StringBuilder card = new StringBuilder()
                    card << "<div style='border:1px solid #ccc;border-radius:10px;padding:12px 14px;margin-bottom:4px;background:#fafafa'>"
                    card << "<div><span style='background:#5f8b6f;color:#fff;border-radius:8px;padding:2px 9px;font-size:0.9em;font-weight:bold'>${model}</span> "
                    card << "<span style='font-size:0.85em'>${hub.getLabel()}</span> "
                    card << "<span style='color:#888;font-size:0.8em'>${idShown}</span></div>"
                    if (!activities) {
                        card << "<div style='margin-top:10px;color:#aaa;font-size:0.85em'>No activities yet.</div>"
                    } else {
                        card << "<div style='margin-top:10px;display:flex;flex-wrap:wrap;gap:8px'>"
                        activities.each { act ->
                            String idBadge = model == "X2" ? " <span style='background:#455a64;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.85em;font-weight:bold'>ID ${act.currentValue('sofabatonActivityId') ?: '?'}</span>" : ""
                            // 2026-09-18: every activity now gets a
                            // delivery tag, not just X2. X1S only ever
                            // has one real path (Sofabaton's cloud
                            // webhook), so it always tags "Webhook" --
                            // consistent labeling beats a tag that only
                            // sometimes shows up depending on hub model.
                            // X2 checks which path is actually configured
                            // since it genuinely has a choice. Amber for
                            // webhook (cloud-dependent, a deliberate
                            // "heads up, this one relies on the internet"
                            // cue), teal for MQTT (local).
                            String deliveryTag
                            if (model == "X2") {
                                boolean hasWebhook = (act.getSetting("webhookUrlOn") as boolean)
                                deliveryTag = hasWebhook ?
                                    " <span style='background:#e8a33d;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Webhook</span>" :
                                    " <span style='background:#26897a;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>MQTT</span>"
                            } else {
                                deliveryTag = " <span style='background:#e8a33d;color:#fff;border-radius:10px;padding:1px 8px;font-size:0.8em'>Webhook</span>"
                            }
                            card << "<span style='display:inline-block;background:#e3eaf3;border-radius:20px;padding:6px 14px;font-size:0.9em'>${act.getLabel()}${idBadge}${deliveryTag}</span>"
                        }
                        card << "</div>"
                    }
                    card << "</div>"
                    paragraph card.toString()
                    // 2026-09-18: each pair is exactly width:3+3=6, so
                    // two pairs together always sum to a full 12-wide row
                    // -- no explicit row-break needed between the hub's
                    // pair and the first activity's pair, they naturally
                    // share one clean row (Edit Hub, Remove Hub, Edit
                    // Activity, Remove Activity, each pair still visually
                    // adjacent). This keeps working automatically as more
                    // activities are added too: every 2 pairs (4 buttons)
                    // fills a row exactly, so the next pair always starts
                    // a fresh row on its own rather than splitting awkwardly
                    // mid-pair.
                    input name: "editHub_${hub.deviceNetworkId}", type: "button", title: "Edit Hub", width: 3
                    input name: "removeHub_${hub.deviceNetworkId}", type: "button", title: "Remove Hub", width: 3
                    activities.each { act ->
                        input name: "editAct_${hub.deviceNetworkId}_${act.deviceNetworkId}", type: "button", title: "Edit Activity", width: 3
                        input name: "removeAct_${hub.deviceNetworkId}_${act.deviceNetworkId}", type: "button", title: "Remove Activity", width: 3
                    }
                    paragraph "<div style='margin-bottom:10px'></div>"
                }
            }
            href name: "toAddHub", title: "Add a Hub", page: "addHubPage"
        }

        if (hubs) {
            section {
                paragraph "<span style='background:#1976d2;color:#fff;border-radius:14px;padding:4px 14px;font-size:1.1em;font-weight:bold'>Activities</span>"
                href name: "toAddActivity", title: "Add an Activity", page: "addActivityPage"
            }
            section {
                paragraph "<span style='color:#888;font-size:0.85em'>Don't forget to tap <b>Done</b> below once you're finished adding hubs and activities -- it's what actually finalizes the app install.</span>"
            }
        }
    }
}

def addHubPage(params = [:]) {
    log.debug "addHubPage() entered -- newHubName=${newHubName}, newHubModel=${newHubModel}, newHubIp=${newHubIp}, newHubMac=${newHubMac}, newHubMqttHost=${newHubMqttHost}, params=${params}, state.editingHubDni=${state.editingHubDni}, state.hubSaveRequested=${state.hubSaveRequested}"
    if (state.hubPageCancelled) {
        state.remove("hubPageCancelled")
        state.remove("editingHubDni")
        state.remove("editHubPrefilled")
        state.remove("hubSaveRequested")
        clearHubSettings()
        return mainPage()
    }
    // Edit mode: entered via a href with params:[editDni: <hub dni>] from
    // the main page's Edit link. Persisted in state so it survives the
    // page's own submitOnChange postbacks (params are only present on the
    // very first navigation in, not later reloads of the same page).
    if (params?.editDni) {
        state.editingHubDni = params.editDni
        state.remove("editHubPrefilled")
    }
    String editingDni = state.editingHubDni
    def bridge = getBridge()
    def editingHub = editingDni ? bridge?.getChildDevice(editingDni) : null

    if (editingHub && !state.editHubPrefilled) {
        app.updateSetting("newHubName", [value: editingHub.getLabel(), type: "text"])
        app.updateSetting("newHubModel", [value: editingHub.currentValue("hubModel"), type: "enum"])
        if (editingHub.currentValue("hubModel") == "X2") {
            app.updateSetting("newHubMqttHost", [value: editingHub.currentValue("mqttHost") ?: "", type: "text"])
            app.updateSetting("newHubMqttPort", [value: editingHub.currentValue("mqttPort") ?: "1883", type: "text"])
            app.updateSetting("newHubMqttUser", [value: editingHub.currentValue("mqttUser") ?: "", type: "text"])
        }
        state.editHubPrefilled = true
    }

    // ONLY an explicit click of "Add This Hub" / "Save Changes" -- which
    // sets state.hubSaveRequested in appButtonHandler -- is ever treated
    // as a real submission. Fields being non-null is not enough on its
    // own: they can become non-null from Edit's prefill, or simply because
    // the user filled them in earlier and the page reloaded for an
    // unrelated reason (an auto-refresh, a stray postback). Gating on an
    // explicit flag instead of "are the fields full" closes off that
    // whole bug family at once rather than patching each trigger path
    // individually.
    String saveError = null
    if (state.hubSaveRequested) {
        state.remove("hubSaveRequested")
        if (!editingHub && newHubName && newHubModel == "X1S" && newHubIp) {
            log.debug "addHubPage() creating X1S hub '${newHubName}'"
            createHttpHub(newHubName, newHubIp)
            clearHubSettings()
            return mainPage()
        } else if (!editingHub && newHubName && newHubModel == "X2" && newHubMac && newHubMqttHost) {
            log.debug "addHubPage() creating X2 hub '${newHubName}'"
            createMqttHub(newHubName, newHubMac, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            return mainPage()
        } else if (editingHub && newHubName && (editingHub.currentValue("hubModel") == "X1S" || newHubMqttHost)) {
            log.debug "addHubPage() updating existing hub '${editingDni}'"
            updateExistingHub(editingHub, newHubName, newHubMqttHost, newHubMqttPort ?: "1883", newHubMqttUser, newHubMqttPass)
            clearHubSettings()
            state.remove("editingHubDni")
            state.remove("editHubPrefilled")
            return mainPage()
        } else {
            saveError = "Please fill in all required fields before saving."
        }
    }

    dynamicPage(name: "addHubPage", title: editingHub ? "Edit ${editingHub.getLabel()}" : "Add a Sofabaton Hub", install: false, uninstall: false) {
        section {
            input name: "cancelHubBtn", type: "button", title: "&larr; Cancel and go back"
        }
        if (saveError) {
            section {
                paragraph "<b style='color:#c00'>${saveError}</b>"
            }
        }
        section {
            input name: "newHubName", type: "text", title: "Hub Name (e.g. Living Room)"
            if (editingHub) {
                paragraph "Model: <b>${editingHub.currentValue('hubModel')}</b> (can't be changed here -- remove and re-add if you need a different model)"
            } else {
                input name: "newHubModel", type: "enum", title: "Hub Model", options: ["X1S", "X2"], submitOnChange: true
            }
        }
        if (!editingHub && newHubModel == "X1S") {
            section {
                input name: "newHubIp", type: "text", title: "Hub IP Address (set a static DHCP reservation first)"
            }
        }
        if (editingHub && editingHub.currentValue("hubModel") == "X1S") {
            section {
                paragraph "IP Address: <b>${editingHub.currentValue('remoteIp') ?: 'not set'}</b> (can't be changed here -- remove and re-add if it changed)"
            }
        }
        if ((!editingHub && newHubModel == "X2") || (editingHub && editingHub.currentValue("hubModel") == "X2")) {
            section("MQTT Connection") {
                paragraph "<b>Before adding an X2 hub, MQTT needs to be running on this Hubitat hub.</b><br>" +
                    "Go to Integrations &rarr; Add Built-In App &rarr; MQTT Import Integration (or Export Integration), enable it, " +
                    "and turn on 'Use built-in MQTT service' if you don't already have an external broker. " +
                    "The app will show you a host, port, and login -- enter that same information below.<br>" +
                    "Then, in the Sofabaton app, go to Devices &rarr; Add Device &rarr; Wi-Fi &rarr; Add Home Assistant Remote, " +
                    "and enter the same broker details there so the hub connects to the same broker Hubitat does.<br><br>" +
                    "<b>Sofabaton Hub MAC ID:</b> this is the X2's own MAC (12 hex characters, colons ok, they'll be stripped), " +
                    "NOT your Hubitat hub's MAC. If you don't already have it, connect a tool like MQTT Explorer to the broker, " +
                    "press an activity button on the remote, and read it out of the topic name: activity/&lt;MAC&gt;/activity_control_up"
                if (editingHub) {
                    paragraph "MAC ID: <b>${editingHub.deviceNetworkId}</b> (can't be changed here -- remove and re-add if it changed)"
                } else {
                    input name: "newHubMac", type: "text", title: "Sofabaton Hub MAC ID (not your Hubitat hub's MAC -- see note above for how to find it)"
                }
                input name: "newHubMqttHost", type: "text", title: "Hubitat Hub's LAN IP (running the broker -- not the Sofabaton hub's IP, not 127.0.0.1)"
                input name: "newHubMqttPort", type: "text", title: "Broker Port", defaultValue: "1883", required: false
                input name: "newHubMqttUser", type: "text", title: "Broker Username (leave blank if none)", required: false
                input name: "newHubMqttPass", type: "password", title: "Broker Password (leave blank if none${editingHub ? ' -- leave blank to keep the current password' : ''})", required: false
                paragraph "One shared MQTT connection is used for all X2 hubs you add -- entering broker details again for a second X2 hub reconnects to the same broker, it doesn't open a second connection."
            }
        }
        section {
            input name: "saveHubBtn", type: "button", title: editingHub ? "Save Changes" : "Add This Hub"
        }
    }
}

private void clearHubSettings() {
    ["newHubName", "newHubModel", "newHubIp", "newHubMac", "newHubMqttHost", "newHubMqttPort", "newHubMqttUser", "newHubMqttPass"].each {
        app.removeSetting(it)
    }
}

// Applies edits to an already-existing hub in place (name always, plus the
// X2 broker fields if this is an X2 hub -- model, MAC, and X1S IP are
// intentionally not editable here, see addHubPage()'s notes on why).
// Password is only overwritten if a new one was actually typed, so leaving
// it blank on an edit keeps whatever was already saved.
private void updateExistingHub(def hub, String name, String mqttHost, String mqttPort, String mqttUser, String mqttPass) {
    hub.setLabel(name)
    if (hub.currentValue("hubModel") == "X2") {
        hub.updateSetting("mqttHost", [value: mqttHost, type: "text"])
        hub.updateSetting("mqttPort", [value: mqttPort, type: "text"])
        hub.updateSetting("mqttUser", [value: mqttUser ?: "", type: "text"])
        if (mqttPass) hub.updateSetting("mqttPass", [value: mqttPass, type: "password"])
        hub.updated()
    }
}

private void createHttpHub(String name, String ip) {
    def bridge = getBridge()
    if (!bridge) return
    String dni = ipToHexForApp(ip)
    if (!dni) {
        log.error "Cannot add hub '$name': '$ip' is not a valid IPv4 address"
        return
    }
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        log.error "Failed to create Remote device for hub '$name'"
        return
    }
    hub.updateSetting("ip", [value: ip, type: "text"])
    hub.updateSetting("hubModel", [value: "X1S", type: "enum"])
    hub.updated()
}

private void createMqttHub(String name, String mac, String host, String port, String user, String pass) {
    def bridge = getBridge()
    if (!bridge) {
        log.error "Cannot add hub '$name': Bridge device not found"
        return
    }
    String dni = (mac ?: "").replaceAll(/[^A-Fa-f0-9]/, "").toUpperCase()
    if (dni.length() != 12) {
        log.error "Cannot add hub '$name': '$mac' does not look like a valid 12-character MAC address"
        return
    }
    log.debug "createMqttHub() creating child device with DNI '${dni}' under bridge"
    def hub = bridge.createRemoteDevice(dni, name)
    if (!hub) {
        log.error "Failed to create Remote device for hub '$name' (createRemoteDevice returned null -- check Bridge driver logs)"
        return
    }
    log.debug "createMqttHub() child device created, setting hubModel/mac/mqtt* and calling updated()"
    hub.updateSetting("hubModel", [value: "X2", type: "enum"])
    hub.updateSetting("mac", [value: dni, type: "text"])
    hub.updateSetting("mqttHost", [value: host, type: "text"])
    hub.updateSetting("mqttPort", [value: port, type: "text"])
    if (user) hub.updateSetting("mqttUser", [value: user, type: "text"])
    if (pass) hub.updateSetting("mqttPass", [value: pass, type: "password"])
    hub.updated()
    log.debug "createMqttHub() done"
}

def addActivityPage() {
    log.debug "addActivityPage() entered -- newActivityHub=${newActivityHub}, newActivityName=${newActivityName}, newActivitySofabatonId=${newActivitySofabatonId}, newActivityUrlOn=${newActivityUrlOn}, state.learnStartedFor=${state.learnStartedFor}, state.activitySaveRequested=${state.activitySaveRequested}, state.editingActivityKey=${state.editingActivityKey}"
    // FIXED A REAL BUG: cancelActivityBtn was clearing state directly in
    // appButtonHandler but never telling this page to actually navigate
    // anywhere -- a button click just triggers Hubitat to re-render this
    // same page, so Cancel silently did nothing visible. Same fix pattern
    // as addHubPage()'s cancelHubBtn: the button only sets a flag, and
    // THIS page checks it first thing and returns mainPage() itself.
    if (state.activityPageCancelled) {
        state.remove("activityPageCancelled")
        state.remove("editingActivityKey")
        state.remove("editActivityPrefilled")
        state.remove("learnStartedFor")
        clearActivitySettings()
        return mainPage()
    }
    def bridge = getBridge()
    def hubs = bridge?.getChildDevices() ?: []

    // Edit mode: state.editingActivityKey is "<hubDni>|<actDni>", set by
    // the Edit button in mainPage()'s activity listing. Same button+state
    // pattern as hub Edit, for the same reason (avoids Hubitat's href/
    // params bug across multiple hrefs to one page name).
    String editingHubDni = null
    String editingActDni = null
    def editingActivity = null
    if (state.editingActivityKey) {
        def keyParts = state.editingActivityKey.split(/\|/, 2)
        if (keyParts.length == 2) {
            editingHubDni = keyParts[0]
            editingActDni = keyParts[1]
            def editingHubDevice = bridge?.getChildDevice(editingHubDni)
            editingActivity = editingHubDevice?.getChildDevice(editingActDni)
        }
    }

    if (editingActivity && !state.editActivityPrefilled) {
        app.updateSetting("newActivityHub", [value: editingHubDni, type: "enum"])
        app.updateSetting("newActivityName", [value: editingActivity.getLabel(), type: "text"])
        if (editingActivity.currentValue("sofabatonActivityId") != null) {
            app.updateSetting("newActivitySofabatonId", [value: editingActivity.currentValue("sofabatonActivityId").toString(), type: "number"])
        }
        // Webhook URLs are settings on the Activity device, not published
        // attributes (unlike sofabatonActivityId) -- read them via the
        // device's own settings map so Edit can prefill what's already
        // configured, same as Edit Hub does for the X2 broker fields.
        app.updateSetting("newActivityUrlOn", [value: editingActivity.getSetting("webhookUrlOn") ?: "", type: "text"])
        app.updateSetting("newActivityUrlOff", [value: editingActivity.getSetting("webhookUrlOff") ?: "", type: "text"])
        // NOTE: getSetting() on a child device from the parent app mirrors
        // the already-proven updateSetting() calls used elsewhere in this
        // file (see createMqttHub/updateExistingHub) -- if this doesn't
        // read back correctly on a future platform version, worst case is
        // the webhook fields prefill blank on Edit and need retyping, not
        // a hard failure.
        state.editActivityPrefilled = true
    }

    def selectedHub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    boolean isX2 = selectedHub?.currentValue("hubModel") == "X2"
    boolean listening = isX2 && state.learnStartedFor == newActivityHub

    // If we're listening, check whether the Bridge has captured a result yet.
    // Learn-mode methods (startActivityLearn, getActivityLearnResult, etc.)
    // live on the BRIDGE device, not on the individual hub -- call bridge
    // here, not selectedHub.
    if (listening) {
        def result = bridge.getActivityLearnResult()
        if (result != null) {
            app.updateSetting("newActivitySofabatonId", [value: (result as Integer).toString(), type: "number"])
            bridge.clearActivityLearnResult()
            state.remove("learnStartedFor")
            listening = false
        }
    }

    // ONLY an explicit click of "Add This Activity" / "Save Changes" --
    // which sets state.activitySaveRequested in appButtonHandler -- is
    // ever treated as a real submission. A captured Listen result or
    // Edit's own prefill filling in fields is not enough on its own to
    // trigger a save. This closes off the same bug family as
    // addHubPage()'s explicit-save gate, for the same reason: fields
    // becoming non-null by themselves must never be treated as
    // equivalent to a real user submission.
    //
    // 2026-09-18: X2 now accepts newActivityUrlOn/Off as well as
    // newActivitySofabatonId -- the ID is still required (it's what
    // drives read-side MQTT state sync from the physical remote), but the
    // webhook fields are optional on top of it, giving a real command
    // path independent of MQTT. See the file header note on why. Also
    // added Edit support: when editingActivity is set, Save updates the
    // existing device in place instead of creating a new one.
    String saveError = null
    if (state.activitySaveRequested) {
        state.remove("activitySaveRequested")
        if (editingActivity) {
            if (newActivityName && (isX2 ? newActivitySofabatonId != null : newActivityUrlOn)) {
                updateExistingActivity(editingActivity, newActivityName, newActivityUrlOn, newActivityUrlOff, isX2 ? (newActivitySofabatonId as Integer) : null)
                clearActivitySettings()
                state.remove("editingActivityKey")
                state.remove("editActivityPrefilled")
                return mainPage()
            } else {
                saveError = "Please fill in all required fields before saving."
            }
        } else if (!isX2 && newActivityHub && newActivityName && newActivityUrlOn) {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, null)
            clearActivitySettings()
            return mainPage()
        } else if (isX2 && newActivityHub && newActivityName && newActivitySofabatonId != null) {
            createActivity(newActivityHub, newActivityName, newActivityUrlOn, newActivityUrlOff, newActivitySofabatonId as Integer)
            clearActivitySettings()
            return mainPage()
        } else {
            saveError = "Please fill in all required fields before saving."
        }
    }

    dynamicPage(name: "addActivityPage", title: editingActivity ? "Edit ${editingActivity.getLabel()}" : "Add an Activity", install: false, uninstall: false) {
        section {
            input name: "cancelActivityBtn", type: "button", title: "&larr; Cancel and go back"
        }
        if (saveError) {
            section {
                paragraph "<b style='color:#c00'>${saveError}</b>"
            }
        }
        section {
            if (editingActivity) {
                paragraph "Hub: <b>${selectedHub?.getLabel() ?: editingHubDni}</b> (can't be changed here -- remove and re-add if it needs to move to a different hub)"
            } else {
                input name: "newActivityHub", type: "enum", title: "Which Hub?", options: hubs.collectEntries { [(it.deviceNetworkId): it.getLabel()] }, submitOnChange: true
            }
            input name: "newActivityName", type: "text", title: "Activity Name (e.g. Watch TV)" + (isX2 ? "" : " -- must match the remote's configured user-definable button label exactly, this is how state sync matches it up")
        }
        if (selectedHub && !isX2) {
            section {
                input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL"
                input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, unconfirmed feature -- leave blank if unsure)", required: false
            }
        }
        if (selectedHub && isX2) {
            section {
                paragraph "Sofabaton Activity ID (required) drives state sync -- keeps this device in sync when the activity is changed from the physical remote. Press Listen below, press the activity's button on the physical remote, then come back to this page (see note below) to see the ID auto-filled -- or enter it manually if you already know it (e.g. from MQTT Explorer)."
                if (listening) {
                    paragraph "<b>Listening...</b> press the activity's button on the physical remote now. This page does NOT auto-refresh (Hubitat's own auto-refresh was unreliable here) -- once you've pressed the button, leave this page and come back (tap Add an Activity again, or Cancel and reopen it) and the captured ID will already be filled in below."
                    input name: "cancelLearnBtn", type: "button", title: "Cancel"
                } else {
                    input name: "learnBtn", type: "button", title: "Listen for Next Activity"
                }
                input name: "newActivitySofabatonId", type: "number", title: "Sofabaton Activity ID", submitOnChange: true
            }
            section("Command Delivery (optional)") {
                paragraph "By default, turning this Activity on/off from Hubitat sends the command over local MQTT. If you'd rather use Sofabaton's cloud webhook instead (or MQTT isn't working right now), fill in a webhook URL below -- generate it in the Sofabaton app under the activity's 'Turn on API' option. If both are set, the webhook is used first and MQTT is the fallback."
                input name: "newActivityUrlOn", type: "text", title: "Start Activity Webhook URL (optional, cloud-dependent)", required: false
                input name: "newActivityUrlOff", type: "text", title: "Stop Activity Webhook URL (optional, cloud-dependent)", required: false
            }
        }
        section {
            input name: "saveActivityBtn", type: "button", title: editingActivity ? "Save Changes" : "Add This Activity"
        }
    }
}
// 2026-09-21: in-app Setup Guide, so the full walkthrough (X1S vs X2, cloud
// webhook steps, button 11-20 explanation, name-matching, known issues)
// ships WITH the app itself rather than living only in an external
// README/repo link. No separate hosting to keep in sync, no stale link,
// one click away from the main page. Content mirrors SETUP-GUIDE.md --
// if that file is updated, update this too so they don't drift apart.
def helpPage() {
    // Small reusable platform pills so each step is instantly scannable --
    // setup bounces between the Sofabaton app and Hubitat constantly, and
    // that was genuinely confusing before this was added. Blue matches the
    // app's existing "Hubitat-side" section headers; purple is deliberately
    // a different color family from the green/amber MODEL pills (X1S/X2)
    // used elsewhere, so model and platform are never visually confused
    // with each other.
    String hubitatPill = "<span style='background:#1976d2;color:#fff;border-radius:8px;padding:1px 9px;font-size:0.75em;font-weight:bold'>HUBITAT</span>"
    String sofabatonPill = "<span style='background:#7c4dff;color:#fff;border-radius:8px;padding:1px 9px;font-size:0.75em;font-weight:bold'>SOFABATON APP</span>"
    dynamicPage(name: "helpPage", title: "Sofabaton Integration -- Setup Guide", install: false, uninstall: false) {
        section {
            href name: "backFromHelp", title: "&larr; Back", page: "mainPage"
        }
        section {
            paragraph "<b>Two hub models, two different setups.</b> Find yours below. Both need a static IP/DHCP reservation on your router first, that's true for either model."
            paragraph "Every step below is tagged so it's clear which app you're in, setup bounces back and forth between the two. The pill sits on its own line right above the step it applies to:<br>$hubitatPill<br>means the step under it happens in this Hubitat app or a device page.<br>$sofabatonPill<br>means the step under it happens in the Sofabaton mobile app, not Hubitat at all."
        }
        section {
            paragraph "<span style='background:#5f8b6f;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.95em;font-weight:bold'>X1S</span> <b>Setup</b>"
            paragraph "$hubitatPill<br><b>1.</b> Add a Hub &rarr; Model: X1S &rarr; enter the hub's static IP."
            paragraph "$sofabatonPill<br><b>2.</b> Devices &rarr; Add Device &rarr; Wi-Fi &rarr; 'Create a virtual device for IP control'. URL: <code>http://[Hubitat IP]:39501/</code>, method PUT. One per activity. See 'What do buttons 11-20 mean?' below before picking a body value."
            paragraph "$sofabatonPill<br><b>3.</b> On that activity, turn on <b>'Turn on API'</b> and copy the webhook URL(s) it gives you. Separate step from #2, this one only covers commands, not reads."
            paragraph "$hubitatPill<br><b>4.</b> Add an Activity here &rarr; Activity Name must exactly match the button's Description you set in step 2 (see 'How does name matching work?' below) &rarr; paste the webhook URL(s) from step 3."
            paragraph "$hubitatPill<br><b>5.</b> Click Done on the main page when finished."
            paragraph "<div style='background:#f5f5f5;border-left:4px solid #5f8b6f;padding:10px 14px;margin-top:6px'><b>Worked example, an Apple TV activity:</b><br>" +
                "&bull; Step 2, body value you send: <code>appleTV</code> (a string, since it's going into one of the 11-20 slots, not a plain number)<br>" +
                "&bull; On the Remote device's Preferences tab, User 1 (11) field: <code>appleTV|Apple TV</code> &mdash; <code>appleTV</code> is the matchString (must exactly equal what you typed in step 2), <code>Apple TV</code> after the pipe is the Description<br>" +
                "&bull; Step 4, Activity Name in Hubitat: <code>Apple TV</code> &mdash; must exactly match the Description half above, capitalization and spacing included<br>" +
                "If any of those three don't line up exactly, the remote will still control your gear, Hubitat just won't know the activity changed.</div>"
        }
        section {
            paragraph "<span style='background:#5f8b6f;color:#fff;border-radius:10px;padding:2px 10px;font-size:0.95em;font-weight:bold'>X2</span> <b>Setup</b>"
            paragraph "$hubitatPill<br><b>1.</b> Integrations &rarr; Add Built-In App &rarr; MQTT Import Integration &rarr; enable, turn on 'Use built-in MQTT service'. Note the host/port/login shown."
            paragraph "$sofabatonPill<br><b>2.</b> Tap <b>Me</b> (bottom of the app) &rarr; <b>Connect to Home Assistant (MQTT broker)</b> &rarr; tap <b>Confirmed MQTT Installed</b> &rarr; enter IP (your Hubitat hub's own IP), Port, Username, Password (last three from step 1)."
            paragraph "<div style='background:#fff8e1;border-left:4px solid #e8a33d;padding:8px 12px;font-size:0.9em'>&#9888;&#65039; Easy to skip, but skipping it means the hub never talks to the broker at all. Two things that trip people up: there's a <i>different</i> feature called 'Home Assistant Remote' under Devices &rarr; Wi-Fi, that's not this, skip it here. And the screen talks about 'Home Assistant', you don't have that, confirm anyway, you're pointing it at Hubitat's broker instead.</div>"
            paragraph "$sofabatonPill<br><b>3.</b> Download <b>MQTT Explorer</b> (free, mqtt-explorer.com) on your computer &rarr; connect it with the same host/port/username/password from step 1 &rarr; on the physical remote, switch to a different activity &rarr; look in the sidebar for something like <code>activity &rarr; 14639332AA40 &rarr; activity_control_up</code> &rarr; that 12-character code is your X2's MAC, write it down."
            paragraph "<div style='background:#fff8e1;border-left:4px solid #e8a33d;padding:8px 12px;font-size:0.9em'>&#9888;&#65039; Has to be an actual activity change, not just any button, plain commands like volume never touch the network, so nothing shows up until you switch activities. Also: this is the <b>Sofabaton hub's</b> MAC, not Hubitat's own, easy to mix up.</div>"
            paragraph "$hubitatPill<br><b>4.</b> Add a Hub here &rarr; Model: X2 &rarr; enter that MAC, plus the broker host/port/login from step 1."
            paragraph "$hubitatPill<br><b>5.</b> Add an Activity here &rarr; Sofabaton Activity ID is required (use Listen for Next Activity, or read it from the MQTT payload). That's it, MQTT handles both reads and writes for this activity from here."
            paragraph "$hubitatPill<br><b>6.</b> Click Done on the main page when finished."
            paragraph "<div style='background:#f5f5f5;border-left:4px solid #5f8b6f;padding:10px 14px;margin-top:6px'><b>Worked example, the same Apple TV activity:</b><br>" +
                "&bull; Step 3/5, Sofabaton Activity ID: <code>101</code> &mdash; a plain number, read off the MQTT payload (e.g. <code>{\"activity_id\":101,\"state\":\"on\"}</code>) or auto-filled by Listen for Next Activity<br>" +
                "&bull; Step 5, Activity Name in Hubitat: <code>Apple TV</code> &mdash; this one's just a label for you, it does not need to match anything in the Sofabaton app, unlike X1S<br>" +
                "The ID is the only thing that has to be correct here, the name is purely cosmetic.</div>"
            paragraph "<div style='background:#fff8e1;border-left:4px solid #e8a33d;padding:10px 14px;margin-top:10px'><b>Temporary workaround, not part of the setup above:</b> X2 is designed to be fully MQTT, both directions, that's the whole point. Until the platform bug below is fixed, MQTT writes don't work yet, so if you want working on/off control today rather than waiting, you can optionally add a webhook URL to an X2 Activity (Turn on API in the Sofabaton app, then paste into the Activity's Edit page). Not the intended long-term design, just a bridge until MQTT writes are fixed.</div>"
        }
        section {
            paragraph "<b style='color:#c00'>Known issue right now:</b> X2 command delivery over MQTT doesn't currently work. MQTT is fully two-way by design, publish and subscribe both work in either direction, that's the whole point of it, and why this integration was built around MQTT for X2 in the first place. Reads (remote &rarr; Hubitat) work fine over MQTT right now. Writes (Hubitat &rarr; hub) don't, specifically because Hubitat's own MQTT client isn't delivering incoming messages properly on this platform, a reported platform-level bug, not a limitation of MQTT itself or of the X2 hub. Until it's fixed, fill in the webhook URL fields on X2 Activities, that gives you working on/off control today over the cloud. No changes needed on your end once the underlying bug is fixed, both directions will work over MQTT as originally intended."
        }
        section {
            paragraph "<b>FAQ</b>"
            paragraph "<b>What do buttons 11-20 mean? (X1S)</b><br>Buttons 1-10 fire when the remote sends a plain number. Buttons 11-20 are ten user-definable slots (set on the Remote device's Preferences tab) as matchString|Description pairs, matchString is whatever text you choose to send in step 2 above, Description is the friendly label (and what name-matching uses, see next). Most setups should just use 11-20, it's more flexible than remembering numbers."
            paragraph "<b>How does name matching work? (X1S)</b><br>Hubitat learns an activity changed by matching the button slot's Description (above) against this Activity's Name here, exactly, capitalization and spacing included. If they don't match, the remote still controls your gear, Hubitat just won't know the state changed."
            paragraph "<b>Where does the webhook URL go?</b><br>Into the Activity's Start/Stop Webhook URL fields, when adding it or later via its Edit button."
            paragraph "<b>My activity name has a space, will the webhook break?</b><br>No, spaces are encoded automatically before the call goes out."
            paragraph "<b>Does X1S need MQTT?</b><br>No, X1S never touches MQTT, local HTTP for reads, cloud webhook for writes."
            paragraph "<b>What actually generates MQTT traffic? (X2)</b><br>Only activity-level changes, starting or switching an activity, or the hub-wide power-off, produce any traffic. Plain remote commands within an activity (volume, channel, play/pause, anything IR or Bluetooth) never touch the network at all, that's a Sofabaton design choice. This integration can only ever react to whole activities changing, not individual button presses."
        }
    }
}

def appButtonHandler(String btn) {
    log.debug "appButtonHandler() received btn=${btn}"
    def bridge = getBridge()

    if (btn == "saveHubBtn") {
        state.hubSaveRequested = true
        return
    }
    if (btn == "cancelHubBtn") {
        state.remove("editingHubDni")
        state.remove("editHubPrefilled")
        state.hubPageCancelled = true
        return
    }
    if (btn.startsWith("editHub_")) {
        String dni = btn - "editHub_"
        state.editingHubDni = dni
        state.remove("editHubPrefilled")
        return
    }
    if (btn.startsWith("removeHub_")) {
        String dni = btn - "removeHub_"
        bridge?.removeRemoteDevice(dni)
        return
    }
    if (btn == "cancelActivityBtn") {
        state.activityPageCancelled = true
        return
    }
    if (btn.startsWith("editAct_")) {
        String rest = btn - "editAct_"
        def parts = rest.split("_", 2)
        if (parts.length == 2) {
            state.editingActivityKey = "${parts[0]}|${parts[1]}"
            state.remove("editActivityPrefilled")
        }
        return
    }
    if (btn.startsWith("removeAct_")) {
        String rest = btn - "removeAct_"
        def parts = rest.split("_", 2)
        if (parts.length == 2) {
            def hub = bridge?.getChildDevice(parts[0])
            hub?.removeActivityDeviceByDni(parts[1])
        }
        return
    }

    def hub = newActivityHub ? bridge?.getChildDevice(newActivityHub) : null
    if (btn == "saveActivityBtn") {
        state.activitySaveRequested = true
        return
    }
    if (!hub) return
    if (btn == "learnBtn") {
        bridge.startActivityLearn(hub.deviceNetworkId)
        state.learnStartedFor = newActivityHub
    }
    if (btn == "cancelLearnBtn") {
        bridge.cancelActivityLearn()
        state.remove("learnStartedFor")
    }
}

private void clearActivitySettings() {
    ["newActivityHub", "newActivityName", "newActivityUrlOn", "newActivityUrlOff", "newActivitySofabatonId"].each {
        app.removeSetting(it)
    }
}

private void createActivity(String hubDni, String name, String urlOn, String urlOff, Integer sofabatonActivityId) {
    def bridge = getBridge()
    def hub = bridge?.getChildDevice(hubDni)
    if (!hub) {
        log.error "Cannot add activity '$name': hub not found"
        return
    }
    def activity = hub.createActivityDevice(name, urlOn, urlOff, sofabatonActivityId)
    if (!activity) {
        log.error "Failed to create Activity device '$name'"
    }
}

// Applies edits to an already-existing Activity device in place: name,
// webhook URLs (either may be blank to clear), and for X2 the Sofabaton
// Activity ID. sofabatonActivityId is null when editing an X1S activity
// (that field doesn't apply there and is left untouched).
private void updateExistingActivity(def activity, String name, String urlOn, String urlOff, Integer sofabatonActivityId) {
    activity.setLabel(name)
    activity.updateSetting("webhookUrlOn", [value: urlOn ?: "", type: "text"])
    activity.updateSetting("webhookUrlOff", [value: urlOff ?: "", type: "text"])
    if (sofabatonActivityId != null) {
        activity.updateSetting("sofabatonActivityId", [value: sofabatonActivityId, type: "number"])
    }
    activity.updated()
}

// Mirrors RemoteDriver.groovy's ipToHex() so the app can compute a matching
// DNI at hub-creation time. Kept in sync manually -- if the driver's logic
// ever changes, this needs to change with it.
private String ipToHexForApp(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) return null
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2, "0").toUpperCase() }.join()
}
