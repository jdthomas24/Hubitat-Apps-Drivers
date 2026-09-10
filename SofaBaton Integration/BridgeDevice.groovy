/*
    Sofabaton Integration Bridge
    Copyright 2026 Jason Thomas. All Rights Reserved

    2026-09-10 jdthomas24
        -Initial publication
        -Lightweight nesting-only device; no protocol logic. Holds "Sofabaton
         Remote" devices as children so multiple hubs group/collapse under
         one entry in the Devices list, mirroring the role
         ReolinkStandaloneDevices.groovy plays in the Reolink Integration.

    *OVERVIEW
     Pure grouping anchor for the Sofabaton Integration. The parent app
     creates exactly one of these on install. Each physical Sofabaton hub
     added via the app becomes a "Sofabaton Remote" child device of THIS
     device (not of the app directly), so hubs nest and collapse together
     in the Devices list. This device has no commands or attributes of its
     own beyond Actuator -- all real logic lives in the Remote and Activity
     drivers.
*/

def version() { return "1.0.0" }

metadata {
    definition (name: "Sofabaton Integration Bridge", namespace: "jdthomas24", author: "Jason Thomas") {
        capability "Actuator"
    }
}

void installed() {
    log.info "Sofabaton Integration Bridge installed"
}

void updated() {
    // Nothing to configure; grouping anchor only
}

void uninstalled() {
    getChildDevices()?.each { child ->
        try {
            deleteChildDevice(child.deviceNetworkId)
        } catch (e) {
            log.warn "Failed to remove child ${child.displayName} on uninstall: ${e.message}"
        }
    }
}

// Called by the parent app when a hub is added. dni must already be the
// ipToHex()-derived value the Remote driver expects, so local LAN routing
// (Hubitat's built-in listener dispatches by DNI matching the sender's IP)
// works immediately without a second round-trip through updated().
def createRemoteDevice(String dni, String label) {
    def existing = getChildDevice(dni)
    if (existing) return existing
    return addChildDevice(
        "jdthomas24",
        "Sofabaton Remote",
        dni,
        [label: label, isComponent: false]
    )
}

void removeRemoteDevice(String dni) {
    def child = getChildDevice(dni)
    if (child) deleteChildDevice(dni)
}

