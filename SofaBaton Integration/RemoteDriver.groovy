/*

	2026-09-10 jdthomas24  -- FORK, renamed "Sofabaton Remote"
		-Converted to a child device created by the Sofabaton Integration app
		 (via the Sofabaton Integration Bridge grouping device), so multiple
		 hubs nest together in the Devices list instead of standing alone.
		 IP is still entered/editable in this device's own preferences exactly
		 as before -- the app just pre-fills it and pre-computes the matching
		 DNI at creation time so local LAN routing works immediately.
		-Added remoteIp attribute so the parent app can read the configured
		 IP back without depending on preference access across app/device
		 boundaries.
		-Added createActivityDevice()/removeActivityDevice(), called by the
		 app, so each hub can own its own "Sofabaton Activity" child devices
		 (nested one level deeper, same pattern as Camera/Doorbell nesting
		 under a Reolink Device Bridge).
		-Added handleActivityStateSync(), called from firePushed(): when the
		 physical remote fires an activity whose label matches a configured
		 Activity child's name, that child is set on (via its local-only
		 syncOn(), no cloud call) and any other Activity child under this
		 same hub that was on gets set off the same way -- mirrors Harmony's
		 one-activity-at-a-time behaviour without assuming the hub sends an
		 explicit "stopped" signal, since it doesn't.
		-NOTE: this is a fork of dJOS's driver, not a from-scratch rewrite.
		 Full credit above retained. Worth reaching out to dJOS as a
		 courtesy before public release, same community norm dJOS followed
		 crediting mike.maxwell/Gassgs/SViel.

	*OVERVIEW
	 This driver allows a Sofabaton X Series remote to trigger Hubitat automations.
	 When a Sofabaton activity is started or stopped on the remote, it sends a value
	 in the request body to this driver via a local HTTP PUT request. That value is mapped
	 to a button press which can then trigger any Hubitat rule or automation.

	 The driver supports three types of input:
	   -on/off: fires a switch event (reserved, always active)
	   -Numeric (1-10): fires a pushed event with the number; use Button Labels in
	    preferences to document what each number represents
	   -User definable (10 slots): match any string the remote sends to a named button;
	    configure the match string and a friendly label in preferences. Slot 1 fires
	    button 11, slot 2 fires button 12, and so on through slot 10 / button 20

	 Every input fires a numeric pushed event, since Hubitat's PushableButton capability
	 defines pushed as a number. To trigger on the string itself instead, use the
	 lastButtonValue attribute (the raw text from the remote) or lastButtonLabel (your
	 configured description) as a Custom Attribute trigger in Rule Machine.

	 If a fired button's resolved label matches the name of a "Sofabaton Activity"
	 child device (added via the parent app), that Activity device's state is kept
	 in sync automatically -- see handleActivityStateSync() below.

	 NOTE: This is one-way communication - remote to Hubitat only.
	 To trigger a Sofabaton activity FROM Hubitat, use the matching "Sofabaton
	 Activity" child device's on()/off() commands, which call Sofabaton's cloud
	 API webhook for that activity.

	*COMPATIBLE HARDWARE
	 X1S (minimum supported), X2

	*HUBITAT CONFIGURATION
	 -Set a static DHCP reservation for the Sofabaton hub
	 -Enter that reserved IP address in this driver's Remote IP Address preference
	  (pre-filled by the parent app when the hub is added)

	*SOFABATON APP CONFIGURATION
	 -In the Sofabaton app, go to Devices and tap Add Device, then select Wi-Fi
	 -Tap the link at the bottom: "Create a virtual device for IP control"
	 -Enter the URL:  http://[your Hubitat IP]:39501/
	 -Set the request method to PUT
	 -Leave Content Type and Additional Headers blank
	 -In the Body field, enter either:
	    -A number 1-10 for a numeric button, or 11-20 for a user definable button
	    -Any string (e.g. "watchTV") matching the Match String configured in this
	     driver's preferences (case insensitive)
	    -on or off to set this device's switch state
	 -Repeat for each activity using a unique value each time


*/

def version() {
    return "1.0.0-jdthomas24"
}

metadata {
    definition (name: "Sofabaton Remote", namespace: "jdthomas24", author: "Jason Thomas (fork of dJOS/mike.maxwell/Gassgs/SViel)", importUrl: "") {
        capability "Actuator"
        capability "PushableButton"
        capability "Switch"
        attribute "lastButtonValue", "string"
        attribute "lastButtonLabel", "string"
        attribute "remoteIp", "string"
        preferences {
            input name: "deviceInfo", type: "paragraph", element: "paragraph", title: "Sofabaton Remote", description: "Driver Version: ${version()}<br>Compatible Hardware: X1S and above"
            input name: "appConfig", type: "paragraph", element: "paragraph", title: "Sofabaton App Configuration", description: "1. In the Sofabaton app, go to Devices and tap Add Device, then select Wi-Fi<br>2. Tap the link at the bottom: 'Create a virtual device for IP control'<br>3. Enter the URL: http://[your Hubitat IP]:39501/<br>4. Set the request method to PUT<br>5. Leave Connect Type and Additional Headers blank<br>6. In the Body field enter either:<br>&nbsp;&nbsp;&nbsp;- A number 1-10 for a numeric button, or 11-20 for a user definable button<br>&nbsp;&nbsp;&nbsp;- Any string (e.g. watchTV) matching a user definable slot<br>&nbsp;&nbsp;&nbsp;- on or off to set this device's switch state<br>7. Repeat for each activity using a unique value each time"
            input name:"ip", type:"text", title: "Remote IP Address"
            input name: "userInfo", type: "paragraph", element: "paragraph", title: "User Definable Buttons", description: "Enter the match string the remote sends. Optionally add a pipe | followed by a description e.g. watchTV|Watch TV. The match string must match what you entered in the remote app.<br>These fire button numbers 11-20 (User 1 = button 11, User 10 = button 20). You can also trigger rules on the lastButtonValue or lastButtonLabel custom attributes if you prefer matching the string itself.<br><br>If a slot's description matches the name of a Sofabaton Activity child device (added via the parent app), that Activity device's state is kept in sync automatically."
            input name:"usrBtn1", type:"text", title:"User 1 (11):", description:"matchString|Description", required:false
            input name:"usrBtn2", type:"text", title:"User 2 (12):", description:"matchString|Description", required:false
            input name:"usrBtn3", type:"text", title:"User 3 (13):", description:"matchString|Description", required:false
            input name:"usrBtn4", type:"text", title:"User 4 (14):", description:"matchString|Description", required:false
            input name:"usrBtn5", type:"text", title:"User 5 (15):", description:"matchString|Description", required:false
            input name:"usrBtn6", type:"text", title:"User 6 (16):", description:"matchString|Description", required:false
            input name:"usrBtn7", type:"text", title:"User 7 (17):", description:"matchString|Description", required:false
            input name:"usrBtn8", type:"text", title:"User 8 (18):", description:"matchString|Description", required:false
            input name:"usrBtn9", type:"text", title:"User 9 (19):", description:"matchString|Description", required:false
            input name:"usrBtn10", type:"text", title:"User 10 (20):", description:"matchString|Description", required:false
            input name: "numericInfo", type: "paragraph", element: "paragraph", title: "Numeric Buttons", description: "Labels for buttons triggered by a number (1-10) in the request body."
            input name:"btnLabel1", type:"text", title:"1:", description:"Button 1 label", required:false
            input name:"btnLabel2", type:"text", title:"2:", description:"Button 2 label", required:false
            input name:"btnLabel3", type:"text", title:"3:", description:"Button 3 label", required:false
            input name:"btnLabel4", type:"text", title:"4:", description:"Button 4 label", required:false
            input name:"btnLabel5", type:"text", title:"5:", description:"Button 5 label", required:false
            input name:"btnLabel6", type:"text", title:"6:", description:"Button 6 label", required:false
            input name:"btnLabel7", type:"text", title:"7:", description:"Button 7 label", required:false
            input name:"btnLabel8", type:"text", title:"8:", description:"Button 8 label", required:false
            input name:"btnLabel9", type:"text", title:"9:", description:"Button 9 label", required:false
            input name:"btnLabel10", type:"text", title:"10:", description:"Button 10 label", required:false
            input name:"logEnable", type: "bool", title: "Enable debug logging", defaultValue: false
            input name:"txtEnable", type: "bool", title: "Enable descriptionText logging", defaultValue: true
        }
    }
}

void logsOff(){
    log.warn "debug logging disabled..."
    device.updateSetting("logEnable",[value:"false",type:"bool"])
}

void installed(){
    log.info "installed..."
    updated()
}

void updated(){
    log.info "updated..."
    log.warn "debug logging is: ${logEnable == true}"
    log.warn "description logging is: ${txtEnable == true}"
    if (logEnable) runIn(1800,logsOff)
    sendEvent(name:"numberOfButtons", value:20)
    // Truncate numeric button labels to 40 chars
    for (int i = 1; i <= 10; i++) {
        def lbl = settings["btnLabel${i}"] ?: ""
        if (lbl.length() > 40) {
            device.updateSetting("btnLabel${i}", [value:lbl.take(40), type:"text"])
        }
    }
    // Truncate user definable button entries to 80 chars (match + pipe + description)
    for (int i = 1; i <= 10; i++) {
        def val = settings["usrBtn${i}"] ?: ""
        if (val.length() > 80) {
            device.updateSetting("usrBtn${i}", [value:val.take(80), type:"text"])
        }
    }
    validateUserButtons()
    // Set the DNI last so a malformed IP cannot prevent any of the above from running
    if (ip) {
        String dni = ipToHex(ip)
        if (dni) device.deviceNetworkId = dni
        sendEvent(name:"remoteIp", value: ip)
    }
}

// Warns about user definable slots that can never fire, so silent misconfiguration
// shows up in the logs at save time rather than as a mystery later
private void validateUserButtons() {
    def seen = [:]
    for (int i = 1; i <= 10; i++) {
        def val = settings["usrBtn${i}"] ?: ""
        if (!val) continue
        String match = val.split(/\|/, 2)[0].trim()
        String slot = "User ${i} (${i + 10})"
        if (!match) {
            log.warn "$slot has a description but no match string, it will never fire"
            continue
        }
        if (match.equalsIgnoreCase("on") || match.equalsIgnoreCase("off")) {
            log.warn "$slot match string '$match' is reserved for the switch state and will never fire this button"
        }
        Integer n = toButtonNumber(match)
        if (n != null && n >= 1 && n <= 20) {
            log.warn "$slot match string '$match' is a plain number and will fire button $n instead"
        }
        String key = match.toLowerCase()
        if (seen[key]) log.warn "$slot match string '$match' duplicates User ${seen[key]}, only the first will fire"
        else seen[key] = i
    }
}

void parse(String description) {
    def msg = parseLanMessage(description)
    if (logEnable) log.debug "String is: $msg"
    if (logEnable) log.debug "String Header is: $msg.header"
    if (logEnable) log.debug "String Body is: $msg.body"
    def data = msg.body?.trim()
    if (!data) {
        if (logEnable) log.warn "$device.label Empty body received, ignoring"
        return
    }

    // 1. Check for on/off switch commands
    if (data.equalsIgnoreCase("on")) {
        sendEvent(name:"switch", value:"on")
        return
    } else if (data.equalsIgnoreCase("off")) {
        sendEvent(name:"switch", value:"off")
        return
    }

    // 2. Check if body is a button number (1-10 numeric, 11-20 user definable)
    Integer btn = toButtonNumber(data)
    if (btn != null && btn >= 1 && btn <= 20) {
        firePushed(btn, labelForButton(btn), data)
        return
    }

    // 3. Check against user definable match strings -> buttons 11-20
    def hit = matchUserButton(data)
    if (hit) {
        firePushed(hit[0] as Integer, hit[1] as String, data)
        return
    }

    // 4. No match found
    log.warn "$device.label No match found for received body value: $data"
}

// Fires the numeric pushed event required by PushableButton, plus the string
// attributes so rules can trigger on the raw value or the friendly label.
// Also drives Activity child state sync -- see handleActivityStateSync().
private void firePushed(Integer btn, String lbl, String raw) {
    if (txtEnable) log.info "$device.label Button $btn${lbl ? ' (' + lbl + ')' : ''} Pushed"
    sendEvent(name:"pushed", value:btn, isStateChange: true, descriptionText:"$device.label button $btn was pushed")
    sendEvent(name:"lastButtonValue", value:raw, isStateChange: true)
    sendEvent(name:"lastButtonLabel", value:(lbl ?: raw), isStateChange: true)
    handleActivityStateSync(lbl ?: raw)
}

// Coerces a button number from a String, Integer or BigDecimal ("11", "11.0", 11).
// Returns null if the value is not a whole number.
private Integer toButtonNumber(def val) {
    String s = val?.toString()?.trim()
    if (!s) return null
    if (s.isInteger()) return s.toInteger()
    if (s.isBigDecimal() && s.toBigDecimal().stripTrailingZeros().scale() <= 0) {
        return s.toBigDecimal().intValue()
    }
    return null
}

// Label for any button 1-20: numeric labels for 1-10, user descriptions for 11-20.
private String labelForButton(Integer btn) {
    if (btn <= 10) return settings["btnLabel${btn}"] ?: ""
    def val = settings["usrBtn${btn - 10}"] ?: ""
    if (!val) return ""
    def parts = val.split(/\|/, 2)
    return parts.size() > 1 ? parts[1].trim() : parts[0].trim()
}

// Returns [button, label] for a user definable slot matching str, or null.
private List matchUserButton(String str) {
    for (int i = 1; i <= 10; i++) {
        def val = settings["usrBtn${i}"] ?: ""
        if (!val) continue
        def parts = val.split(/\|/, 2)
        def match = parts[0].trim()
        if (match && match.equalsIgnoreCase(str)) {
            return [i + 10, parts.size() > 1 ? parts[1].trim() : match]
        }
    }
    return null
}

void push(data) {
    String str = data?.toString()?.trim() ?: ""

    // Numeric buttons: 1-10 are the numeric slots, 11-20 the user definable ones
    Integer btn = toButtonNumber(str)
    if (btn != null) {
        if (btn < 1 || btn > 20) {
            log.warn "$device.label Button $btn is out of range (1-20), ignoring"
            return
        }
        firePushed(btn, labelForButton(btn), str)
        return
    }

    // String input: resolve it to its user definable button number
    def hit = matchUserButton(str)
    if (hit) {
        firePushed(hit[0] as Integer, hit[1] as String, str)
    } else {
        log.warn "$device.label No user definable button matches '$str', ignoring"
    }
}

void on() {
    if (txtEnable) log.info "$device.label Switch On"
    sendEvent(name:"switch", value:"on")
}

void off() {
    if (txtEnable) log.info "$device.label Switch Off"
    sendEvent(name:"switch", value:"off")
}

// Returns the hex DNI for an IPv4 address, or null (with an error logged) if malformed
String ipToHex(String ipAddress) {
    List<String> quad = (ipAddress ?: "").trim().split(/\./)
    boolean valid = quad.size() == 4 && quad.every {
        it.isInteger() && it.toInteger() >= 0 && it.toInteger() <= 255
    }
    if (!valid) {
        log.error "$device.label Remote IP Address '${ipAddress}' is not a valid IPv4 address - the remote will not be able to reach this device"
        return null
    }
    return quad.collect { Integer.toHexString(it.toInteger()).padLeft(2,"0").toUpperCase() }.join()
}

// ============================================================
// ============= ACTIVITY CHILD MANAGEMENT (jdthomas24) =========
// ============================================================

// Called by the parent app when an activity is added for this hub.
// Sets webhookUrlOn/webhookUrlOff directly so the Activity device is
// immediately usable without a manual preferences visit.
def createActivityDevice(String name, String urlOn, String urlOff = null) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def existing = getChildDevice(dni)
    if (existing) return existing
    def child = addChildDevice("jdthomas24", "Sofabaton Activity", dni, [label: name])
    if (child) {
        child.updateSetting("webhookUrlOn", [value: urlOn, type: "text"])
        if (urlOff) child.updateSetting("webhookUrlOff", [value: urlOff, type: "text"])
        child.updated()
    }
    return child
}

void removeActivityDevice(String name) {
    String dni = "${device.deviceNetworkId}-activity-${name.replaceAll(/[^A-Za-z0-9]/, '')}"
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

// If the fired button's resolved label matches a Sofabaton Activity child's
// name, mark it on (local sync only, no cloud call) and mark any other
// Activity child under THIS hub that was on as off the same way. Mirrors
// Harmony's one-activity-at-a-time behaviour without assuming the remote
// sends an explicit "stopped" event for the previous activity -- it doesn't,
// this driver only ever learns about the activity that just started.
private void handleActivityStateSync(String activityKey) {
    if (!activityKey) return
    def activityChildren = getChildDevices()?.findAll { it.typeName == "Sofabaton Activity" }
    if (!activityChildren) return

    def matched = activityChildren.find { it.getLabel()?.equalsIgnoreCase(activityKey) }
    if (!matched) {
        if (logEnable) log.debug "$device.label: no Activity device matches '$activityKey', skipping state sync"
        return
    }

    activityChildren.findAll { it.deviceNetworkId != matched.deviceNetworkId && it.currentValue("switch") == "on" }.each {
        it.syncOff()
    }
    matched.syncOn()
}

