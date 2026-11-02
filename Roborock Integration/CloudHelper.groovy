/*
    Roborock Integration - Cloud Helper Driver
    Copyright 2026 JDThomas. All Rights Reserved

    Notes:
     -One hidden child of the app per account. Only used to find a vacuum's new IP when it can't be reached
      locally: connects to Roborock's MQTT broker, asks the vacuum for get_network_info, hands the IP and MAC
      back to the app, then disconnects. Idle the rest of the time (no connection, no polling).
     -Message format and MQTT login follow bloodtick's Roborock driver: protocol 1.0 frames, AES-ECB with
      md5(encodeTimestamp(t) + localKey + salt), user md5(u:k)[2..10], password md5(s:k)[16..].
     -Credentials come from the app on each lookup and are never stored here.
*/

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

@Field static final String VERSION = "0.2.0"
@Field static final String SALT = 'TXdfu$jyZ#TZHsg4'
@Field static final int TIMEOUT_SEC = 30
@Field static Map run = [:]   // per device: [duid, id, key, topicIn, topicOut]

metadata {
    definition(name: "Roborock Cloud Helper", namespace: "jdthomas24", author: "jdthomas24",
               importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Roborock%20Integration/CloudHelper.groovy") {
        capability "Actuator"
        attribute "lastLookup", "string"
    }
}

def installed() { sendEvent(name: "lastLookup", value: "none yet") }
def updated() { }

// Called by the app. creds: [rriot: Map, localKey: String]
void lookupNetworkInfo(String duid, Map creds) {
    Map rr = creds?.rriot as Map
    if (!rr?.r?.m || !creds?.localKey) { done(duid, null, "no Roborock sign-in"); return }
    String user = md5hex("${rr.u}:${rr.k}").substring(2, 10)
    String pass = md5hex("${rr.s}:${rr.k}").substring(16)
    int id = 10000 + new Random().nextInt(80000)
    run[device.id] = [duid: duid, id: id, key: creds.localKey, topicIn: "rr/m/i/${rr.u}/${user}/${duid}", topicOut: "rr/m/o/${rr.u}/${user}/#"]
    runIn(TIMEOUT_SEC, "lookupTimeout")
    try {
        if (interfaces.mqtt.isConnected()) { sendRequest(); return }
        dbg "connecting to ${rr.r.m}"
        interfaces.mqtt.connect(rr.r.m as String, "hubitat-${device.id}-${id}", user, pass, byteInterface: true)
    } catch (e) {
        done(duid, null, "couldn't reach Roborock's cloud (${e.message})")
    }
}

def mqttClientStatus(String msg) {
    dbg "MQTT: ${msg}"
    Map r = run[device.id] as Map
    if (!r) return
    if (msg?.toLowerCase()?.contains("succeeded")) {
        interfaces.mqtt.subscribe(r.topicOut as String)
        runIn(1, "sendRequest")
    } else if (msg?.toLowerCase()?.contains("error")) {
        done(r.duid as String, null, "cloud connection failed (${msg})")
    }
}

def sendRequest() {
    Map r = run[device.id] as Map
    if (!r || !interfaces.mqtt.isConnected()) return
    long ts = (long) (now() / 1000)
    String inner = JsonOutput.toJson([id: r.id, method: "get_network_info", params: []])
    String body = JsonOutput.toJson([t: ts, dps: ["101": inner]])
    byte[] enc = aes(body.getBytes("UTF-8"), r.key as String, ts, Cipher.ENCRYPT_MODE)
    byte[] f = new byte[23 + enc.length]
    f[0] = 49; f[1] = 46; f[2] = 48   // "1.0"
    put32(f, 3, new Random().nextInt(100000)); put32(f, 7, 100000 + new Random().nextInt(900000)); put32(f, 11, ts)
    f[15] = 0; f[16] = 101
    f[17] = (byte) ((enc.length >> 8) & 0xFF); f[18] = (byte) (enc.length & 0xFF)
    System.arraycopy(enc, 0, f, 19, enc.length)
    put32(f, f.length - 4, crc32(f, f.length - 4))
    dbg "asking ${r.duid} for its network info"
    interfaces.mqtt.publish(r.topicIn as String, f.encodeHex().toString())
}

def parse(String message) {
    Map r = run[device.id] as Map
    if (!r) return
    Map m = interfaces.mqtt.parseMessage(message)
    if (!m?.topic?.endsWith("/${r.duid}")) return
    byte[] b = (m.payload as String).decodeHex()
    if (b.length < 23 || new String(b, 0, 3, "ISO-8859-1") != "1.0") return
    int proto = ((b[15] & 0xFF) << 8) | (b[16] & 0xFF)
    if (proto != 102) return
    long ts = get32(b, 11)
    int len = ((b[17] & 0xFF) << 8) | (b[18] & 0xFF)
    byte[] ct = new byte[len]; System.arraycopy(b, 19, ct, 0, len)
    try {
        Map msg = new JsonSlurper().parseText(new String(aes(ct, r.key as String, ts, Cipher.DECRYPT_MODE), "UTF-8")) as Map
        msg?.dps?.each { k, v ->
            Map rpc = v instanceof String && v.startsWith("{") ? new JsonSlurper().parseText(v) as Map : null
            if (rpc && (rpc.id as int) == (r.id as int)) {
                def res = rpc.result instanceof List ? rpc.result[0] : rpc.result
                if (res instanceof Map && res.ip) done(r.duid as String, res as Map, null)
                else done(r.duid as String, null, "unexpected reply ${rpc.result ?: rpc.error}")
            }
        }
    } catch (e) { dbg "couldn't read a cloud message: ${e.message}" }
}

def lookupTimeout() {
    Map r = run[device.id] as Map
    if (r) done(r.duid as String, null, "no answer from the vacuum through Roborock's cloud within ${TIMEOUT_SEC} s")
}

private void done(String duid, Map info, String err) {
    unschedule("lookupTimeout")
    run.remove(device.id)
    try { if (interfaces.mqtt.isConnected()) interfaces.mqtt.disconnect() } catch (e) { }
    sendEvent(name: "lastLookup", value: "${new Date().format('yyyy-MM-dd HH:mm')}: ${err ? 'failed' : 'found ' + info.ip}")
    parent?.componentNetworkInfo(duid, info, err, "cloud")
}

// ---------- crypto and bytes ----------

private byte[] aes(byte[] data, String localKey, long ts, int mode) {
    byte[] k = MessageDigest.getInstance("MD5").digest((encodeTs(ts) + localKey + SALT).getBytes("UTF-8"))
    Cipher c = Cipher.getInstance("AES/ECB/PKCS5Padding")
    c.init(mode, new SecretKeySpec(k, "AES"))
    return c.doFinal(data)
}

private String encodeTs(long ts) {
    String h = Long.toHexString(ts).padLeft(8, "0")
    return [5, 6, 3, 7, 1, 2, 0, 4].collect { h[it] }.join()
}

private String md5hex(String s) { MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8")).encodeHex().toString() }

private long crc32(byte[] b, int len) {
    long crc = 0xFFFFFFFFL
    for (int i = 0; i < len; i++) {
        crc ^= (b[i] & 0xFF)
        for (int k = 0; k < 8; k++) crc = (crc & 1L) ? ((crc >>> 1) ^ 0xEDB88320L) : (crc >>> 1)
    }
    return crc ^ 0xFFFFFFFFL
}

private void put32(byte[] b, int at, long v) { (0..3).each { b[at + it] = (byte) ((v >> (24 - 8 * it)) & 0xFF) } }
private long get32(byte[] b, int at) { ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL) }

private void dbg(String m) { parent ? parent.childLog("debug", device.displayName, m) : log.debug(m) }
