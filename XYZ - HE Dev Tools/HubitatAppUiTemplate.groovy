/**
 * Hubitat App UI Template
 * Updated: Oct 30, 2026, from Reolink Integration 1.6.17 and Sofabaton 1.1.6 (UI design by gopher.ny)
 * See Hubitat-App-UI-Style-Guide.md in this folder for the layout rules behind it.
 *
 * Drop-in pieces:
 *   1. Status banner, no gap above it, stacks on phones (Reolink 1.6.17)
 *   2. Two-column main page, 62/38, stacks under 1000px
 *   3. Dark mode: hub theme classes and var() tokens, plus legacyLightThemeCss() for pre-2.5.3 firmware (Reolink 1.6.13)
 *   4. Link-only navigation for Add/Edit pages: Cancel and Save are links to mainPage, Save carries a
 *      one-time token so a reload can't save twice, Hubitat's Done is hidden there (Reolink 1.6.16, Sofabaton 1.1.5)
 *   5. Logging details popup, Help & Support row, version footer
 *   6. Tips & Troubleshooting page (sidebar nav + article cards)
 *
 * To use: copy into your app, set the constants, add page(name: "addItemPage") and page(name: "tipsPage")
 * to preferences, rename the "app-" classes if you want them app-specific, and fill in the TODOs.
 * Rules: pages only link to each other, never draw another page in place. Opening a page never creates
 * or saves anything. No required:true on Add/Edit inputs (browser validation blocks Cancel).
 */

import groovy.transform.Field

// TODO: per-app values
@Field static final String APP_NAME = "My Integration"
@Field static final String APP_VERSION = "1.0.0"
@Field static final String COMMUNITY_URL = "https://community.hubitat.com/t/your-thread"
@Field static final String COFFEE_URL = "https://www.paypal.com/paypalme/jdthomas24?locale.x=en_US&country.x=US"
@Field static final String DEFAULT_TIP_TOPIC = "start"
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]

// ============================================================================
// Main page: banner, two columns, Help & Support, footer
// ============================================================================

def mainPage(params = null) {
    // Save on Add/Edit is a link here; the one-time token stops a reload from saving twice.
    String notice = null
    if (params?.formAction && params?.token && params.token == state.formToken) {
        state.remove("formToken")
        if (params.formAction == "saveItem") notice = saveItemForm()
    }
    clearItemForm()   // coming back here ends any Add/Edit in progress

    int itemCount = 0     // TODO: e.g. sources, hubs, accounts
    int deviceCount = getChildDevices()?.size() ?: 0
    boolean ok = itemCount > 0

    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section(sectionClass: "app-main-overview") {
            paragraph rawHtml: true, statusBannerHtml(ok,
                ok ? "Integration ready" : "Setup required",
                ok ? "${countText(itemCount, 'item')} configured &middot; ${countText(deviceCount, 'device')} created" : "Add an item to get started")
            if (notice) paragraph rawHtml: true, warningMessageHtml(notice)
        }
        section(sectionClass: "app-ui-links app-main-content") {
            paragraph rawHtml: true, columnHeader("Items")   // TODO
            if (!itemCount) paragraph rawHtml: true, emptyStateHtml("No items yet", "Add one below to get started.")
            // TODO: one href per item, e.g. Edit links carry the item in params:
            // href name: "editItem_${id}", title: "Edit", description: "", page: "addItemPage", params: [editId: id], width: 3
            href name: "addItem", title: "<span><i class='fa-regular fa-plus mr-2'></i>Add item</span>",
                description: "What this adds", page: "addItemPage", width: 12, style: "margin:8px;"
        }
        section(sectionClass: "app-ui-links app-main-settings") {
            paragraph rawHtml: true, columnHeader("Quick Settings")
            input "logLevel", "enum", title: "Log level", options: LOG_LEVELS,
                defaultValue: "Errors Only", submitOnChange: true, width: 12,
                style: "margin-left: 0.5em; margin-right: 0.5em; margin-top: 0; padding-right:0;"
            paragraph rawHtml: true, loggingDetailsPopupHtml()
        }
        helpAndSupportSection()
        versionFooterSection()
    }
}

// ============================================================================
// Add/Edit page: link-only navigation
// ============================================================================

def addItemPage(params = [:]) {
    // Edit links pass the item in params; a redraw with the same params keeps the user's edits.
    if (params?.editId && params.editId != state.editingItemId) {
        state.editingItemId = params.editId
        state.remove("editItemPrefilled")
    }
    def editing = state.editingItemId ? findItem(state.editingItemId) : null
    if (editing && !state.editItemPrefilled) {
        app.updateSetting("newItemName", [value: editing.name, type: "text"])   // TODO: prefill each field
        state.editItemPrefilled = true
    }
    List missing = itemMissing()

    dynamicPage(name: "addItemPage", title: editing ? "Edit ${editing.name}" : "Add an item", install: false, uninstall: false) {
        section {
            paragraph rawHtml: true, HIDE_DONE_CSS
            input "newItemName", "text", title: "Name", submitOnChange: true, width: 6   // no required:true
            // TODO: other fields
            if (state.editingItemId && !editing) paragraph rawHtml: true, warningMessageHtml("This item no longer exists.")
            else if (missing) paragraph "<span class='text-color-secondary'>Still needed: ${missing.join(', ')}</span>"
        }
        section {
            formButtons("Item", editing ? "Save" : "Add", !missing && !(state.editingItemId && !editing))
        }
    }
}

// Cancel goes to mainPage, which clears the form. Save links to mainPage only when the form is complete;
// until then it's gray and just redraws this page.
private void formButtons(String kind, String saveTitle, boolean ready) {
    href name: "appCancel${kind}", title: "Cancel", description: "", page: "mainPage", width: 3
    if (ready) {
        if (!state.formToken) state.formToken = now().toString()
        href name: "appSave${kind}Ready", title: saveTitle, description: "", page: "mainPage",
            params: [formAction: "save${kind}", token: state.formToken], width: 3
    } else {
        href name: "appSave${kind}", title: saveTitle, description: "", page: "add${kind}Page", width: 3
    }
}

// Runs on mainPage after Save. Returns an error to show there, or null when saved.
private String saveItemForm() {
    if (state.editingItemId && !findItem(state.editingItemId)) return "Not saved: that item no longer exists."
    List missing = itemMissing()
    if (missing) return "Not saved. Still needed: ${missing.join(', ')}."
    // TODO: create or update the item
    return null
}

private List itemMissing() {
    List m = []
    if (!newItemName) m << "Name"   // TODO
    return m
}

private void clearItemForm() {
    ["newItemName"].each { app.removeSetting(it) }   // TODO: every Add/Edit field
    ["editingItemId", "editItemPrefilled"].each { state.remove(it) }
}

private Map findItem(id) { null }   // TODO

// Add/Edit pages: Hubitat's Done would leave without saving, so only Cancel and Save are shown.
@Field static final String HIDE_DONE_CSS = """<style>
  #fieldsetAppButtons { display: none !important; }
  button.hrefElem[name^='_action_href_appCancel'], button.hrefElem[name^='_action_href_appSave'] {
    width: auto !important; min-width: 140px; min-height: 0 !important; height: auto !important;
    display: inline-block !important; padding: 8px 20px !important; margin: 4px 12px 4px 0 !important;
    border: 1px solid transparent !important; border-radius: 4px !important; box-shadow: none !important;
    font-size: 15px !important; font-weight: 600 !important; text-align: center !important; line-height: 1.4 !important;
    cursor: pointer; transition: background 0.15s, border-color 0.15s, box-shadow 0.15s, transform 0.15s; }
  button.hrefElem[name^='_action_href_appCancel'] { background: var(--hubitat-control-background, #f1f3f5) !important; border-color: var(--hubitat-control-border, #d0d7de) !important; }
  button.hrefElem[name^='_action_href_appCancel']:hover { background: var(--hubitat-control-hover-background, #eaf2fc) !important; border-color: var(--hubitat-link-color, #1565c0) !important; }
  button.hrefElem[name^='_action_href_appCancel']:hover span { color: var(--hubitat-link-color, #1565c0) !important; }
  button.hrefElem[name^='_action_href_appSave'] { background: var(--hubitat-control-background, #bdbdbd) !important; border-color: var(--hubitat-control-background, #bdbdbd) !important; opacity: .6; }
  button.hrefElem[name^='_action_href_appSave']:not([name*='Ready']) { cursor: not-allowed !important; }
  button.hrefElem[name^='_action_href_appSave'][name*='Ready'] { background: var(--hubitat-brand-background, #81bc00) !important; border-color: var(--hubitat-brand-background, #81bc00) !important; opacity: 1; }
  button.hrefElem[name^='_action_href_appSave'][name*='Ready']:hover { filter: brightness(.92); box-shadow: 0 2px 6px rgba(0,0,0,0.25) !important; transform: translateY(-1px); }
  button.hrefElem[name^='_action_href_appCancel']::before, button.hrefElem[name^='_action_href_appSave']::before,
  button.hrefElem[name^='_action_href_appCancel']::after, button.hrefElem[name^='_action_href_appSave']::after { display: none !important; }
  button.hrefElem[name^='_action_href_appCancel'] span, button.hrefElem[name^='_action_href_appSave'] span { color: var(--hubitat-control-text, #333) !important; font-size: 15px !important; }
  button.hrefElem[name^='_action_href_appSave'][name*='Ready'] span { color: var(--hubitat-on-brand, #fff) !important; }
  button.hrefElem[name^='_action_href_appCancel'] :is(.state-incomplete-text, .state-complete-text),
  button.hrefElem[name^='_action_href_appSave'] :is(.state-incomplete-text, .state-complete-text) { display: none !important; }
  .mdl-cell:has(> button.hrefElem:is([name^='_action_href_appCancel'], [name^='_action_href_appSave'])) {
    width: auto !important; flex: 0 0 auto !important; background: transparent !important; border: 0 !important; padding: 0 !important; }
</style>"""

// ============================================================================
// Help & Support row + version footer
// ============================================================================

/** Three equal cards. The href must stay named "tips" for tipsCardCss() to style it. */
private void helpAndSupportSection() {
    section(title: "<b>Help & Support</b>", sectionClass: "app-ui-links app-main-support") {
        href name: "tips", title: "<i class='pi pi-info-circle' aria-hidden='true'></i>Tips & Troubleshooting",
            page: "tipsPage", description: "Known quirks and setup guidance", width: 4, style: "margin:8px;"
        paragraph rawHtml: true, supportLinkHtml(COMMUNITY_URL, "pi pi-comments",
            "Hubitat Community Thread", "Questions, feedback, and release notes"), width: 4
        paragraph rawHtml: true, supportLinkHtml(COFFEE_URL, "fa-solid fa-mug-hot",
            "Buy Me a Coffee", "Support development"), width: 4
    }
}

private void versionFooterSection() {
    section {
        paragraph "<div class='text-center text-color-secondary text-xs mt-2'>${APP_NAME} v${APP_VERSION}</div>"
    }
}

private String supportLinkHtml(String url, String iconClass, String title, String subtitle) {
    """
<a href='${url}' target='_blank' rel='noopener noreferrer'
   class='flex align-items-center gap-3 border-1 surface-border border-round px-3 py-2 text-color no-underline'>
  <i class='${iconClass} hub-text-link text-xl flex-shrink-0'></i>
  <span class='min-w-0'>
    <span class='block hub-text-link font-semibold'>${title}</span>
    <span class='block text-color-secondary mt-1' style='font-size:14px;'>${subtitle}</span>
  </span>
</a>
"""
}

// ============================================================================
// Status banner (also carries the main-page layout CSS)
// ============================================================================

private String statusBannerHtml(boolean ok, String title, String summary) {
    String tone = ok ? "p-message p-message-success app-message" : "p-message p-message-warn app-message"
    String icon = ok ? "pi pi-check-circle hub-text-success" : "fa-solid fa-exclamation-triangle hub-text-warning"
    """
<style>
  ${appPageSpacingCss()}
  ${tipsCardCss()}
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
  .app-main-support button.hrefElem[name^='_action_href_tips'] { min-height: 61.5px; padding-bottom: 13.5px; box-sizing: border-box; }
  @media (max-width: 1000px) {
    .app-main-content, .app-main-settings { float: none; width: 100%; border-left: 0; padding-left: 0; }
  }
  @media (max-width: 37.5rem) {
    .app-main-summary { flex-direction: column; align-items: flex-start !important; }
  }
</style>
<div class='app-main-summary flex align-items-center justify-content-between gap-3 ${tone} border-round p-3'>
  <div class='flex align-items-center gap-3 min-w-0'>
    <div class='flex-shrink-0'><i class='${icon} text-xl' aria-hidden='true'></i></div>
    <div class='min-w-0'>
      <div class='app-heading font-semibold'>${title}</div>
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

/** Neutral outlined tile for an empty list (style guide section 5). */
private String emptyStateHtml(String title, String detail) {
    """
<div class='border-1 surface-border border-round p-3 text-color-secondary'>
  <div class='font-semibold text-color mb-1'>${title}</div>
  <div class='text-sm'>${detail}</div>
</div>
"""
}

private String countText(int count, String singular) {
    "${count} ${singular}${count == 1 ? '' : 's'}"
}

// ============================================================================
// Logging details popup (Hubitat's native dialog)
// ============================================================================

private String loggingDetailsPopupHtml() {
    String current = LOG_LEVELS.contains(logLevel) ? logLevel : "Errors Only"
    String details = """
<div class='app-dialog text-left text-color' style='max-width:520px;line-height:1.45;'>
  <div class='text-xl font-bold mb-1'>Logging levels</div>
  <div class='text-color-secondary text-base mb-3'>Choose how much activity appears in the app logs.</div>
  ${logLevelDetailHtml("Errors Only", "Warnings and errors only.", current)}
  ${logLevelDetailHtml("Normal", "Errors, plus meaningful events and changes.", current)}
  ${logLevelDetailHtml("Full", "Everything, including routine activity. Use while troubleshooting.", current)}
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
    String tone = name == "Errors Only" ? "p-tag p-tag-danger" : name == "Full" ? "p-tag p-tag-warning" : "p-tag p-tag-info"
    // TODO: if Full auto-reverts in your app, add a warningMessageHtml() note under Full saying when.
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

// ============================================================================
// Tips & Troubleshooting page
// ============================================================================

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

/**
 * Topic fields: id, label (sidebar), title (card header), group (sidebar section), icon (PrimeIcons "pi-*"),
 * body (HTML, paragraphs in <p>). Optional: checklist [[title, detail]], warning (HTML), append (another topic id
 * shown below). List order = sidebar order; groups appear in first-seen order.
 */
private List tipsTopics() {
    [
        // TODO: replace with this app's content
        [id: "start", label: "Before you begin", title: "Before you begin", group: "Getting started", icon: "pi-sitemap",
            body: "<p>Short explanation of the most common setup requirement.</p>",
            checklist: [[title: "Requirement one", detail: "Where to enable it."],
                        [title: "Requirement two", detail: "Where to enable it."]],
            warning: "<b>These are often off by default.</b><br>Check them before troubleshooting.",
            append: "basics"],
        [id: "basics", label: "Basics", title: "How it works", group: "Getting started", icon: "pi-info-circle",
            body: "<p>One or two paragraphs on core concepts.</p>"],
        [id: "removal", label: "Device removal", title: "Remove devices safely", group: "Devices", icon: "pi-trash",
            body: "<p>Remove devices from inside the app, not Hubitat's Devices page.</p>"],
        [id: "logging", label: "Log levels", title: "Log levels", group: "Logging", icon: "pi-file",
            body: "<p>" + logLevelPill("Errors Only") + " Default. Warnings and errors only.</p>" +
                "<p>" + logLevelPill("Normal") + " Errors plus meaningful events.</p>" +
                "<p>" + logLevelPill("Full") + " Everything. Use while troubleshooting.</p>"]
    ]
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

private String logLevelPill(String level) {
    def tones = ["Errors Only": "p-tag p-tag-danger", "Normal": "surface-ground text-color-secondary", "Full": "p-tag p-tag-warning"]
    "<span class='inline-block ${tones[level] ?: tones['Normal']} font-bold text-xs px-2 py-1 border-round-xl'>${level}</span>"
}

// ============================================================================
// Shared styling helpers
// ============================================================================

/** Warning callout; icon and text in separate columns so wrapped text stays aligned. */
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

/** Matches Hubitat's native href secondary text size; also carries the legacy theme fallback. */
private String tileSecondaryTextCss() {
    legacyLightThemeCss() + "#formApp .hrefElem > .state-incomplete-text, #formApp .hrefElem > .state-complete-text { font-size: 14px !important; }"
}

/** Styles any href named "tips*" as an outlined card with a left icon. */
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

/**
 * Hub firmware 2.5.3+ marks the page with data-hubitat-app-ui-semantics="1" and styles the hub-text-*,
 * app-ui-button*, app-ui-links and app-ui-navigation classes itself. Older firmware gets these scoped fallbacks.
 */
private String legacyLightThemeCss() {
    String L = ':root:not(:has([data-hubitat-app-ui-semantics="1"])) :is(#formApp, .app-dialog)'
    """
  ${L} .hub-text-link { color: var(--hubitat-link-color, #1565c0) !important; }
  ${L} .hub-text-success { color: var(--hubitat-success-color, #356700) !important; }
  ${L} .hub-text-warning { color: var(--hubitat-warning-color, #8a5a00) !important; }
  ${L} .hub-text-danger { color: var(--hubitat-validation-color, #b71c1c) !important; }
  ${L} .app-ui-links .hrefElem { color: var(--hubitat-link-color, #1565c0) !important; }
  ${L} .app-ui-links .hrefElem > .state-incomplete-text,
  ${L} .app-ui-links .hrefElem > .state-complete-text { color: var(--text-color-secondary, #616161); }
  ${L} button.mdl-button.app-ui-button { background: var(--hubitat-control-background, rgba(158,158,158,.2)); color: var(--hubitat-control-text, #363636); border: 1px solid transparent; border-radius: 4px; box-shadow: 0 2px 2px rgba(0,0,0,.14); }
  ${L} button.mdl-button.app-ui-button:enabled:hover { background: var(--hubitat-control-hover-background, #cacfdc); }
  ${L} button.mdl-button.app-ui-button-primary,
  ${L} button.mdl-button.app-ui-button-primary:enabled:hover { background: var(--hubitat-brand-background, #81bc00); color: var(--hubitat-on-brand, #fff); }
  ${L} button.mdl-button.app-ui-button-secondary { background: var(--surface-card, #fff); color: var(--hubitat-link-color, #1565c0); border-color: var(--hubitat-control-border, #b0bec5); box-shadow: none; }
  ${L} button.mdl-button.app-ui-button-danger,
  ${L} button.mdl-button.app-ui-button-danger:enabled:hover { background: var(--hubitat-danger-background, #d32f2f); color: var(--hubitat-on-danger, #fff); }
  ${L} button.mdl-button.app-ui-button:enabled:active { box-shadow: none; }
  ${L} button.mdl-button.app-ui-button:disabled { background: var(--hubitat-control-background, #eee); color: var(--text-color-secondary, #616161); opacity: .6; cursor: not-allowed; box-shadow: none; }
  ${L} .app-ui-navigation .hrefElem { background: transparent; color: var(--hubitat-link-color, #1565c0) !important; border: 0; border-left: 3px solid transparent; border-radius: 0; box-shadow: none; padding: 12px; font-family: inherit; font-size: 16px; }
  ${L} .app-ui-navigation .hrefElem::before { display: none; }
  ${L} .app-ui-navigation .hrefElem:not(:disabled):not(:has(.app-ui-navigation-selected)):hover { background: var(--surface-hover, #f3f6fa); }
  ${L} .app-ui-navigation .hrefElem:has(.app-ui-navigation-selected) { background: var(--hubitat-selection-background, #eaf2fc); border-left-color: var(--hubitat-link-color, #1565c0); font-weight: 600; }
  ${L} .app-ui-navigation .hrefElem:disabled { opacity: .6; cursor: not-allowed; }
  ${L} button.mdl-button.app-ui-button:focus-visible,
  ${L} .app-ui-navigation .hrefElem:focus-visible { outline: 2px solid var(--hubitat-link-color, #1565c0); outline-offset: 2px; }
"""
}
