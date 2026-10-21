/*
    Apple TV Now Playing (Beta) - Driver
    Copyright 2026 Jason Thomas. All Rights Reserved

    Credits: protocol knowledge from the pyatv project (Pierre Ståhl, MIT).

    Notes:
     -Standalone beta, separate from Apple TV Integration. One device per Apple TV, created by the app.
      The same driver runs hidden helper devices, since a device holds one raw socket. Role comes from the
      DNI ("-data" = link, "-event" = setup link). Both stay open for the whole session: the Apple TV ends it
      if the event channel closes (one-helper test, 1.2.0.7).
     -Pairing: AirPlay pair-setup on port 7000 (PIN on screen). Keys go to the app, memory only here.
     -Session: pair-verify, encrypted RTSP control channel, remote-control SETUP, event channel, RECORD,
      data channel, then Media Remote (MRP). Keepalive POST /feedback, default 120s (2-120s tested).
      No reply in 20s, or any channel closing, reconnects with backoff (5s to 5 min, offline after 4).
     -Saving new driver code clears memory but leaves sockets open: healthCheck (every 5 min, keepalive, or any
      data arriving for a forgotten session) reconnects so the device never looks connected while deaf.
     -The session survives Apple TV sleep. tvState from DEVICE_INFO(_UPDATE) logicalDeviceCount: 0 = asleep
      (applied after 4s, wake flickers 1-0-1), else awake.
     -Now playing tracked per app like pyatv: clients by bundle ID, players by ID, active client from
      SET_NOW_PLAYING_CLIENT. Attributes publish 1s after the last change, so an app switch lands once.
      A content item update with a new ID replaces a single-item queue (live TV programme change, 1.2.0.11).
      Leaving an app pauses it; the home screen shows the last app, paused. Asleep clears it (none, stopped).
      After waking, and after every new connection, it stays none (idle) until an app actually plays: on connect
      the Apple TV can report an old paused app (YouTube after Infuse had played, re-pair test 1.2.0.10).
     -Hub load: no volume or keyboard updates are requested. Artwork updates stay on: with them off the Apple TV
      sends no now-playing at all (tested 1.2.0.10). Output-device updates stay on (may carry awake/asleep). Once connected, only the message types in MRP_USED are decoded;
      Full logging decodes everything. Connects start 3s + up to 12s by device id, so TVs don't all connect at once.
     -Session map is a ConcurrentHashMap: remove(), never "= null".
*/

import groovy.transform.Field
import java.security.MessageDigest

def version() { "1.2.0.11" }

metadata {
    definition(name: "Apple TV Now Playing (Beta)", namespace: "jdthomas24", author: "jdthomas24",
        importUrl: "https://raw.githubusercontent.com/jdthomas24/Hubitat-Apps-Drivers/main/Tests%20-%20Groovy%20RAW/Apple%20TV%20Integration%20BETA/AppleTVNowPlayingDriver.groovy") {
        capability "Actuator"
        capability "Initialize"
        capability "Refresh"
        attribute "currentApp", "string"
        attribute "currentAppId", "string"
        attribute "playbackState", "string"
        attribute "title", "string"
        attribute "artist", "string"
        attribute "duration", "number"
        attribute "tvState", "string"
        attribute "connection", "string"
        command "reconnect"
        command "disconnect"
    }
    preferences {
        input name: "keepaliveSec", type: "number", title: "Keepalive interval (seconds)",
            description: "How often the hub checks in with the Apple TV. Changes still arrive instantly. Default 120.",
            defaultValue: 120, range: "2..120"
    }
}

@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> MEM = new java.util.concurrent.ConcurrentHashMap()
@Field static final Map AP_HEADERS = ["User-Agent": "AirPlay/320.20", "Connection": "keep-alive", "X-Apple-HKP": "3",
                                      "Content-Type": "application/octet-stream"]
@Field static final List RETRY_SEC = [5, 15, 30, 60, 120, 300]
@Field static final int OFFLINE_AFTER = 4
@Field static final List LOG_LEVELS = ["Errors Only", "Normal", "Full"]
@Field static final List PAIR_STEPS = ["PINSTART", "M2", "PIN", "M4", "M6"]
@Field static final List SESSION_STEPS = ["PV2", "PV4", "SETUP", "EVENTCH", "RECORD", "SETUP2", "DATACH", "READY"]
// ProtocolMessage types the driver uses once connected (SET_STATE, DEVICE_INFO, DEVICE_INFO_UPDATE,
// SET_NOW_PLAYING_CLIENT/PLAYER, REMOVE_CLIENT, UPDATE_CLIENT, UPDATE_CONTENT_ITEM, PLAYER_CLIENT_PROPERTIES)
@Field static final List MRP_USED = [4, 15, 37, 46, 47, 53, 55, 56, 104]
@Field static final java.util.concurrent.ConcurrentHashMap<String, Object> ED_CACHE = new java.util.concurrent.ConcurrentHashMap()
@Field static final Map HELPER_LABEL = [data: "link", event: "setup link"]
@Field static final List KEEP = ["failCount", "creds", "disabled", "pub", "pairResult", "pairStatus"]
@Field static final Map STATES = [Playing: "playing", Paused: "paused", Stopped: "stopped", Interrupted: "paused", Seeking: "playing"]
@Field static final Map KNOWN_APPS = ["com.netflix.Netflix": "Netflix", "com.plexapp.plex": "Plex", "com.google.ios.youtube": "YouTube",
    "com.google.ios.youtubeunplugged": "YouTube TV", "com.apple.TVWatchList": "TV", "com.apple.TVMusic": "Music",
    "com.apple.TVAirPlay": "AirPlay", "com.disney.disneyplus": "Disney+", "com.amazon.aiv.AIVApp": "Prime Video",
    "com.hbo.hbonow": "HBO Max", "com.wbd.stream": "HBO Max", "com.hulu.plus": "Hulu", "com.spotify.client": "Spotify",
    "com.peacocktv.peacock": "Peacock", "com.cbsvideo.app": "Paramount+", "com.apple.podcasts": "Podcasts"]

// MRP protobuf field map, generated from pyatv's .proto files: message -> field number -> [name, type, repeated]
@Field static final String PB_JSON = '{"m":{"ProtocolMessage":{"1":["type","E:ProtocolMessage.Type",0],"2":["identifier","S",0],"3":["authenticationToken","S",0],"4":["errorCode","E:ErrorCode.Enum",0],"5":["timestamp","U",0],"78":["errorDescription","S",0],"85":["uniqueIdentifier","S",0],"30":["textInputMessage","M:TextInputMessage",0],"61":["updateContentItemArtworkMessage","M:UpdateContentItemArtworkMessage",0],"22":["volumeControlAvailabilityMessage","M:VolumeControlAvailabilityMessage",0],"68":["volumeControlCapabilitiesDidChangeMessage","M:VolumeControlCapabilitiesDidChangeMessage",0],"45":["wakeDeviceMessage","M:WakeDeviceMessage",0],"62":["updatePlayerMessage","M:UpdatePlayerMessage",0],"72":["getRemoteTextInputSessionMessage","M:GetRemoteTextInputSessionMessage",0],"28":["keyboardMessage","M:KeyboardMessage",0],"52":["modifyOutputContextRequestMessage","M:ModifyOutputContextRequestMessage",0],"88":["audioFadeMessage","M:AudioFadeMessage",0],"27":["registerForGameControllerEventsMessage","M:RegisterForGameControllerEventsMessage",0],"36":["sendVoiceInputMessage","M:SendVoiceInputMessage",0],"9":["setStateMessage","M:SetStateMessage",0],"59":["updateClientMessage","M:UpdateClientMessage",0],"56":["volumeDidChangeMessage","M:VolumeDidChangeMessage",0],"34":["registerVoiceInputDeviceResponseMessage","M:RegisterVoiceInputDeviceResponseMessage",0],"71":["remoteTextInputMessage","M:RemoteTextInputMessage",0],"54":["getVolumeResultMessage","M:GetVolumeResultMessage",0],"20":["deviceInfoMessage","M:DeviceInfoMessage",0],"94":["configureConnectionMessage","M:ConfigureConnectionMessage",0],"39":["cryptoPairingMessage","M:CryptoPairingMessage",0],"87":["originClientPropertiesMessage","M:OriginClientPropertiesMessage",0],"12":["registerHIDDeviceResultMessage","M:RegisterHIDDeviceResultMessage",0],"33":["registerVoiceInputDeviceMessage","M:RegisterVoiceInputDeviceMessage",0],"7":["sendCommandResultMessage","M:SendCommandResultMessage",0],"10":["setArtworkMessage","M:SetArtworkMessage",0],"55":["setVolumeMessage","M:SetVolumeMessage",0],"38":["transactionMessage","M:TransactionMessage",0],"60":["updateContentItemMessage","M:UpdateContentItemMessage",0],"83":["updateEndPointsMessage","M:UpdateEndPointsMessage",0],"69":["updateOutputDeviceMessage","M:UpdateOutputDeviceMessage",0],"58":["removePlayerMessage","M:RemovePlayerMessage",0],"43":["sendButtonEventMessage","M:SendButtonEventMessage",0],"53":["getVolumeMessage","M:GetVolumeMessage",0],"16":["notificationMessage","M:NotificationMessage",0],"89":["audioFadeResponseMessage","M:AudioFadeResponseMessage",0],"21":["clientUpdatesConfigMessage","M:ClientUpdatesConfigMessage",0],"46":["genericMessage","M:GenericMessage",0],"29":["getKeyboardSessionMessage","S",0],"86":["playerClientPropertiesMessage","M:PlayerClientPropertiesMessage",0],"11":["registerHIDDeviceMessage","M:RegisterHIDDeviceMessage",0],"57":["removeClientMessage","M:RemoveClientMessage",0],"84":["removeEndpointsMessage","M:RemoveEndpointsMessage",0],"70":["removeOutputDevicesMessage","M:RemoveOutputDevicesMessage",0],"6":["sendCommandMessage","M:SendCommandMessage",0],"13":["sendHIDEventMessage","M:SendHIDEventMessage",0],"47":["sendPackedVirtualTouchEventMessage","M:SendPackedVirtualTouchEventMessage",0],"37":["playbackQueueRequestMessage","M:PlaybackQueueRequestMessage",0],"42":["setConnectionStateMessage","M:SetConnectionStateMessage",0],"75":["setDefaultSupportedCommandsMessage","M:SetDefaultSupportedCommandsMessage",0],"82":["setDiscoveryModeMessage","M:SetDiscoveryModeMessage",0],"44":["setHiliteModeMessage","M:SetHiliteModeMessage",0],"50":["setNowPlayingClientMessage","M:SetNowPlayingClientMessage",0],"51":["setNowPlayingPlayerMessage","M:SetNowPlayingPlayerMessage",0],"35":["setRecordingStateMessage","M:SetRecordingStateMessage",0]},"TextInputMessage":{"1":["timestamp","D",0],"2":["text","S",0],"3":["actionType","E:ActionType.Enum",0]},"UpdateContentItemArtworkMessage":{"1":["contentItems","M:ContentItem",1],"2":["playerPath","M:PlayerPath",0]},"ContentItem":{"1":["identifier","S",0],"2":["metadata","M:ContentItemMetadata",0],"3":["artworkData","B",0],"4":["info","S",0],"5":["availableLanguageOptions","M:LanguageOptionGroup",1],"6":["currentLanguageOptions","M:LanguageOption",1],"9":["parentIdentifier","S",0],"10":["ancestorIdentifier","S",0],"11":["queueIdentifier","S",0],"12":["requestIdentifier","S",0],"13":["artworkDataWidth","I",0],"14":["artworkDataHeight","I",0]},"ContentItemMetadata":{"1":["title","S",0],"2":["subtitle","S",0],"3":["isContainer","Z",0],"4":["isPlayable","Z",0],"5":["playbackProgress","F",0],"6":["albumName","S",0],"7":["trackArtistName","S",0],"8":["albumArtistName","S",0],"9":["directorName","S",0],"10":["seasonNumber","I",0],"11":["episodeNumber","I",0],"12":["releaseDate","D",0],"13":["playCount","I",0],"14":["duration","D",0],"15":["localizedContentRating","S",0],"16":["isExplicitItem","Z",0],"17":["playlistType","I",0],"18":["radioStationType","I",0],"19":["artworkAvailable","Z",0],"21":["infoAvailable","Z",0],"22":["languageOptionsAvailable","Z",0],"23":["numberOfSections","I",0],"24":["lyricsAvailable","Z",0],"25":["editingStyleFlags","I",0],"26":["isStreamingContent","Z",0],"27":["isCurrentlyPlaying","Z",0],"28":["collectionIdentifier","S",0],"29":["profileIdentifier","S",0],"30":["startTime","D",0],"31":["artworkMIMEType","S",0],"32":["assetURLString","S",0],"33":["composer","S",0],"34":["discNumber","I",0],"35":["elapsedTime","D",0],"36":["genre","S",0],"37":["isAlwaysLive","Z",0],"39":["playbackRate","F",0],"40":["chapterCount","I",0],"41":["totalDiscCount","I",0],"42":["totalTrackCount","I",0],"43":["trackNumber","I",0],"44":["contentIdentifier","S",0],"46":["isSharable","Z",0],"48":["isLiked","Z",0],"49":["isInWishList","Z",0],"50":["radioStationIdentifier","I",0],"52":["radioStationName","S",0],"53":["radioStationString","S",0],"54":["iTunesStoreIdentifier","I",0],"55":["iTunesStoreSubscriptionIdentifier","I",0],"56":["iTunesStoreArtistIdentifier","I",0],"57":["iTunesStoreAlbumIdentifier","I",0],"58":["purchaseInfoData","B",0],"59":["defaultPlaybackRate","F",0],"60":["downloadState","I",0],"61":["downloadProgress","F",0],"62":["appMetricsData","B",0],"63":["seriesName","S",0],"64":["mediaType","E:ContentItemMetadata.MediaType",0],"65":["mediaSubType","E:ContentItemMetadata.MediaSubType",0],"67":["nowPlayingInfoData","B",0],"68":["userInfoData","B",0],"69":["isSteerable","Z",0],"70":["artworkURL","S",0],"71":["lyricsURL","S",0],"72":["deviceSpecificUserInfoData","B",0],"73":["collectionInfoData","B",0],"74":["elapsedTimeTimestamp","D",0],"75":["inferredTimestamp","D",0],"76":["serviceIdentifier","S",0],"77":["artworkDataWidth","I",0],"78":["artworkDataHeight","I",0],"79":["currentPlaybackDateData","B",0],"80":["artworkIdentifier","S",0],"81":["isLoading","Z",0],"82":["artworkURLTemplatesData","B",0],"83":["legacyUniqueIdentifier","I",0],"84":["episodeType","I",0],"85":["artworkFileURL","S",0],"86":["brandIdentifier","S",0],"87":["localizedDurationString","S",0],"88":["albumYear","S",0],"89":["songTraits","E:SongTraits.Enum",0],"90":["albumTraits","E:AlbumTraits.Enum",0],"91":["playlistTraits","E:PlaylistTraits.Enum",0],"92":["preferredFormat","M:AudioFormat",0],"93":["activeFormat","M:AudioFormat",0],"94":["activeFormatJustification","E:ActiveFormatJustification.Enum",0],"95":["formatTierPreference","E:FormatTier.Enum",0],"96":["audioRoute","M:AudioRoute",0],"97":["alternativeFormats","M:AudioFormat",1]},"AudioFormat":{"1":["tier","E:AudioTier.Enum",0],"2":["bitrate","I",0],"3":["sampleRate","I",0],"4":["bitDepth","I",0],"5":["codec","U",0],"6":["spatialized","Z",0],"7":["multiChannel","Z",0],"8":["channelLayout","U",0],"9":["audioChannelLayoutDescription","S",0],"10":["groupID","S",0],"11":["stableVariantID","S",0]},"AudioRoute":{"1":["type","M:AudioRouteType",0],"2":["name","S",0],"3":["supportsSpatialization","Z",0]},"AudioRouteType":{},"LanguageOptionGroup":{"1":["allowEmptySelection","Z",0],"2":["defaultLanguageOption","M:LanguageOption",0],"3":["languageOptions","M:LanguageOption",1]},"LanguageOption":{"1":["type","I",0],"2":["languageTag","S",0],"3":["characteristics","S",1],"4":["displayName","S",0],"5":["identifier","S",0]},"PlayerPath":{"1":["origin","M:Origin",0],"2":["client","M:NowPlayingClient",0],"3":["player","M:NowPlayingPlayer",0]},"Origin":{"1":["type","E:Origin.Type",0],"2":["displayName","S",0],"3":["identifier","I",0],"4":["deviceInfo","M:DeviceInfoMessage",0],"5":["isLocallyHosted","Z",0]},"DeviceInfoMessage":{"1":["uniqueIdentifier","S",0],"2":["name","S",0],"3":["localizedModelName","S",0],"4":["systemBuildVersion","S",0],"5":["applicationBundleIdentifier","S",0],"6":["applicationBundleVersion","S",0],"7":["protocolVersion","I",0],"8":["lastSupportedMessageType","U",0],"9":["supportsSystemPairing","Z",0],"10":["allowsPairing","Z",0],"11":["connected","Z",0],"12":["systemMediaApplication","S",0],"13":["supportsACL","Z",0],"14":["supportsSharedQueue","Z",0],"15":["supportsExtendedMotion","Z",0],"16":["bluetoothAddress","B",0],"17":["sharedQueueVersion","U",0],"19":["deviceUID","S",0],"20":["managedConfigDeviceID","S",0],"21":["deviceClass","E:DeviceClass.Enum",0],"22":["logicalDeviceCount","U",0],"23":["tightlySyncedGroup","Z",0],"24":["isProxyGroupPlayer","Z",0],"25":["tightSyncUID","S",0],"26":["groupUID","S",0],"27":["groupName","S",0],"28":["groupedDevices","M:DeviceInfoMessage",1],"29":["isGroupLeader","Z",0],"30":["isAirplayActive","Z",0],"31":["systemPodcastApplication","S",0],"32":["senderDefaultGroupUID","S",0],"33":["airplayReceivers","S",1],"34":["linkAgent","S",0],"35":["clusterID","S",0],"36":["clusterLeaderID","S",0],"37":["clusterType","U",0],"38":["isClusterAware","Z",0],"39":["modelID","S",0],"40":["supportsMultiplayer","Z",0],"41":["routingContextID","S",0],"42":["airPlayGroupID","S",0],"43":["systemBooksApplication","S",0],"44":["clusteredDevices","M:DeviceInfoMessage",1],"45":["parentGroupContainsDiscoverableGroupLeader","U",0],"46":["groupContainsDiscoverableGroupLeader","U",0],"47":["lastKnownClusterType","U",0],"49":["supportsOutputContextSync","Z",0],"50":["computerName","S",0],"51":["configuredClusterSize","U",0],"52":["preferredEncoding","E:PreferredEncoding.Enum",0]},"NowPlayingClient":{"1":["processIdentifier","I",0],"2":["bundleIdentifier","S",0],"3":["parentApplicationBundleIdentifier","S",0],"4":["processUserIdentifier","I",0],"5":["nowPlayingVisibility","I",0],"7":["displayName","S",0],"8":["bundleIdentifierHierarchys","S",1]},"NowPlayingPlayer":{"1":["identifier","S",0],"2":["displayName","S",0],"3":["isDefaultPlayer","Z",0],"4":["audioSessionType","I",0],"5":["mxSessionIDs","I",0],"6":["audioSessionID","U",0],"7":["iconURL","S",0]},"VolumeControlAvailabilityMessage":{"1":["volumeControlAvailable","Z",0],"2":["volumeCapabilities","E:VolumeCapabilities.Enum",0]},"VolumeControlCapabilitiesDidChangeMessage":{"1":["capabilities","M:VolumeControlAvailabilityMessage",0],"3":["endpointUID","S",0],"4":["outputDeviceUID","S",0]},"WakeDeviceMessage":{},"UpdatePlayerMessage":{"1":["playerPath","M:PlayerPath",0]},"GetRemoteTextInputSessionMessage":{},"KeyboardMessage":{"1":["state","E:KeyboardState.Enum",0],"3":["attributes","M:TextEditingAttributes",0],"4":["encryptedTextCyphertext","B",0]},"TextEditingAttributes":{"1":["title","S",0],"2":["prompt","S",0],"3":["inputTraits","M:TextInputTraits",0]},"TextInputTraits":{"1":["autocapitalizationType","E:AutocapitalizationType.Enum",0],"2":["keyboardType","E:KeyboardType.Enum",0],"3":["returnKeyType","E:ReturnKeyType.Enum",0],"4":["autocorrection","Z",0],"5":["spellchecking","Z",0],"6":["enablesReturnKeyAutomatically","Z",0],"7":["secureTextEntry","Z",0],"8":["validTextRangeLocation","U",0],"9":["validTextRangeLength","U",0],"10":["pINEntrySeparatorIndexes","U",1]},"ModifyOutputContextRequestMessage":{"1":["type","E:ModifyOutputContextRequestType.Enum",0],"2":["addingDevices","S",1],"3":["removingDevices","S",1],"4":["settingDevices","S",1],"5":["clusterAwareAddingDevices","S",1],"6":["clusterAwareRemovingDevices","S",1],"7":["clusterAwareSettingDevices","S",1]},"AudioFadeMessage":{"1":["playerPath","M:PlayerPath",0],"2":["fadeType","I",0]},"RegisterForGameControllerEventsMessage":{"1":["inputModeFlags","E:RegisterForGameControllerEventsMessage.InputModeFlags",0]},"SendVoiceInputMessage":{"1":["dataBlock","M:AudioDataBlock",0]},"AudioDataBlock":{"1":["buffer","M:AudioBuffer",0],"2":["time","M:AudioTime",0],"3":["gain","D",0]},"AudioBuffer":{"1":["formatSettings","M:AudioFormatSettings",0],"2":["packetCapacity","I",0],"3":["maximumPacketSize","I",0],"4":["packetCount","I",0],"5":["contents","B",0],"6":["packetDescriptions","M:AudioStreamPacketDescription",1]},"AudioFormatSettings":{"1":["formatSettingsPlistData","B",0]},"AudioStreamPacketDescription":{"1":["startOffset","I",0],"2":["variableFramesInPacket","U",0],"3":["dataByteSize","U",0]},"AudioTime":{"1":["timestamp","D",0],"2":["sampleRate","D",0]},"SetStateMessage":{"1":["nowPlayingInfo","M:NowPlayingInfo",0],"2":["supportedCommands","M:SupportedCommands",0],"3":["playbackQueue","M:PlaybackQueue",0],"4":["displayID","S",0],"5":["displayName","S",0],"6":["playbackState","E:PlaybackState.Enum",0],"8":["playbackQueueCapabilities","M:PlaybackQueueCapabilities",0],"9":["playerPath","M:PlayerPath",0],"10":["request","M:PlaybackQueueRequestMessage",0],"11":["playbackStateTimestamp","D",0]},"NowPlayingInfo":{"1":["album","S",0],"2":["artist","S",0],"3":["duration","D",0],"4":["elapsedTime","D",0],"5":["playbackRate","F",0],"6":["repeatMode","E:RepeatMode.Enum",0],"7":["shuffleMode","E:ShuffleMode.Enum",0],"8":["timestamp","D",0],"9":["title","S",0],"10":["uniqueIdentifier","U",0],"11":["isExplicitTrack","Z",0],"12":["isMusicApp","Z",0],"13":["radioStationIdentifier","I",0],"14":["radioStationHash","S",0],"15":["radioStationName","S",0],"16":["artworkDataDigest","B",0],"17":["isAlwaysLive","Z",0],"18":["isAdvertisement","Z",0]},"SupportedCommands":{"1":["supportedCommands","M:CommandInfo",1]},"CommandInfo":{"1":["command","E:Command",0],"2":["enabled","Z",0],"3":["active","Z",0],"4":["preferredIntervals","D",1],"5":["localizedTitle","S",0],"6":["minimumRating","F",0],"7":["maximumRating","F",0],"8":["supportedRates","F",1],"9":["localizedShortTitle","S",0],"10":["repeatMode","E:RepeatMode.Enum",0],"11":["shuffleMode","E:ShuffleMode.Enum",0],"12":["presentationStyle","I",0],"13":["skipInterval","I",0],"14":["numAvailableSkips","I",0],"15":["skipFrequency","I",0],"16":["canScrub","I",0],"17":["supportedPlaybackQueueTypes","I",1],"18":["supportedCustomQueueIdentifiers","S",1],"19":["supportedInsertionPositions","I",1],"20":["supportsSharedQueue","Z",0],"21":["upNextItemCount","I",0],"22":["preferredPlaybackRate","F",0],"23":["supportedPlaybackSessionTypes","S",1],"24":["currentPlaybackSessionTypes","S",1],"25":["playbackSessionIdentifier","S",0],"26":["currentQueueEndAction","E:QueueEndAction.Enum",0],"27":["supportedEndQueueActions","E:QueueEndAction.Enum",1],"28":["disableReason","E:DisableReason.Enum",0],"29":["supportedPlaybackSessionIdentifiers","M:PreloadedPlaybackSessionInfo",1]},"PreloadedPlaybackSessionInfo":{"1":["playbackSessionIdentifier","S",0],"2":["playbackSessionRevision","S",0],"3":["playbackSessionPriority","I",0]},"PlaybackQueue":{"1":["location","I",0],"2":["contentItems","M:ContentItem",1],"3":["context","M:PlaybackQueueContext",0],"4":["requestId","S",0],"5":["resolvedPlayerPath","M:PlayerPath",0],"6":["sendingPlaybackQueueTransaction","Z",0],"7":["queueIdentifier","S",0]},"PlaybackQueueContext":{"1":["revision","S",0]},"PlaybackQueueCapabilities":{"1":["requestByRange","Z",0],"2":["requestByIdentifiers","Z",0],"3":["requestByRequest","Z",0]},"PlaybackQueueRequestMessage":{"1":["location","I",0],"2":["length","I",0],"3":["includeMetadata","Z",0],"4":["artworkWidth","D",0],"5":["artworkHeight","D",0],"6":["includeLyrics","Z",0],"7":["includeSections","Z",0],"8":["includeInfo","Z",0],"9":["includeLanguageOptions","Z",0],"10":["context","M:PlaybackQueueContext",0],"11":["requestID","S",0],"12":["contentItemIdentifiers","S",1],"13":["returnContentItemAssetsInUserCompletion","Z",0],"14":["playerPath","M:PlayerPath",0],"15":["cachingPolicy","I",0],"16":["label","S",0],"17":["isLegacyNowPlayingInfoRequest","Z",0]},"UpdateClientMessage":{"1":["client","M:NowPlayingClient",0]},"VolumeDidChangeMessage":{"1":["volume","F",0],"2":["endpointUID","S",0],"3":["outputDeviceUID","S",0]},"RegisterVoiceInputDeviceResponseMessage":{"1":["deviceID","I",0],"2":["errorCode","I",0]},"RemoteTextInputMessage":{"1":["timestamp","D",0],"2":["version","U",0],"3":["data","B",0]},"GetVolumeResultMessage":{"1":["volume","F",0]},"ConfigureConnectionMessage":{"1":["groupID","S",0]},"CryptoPairingMessage":{"1":["pairingData","B",0],"2":["status","I",0],"3":["isRetrying","Z",0],"4":["isUsingSystemPairing","Z",0],"5":["state","I",0]},"OriginClientPropertiesMessage":{"1":["lastPlayingTimestamp","D",0]},"RegisterHIDDeviceResultMessage":{"1":["errorCode","I",0],"2":["deviceIdentifier","I",0]},"RegisterVoiceInputDeviceMessage":{"1":["deviceDescriptor","M:VoiceInputDeviceDescriptor",0]},"VoiceInputDeviceDescriptor":{"1":["defaultFormat","M:AudioFormatSettings",0],"2":["supportedFormats","M:AudioFormatSettings",1]},"SendCommandResultMessage":{"1":["sendError","E:SendError.Enum",0],"2":["handlerReturnStatus","E:HandlerReturnStatus.Enum",0],"3":["handlerReturnStatusDatas","B",1],"4":["commandID","S",0],"5":["playerPath","M:PlayerPath",0],"6":["commandResult","M:SendCommandResult",0]},"SendCommandResult":{"1":["playerPath","M:PlayerPath",0],"2":["sendError","E:SendError.Enum",0],"3":["statuses","M:SendCommandResultStatus",1]},"SendCommandResultStatus":{"1":["statusCode","E:SendCommandStatusCode.Enum",0],"2":["type","E:SendCommandResultType.Enum",0],"5":["customData","B",0],"6":["customDataType","S",0]},"SetArtworkMessage":{"1":["jpegData","B",0]},"SetVolumeMessage":{"1":["volume","F",0],"2":["outputDeviceUID","S",0]},"TransactionMessage":{"1":["name","U",0],"2":["packets","M:TransactionPackets",0],"3":["playerPath","M:PlayerPath",0]},"TransactionPackets":{"1":["packets","M:TransactionPacket",1]},"TransactionPacket":{"1":["key","M:TransactionKey",0],"2":["packetData","B",0],"3":["identifier","S",0],"4":["totalLength","U",0],"5":["totalWritePosition","U",0]},"TransactionKey":{"1":["identifier","S",0],"2":["userData","B",0]},"UpdateContentItemMessage":{"1":["contentItems","M:ContentItem",1],"2":["playerPath","M:PlayerPath",0]},"UpdateEndPointsMessage":{"1":["endpoints","M:AVEndpointDescriptor",0],"2":["endpointFeatures","I",0]},"AVEndpointDescriptor":{"1":["name","S",0],"2":["uniqueIdentifier","S",0],"5":["isLocalEndpoint","Z",0],"6":["instanceIdentifier","S",0],"7":["isProxyGroupPlayer","Z",0],"8":["connectionType","I",0],"9":["canModifyGroupMembership","Z",0]},"UpdateOutputDeviceMessage":{"1":["outputDevices","M:AVOutputDeviceDescriptor",1],"2":["endpointUID","S",0],"3":["clusterAwareOutputDevices","M:AVOutputDeviceDescriptor",1]},"AVOutputDeviceDescriptor":{"1":["name","S",0],"2":["uniqueIdentifier","S",0],"3":["groupID","S",0],"4":["modelID","S",0],"5":["macAddress","B",0],"6":["canAccessRemoteAssets","Z",0],"7":["isRemoteControllable","Z",0],"8":["isGroupLeader","Z",0],"9":["isGroupable","Z",0],"10":["deviceType","E:DeviceType.Enum",0],"11":["deviceSubType","E:DeviceSubType.Enum",0],"12":["modelSpecificInfoData","B",0],"13":["batteryLevel","F",0],"14":["isLocalDevice","Z",0],"15":["supportsExternalScreen","Z",0],"16":["requiresAuthorization","Z",0],"17":["shouldForceRemoteControlabillity","Z",0],"18":["sourceInfo","M:AVOutputDeviceSourceInfo",0],"19":["isDeviceGroupable","Z",0],"20":["canRelayCommunicationChannel","Z",0],"21":["logicalDeviceID","S",0],"22":["isProxyGroupPlayer","Z",0],"23":["firmwareVersion","S",0],"24":["volume","F",0],"25":["isVolumeControlAvailable","Z",0],"26":["canAccessAppleMusic","Z",0],"27":["canAccessiCloudMusicLibrary","Z",0],"28":["groupContainsGroupLeader","Z",0],"29":["supportsBufferedAirPlay","Z",0],"30":["canPlayEncryptedProgressiveDownloadAssets","Z",0],"31":["canFetchMediaDataFromSender","Z",0],"32":["resentsOptimizedUserInterfaceWhenPlayingFetchedAudioOnlyAssets","Z",0],"33":["isAirPlayReceiverSessionActive","Z",0],"34":["parentGroupIdentifier","S",0],"35":["parentGroupContainsDiscoverableLeader","Z",0],"36":["isAddedToHomeKit","Z",0],"37":["volumeCapabilities","I",0],"38":["bluetoothID","S",0],"39":["supportsHAP","Z",0],"40":["usingJSONProtocol","Z",0],"41":["clusterCompositions","M:AVOutputDeviceDescriptor",1],"42":["clusterType","U",0],"43":["primaryUID","S",0],"44":["configuredClusterSize","U",0],"45":["supportsRapportRemoteControlTransport","Z",0],"46":["currentBluetoothListeningMode","S",0],"47":["availableBluetoothListeningModes","S",1],"48":["supportsMultiplayer","Z",1],"49":["producesLowFidelityAudio","Z",0],"50":["hostDeviceClass","I",0],"51":["airPlayGroupID","S",0],"52":["supportsSharePlayHandoff","Z",0],"53":["distance","F",0],"54":["discoveredOnSameInfra","Z",0],"55":["activatedClusterMembers","M:AVOutputDeviceDescriptor",1],"56":["pickable","Z",1],"57":["transportType","I",0],"58":["clusterID","S",0],"59":["isClusterLeader","Z",0],"60":["isAppleAccessory","Z",0],"61":["parentUniqueIdentifier","S",0],"62":["roomID","S",0],"63":["roomName","S",0],"64":["allClusterMembers","M:AVOutputDeviceDescriptor",1],"67":["engageOnClusterActivate","Z",0]},"AVOutputDeviceSourceInfo":{"1":["routingContextUID","S",0],"2":["multipleBuiltInDevices","Z",0]},"RemovePlayerMessage":{"1":["playerPath","M:PlayerPath",0]},"SendButtonEventMessage":{"1":["usagePage","U",0],"2":["usage","U",0],"3":["buttonDown","Z",0]},"GetVolumeMessage":{"1":["outputDeviceUID","S",0]},"NotificationMessage":{"1":["notification","S",1],"2":["userInfo","B",1]},"AudioFadeResponseMessage":{"1":["fadeDuration","I",0]},"ClientUpdatesConfigMessage":{"1":["artworkUpdates","Z",0],"2":["nowPlayingUpdates","Z",0],"3":["volumeUpdates","Z",0],"4":["keyboardUpdates","Z",0],"5":["outputDeviceUpdates","Z",0]},"GenericMessage":{"1":["key","S",0],"2":["value","B",0]},"PlayerClientPropertiesMessage":{"1":["playerPath","M:PlayerPath",0],"2":["lastPlayingTimestamp","D",0]},"RegisterHIDDeviceMessage":{"1":["deviceDescriptor","M:VirtualTouchDeviceDescriptor",0]},"VirtualTouchDeviceDescriptor":{"1":["absolute","Z",0],"2":["integratedDisplay","Z",0],"3":["screenSizeWidth","F",0],"4":["screenSizeHeight","F",0]},"RemoveClientMessage":{"1":["client","M:NowPlayingClient",0]},"RemoveEndpointsMessage":{"1":["endpointUIDs","S",1]},"RemoveOutputDevicesMessage":{"1":["outputDeviceUIDs","S",1],"2":["endpointUID","S",0]},"SendCommandMessage":{"1":["command","E:Command",0],"2":["options","M:CommandOptions",0],"3":["playerPath","M:PlayerPath",0]},"CommandOptions":{"2":["sourceId","S",0],"3":["mediaType","S",0],"4":["externalPlayerCommand","Z",0],"5":["skipInterval","F",0],"6":["playbackRate","F",0],"7":["rating","F",0],"8":["negative","Z",0],"9":["playbackPosition","D",0],"10":["repeatMode","E:RepeatMode.Enum",0],"11":["shuffleMode","E:ShuffleMode.Enum",0],"12":["trackID","U",0],"13":["radioStationID","I",0],"14":["radioStationHash","S",0],"15":["systemAppPlaybackQueueData","B",0],"16":["destinationAppDisplayID","S",0],"17":["sendOptions","U",0],"18":["requestDefermentToPlaybackQueuePosition","Z",0],"19":["contextID","S",0],"20":["shouldOverrideManuallyCuratedQueue","Z",0],"21":["stationURL","S",0],"22":["shouldBeginRadioPlayback","Z",0],"23":["playbackQueueInsertionPosition","I",0],"24":["contentItemID","S",0],"25":["playbackQueueOffset","I",0],"26":["playbackQueueDestinationOffset","I",0],"27":["languageOption","B",0],"28":["playbackQueueContext","B",0],"29":["insertAfterContentItemID","S",0],"30":["nowPlayingContentItemID","S",0],"31":["replaceIntent","I",0]},"SendHIDEventMessage":{"1":["hidEventData","B",0]},"SendPackedVirtualTouchEventMessage":{"1":["data","B",0]},"SetConnectionStateMessage":{"1":["state","E:SetConnectionStateMessage.ConnectionState",0]},"SetDefaultSupportedCommandsMessage":{"1":["nowPlayingInfo","M:NowPlayingInfo",0],"2":["supportedCommands","M:SupportedCommands",0],"3":["playbackQueue","M:PlaybackQueue",0],"4":["displayID","S",0],"5":["displayName","S",0],"6":["playbackState","E:PlaybackState.Enum",0],"8":["playbackQueueCapabilities","M:PlaybackQueueCapabilities",0],"9":["playerPath","M:PlayerPath",0],"10":["request","M:PlaybackQueueRequestMessage",0],"11":["playbackStateTimestamp","D",0]},"SetDiscoveryModeMessage":{"1":["mode","I",0],"2":["features","I",0]},"SetHiliteModeMessage":{"1":["hiliteMode","I",0]},"SetNowPlayingClientMessage":{"1":["client","M:NowPlayingClient",0]},"SetNowPlayingPlayerMessage":{"1":["playerPath","M:PlayerPath",0]},"SetRecordingStateMessage":{"1":["state","E:SetRecordingStateMessage.RecordingState",0]}},"e":{"ProtocolMessage.Type":{"0":"UNKNOWN_MESSAGE","1":"SEND_COMMAND_MESSAGE","2":"SEND_COMMAND_RESULT_MESSAGE","3":"GET_STATE_MESSAGE","4":"SET_STATE_MESSAGE","5":"SET_ARTWORK_MESSAGE","6":"REGISTER_HID_DEVICE_MESSAGE","7":"REGISTER_HID_DEVICE_RESULT_MESSAGE","8":"SEND_HID_EVENT_MESSAGE","9":"SEND_HID_REPORT_MESSAGE","10":"SEND_VIRTUAL_TOUCH_EVENT_MESSAGE","11":"NOTIFICATION_MESSAGE","12":"CONTENT_ITEMS_CHANGED_NOTIFICATION_MESSAGE","15":"DEVICE_INFO_MESSAGE","16":"CLIENT_UPDATES_CONFIG_MESSAGE","17":"VOLUME_CONTROL_AVAILABILITY_MESSAGE","18":"GAME_CONTROLLER_MESSAGE","19":"REGISTER_GAME_CONTROLLER_MESSAGE","20":"REGISTER_GAME_CONTROLLER_RESPONSE_MESSAGE","21":"UNREGISTER_GAME_CONTROLLER_MESSAGE","22":"REGISTER_FOR_GAME_CONTROLLER_EVENTS_MESSAGE","23":"KEYBOARD_MESSAGE","24":"GET_KEYBOARD_SESSION_MESSAGE","25":"TEXT_INPUT_MESSAGE","26":"GET_VOICE_INPUT_DEVICES_MESSAGE","27":"GET_VOICE_INPUT_DEVICES_RESPONSE_MESSAGE","28":"REGISTER_VOICE_INPUT_DEVICE_MESSAGE","29":"REGISTER_VOICE_INPUT_DEVICE_RESPONSE_MESSAGE","30":"SET_RECORDING_STATE_MESSAGE","31":"SEND_VOICE_INPUT_MESSAGE","32":"PLAYBACK_QUEUE_REQUEST_MESSAGE","33":"TRANSACTION_MESSAGE","34":"CRYPTO_PAIRING_MESSAGE","35":"GAME_CONTROLLER_PROPERTIES_MESSAGE","36":"SET_READY_STATE_MESSAGE","37":"DEVICE_INFO_UPDATE_MESSAGE","38":"SET_CONNECTION_STATE_MESSAGE","39":"SEND_BUTTON_EVENT_MESSAGE","40":"SET_HILITE_MODE_MESSAGE","41":"WAKE_DEVICE_MESSAGE","42":"GENERIC_MESSAGE","43":"SEND_PACKED_VIRTUAL_TOUCH_EVENT_MESSAGE","44":"SEND_LYRICS_EVENT","46":"SET_NOW_PLAYING_CLIENT_MESSAGE","47":"SET_NOW_PLAYING_PLAYER_MESSAGE","48":"MODIFY_OUTPUT_CONTEXT_REQUEST_MESSAGE","49":"GET_VOLUME_MESSAGE","50":"GET_VOLUME_RESULT_MESSAGE","51":"SET_VOLUME_MESSAGE","52":"VOLUME_DID_CHANGE_MESSAGE","53":"REMOVE_CLIENT_MESSAGE","54":"REMOVE_PLAYER_MESSAGE","55":"UPDATE_CLIENT_MESSAGE","56":"UPDATE_CONTENT_ITEM_MESSAGE","57":"UPDATE_CONTENT_ITEM_ARTWORK_MESSAGE","58":"UPDATE_PLAYER_MESSAGE","59":"PROMPT_FOR_ROUTE_AUTHORIZATION_MESSAGE","60":"PROMPT_FOR_ROUTE_AUTHORIZATION_RESPONSE_MESSAGE","61":"PRESENT_ROUTE_AUTHORIZATION_STATUS_MESSAGE","62":"GET_VOLUME_CONTROL_CAPABILITIES_MESSAGE","63":"GET_VOLUME_CONTROL_CAPABILITIES_RESULT_MESSAGE","64":"VOLUME_CONTROL_CAPABILITIES_DID_CHANGE_MESSAGE","65":"UPDATE_OUTPUT_DEVICE_MESSAGE","66":"REMOVE_OUTPUT_DEVICES_MESSAGE","67":"REMOTE_TEXT_INPUT_MESSAGE","68":"GET_REMOTE_TEXT_INPUT_SESSION_MESSAGE","69":"REMOVE_OUTPUT_DEVICES_MESSAGE2","70":"PLAYBACK_SESSION_REQUEST_MESSAGE","71":"PLAYBACK_SESSION_RESPONSE_MESSAGE","72":"SET_DEFAULT_SUPPORTED_COMMANDS_MESSAGE","73":"PLAYBACK_SESSION_MIGRATE_REQUEST_MESSAGE","74":"PLAYBACK_SESSION_MIGRATE_RESPONSE_MESSAGE","75":"PLAYBACK_SESSION_MIGRATE_BEGIN_MESSAGE","76":"PLAYBACK_SESSION_MIGRATE_END_MESSAGE","77":"UPDATE_ACTIVE_SYSTEM_ENDPOINT_MESSAGE","101":"SET_DISCOVERY_MODE_MESSAGE","102":"UPDATE_END_POINTS_MESSAGE","103":"REMOVE_ENDPOINTS_MESSAGE","104":"PLAYER_CLIENT_PROPERTIES_MESSAGE","105":"ORIGIN_CLIENT_PROPERTIES_MESSAGE","106":"AUDIO_FADE_MESSAGE","107":"AUDIO_FADE_RESPONSE_MESSAGE","120":"CONFIGURE_CONNECTION_MESSAGE"},"ErrorCode.Enum":{"0":"NoError","1":"UnknownError","2":"InvalidOperation","3":"OperationNotPermitted","4":"ClientDoesNotExist","5":"OriginDoesNotExist","6":"UnsupportedOperation","7":"FailedToSetPickedRoute","8":"FailedToRegisterCustomOrigin","9":"FailedToRemoveCustomOrigin","10":"TheApplicationActivityDoesNotExist","11":"TheAppHasNotSetupABrowsableContentEndpoint","12":"TheRequestedBrowsableContentApiIsNotSupportedByTheApplication","13":"TheNotficationHasNotBeenWhitelistedByTheServer","14":"OperationRequiresAClientCallbackToHaveBeenRegistered","15":"OperationRequiresAClientDataSourceToHaveBeenRegistered","16":"RequestedDataIsOutOfDateAndShouldBeRequestedAgain","17":"TheDevicesEnforcedVolumeLimitHasBeenExceeded","18":"VolumeValueIsOutOfRange","19":"VolumeIsAlreadyAtTheMaximumValue","20":"VolumeIsAlreadyMuted","21":"VoiceInputEndpointDoesNotExist","22":"TheVoiceInputDeviceIsNotRegisteredOrDoesNotExist","23":"EncryptionFailure","24":"EndpointDoesNotExist","25":"TheClientsApplicationCancelledTheOperation","26":"TheOperationTimedOut","27":"TheSpecifiedPlayerPathObjectWasInvalid","28":"AddingOrRemovingDevicesFromTheAvOutputContextHasFailed","29":"CouldNotFindTheSpecifiedNowPlayingPlayer","30":"TheSpecifiedContentItemDoesNotExist","31":"TheSpecifiedOffsetIsInvalid","32":"TheSpecifiedOutputContextIsInvalid","33":"OneOrMoreSpecifiedOutputDevicesAreNotGroupable","34":"TheSpecifiedOutputContextDoesNotSupportAddingMoreThanOneOutputDevice","35":"CouldNotFindTheSpecifiedNowPlayingClient","36":"EndpointVolumeControlIsOnlyPossibleIfTheEndpointIsPickedOrRemoteControllable","37":"OutputDeviceVolumeControlIsOnlyPossibleIfTheEndpointIsPickedOrRemoteControllable","38":"CoderMustSupportKeyValueCoding","39":"CouldNotFindTheGivenOutputdevice","100":"FailedToConnectToRemoteDevice","101":"AuthenticationTokenIsInvalid","102":"RecordingSessionIsAlreadyInProgressOnThisDevice","103":"TheDeviceIsNotCurrentlyRecording","104":"TheClientHasDisconnected","105":"TheServerHasDisconnected","106":"TheConnectionHasBeenCancelledByTheClient","107":"PairingFunctionalityIsLockedDueToSecurityReasons","108":"TheClientsOperatingSystemVersionIsTooOld","109":"TheClientsApplicationVersionIsTooOld","110":"TheDeviceIsNotPaired","111":"ThePinPairingDialogWasRemovedByTheUserBeforePairingOccoured","112":"ThePinPairingDialogWasRemovedByATimeoutBeforePairingOccoured","113":"TheConnectionTimedout","114":"PairingWithThisDeviceIsBlocked","115":"TheDeviceIsGoingToSleep","116":"ConnectionBlockedByServer","117":"MravendpointWasDeallocatedWhileWaitingForDeviceToConnect","200":"OutputContextModificationCausedADeviceToNoLongerBeAProxyGroupPlayer","201":"OutputContextModificationCausedADeviceToBecomeAProxyGroupPlayer","202":"OutputContextModificationRequestedNoTopologyChange","299":"OtherUnknownError"},"ActionType.Enum":{"0":"Unknown","1":"Insert","2":"Set","3":"Delete","4":"ClearAction"},"ContentItemMetadata.MediaType":{"0":"UnknownMediaType","1":"Audio","2":"Video"},"ContentItemMetadata.MediaSubType":{"0":"UnknownMediaSubType","1":"Music","4":"Podcast","5":"AudioBook","6":"ITunesU"},"SongTraits.Enum":{"0":"None","1":"AppleDigitialMaster","2":"Lossless","4":"HighResolutionLossless","8":"Spatial","16":"Atmos","32":"Surround"},"AlbumTraits.Enum":{"0":"None","1":"AppleDigitialMaster","2":"Lossless","4":"HighResolutionLossless","8":"Spatial","16":"Atmos","32":"Surround"},"PlaylistTraits.Enum":{"0":"None","8":"Spatial","16":"Atmos","32":"Surround"},"AudioTier.Enum":{"1":"LowBandwidthStereo","2":"HighQualityStereo","3":"Lossless","4":"HighResolutionLossless","5":"Spatial"},"ActiveFormatJustification.Enum":{"0":"Unknown","1":"Unavailable","100":"UserPreference","101":"UserDownload","500":"RouteIncompatible","501":"RouteUnknownCompatibility","1000":"BandwidthInsufficient"},"FormatTier.Enum":{"1":"LowBandwidthStereo","2":"HighQualityStereo","4":"Lossless","8":"HighResolutionLossless","16":"Spatial"},"Origin.Type":{"0":"Unknown","1":"Local","2":"Custom"},"DeviceClass.Enum":{"0":"Invalid","1":"iPhone","2":"iPod","3":"iPad","4":"AppleTV","5":"iFPGA","6":"Watch","7":"Accessory","8":"Bridge","9":"Mac"},"PreferredEncoding.Enum":{"0":"Default","1":"JSON"},"VolumeCapabilities.Enum":{"0":"None","1":"Relative","2":"Absolute","3":"Both"},"KeyboardState.Enum":{"0":"Unknown","1":"NotEditing","2":"DidBeginEditing","3":"Editing","4":"TextDidChange","5":"DidEndEditing","6":"Response"},"AutocapitalizationType.Enum":{"0":"None","1":"Words","2":"Sentences","3":"AllCharacters"},"KeyboardType.Enum":{"0":"Default","1":"ASCII_Capable","2":"NumbersAndPunctuation","3":"URL","4":"NumberPad","5":"PhonePad","6":"NamePhonePad","7":"EmailAddress","8":"DecimalPad","9":"Twitter","10":"WebSearch","11":"Alphanet","12":"PasscodePad"},"ReturnKeyType.Enum":{"0":"Default","1":"Go","2":"Google","3":"Join","4":"Next","5":"Route","6":"Search","7":"Send","8":"Yahoo","9":"Done","10":"EmergencyCall","11":"Continue"},"ModifyOutputContextRequestType.Enum":{"1":"SharedAudioPresentation"},"RegisterForGameControllerEventsMessage.InputModeFlags":{"0":"None","1":"Motion","2":"Buttons","3":"Digitizer"},"RepeatMode.Enum":{"0":"Unknown","1":"Off","2":"One","3":"All"},"ShuffleMode.Enum":{"0":"Unknown","1":"Off","2":"Albums","3":"Songs"},"Command":{"0":"Unknown","1":"Play","2":"Pause","3":"TogglePlayPause","4":"Stop","5":"NextTrack","6":"PreviousTrack","7":"AdvanceShuffleMode","8":"AdvanceRepeatMode","9":"BeginFastForward","10":"EndFastForward","11":"BeginRewind","12":"EndRewind","13":"Rewind15Seconds","14":"FastForward15Seconds","15":"Rewind30Seconds","16":"FastForward30Seconds","18":"SkipForward","19":"SkipBackward","20":"ChangePlaybackRate","21":"RateTrack","22":"LikeTrack","23":"DislikeTrack","24":"BookmarkTrack","45":"SeekToPlaybackPosition","46":"ChangeRepeatMode","47":"ChangeShuffleMode","53":"EnableLanguageOption","54":"DisableLanguageOption","25":"NextChapter","26":"PreviousChapter","27":"NextAlbum","28":"PreviousAlbum","29":"NextPlaylist","30":"PreviousPlaylist","31":"BanTrack","32":"AddTrackToWishList","33":"RemoveTrackFromWishList","34":"NextInContext","35":"PreviousInContext","41":"ResetPlaybackTimeout","48":"SetPlaybackQueue","49":"AddNowPlayingItemToLibrary","50":"CreateRadioStation","51":"AddItemToLibrary","52":"InsertIntoPlaybackQueue","55":"ReorderPlaybackQueue","56":"RemoveFromPlaybackQueue","57":"PlayItemInPlaybackQueue","58":"PrepareForSetQueue","59":"SetPlaybackSession","60":"PreloadedPlaybackSession","61":"SetPriorityForPlaybackSession","62":"DiscardPlaybackSession","63":"Reshuffle","135":"ChangeQueueEndAction"},"QueueEndAction.Enum":{"0":"ClearAction","1":"None","2":"Reset","3":"AutoPlay"},"DisableReason.Enum":{"0":"Unknown","1":"AdPlayback","2":"SkipLimitReached"},"PlaybackState.Enum":{"0":"Unknown","1":"Playing","2":"Paused","3":"Stopped","4":"Interrupted","5":"Seeking"},"SendError.Enum":{"0":"NoError","1":"ApplicationNotFound","2":"ConnectionFailed","3":"Ignored","4":"CouldNotLaunchApplication","5":"TimedOut","6":"OriginDoesNotExist","7":"InvalidOptions","8":"NoCommandHandlers","9":"ApplicationNotInstalled","10":"NotSupported"},"HandlerReturnStatus.Enum":{"0":"Success","1":"NoSuchContent","2":"CommandFailed","10":"NoActionableNowPlayingItem","20":"DeviceNotFound","3":"UIKitLegacy","100":"SkipAdProhibited","101":"QueueIsUserCurated","102":"UserModifiedQueueDisabled","103":"UserQueueModificationNotSupportedForCurrentItem","104":"SubscriptionRequiredForSharedQueue","105":"InsertionPositionNotSpecified","106":"InvalidInsertionPosition","107":"RequestParametersOutOfBounds","108":"SkipLimitReached","401":"AuthenticationFailure","501":"MediaServiceUnavailable"},"SendCommandStatusCode.Enum":{"0":"Success","1":"NoSuchContent","2":"CommandFailed","10":"NoActionableNowPlayingItem","20":"DeviceNotFound","3":"UIKitLegacy","100":"SkipAdProhibited","101":"QueueIsUserCurated","102":"UserModifiedQueueDisabled","103":"UserQueueModificationNotSupportedForCurrentItem","104":"SubscriptionRequiredForSharedQueue","105":"InsertionPositionNotSpecified","106":"InvalidInsertionPosition","107":"RequestParametersOutOfBounds","108":"SkipLimitReached","401":"AuthenticationFailure","501":"MediaServiceUnavailable"},"SendCommandResultType.Enum":{"1":"Dialog","2":"Error","999":"Custom"},"DeviceType.Enum":{"0":"Unknown","1":"AirPlay","2":"Bluetooth","3":"CarPlay","4":"BuiltIn","5":"Wired"},"DeviceSubType.Enum":{"0":"Default","1":"Speaker","2":"Headphones","3":"Headset","4":"Receiver","5":"LineOut","6":"USB","7":"DisplayPort","8":"HDMI","9":"LowEnergy","10":"SPDIF","11":"TV","12":"HomePod","13":"AppleTV","14":"Vehicle","15":"Cluster","16":"SetTopBox","17":"TVStick"},"SetConnectionStateMessage.ConnectionState":{"0":"None","1":"Connecting","2":"Connected","3":"Disconnected"},"SetRecordingStateMessage.RecordingState":{"0":"Unknown","1":"Recording","2":"NotRecording"}}}'

// ---------- lifecycle ----------

private String role() {
    String d = device.deviceNetworkId ?: ""
    return d.endsWith("-event") ? "event" : d.endsWith("-data") ? "data" : "main"
}

def installed() {
    if (role() != "main") return
    state.version = version()
    sendIfChanged("connection", "not paired")
    initialize()
}

def updated() {
    if (role() != "main") return
    logN("keepalive every ${kaSec()}s")
    if (mem().step == "READY") runIn(kaSec(), "feedback")
}

def initialize() {
    if (role() != "main") return
    state.version = version()
    Map m = mem()
    if (m.step in PAIR_STEPS) return
    ["disabled", "mode", "pairResult", "pairStatus"].each { m.remove(it) }
    m.failCount = 0
    teardown()
    runIn(3 + (((device.id ?: "0").toString().replaceAll("[^0-9]", "") ?: "0").toLong() % 5) * 3, "connect")
}

def uninstalled() {
    if (role() == "main") {
        teardown()
        getChildDevices().each { try { deleteChildDevice(it.deviceNetworkId) } catch (e) { } }
    } else chClose()
    MEM.remove(device.id.toString())
}

private Map mem() {
    String k = device.id.toString()
    Map m = MEM.get(k)
    if (m == null) { m = new java.util.concurrent.ConcurrentHashMap(); MEM.put(k, m) }
    return m
}

// ---------- commands ----------

def refresh() {
    if (role() != "main") return
    Map m = mem()
    if (m.mrpStage == "READY") { m.remove("pub"); publish() }
    else reconnect()
}

def reconnect() {
    if (role() != "main") return
    Map m = mem()
    if (m.step in PAIR_STEPS) return
    m.remove("disabled")
    m.failCount = 0
    teardown()
    logN("reconnecting")
    runIn(1, "connect")
}

def disconnect() {
    if (role() != "main") return
    Map m = mem()
    m.disabled = true
    teardown()
    unschedule("connect")
    sendIfChanged("connection", "disconnected")
    logN("disconnected. Reconnect (or Initialize) starts it again.")
}

// ---------- app interface ----------

void setLogLevel(String level) { if (state.logLevel != level) state.logLevel = level }

def componentSetAddress(String ip, String model = null, String tvName = null) {
    if (ip && state.ip != ip) {
        if (state.ip) logN("address changed to ${ip}")
        state.ip = ip
        updateDataValue("ip", ip)
    }
    if (model && getDataValue("model") != model) updateDataValue("model", model)
    if (tvName && getDataValue("appleTvName") != tvName) updateDataValue("appleTvName", tvName)
}

String componentPairingStage() {
    Map m = mem()
    return m.step in PAIR_STEPS ? m.step : m.pairResult
}

String componentPairingStatus() { mem().pairStatus }

def componentStartPairing() {
    unschedule("connect")
    teardown()
    Map m = mem()
    resetSession(m)
    m.remove("creds")
    m.mode = "pair"
    m.st = [:]
    m.step = "PINSTART"
    m.pairStatus = "Asking the Apple TV for a PIN..."
    if (!openCtl()) { pairFailed("Couldn't reach the Apple TV at ${state.ip}. Check that it's on."); return }
    post("/pair-pin-start", null)
    runIn(15, "pairTimeout")
}

def componentSubmitPin(String pin) {
    Map m = mem()
    if (m.step != "PIN") return
    String p = (pin ?: "").replaceAll("[^0-9]", "")
    if (p.length() != 4) { m.pairStatus = "Enter the 4-digit PIN shown on the TV."; return }
    Map st = m.st as Map
    Map srp = srpClient(randomBytes(32), p, st.salt as byte[], st.atvSrpPublic as byte[])
    st.srpK = srp.K
    st.expectM2 = srp.M2
    m.step = "M4"
    m.pairStatus = "Checking the PIN..."
    post("/pair-setup", tlv8Write([6: [0x03] as byte[], 3: srp.A, 4: srp.M1]))
    runIn(15, "pairTimeout")
}

def componentCancelPairing() {
    Map m = mem()
    teardown()
    m.remove("mode"); m.remove("pairStatus"); m.remove("pairResult")
    if (creds()) runIn(2, "connect")
}

def pairTimeout() {
    if (mem().step in ["PINSTART", "M2", "M4", "M6"]) pairFailed("No reply from the Apple TV. Try again.")
}

private void pairFailed(String why) {
    Map m = mem()
    unschedule("pairTimeout")
    closeCtl()
    m.remove("step"); m.remove("mode")
    m.pairResult = "FAILED"
    m.pairStatus = why
    logE("pairing failed: ${why}")
    runIn(5, "connect")   // a TV that was already paired goes back to its old pairing
}

private void pairDone(byte[] atvId, byte[] atvLtpk, Map st) {
    Map m = mem()
    unschedule("pairTimeout")
    closeCtl()
    Map c = [atvId: new String(atvId, "UTF-8"), atvLtpk: toHex(atvLtpk), seed: toHex(st.seed as byte[]),
             pairingId: new String(st.pairingId as byte[], "UTF-8")]
    parent?.componentPairingComplete(device.deviceNetworkId, c)
    m.remove("step"); m.remove("st"); m.remove("mode")
    m.pairResult = "DONE"
    m.pairStatus = "Paired"
    logN("paired")
    m.failCount = 0
    runIn(2, "connect")
}

private Map creds() {
    Map m = mem()
    if (m.creds) return m.creds as Map
    Map c = parent?.componentGetCredentials(device.deviceNetworkId)
    if (!c?.seed) return null
    m.creds = [atvId: (c.atvId as String).getBytes("UTF-8"), atvLtpk: fromHex(c.atvLtpk as String), seed: fromHex(c.seed as String),
               pairingId: (c.pairingId as String).getBytes("UTF-8"), pairingIdStr: c.pairingId]
    return m.creds as Map
}

// ---------- connection ----------

def connect() {
    if (role() != "main") return
    Map m = mem()
    if (m.disabled || m.mode == "pair" || m.step in SESSION_STEPS) return
    if (!creds()) { sendIfChanged("connection", "not paired"); return }
    state.version = version()
    runEvery5Minutes("healthCheck")
    if (!state.ip) refreshAddress()
    if (!state.ip) { scheduleRetry("no address yet"); return }
    if (!ensureHelpers()) { scheduleRetry("couldn't create the helper devices"); return }
    resetSession(m)
    m.mode = "session"
    int fails = (m.failCount ?: 0) as int
    sendIfChanged("connection", fails ? (device.currentValue("connection") == "offline" ? "offline" : "reconnecting") : "connecting")
    if (!openCtl()) { scheduleRetry("could not connect to ${state.ip}"); return }
    Map ss = [:]
    ss.xPriv = randomBytes(32)
    ss.xPub = x25519PublicKey(ss.xPriv as byte[])
    m.ss = ss
    m.step = "PV2"
    logF("connecting to ${state.ip}:7000")
    post("/pair-verify", tlv8Write([6: [0x01] as byte[], 3: ss.xPub]))
    runIn(30, "connectTimeout")
}

// Every 5 min, and whenever data arrives for a session this device no longer knows about. Saving new driver
// code clears its memory while the sockets stay open, so the device would look connected but hear nothing.
def healthCheck() {
    if (role() != "main") return
    Map m = mem()
    if (m.disabled || m.step in PAIR_STEPS || m.step in SESSION_STEPS) return
    if (!(device.currentValue("connection") in ["connected", "connecting"])) return   // retries already scheduled
    if (m.healAt && now() - (m.healAt as long) < 10000) return
    m.healAt = now()
    logN("session was reset (driver updated or hub restarted), reconnecting")
    m.failCount = 0
    teardown()
    runIn(1, "connect")
}

// From the app, about a minute after a hub restart: reconnect if Initialize at boot didn't get a session going.
void componentEnsureConnected() {
    if (role() != "main") return
    Map m = mem()
    if (m.disabled || m.step in PAIR_STEPS || m.mrpStage == "READY" || m.step in SESSION_STEPS) return
    logN("not connected after the hub restart, reconnecting")
    reconnect()
}

void channelOrphan() { if (!(mem().step in SESSION_STEPS)) runIn(1, "healthCheck") }

def connectTimeout() {
    Map m = mem()
    if (!(m.step in SESSION_STEPS) || m.mrpStage == "READY") return
    lost("no reply while connecting (at ${m.step == 'READY' ? 'Media Remote ' + m.mrpStage : m.step})")
}

private void resetSession(Map m) {
    (m.keySet() as List).each { k -> if (!(k in KEEP)) m.remove(k) }
}

private void lost(String why) {
    Map m = mem()
    boolean wasUp = m.mrpStage == "READY"
    teardown()
    if (m.disabled) return
    if (wasUp) logN("connection lost (${why}), reconnecting")
    scheduleRetry(why)
}

private void scheduleRetry(String reason) {
    Map m = mem()
    int n = ((m.failCount ?: 0) as int) + 1
    m.failCount = n
    int delay = RETRY_SEC[Math.min(n - 1, RETRY_SEC.size() - 1)] as int
    runIn(delay, "connect")   // first, so nothing below can leave the device without a retry
    if (n == OFFLINE_AFTER) {
        sendIfChanged("connection", "offline")
        logE("offline (${reason}). Retrying every 5 minutes. Check that the Apple TV has power and is on the network.")
    } else if (n < OFFLINE_AFTER) sendIfChanged("connection", "reconnecting")
    if (n >= 2) refreshAddress()
    logF("retry ${n} in ${delay}s (${reason})")
}

private void refreshAddress() {
    String ip = parent?.componentLookupAddress(device.deviceNetworkId, state.ip as String)
    if (ip) componentSetAddress(ip)
}

private void teardown() {
    ["feedback", "fbCheck", "mrpStageTimeout", "connectTimeout", "pairTimeout", "publish"].each { unschedule(it) }
    Map m = mem()
    if (m.step in SESSION_STEPS) m.remove("step")
    m.remove("mrpStage")
    ["event", "data"].each { k -> try { helper(k)?.chClose() } catch (e) { } }
    closeCtl()
}

private boolean ensureHelpers() {
    try {
        ["event", "data"].each { k ->
            String lbl = "${device.displayName} (${HELPER_LABEL[k]})"
            def h = helper(k)
            if (!h) addChildDevice("jdthomas24", "Apple TV Now Playing (Beta)", helperDni(k),
                [name: "Apple TV Now Playing ${HELPER_LABEL[k]}", label: lbl, isComponent: true])
            else if (h.label == "${device.displayName} (${k} channel)".toString()) { h.setLabel(lbl); h.setName("Apple TV Now Playing ${HELPER_LABEL[k]}") }
        }
        return true
    } catch (e) {
        logE("couldn't create the helper devices: ${e.message}")
        return false
    }
}



private String helperDni(String k) { "ATVNP-H${device.id}-${k}" }
private helper(String k) { getChildDevice(helperDni(k)) }

private int kaSec() {
    def v = settings.keepaliveSec
    int n = 120
    try { if (v != null && v.toString().trim()) n = new BigDecimal(v.toString().trim()).intValue() } catch (e) { }
    return Math.max(2, Math.min(120, n))
}

// ---------- control socket (HTTP, then encrypted RTSP) ----------

private boolean openCtl() {
    closeCtl()
    Map m = mem()
    m.rx = ""
    try {
        interfaces.rawSocket.connect(state.ip.toString(), 7000, byteInterface: true)
        return true
    } catch (e) {
        logF("connect to ${state.ip}:7000 failed: ${e.message}")
        return false
    }
}

private void closeCtl() {
    mem().closingAt = now()
    try { interfaces.rawSocket.close() } catch (e) { }
}

private void post(String path, byte[] body) {
    StringBuilder h = new StringBuilder("POST ${path} HTTP/1.1")
    if (body) h.append("\r\nContent-Length: ${body.length}")
    AP_HEADERS.each { k, v -> h.append("\r\n${k}: ${v}") }
    h.append("\r\n\r\n")
    byte[] out = h.toString().getBytes("UTF-8")
    if (body) out = cdConcat(out, body)
    logF("POST ${path}")
    sendCtl(out)
}

private void rtspSend(Map m, String method, String path, Map plistBody) {
    int cseq = (m.cseq ?: 0) as int
    m.cseq = cseq + 1
    byte[] body = plistBody != null ? bplistWrite(plistBody) : null
    StringBuilder h = new StringBuilder("${method} ${path ?: m.uri} RTSP/1.0\r\nUser-Agent: AirPlay/550.10")
    if (body) h.append("\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: ${body.length}")
    h.append("\r\nCSeq: ${cseq}\r\nDACP-ID: ${m.dacp}\r\nActive-Remote: ${m.activeRemote}\r\nClient-Instance: ${m.dacp}\r\n\r\n")
    byte[] out = h.toString().getBytes("UTF-8")
    if (body) out = cdConcat(out, body)
    logF("${method} ${path ?: ''} (CSeq ${cseq})")
    sendCtl(hapEncrypt(m.ss as Map, out))
}

private void sendCtl(byte[] b) {
    try { interfaces.rawSocket.sendMessage(toHex(b)) }
    catch (e) {
        Map m = mem()
        if (m.mode == "pair") pairFailed("Couldn't send to the Apple TV: ${e.message}")
        else lost("send failed: ${e.message}")
    }
}

// Handled on a scheduled thread: the hub may interrupt the socket's own thread while it closes.
def socketStatus(String s) { runInMillis(50, "socketStatusLater", [data: [s: s], overwrite: false]) }

def socketStatusLater(Map d) {
    String s = d?.s as String
    if (role() != "main") { chSocketStatus(s); return }
    Map m = mem()
    logF("socket status: ${s}")
    String l = s?.toLowerCase() ?: ""
    if (!(l.contains("error") || l.contains("closed") || l.contains("disconnect"))) return
    if (m.closingAt && now() - (m.closingAt as long) < 3000) return
    if (m.mode == "pair") {
        if (m.step in PAIR_STEPS) pairFailed("The Apple TV closed the connection. Try again.")
        return
    }
    if (m.step in SESSION_STEPS) lost("control connection closed")
}

def parse(String hex) {
    if (role() != "main") { chParse(hex); return }
    Map m = mem()
    if (!m.step) { if (m.mode != "pair") runIn(1, "healthCheck"); return }
    m.rx = (m.rx ?: "") + hex
    try {
        if (m.enc) {
            List d = hapDecrypt(m.ss as Map, fromHex(m.rx as String))
            m.rx = toHex(d[1] as byte[])
            m.rxPlain = (m.rxPlain ?: "") + toHex(d[0] as byte[])
            while (httpFrom(m, "rxPlain")) { }
        } else while (httpFrom(m, "rx")) { }
    } catch (e) {
        if (m.mode == "pair") pairFailed(e.message ?: "${e}")
        else lost(e.message ?: "${e}")
    }
}

// One complete HTTP/RTSP response from m[key] (hex) to handle(). Returns true if one was taken.
private boolean httpFrom(Map m, String key) {
    byte[] buf = fromHex(m[key] as String)
    int hdrEnd = indexOf(buf, "\r\n\r\n".getBytes("UTF-8"))
    if (hdrEnd < 0) return false
    String head = new String(cdSlice(buf, 0, hdrEnd), "UTF-8")
    def cl = head =~ /(?i)content-length:\s*(\d+)/
    int len = cl.find() ? (cl.group(1) as int) : 0
    if (buf.length < hdrEnd + 4 + len) return false
    byte[] body = cdSlice(buf, hdrEnd + 4, hdrEnd + 4 + len)
    m[key] = toHex(cdSlice(buf, hdrEnd + 4 + len, buf.length))
    def sm = head =~ /^\S+\s+(\d{3})/
    int status = sm.find() ? (sm.group(1) as int) : 0
    handle(m, status, body)
    return true
}

private void handle(Map m, int status, byte[] body) {
    if (status != 200) {
        String hint = status == 470 ? " (not allowed: check AirPlay > Allow Access on the Apple TV)" :
            status == 403 ? " (refused: the pairing may have been removed on the Apple TV, pair again)" : ""
        throw new IllegalStateException("Apple TV answered ${status} at ${m.step}${hint}")
    }
    Map st = m.st as Map
    switch (m.step) {
        case "PINSTART":
            m.step = "M2"
            post("/pair-setup", tlv8Write([0: [0x00] as byte[], 6: [0x01] as byte[]]))
            break
        case "M2":
            Map t = tlvOrError(body)
            st.salt = t[2]; st.atvSrpPublic = t[3]
            if (!st.salt || !st.atvSrpPublic) throw new IllegalStateException("The Apple TV's pairing reply was incomplete. Try again.")
            unschedule("pairTimeout")
            m.step = "PIN"
            m.pairStatus = "Enter the PIN shown on the TV."
            break
        case "M4":
            Map t = tlvOrError(body)
            if (!cdEq(t[4], st.expectM2)) throw new IllegalStateException("Wrong PIN. Tap Pair to get a new one.")
            st.seed = randomBytes(32)
            st.ltpk = ed25519PublicKey(st.seed as byte[])
            st.pairingId = UUID.randomUUID().toString().toUpperCase().getBytes("UTF-8")
            byte[] K = st.srpK as byte[]
            byte[] signX = hkdfSha512("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", K)
            byte[] encKey = hkdfSha512("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", K)
            byte[] sig = ed25519Sign(st.seed as byte[], cdConcat(cdConcat(signX, st.pairingId as byte[]), st.ltpk as byte[]))
            byte[] enc = chachaSeal(encKey, "PS-Msg05", tlv8Write([1: st.pairingId, 3: st.ltpk, 10: sig,
                0x11: opackPack(["name": "Hubitat"])]), null)
            m.step = "M6"
            m.pairStatus = "PIN accepted, finishing..."
            post("/pair-setup", tlv8Write([6: [0x05] as byte[], 5: enc]))
            break
        case "M6":
            Map t = tlvOrError(body)
            byte[] K = st.srpK as byte[]
            byte[] encKey = hkdfSha512("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", K)
            Map inner = tlv8Read(chachaOpen(encKey, "PS-Msg06", t[5] as byte[], null))
            byte[] atvId = inner[1], atvLtpk = inner[3], atvSig = inner[10]
            byte[] accX = hkdfSha512("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", K)
            if (!(atvSig && ed25519Verify(atvLtpk, cdConcat(cdConcat(accX, atvId), atvLtpk), atvSig)))
                throw new IllegalStateException("The Apple TV's signature didn't verify. Try again.")
            pairDone(atvId, atvLtpk, st)
            break
        case "PV2":
            Map t = tlvOrError(body)
            Map ss = m.ss as Map
            Map c = creds()
            byte[] atvPub = t[3] as byte[]
            byte[] shared = x25519Shared(ss.xPriv as byte[], atvPub)
            byte[] key = hkdfSha512("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
            Map inner = tlv8Read(chachaOpen(key, "PV-Msg02", t[5] as byte[], null))
            ss.shared = shared
            if (!cdEq(inner[1], c.atvId)) throw new IllegalStateException("a different Apple TV answered at ${state.ip}")
            if (!ed25519Verify(c.atvLtpk as byte[], cdConcat(cdConcat(atvPub, inner[1] as byte[]), ss.xPub as byte[]), inner[10] as byte[]))
                throw new IllegalStateException("the Apple TV's signature didn't verify")
            byte[] sig = ed25519Sign(c.seed as byte[], cdConcat(cdConcat(ss.xPub as byte[], c.pairingId as byte[]), atvPub))
            byte[] enc = chachaSeal(key, "PV-Msg03", tlv8Write([1: c.pairingId, 10: sig]), null)
            m.step = "PV4"
            post("/pair-verify", tlv8Write([6: [0x03] as byte[], 5: enc]))
            break
        case "PV4":
            if (body?.length) tlvOrError(body)
            startSession(m)
            break
        case "SETUP":
            def reply = body?.length ? bplistParse(body) : null
            def port = reply instanceof Map ? reply.eventPort : null
            if (!port) throw new IllegalStateException("session SETUP had no event port")
            Map ss = m.ss as Map
            m.step = "EVENTCH"
            // Read/Write reversed for the event channel, as in pyatv (the receiver originates its messages).
            helper("event").chOpen([kind: "event", ip: state.ip, port: port as int,
                outKey: toHex(hkdfSha512("Events-Salt", "Events-Read-Encryption-Key", ss.shared as byte[])),
                inKey: toHex(hkdfSha512("Events-Salt", "Events-Write-Encryption-Key", ss.shared as byte[]))])
            break
        case "RECORD":
            m.step = "SETUP2"
            long seed = new java.security.SecureRandom().nextLong() & Long.MAX_VALUE
            m.seed = seed
            rtspSend(m, "SETUP", null, ["streams": [["controlType": 2, "channelID": UUID.randomUUID().toString().toUpperCase(),
                "seed": seed, "clientUUID": UUID.randomUUID().toString().toUpperCase(), "type": 130,
                "wantsDedicatedSocket": true, "clientTypeUUID": "1910A70F-DBC0-4242-AF95-115DB30604E1"]]])
            break
        case "SETUP2":
            def reply = body?.length ? bplistParse(body) : null
            def dport = reply instanceof Map && reply.streams instanceof List ? (reply.streams[0] as Map)?.dataPort : null
            if (!dport) throw new IllegalStateException("data SETUP had no data port")
            Map ss = m.ss as Map
            String salt = "DataStream-Salt${m.seed}"
            m.step = "DATACH"
            helper("data").chOpen([kind: "data", ip: state.ip, port: dport as int,
                outKey: toHex(hkdfSha512(salt, "DataStream-Output-Encryption-Key", ss.shared as byte[])),
                inKey: toHex(hkdfSha512(salt, "DataStream-Input-Encryption-Key", ss.shared as byte[]))])
            break
        case "READY":
            m.remove("fbAt")   // keepalive answered
            break
        default:
            logF("ignoring a reply at ${m.step}")
    }
}

private void startSession(Map m) {
    Map ss = m.ss as Map
    ss.outKey = hkdfSha512("Control-Salt", "Control-Write-Encryption-Key", ss.shared as byte[])
    ss.inKey = hkdfSha512("Control-Salt", "Control-Read-Encryption-Key", ss.shared as byte[])
    ss.outCount = 0L
    ss.inCount = 0L
    m.enc = true
    m.rxPlain = ""
    def rnd = new java.security.SecureRandom()
    m.dacp = String.format("%016X", rnd.nextLong())
    m.activeRemote = rnd.nextInt(Integer.MAX_VALUE)
    m.uri = "rtsp://${location?.hub?.localIP ?: '0.0.0.0'}/${rnd.nextInt(Integer.MAX_VALUE)}".toString()
    m.cseq = 0
    m.step = "SETUP"
    rtspSend(m, "SETUP", null, ["isRemoteControlOnly": true, "osName": "iPhone OS", "sourceVersion": "550.10", "timingProtocol": "None",
        "model": "iPhone10,6", "deviceID": "FF:70:79:61:74:76", "osVersion": "14.7.1", "osBuildVersion": "18G82",
        "macAddress": "02:70:79:61:74:76", "sessionUUID": UUID.randomUUID().toString().toUpperCase(), "name": "Hubitat"])
}

// ---------- callbacks from the helper devices ----------

void channelUp(String kind) {
    Map m = mem()
    logF("${kind} channel open")
    if (kind == "event" && m.step == "EVENTCH") {
        m.step = "RECORD"
        rtspSend(m, "RECORD", null, null)
    } else if (kind == "data" && m.step == "DATACH") {
        m.step = "READY"
        runIn(kaSec(), "feedback")
        mrpStart(m)
    }
}

void channelDown(String kind, String why) {
    Map m = mem()
    if (m.step in SESSION_STEPS) lost("${kind} channel closed: ${why}")
}

void channelMessage(String kind, String summary) { logF("${kind} channel: ${summary}") }

def feedback() {
    Map m = mem()
    if (m.step != "READY") { if (!m.step) healthCheck(); return }
    if (m.fbAt) {
        if (now() - (m.fbAt as long) >= 20000) { lost("no reply to keepalive in 20s"); return }
        runIn(kaSec(), "feedback")
        return
    }
    m.fbAt = now()
    rtspSend(m, "POST", "/feedback", null)
    runIn(20, "fbCheck")
    runIn(kaSec(), "feedback")
}

def fbCheck() {
    Map m = mem()
    if (m.step == "READY" && m.fbAt && now() - (m.fbAt as long) >= 19000) lost("no reply to keepalive in 20s")
}

// ---------- Media Remote (MRP) over the data channel ----------

@Field static final java.util.concurrent.ConcurrentHashMap PB_CACHE = new java.util.concurrent.ConcurrentHashMap()

private Map pb() {
    Map s = PB_CACHE.get("s") as Map
    if (s == null) { s = new groovy.json.JsonSlurper().parseText(PB_JSON) as Map; PB_CACHE.put("s", s) }
    return s
}

private void mrpStart(Map m) {
    m.np = [:]; m.clients = [:]; ["activeClient", "npKey", "owner", "ownerGone"].each { m.remove(it) }
    m.mrpStage = "DEVINFO"
    m.mrpWait = UUID.randomUUID().toString().toUpperCase()
    mrpSend(m, "DEVICE_INFO_MESSAGE", m.mrpWait as String, ["deviceInfoMessage": [
        "uniqueIdentifier": (creds()?.pairingIdStr ?: UUID.randomUUID().toString()), "name": "Hubitat", "localizedModelName": "iPhone",
        "systemBuildVersion": "18G82", "applicationBundleIdentifier": "com.apple.TVRemote", "applicationBundleVersion": "344.28",
        "protocolVersion": 1, "lastSupportedMessageType": 108, "supportsSystemPairing": true, "allowsPairing": true,
        "systemMediaApplication": "com.apple.TVMusic", "supportsACL": true, "supportsSharedQueue": true,
        "supportsExtendedMotion": true, "sharedQueueVersion": 2, "deviceClass": "iPhone", "logicalDeviceCount": 1]])
    runIn(8, "mrpStageTimeout")
}

def mrpStageTimeout() {
    Map m = mem()
    if (m.step != "READY" || m.mrpStage == "READY") return
    logF("Media Remote: no reply at ${m.mrpStage} after 8s, moving on")
    mrpNext(m)
}

// pyatv order: after the Apple TV's DEVICE_INFO, SET_CONNECTION_STATE, then CLIENT_UPDATES_CONFIG and
// GET_KEYBOARD_SESSION, each waiting for the reply with the same identifier. artworkUpdates must be on,
// or the Apple TV sends no now-playing updates.
private void mrpNext(Map m) {
    unschedule("mrpStageTimeout")
    String id = UUID.randomUUID().toString().toUpperCase()
    switch (m.mrpStage) {
        case "DEVINFO":
            mrpSend(m, "SET_CONNECTION_STATE_MESSAGE", null, ["setConnectionStateMessage": ["state": "Connected"]])
            m.mrpStage = "CONFIG"; m.mrpWait = id
            mrpSend(m, "CLIENT_UPDATES_CONFIG_MESSAGE", id, ["clientUpdatesConfigMessage": ["artworkUpdates": true,
                "nowPlayingUpdates": false, "volumeUpdates": false, "keyboardUpdates": false, "outputDeviceUpdates": true]])
            runIn(8, "mrpStageTimeout")
            break
        case "CONFIG":
            m.mrpStage = "KEYBOARD"; m.mrpWait = id
            mrpSend(m, "GET_KEYBOARD_SESSION_MESSAGE", id, [:])
            runIn(8, "mrpStageTimeout")
            break
        case "KEYBOARD":
            m.mrpStage = "READY"; m.remove("mrpWait")
            unschedule("connectTimeout")
            boolean wasOffline = device.currentValue("connection") == "offline"
            m.failCount = 0
            m.remove("pairResult"); m.remove("pairStatus")
            m.waitPlay = true   // the Apple TV's reported app at connect can be stale; wait for one that plays
            sendIfChanged("connection", "connected")
            if (wasOffline) logE("back online")
            logN("connected to ${getDataValue('appleTvName') ?: state.ip}, keepalive every ${kaSec()}s")
            schedulePublish()
            break
    }
}

private void mrpSend(Map m, String type, String identifier, Map inner) {
    Map msg = ["type": type, "errorCode": 0, "uniqueIdentifier": UUID.randomUUID().toString().toUpperCase()]
    if (identifier) msg.identifier = identifier
    msg.putAll(inner)
    byte[] pbBytes = pbEncode(msg, "ProtocolMessage")
    java.io.ByteArrayOutputStream d = new java.io.ByteArrayOutputStream()
    pbVarintWrite(d, pbBytes.length as long)
    d.write(pbBytes)
    logF("Media Remote >> ${type}")
    helper("data")?.chSendSync(toHex(bplistWrite(["params": ["data": d.toByteArray()]])))
}

// From the data helper: protobuf bytes from one "sync" message (one or more ProtocolMessages).
void channelProtobuf(String hex) {
    Map m = mem()
    byte[] data = fromHex(hex)
    int p = 0
    while (p < data.length) {
        byte[] one
        if ((data[p] & 0xFF) == 0x08) { one = cdSlice(data, p, data.length); p = data.length }   // not length-prefixed
        else {
            List r = pbVarintRead(data, p)
            int len = (r[0] as long) as int
            p = r[1] as int
            one = cdSlice(data, p, Math.min(p + len, data.length)); p += len
        }
        if (m.mrpStage == "READY" && lvl() < 2 && one.length > 1 && (one[0] & 0xFF) == 0x08) {
            int t = (pbVarintRead(one, 1)[0] as long) as int
            if (!(t in MRP_USED)) continue   // not needed: skip before decoding
        }
        Map msg
        try { msg = pbDecode(one, "ProtocolMessage") } catch (e) { logF("Media Remote: couldn't decode a message: ${e.message}"); continue }
        mrpHandle(m, msg)
    }
    npCheck(m)
}

private void mrpHandle(Map m, Map msg) {
    String type = msg.type?.toString() ?: "?"
    if (lvl() >= 2) logF("Media Remote << ${type}: ${render(msg.findAll { k, v -> !(k in ['type', 'uniqueIdentifier', 'errorCode']) }).take(500)}")
    Map di = msg.deviceInfoMessage as Map
    if (di && di.logicalDeviceCount != null) tvCount(di.logicalDeviceCount as int)
    if (m.mrpStage == "DEVINFO" && type == "DEVICE_INFO_MESSAGE") {
        if (di?.name && getDataValue("appleTvName") != di.name) updateDataValue("appleTvName", di.name as String)
        if (di?.systemBuildVersion) updateDataValue("tvOSBuild", di.systemBuildVersion as String)
        mrpNext(m)
    } else if (m.mrpWait && msg.identifier == m.mrpWait && m.mrpStage in ["CONFIG", "KEYBOARD"]) {
        mrpNext(m)
    }
    npUpdate(m, msg)
}

// ---------- TV awake / asleep ----------

private void tvCount(int n) {
    if (n > 0) { unschedule("tvAsleep"); setTv("awake") }
    else if (device.currentValue("tvState") != "asleep") runIn(4, "tvAsleep")
}

def tvAsleep() { setTv("asleep") }

private void setTv(String v) {
    if (device.currentValue("tvState") == v) return
    if (v == "asleep") mem().waitPlay = true   // after waking, show nothing until an app plays
    logN("Apple TV is ${v}")
    sendIfChanged("tvState", v)
    schedulePublish()
}

// ---------- now playing ----------

// Called once per data-channel batch, so an app switch (new client + its new state) is judged as a whole.
private void npCheck(Map m) {
    Map np = (m.np ?: [:]) as Map
    String key = "${np.bundle}|${np.state}|${np.title}|${np.artist}|${np.duration}"
    if (key != m.npKey) { m.npKey = key; schedulePublish() }
}

private void schedulePublish() { if (mem().mrpStage == "READY") runInMillis(1000, "publish") }

def publish() {
    Map m = mem()
    if (m.mrpStage != "READY") return
    Map np = (m.np ?: [:]) as Map
    String bundle = np.bundle ?: ""
    String app = bundle ? (np.app ?: KNOWN_APPS[bundle] ?: bundle) : "none"
    String st = bundle ? (STATES[np.state] ?: "idle") : "idle"
    String title = (np.title ?: "").toString().trim()
    String artist = (np.artist ?: np.series ?: "").toString().trim()
    int dur = 0
    if (np.duration != null) { double d = np.duration as double; if (!d.isNaN() && !d.isInfinite() && d > 0) dur = Math.round(d) as int }
    if (device.currentValue("tvState") == "asleep") { app = "none"; bundle = ""; st = "stopped"; title = ""; artist = ""; dur = 0 }
    else if (m.waitPlay) { app = "none"; bundle = ""; st = "idle"; title = ""; artist = ""; dur = 0 }
    String sig = "${app}|${bundle}|${st}|${title}|${artist}|${dur}"
    if (sig == m.pub) return
    m.pub = sig
    sendIfChanged("currentApp", app)
    sendIfChanged("currentAppId", bundle ?: "none")
    sendIfChanged("playbackState", st)
    sendIfChanged("title", title ?: "none")
    sendIfChanged("artist", artist ?: "none")
    if (device.currentValue("duration") == null || (device.currentValue("duration") as int) != dur)
        sendEvent(name: "duration", value: dur, unit: "s", descriptionText: "${device.displayName} duration is ${dur}s")
    logN("${app}: ${st}${title ? ', ' + title : ''}${artist ? ' (' + artist + ')' : ''}")
}

// Per-app tracking like pyatv's PlayerStateManager.
private Map npClient(Map m, Map c) {
    Map clients = (m.clients ?: [:]) as Map
    String b = (c?.bundleIdentifier ?: "") as String
    Map cl = (clients[b] ?: [bundle: b, players: [:]]) as Map
    if (c?.displayName) cl.name = c.displayName
    clients[b] = cl; m.clients = clients
    return cl
}

private Map npPlayer(Map m, Map path) {
    Map cl = npClient(m, (path?.client ?: [:]) as Map)
    Map pl = (path?.player ?: [:]) as Map
    String id = (pl.identifier ?: "") as String
    Map players = cl.players as Map
    Map p = (players[id] ?: [id: id, items: [], location: 0]) as Map
    players[id] = p
    return p
}

private void npUpdate(Map m, Map msg) {
    Map ss = msg.setStateMessage as Map
    if (ss) {
        if (ss.displayName) npClient(m, ((ss.playerPath as Map)?.client ?: [:]) as Map).name = ss.displayName
        Map p = npPlayer(m, ss.playerPath as Map)
        if (ss.playbackState) p.state = ss.playbackState
        if (ss.playbackQueue instanceof Map) {
            p.items = ((ss.playbackQueue as Map).contentItems ?: []) as List
            p.location = ((ss.playbackQueue as Map).location ?: 0) as int
        }
    }
    Map uc = msg.updateContentItemMessage as Map
    if (uc) {
        Map p = npPlayer(m, uc.playerPath as Map)
        List items = new ArrayList((p.items ?: []) as List)
        (uc.contentItems ?: []).each { Map upd ->
            if (!(upd.metadata instanceof Map)) return
            Map ex = items.find { (it as Map).identifier == upd.identifier } as Map
            if (ex) ex.metadata = ((ex.metadata ?: [:]) as Map) + (upd.metadata as Map)
            else if (items.size() <= 1) { items = [upd]; p.location = 0 }   // live TV: new programme arrives as a new item
        }
        p.items = items
    }
    Map pc = msg.playerClientPropertiesMessage as Map
    if (pc) npClient(m, ((pc.playerPath as Map)?.client ?: [:]) as Map)
    Map sc = msg.setNowPlayingClientMessage as Map
    if (sc) m.activeClient = npClient(m, (sc.client ?: [:]) as Map).bundle
    Map sp = msg.setNowPlayingPlayerMessage as Map
    if (sp) {
        Map cl = npClient(m, ((sp.playerPath as Map)?.client ?: [:]) as Map)
        cl.activePlayer = npPlayer(m, sp.playerPath as Map).id
    }
    Map up = msg.updateClientMessage as Map
    if (up) npClient(m, (up.client ?: [:]) as Map)
    Map rc = msg.removeClientMessage as Map
    if (rc) {
        String b = ((rc.client as Map)?.bundleIdentifier ?: "") as String
        if (b && b == m.owner) m.ownerGone = npFor(m, b) + [state: "Stopped"]
        (m.clients as Map)?.remove(b)
        if (m.activeClient == b) m.remove("activeClient")
    }
    // The last app that played owns the display; a paused app the Apple TV falls back to can't take over.
    String played = ss?.playbackState == "Playing" ? ((((ss.playerPath as Map)?.client as Map)?.bundleIdentifier ?: "") as String) : ""
    if (played) npTake(m, played)
    if (m.activeClient && npFor(m, m.activeClient as String).state == "Playing") npTake(m, m.activeClient as String)
    m.np = npShown(m)
}

private void npTake(Map m, String b) { m.owner = b; m.remove("ownerGone"); m.remove("waitPlay") }

private Map npShown(Map m) {
    if (m.owner) {
        if (((m.clients ?: [:]) as Map)[m.owner]) return npFor(m, m.owner as String)
        if (m.ownerGone) return m.ownerGone as Map
    }
    return m.activeClient != null ? npFor(m, m.activeClient as String) : [:]
}

// A client's active player (or its default player).
private Map npFor(Map m, String b) {
    Map cl = ((m.clients ?: [:]) as Map)[b] as Map
    if (!cl) return [:]
    Map players = cl.players as Map
    Map p = (cl.activePlayer != null ? players[cl.activePlayer] : null) ?: players["MediaRemote-DefaultPlayer"] ?: (players.size() == 1 ? players.values()[0] : null)
    Map out = [app: cl.name ?: "", bundle: cl.bundle]
    if (!p) return out
    List items = (p.items ?: []) as List
    Map md = items.size() > (p.location as int) ? (((items[p.location as int] as Map).metadata ?: [:]) as Map) : null
    if (p.state) out.state = p.state
    if (md) {
        [title: "title", artist: "trackArtistName", series: "seriesName", duration: "duration"].each { k, f -> if (md[f] != null && md[f] != "") out[k] = md[f] }
    }
    return out
}

private String render(v) {
    if (v instanceof Map) return "{" + (v as Map).collect { k, x -> "${k}: ${render(x)}" }.join(", ") + "}"
    if (v instanceof List) return "[" + (v as List).collect { render(it) }.join(", ") + "]"
    if (v instanceof byte[]) return "<${(v as byte[]).length} bytes>"
    if (v instanceof String) return (v as String).length() > 80 ? "'${(v as String).take(80)}...'" : "'${v}'"
    return "${v}"
}

// ---------- helper role: one channel socket (event or data), HAP-encrypted with keys from the main device ----------
// Event channel: answers each request 200 OK. Data channel: answers each "sync" with "rply" (same seqno), passes
// protobuf to the main device, sends its protobuf as "sync"/"comm" with one fixed seqno, like pyatv.

void chOpen(Map cfg) {
    Map m = mem()
    boolean wasOpen = m.open
    m.clear()
    if (wasOpen) m.closingAt = now()   // swap: ignore the old socket's close status
    m.kind = cfg.kind
    m.ss = [outKey: fromHex(cfg.outKey as String), inKey: fromHex(cfg.inKey as String), outCount: 0L, inCount: 0L]
    m.rx = ""; m.plain = ""
    try { interfaces.rawSocket.close() } catch (e) { }
    try {
        interfaces.rawSocket.connect(cfg.ip as String, cfg.port as int, byteInterface: true)
    } catch (e) {
        parent?.channelDown(m.kind as String, "could not connect to port ${cfg.port}: ${e.message}")
        return
    }
    m.open = true
    m.sendSeq = 0x100000000L + (new java.security.SecureRandom().nextInt(Integer.MAX_VALUE) as long)
    parent?.channelUp(m.kind as String)
}

void chSendSync(String payloadHex) {
    Map m = mem()
    if (!m.open) return
    byte[] payload = fromHex(payloadHex)
    java.io.ByteArrayOutputStream h = new java.io.ByteArrayOutputStream()
    long size = 32 + payload.length
    for (int i = 3; i >= 0; i--) h.write((int) ((size >>> (8 * i)) & 0xFF))
    h.write("sync".getBytes("US-ASCII")); h.write(new byte[8]); h.write("comm".getBytes("US-ASCII"))
    long seq = m.sendSeq as long
    for (int i = 7; i >= 0; i--) h.write((int) ((seq >>> (8 * i)) & 0xFF))
    h.write(new byte[4]); h.write(payload)
    chWrite(m, h.toByteArray())
}

void chClose() {
    Map m = mem()
    m.remove("open")
    m.closingAt = now()
    try { interfaces.rawSocket.close() } catch (e) { }
}

private void chSocketStatus(String s) {
    Map m = mem()
    if (!m.open) return
    String l = s?.toLowerCase() ?: ""
    if (!(l.contains("error") || l.contains("closed") || l.contains("disconnect"))) return
    if (m.closingAt && now() - (m.closingAt as long) < 3000) return
    m.remove("open")
    parent?.channelDown(m.kind as String, s)
}

private void chParse(String hex) {
    Map m = mem()
    if (!m.open) { parent?.channelOrphan(); return }
    m.rx = (m.rx ?: "") + hex
    try {
        List d = hapDecrypt(m.ss as Map, fromHex(m.rx as String))
        m.rx = toHex(d[1] as byte[])
        m.plain = (m.plain ?: "") + toHex(d[0] as byte[])
        if (m.kind == "event") chEvents(m) else chData(m)
    } catch (e) {
        if (m.closingAt && now() - (m.closingAt as long) < 3000) { m.rx = ""; m.plain = ""; return }   // tail of the swapped-out socket
        m.remove("open")
        parent?.channelDown(m.kind as String, "could not read a message: ${e.message}")
    }
}

// RTSP-style requests from the Apple TV; reply 200 OK (Content-Length 0, Audio-Latency 0).
private void chEvents(Map m) {
    while (true) {
        byte[] buf = fromHex(m.plain as String)
        int end = indexOf(buf, "\r\n\r\n".getBytes("UTF-8"))
        if (end < 0) return
        String head = new String(cdSlice(buf, 0, end), "UTF-8")
        def cl = head =~ /(?i)content-length:\s*(\d+)/
        int len = cl.find() ? (cl.group(1) as int) : 0
        if (buf.length < end + 4 + len) return
        m.plain = toHex(cdSlice(buf, end + 4 + len, buf.length))
        List lines = head.split("\r\n") as List
        String proto = (lines[0].split(" ") as List).last()
        Map hdr = [:]
        lines.drop(1).each { l -> int i = l.indexOf(":"); if (i > 0) hdr[l.substring(0, i).trim().toLowerCase()] = l.substring(i + 1).trim() }
        StringBuilder r = new StringBuilder("${proto} 200 OK\r\nContent-Length: 0\r\nAudio-Latency: 0")
        if (hdr.server) r.append("\r\nServer: ${hdr.server}")
        if (hdr.cseq) r.append("\r\nCSeq: ${hdr.cseq}")
        r.append("\r\n\r\n")
        chWrite(m, r.toString().getBytes("UTF-8"))
    }
}

// 32-byte header (size, 12-byte type, 4-byte command, 8-byte seqno, padding), then a plist payload.
private void chData(Map m) {
    while (true) {
        byte[] buf = fromHex(m.plain as String)
        if (buf.length < 32) return
        long size = beInt(buf, 0, 4)
        if (size < 32) throw new IllegalStateException("bad data frame size ${size}")
        if (buf.length < size) return
        String type = new String(cdSlice(buf, 4, 16), "US-ASCII").replaceAll(/\x00+$/, "")
        byte[] seq = cdSlice(buf, 20, 28)
        byte[] payload = cdSlice(buf, 32, size as int)
        m.plain = toHex(cdSlice(buf, size as int, buf.length))
        if (type.startsWith("sync")) {
            java.io.ByteArrayOutputStream r = new java.io.ByteArrayOutputStream()
            [0, 0, 0, 32].each { r.write(it) }
            r.write("rply".getBytes("US-ASCII")); r.write(new byte[8]); r.write(new byte[4]); r.write(seq); r.write(new byte[4])
            chWrite(m, r.toByteArray())
        }
        if (payload.length) {
            def data = null
            try {
                def p = bplistParse(payload)
                data = p instanceof Map && p.params instanceof Map ? (p.params as Map).data : null
            } catch (e) { }
            if (data instanceof byte[]) parent?.channelProtobuf(toHex(data as byte[]))
        }
    }
}

private void chWrite(Map m, byte[] plain) {
    try { interfaces.rawSocket.sendMessage(toHex(hapEncrypt(m.ss as Map, plain))) }
    catch (e) { m.remove("open"); parent?.channelDown(m.kind as String, "send failed: ${e.message}") }
}

private long beInt(byte[] b, int p, int n) { long v = 0; for (int i = 0; i < n; i++) v = (v << 8) | (b[p + i] & 0xFF); return v }

// ---------- events and logging (routed through the app) ----------

private void sendIfChanged(String name, String value) {
    if (device.currentValue(name) == value) return
    sendEvent(name: name, value: value, descriptionText: "${device.displayName} ${name} is ${value}")
}

private int lvl() { LOG_LEVELS.indexOf(state.logLevel ?: "Errors Only") }

private void logE(String s) { slog("warn", s) }
private void logN(String s) { if (lvl() >= 1) slog("info", s) }
private void logF(String s) { if (lvl() >= 2) slog("debug", s) }

private void slog(String level, String msg) {
    try { parent.childLog(level, device.displayName, msg) }
    catch (e) {
        String line = "${device.displayName}: ${msg}"
        if (level == "warn") log.warn line else if (level == "info") log.info line else log.debug line
    }
}

// ---------- HAP framing (pyatv auth/hap_session.py) ----------

private byte[] hapNonce(long n) {
    byte[] out = new byte[8]
    for (int i = 0; i < 8; i++) out[i] = (byte) ((n >>> (8 * i)) & 0xFF)
    return out
}

private byte[] hapEncrypt(Map ss, byte[] data) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()
    for (int p = 0; p < data.length; p += 1024) {
        byte[] frame = cdSlice(data, p, Math.min(p + 1024, data.length))
        byte[] len = [frame.length & 0xFF, (frame.length >> 8) & 0xFF] as byte[]
        out.write(len)
        out.write(chachaSeal(ss.outKey as byte[], hapNonce(ss.outCount as long), frame, len))
        ss.outCount = (ss.outCount as long) + 1
    }
    return out.toByteArray()
}

// Decrypts whole blocks from raw bytes; returns [plain, leftover raw].
private List hapDecrypt(Map ss, byte[] raw) {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()
    int p = 0
    while (raw.length - p >= 2) {
        int len = (raw[p] & 0xFF) | ((raw[p + 1] & 0xFF) << 8)
        if (raw.length - p < 2 + len + 16) break
        byte[] aad = cdSlice(raw, p, p + 2)
        out.write(chachaOpen(ss.inKey as byte[], hapNonce(ss.inCount as long), cdSlice(raw, p + 2, p + 2 + len + 16), aad))
        ss.inCount = (ss.inCount as long) + 1
        p += 2 + len + 16
    }
    return [out.toByteArray(), cdSlice(raw, p, raw.length)]
}

private Map tlvOrError(byte[] body) {
    if (!body?.length) throw new IllegalStateException("empty reply from the Apple TV")
    Map t = tlv8Read(body)
    if (t[7]) {
        int code = (t[7] as byte[])[0] & 0xFF
        String why = [1: "unknown error", 2: "wrong PIN", 3: "too many tries, wait a minute and try again", 4: "too many paired devices",
                      5: "too many tries", 6: "pairing unavailable", 7: "busy, try again"][code] ?: "error ${code}"
        throw new IllegalStateException("The Apple TV said: ${why}")
    }
    return t
}

private int indexOf(byte[] hay, byte[] needle) {
    outer:
    for (int i = 0; i <= hay.length - needle.length; i++) {
        for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer
        return i
    }
    return -1
}

byte[] fromHex(String s) {
    byte[] out = new byte[(s ?: "").length() / 2]
    for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16)
    return out
}

String toHex(byte[] b) {
    StringBuilder sb = new StringBuilder()
    b.each { sb.append(String.format("%02x", it & 0xFF)) }
    return sb.toString()
}


// ---------- protobuf (proto2 wire format), driven by PB_JSON ----------

Map pbDecode(byte[] b, String type, int depth = 0) {
    Map sch = (pb().m as Map)[type] as Map
    Map out = [:]
    int p = 0
    while (p < b.length) {
        List k = pbVarintRead(b, p)
        long key = k[0] as long
        p = k[1] as int
        int num = (int) (key >>> 3), wt = (int) (key & 7)
        List f = sch ? (sch[num.toString()] as List) : null
        String name = f ? f[0] : "#${num}"
        String t = f ? f[1] : null
        boolean rep = f ? (f[2] as int) == 1 : false
        def v
        if (wt == 0) {
            List r = pbVarintRead(b, p); p = r[1] as int
            v = pbScalar(r[0] as long, t)
        } else if (wt == 1) {
            long x = pbLe(b, p, 8); p += 8
            v = t == "D" ? Double.longBitsToDouble(x) : x
        } else if (wt == 5) {
            long x = pbLe(b, p, 4); p += 4
            v = t == "F" ? (double) Float.intBitsToFloat((int) x) : x
        } else if (wt == 2) {
            List r = pbVarintRead(b, p)
            int len = (r[0] as long) as int
            p = r[1] as int
            if (len < 0 || p + len > b.length) throw new IllegalStateException("field ${name} runs past the end")
            byte[] d = cdSlice(b, p, p + len); p += len
            if (t?.startsWith("M:")) v = depth < 30 ? pbDecode(d, t.substring(2), depth + 1) : "<too deep>"
            else if (t == "S") v = new String(d, "UTF-8")
            else if (t && t != "B") {   // packed repeated numbers
                List l = (out[name] ?: []) as List
                int q = 0
                while (q < d.length) {
                    if (t == "D" || t == "X64") { long x = pbLe(d, q, 8); q += 8; l << (t == "D" ? Double.longBitsToDouble(x) : x) }
                    else if (t == "F" || t == "X32") { long x = pbLe(d, q, 4); q += 4; l << (t == "F" ? (double) Float.intBitsToFloat((int) x) : x) }
                    else { List rr = pbVarintRead(d, q); q = rr[1] as int; l << pbScalar(rr[0] as long, t) }
                }
                out[name] = l
                continue
            } else v = d
        } else throw new IllegalStateException("unsupported wire type ${wt} in ${type}")
        if (rep) { List l = (out[name] ?: []) as List; l << v; out[name] = l } else out[name] = v
    }
    return out
}

private pbScalar(long v, String t) {
    if (t == "Z") return v != 0
    if (t == "SI") return (v >>> 1) ^ -(v & 1)
    if (t?.startsWith("E:")) { Map e = (pb().e as Map)[t.substring(2)] as Map; return e?.get(v.toString()) ?: v }
    return v
}

byte[] pbEncode(Map values, String type) {
    Map sch = (pb().m as Map)[type] as Map
    if (sch == null) throw new IllegalArgumentException("unknown message ${type}")
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()
    values.each { name, val ->
        def e = sch.find { k, f -> (f as List)[0] == name }
        if (!e) throw new IllegalArgumentException("${type} has no field ${name}")
        List f = e.value as List
        ((f[2] as int) == 1 ? (val as List) : [val]).each { v -> pbField(out, (e.key as String) as int, f[1] as String, v) }
    }
    return out.toByteArray()
}

private void pbField(java.io.ByteArrayOutputStream out, int num, String t, v) {
    if (t.startsWith("M:") || t == "S" || t == "B") {
        byte[] d = t.startsWith("M:") ? pbEncode(v as Map, t.substring(2)) : t == "S" ? v.toString().getBytes("UTF-8") : v as byte[]
        pbVarintWrite(out, ((num << 3) | 2) as long); pbVarintWrite(out, d.length as long); out.write(d)
    } else if (t == "D" || t == "X64") {
        pbVarintWrite(out, ((num << 3) | 1) as long)
        long x = t == "D" ? Double.doubleToLongBits(v as double) : (v as long)
        for (int i = 0; i < 8; i++) out.write((int) ((x >>> (8 * i)) & 0xFF))
    } else if (t == "F" || t == "X32") {
        pbVarintWrite(out, ((num << 3) | 5) as long)
        long x = t == "F" ? Float.floatToIntBits(v as float) : (v as long)
        for (int i = 0; i < 4; i++) out.write((int) ((x >>> (8 * i)) & 0xFF))
    } else {
        long x
        if (t == "Z") x = v ? 1L : 0L
        else if (t.startsWith("E:")) {
            if (v instanceof Number) x = v as long
            else {
                Map e = (pb().e as Map)[t.substring(2)] as Map
                def hit = e?.find { k, n -> n == v.toString() }
                if (!hit) throw new IllegalArgumentException("no ${t.substring(2)} value ${v}")
                x = (hit.key as String) as long
            }
        } else x = v as long
        if (t == "SI") x = (x << 1) ^ (x >> 63)
        pbVarintWrite(out, ((num << 3) | 0) as long); pbVarintWrite(out, x)
    }
}

private List pbVarintRead(byte[] b, int p) {
    long v = 0
    int s = 0
    while (true) {
        if (p >= b.length) throw new IllegalStateException("varint runs past the end")
        int x = b[p++] & 0xFF
        v |= ((long) (x & 0x7F)) << s
        if ((x & 0x80) == 0) break
        s += 7
        if (s > 63) throw new IllegalStateException("varint too long")
    }
    return [v, p]
}

private void pbVarintWrite(java.io.ByteArrayOutputStream out, long v) {
    while ((v & ~0x7FL) != 0) { out.write((int) ((v & 0x7F) | 0x80)); v = v >>> 7 }
    out.write((int) v)
}

private long pbLe(byte[] b, int p, int n) {
    long v = 0
    for (int i = n - 1; i >= 0; i--) v = (v << 8) | (b[p + i] & 0xFF)
    return v
}

// ---------- binary plist writer (dict, list, string, bool, int) ----------

byte[] bplistWrite(root) {
    List objs = []
    Closure add
    add = { v ->
        int idx = objs.size()
        objs << null
        if (v instanceof Map) {
            List ks = (v as Map).keySet().collect { add(it.toString()) }
            List vs = (v as Map).values().collect { add(it) }
            objs[idx] = [t: "dict", k: ks, v: vs]
        } else if (v instanceof List) {
            objs[idx] = [t: "list", v: (v as List).collect { add(it) }]
        } else objs[idx] = [t: "val", v: v]
        return idx
    }
    add(root)
    int refSize = objs.size() < 256 ? 1 : 2
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()
    out.write("bplist00".getBytes("UTF-8"))
    List offsets = []
    objs.each { o ->
        offsets << out.size()
        if (o.t == "dict") {
            bpMarker(out, 0xD, (o.k as List).size())
            (o.k + o.v).each { bpWriteUInt(out, it as long, refSize) }
        } else if (o.t == "list") {
            bpMarker(out, 0xA, (o.v as List).size())
            (o.v as List).each { bpWriteUInt(out, it as long, refSize) }
        } else {
            def v = o.v
            if (v instanceof Boolean) out.write(v ? 0x09 : 0x08)
            else if (v instanceof byte[]) { bpMarker(out, 0x4, (v as byte[]).length); out.write(v as byte[]) }
            else if (v instanceof Number) {
                BigInteger n = v as BigInteger
                if (n.signum() >= 0 && n.bitLength() <= 8) { out.write(0x10); bpWriteUInt(out, n.longValue(), 1) }
                else if (n.signum() >= 0 && n.bitLength() <= 16) { out.write(0x11); bpWriteUInt(out, n.longValue(), 2) }
                else if (n.signum() >= 0 && n.bitLength() <= 32) { out.write(0x12); bpWriteUInt(out, n.longValue(), 4) }
                else if (n.bitLength() <= 63) { out.write(0x13); bpWriteUInt(out, n.longValue(), 8) }
                else { out.write(0x14); byte[] b = new byte[16]; byte[] m = n.toByteArray(); for (int i = 0; i < Math.min(16, m.length); i++) b[15 - i] = m[m.length - 1 - i]; out.write(b) }
            } else {
                String sv = v.toString()
                if (sv.every { (it as char) < 128 }) { bpMarker(out, 0x5, sv.length()); out.write(sv.getBytes("US-ASCII")) }
                else { bpMarker(out, 0x6, sv.length()); out.write(sv.getBytes("UTF-16BE")) }
            }
        }
    }
    int offTable = out.size()
    int offSize = offTable < 256 ? 1 : offTable < 65536 ? 2 : 4
    offsets.each { bpWriteUInt(out, it as long, offSize) }
    out.write(new byte[6])
    out.write(offSize); out.write(refSize)
    bpWriteUInt(out, objs.size() as long, 8); bpWriteUInt(out, 0L, 8); bpWriteUInt(out, offTable as long, 8)
    return out.toByteArray()
}

private void bpMarker(java.io.ByteArrayOutputStream out, int type, int count) {
    if (count < 15) { out.write((type << 4) | count); return }
    out.write((type << 4) | 0xF)
    if (count < 256) { out.write(0x10); bpWriteUInt(out, count as long, 1) }
    else if (count < 65536) { out.write(0x11); bpWriteUInt(out, count as long, 2) }
    else { out.write(0x12); bpWriteUInt(out, count as long, 4) }
}

private void bpWriteUInt(java.io.ByteArrayOutputStream out, long v, int size) {
    for (int i = size - 1; i >= 0; i--) out.write((int) ((v >>> (8 * i)) & 0xFF))
}

def bplistParse(byte[] d) {
    int n = d.length
    int offSize = d[n - 26] & 0xFF
    int refSize = d[n - 25] & 0xFF
    long count = bpUInt(d, n - 24, 8)
    long top = bpUInt(d, n - 16, 8)
    long tableOff = bpUInt(d, n - 8, 8)
    long[] offsets = new long[(int) count]
    for (int i = 0; i < count; i++) offsets[i] = bpUInt(d, (int) (tableOff + i * offSize), offSize)
    return bpObj(d, offsets, refSize, (int) top, 0)
}

private bpObj(byte[] d, long[] offsets, int refSize, int idx, int depth) {
    if (depth > 64) throw new IllegalStateException("plist nested too deeply")
    int p = (int) offsets[idx]
    int marker = d[p] & 0xFF
    int hi = marker >> 4, lo = marker & 0xF
    switch (hi) {
        case 0x0: return lo == 0x8 ? false : lo == 0x9 ? true : null
        case 0x1: return bpUInt(d, p + 1, 1 << lo)
        case 0x2: return lo == 2 ? (double) Float.intBitsToFloat((int) bpUInt(d, p + 1, 4)) : Double.longBitsToDouble(bpUInt(d, p + 1, 8))
        case 0x3: return Double.longBitsToDouble(bpUInt(d, p + 1, 8))
    }
    def (int len, int start) = bpLen(d, p, lo)
    switch (hi) {
        case 0x4: return cdSlice(d, start, start + len)
        case 0x5: return new String(cdSlice(d, start, start + len), "US-ASCII")
        case 0x6: return new String(cdSlice(d, start, start + len * 2), "UTF-16BE")
        case 0x8: return ['$uid': bpUInt(d, p + 1, lo + 1)]
        case 0xA:
            List l = []
            for (int i = 0; i < len; i++) l << bpObj(d, offsets, refSize, (int) bpUInt(d, start + i * refSize, refSize), depth + 1)
            return l
        case 0xD:
            Map m = [:]
            for (int i = 0; i < len; i++) {
                def k = bpObj(d, offsets, refSize, (int) bpUInt(d, start + i * refSize, refSize), depth + 1)
                m[k] = bpObj(d, offsets, refSize, (int) bpUInt(d, start + (len + i) * refSize, refSize), depth + 1)
            }
            return m
        default: return null
    }
}

private List bpLen(byte[] d, int p, int lo) {
    if (lo != 0xF) return [lo, p + 1]
    int size = 1 << ((d[p + 1] & 0xFF) & 0xF)
    return [(int) bpUInt(d, p + 2, size), p + 2 + size]
}

private long bpUInt(byte[] d, int p, int size) {
    long v = 0
    for (int i = 0; i < size; i++) v = (v << 8) | (d[p + i] & 0xFF)
    return v
}

// ---------- crypto and codecs (unchanged from the tested pair driver) ----------

// ---------- Ed25519 (RFC 8032), pure Groovy ----------
// The hub's Java has no Ed25519 (added in Java 15). Used only for the Companion pairing
// handshake (a few operations per connection), so BigInteger speed is fine.
// Keys and signatures are byte[]: private seed 32, public 32, signature 64.

// Constants as literals: Hubitat cannot build one static @Field from another.
@Field static final BigInteger ED_P = new BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")
@Field static final BigInteger ED_L = new BigInteger("7237005577332262213973186563042994240857116359379907606001950938285454250989")
@Field static final BigInteger ED_D = new BigInteger("37095705934669439343138083508754565189542113879843219016388785533085940283555")
@Field static final BigInteger ED_SQRT_M1 = new BigInteger("19681161376707505956807079304988542015446066515923890162744021073123829784752")
@Field static final BigInteger ED_GY = new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")

/** Public key (32 bytes) for a 32-byte private seed. */
byte[] ed25519PublicKey(byte[] seed) {
    def (BigInteger a, byte[] prefix) = edExpand(seed)
    return edCompress(edBaseMul(a))
}

/** 64-byte signature of msg with a 32-byte private seed. */
byte[] ed25519Sign(byte[] seed, byte[] msg) {
    def (BigInteger a, byte[] prefix) = edExpand(seed)
    byte[] pub = edCompress(edBaseMul(a))
    BigInteger r = edLe(edSha512([prefix, msg])).mod(ED_L)
    byte[] rs = edCompress(edBaseMul(r))
    BigInteger h = edLe(edSha512([rs, pub, msg])).mod(ED_L)
    BigInteger s = r.add(h.multiply(a)).mod(ED_L)
    return edConcat(rs, edToLe(s, 32))
}

/** True if sig is a valid signature of msg by the 32-byte public key. */
boolean ed25519Verify(byte[] pub, byte[] msg, byte[] sig) {
    if (pub == null || sig == null || pub.length != 32 || sig.length != 64) return false
    List<BigInteger> A = edDecompress(pub)
    if (A == null) return false
    byte[] rs = edSlice(sig, 0, 32)
    List<BigInteger> R = edDecompress(rs)
    if (R == null) return false
    BigInteger s = edLe(edSlice(sig, 32, 64))
    if (s >= ED_L) return false
    BigInteger h = edLe(edSha512([rs, pub, msg])).mod(ED_L)
    // s*B == R + h*A, as s*B + h*(-A) == R in one pass (Shamir)
    List<BigInteger> negA = [ED_P.subtract(A[0]).mod(ED_P), A[1], A[2], ED_P.subtract(A[3]).mod(ED_P)]
    return edEqual(edShamir(s, h, negA), R)
}

// Points are extended coordinates [X, Y, Z, T] with x = X/Z, y = Y/Z, x*y = T/Z.
private List<BigInteger> edBase() {
    List<BigInteger> b = (List) ED_CACHE.get("B")
    if (b) return b
    BigInteger x = edRecoverX(ED_GY, 0)
    b = [x, ED_GY, BigInteger.ONE, x.multiply(ED_GY).mod(ED_P)]
    ED_CACHE.put("B", b)
    return b
}

// s*B from a table of B*2^i, built once (additions only, no doublings). About 3x faster per connect.
private List<BigInteger> edBaseMul(BigInteger s) {
    List<List<BigInteger>> t = (List) ED_CACHE.get("T")
    if (!t) {
        t = []
        List<BigInteger> R = edBase()
        256.times { t << R; R = edAdd(R, R) }
        ED_CACHE.put("T", t)
    }
    List<BigInteger> Q = [BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO]
    for (int i = 0; i < 256; i++) if (s.testBit(i)) Q = edAdd(Q, t[i])
    return Q
}

private List<BigInteger> edShamir(BigInteger s, BigInteger h, List<BigInteger> A) {
    List<BigInteger> B = edBase(), BA = edAdd(B, A)
    List<BigInteger> Q = [BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO]
    for (int i = Math.max(s.bitLength(), h.bitLength()) - 1; i >= 0; i--) {
        Q = edAdd(Q, Q)
        boolean x = s.testBit(i), y = h.testBit(i)
        if (x && y) Q = edAdd(Q, BA) else if (x) Q = edAdd(Q, B) else if (y) Q = edAdd(Q, A)
    }
    return Q
}

private List<BigInteger> edAdd(List<BigInteger> P, List<BigInteger> Q) {
    BigInteger p = ED_P
    BigInteger A = P[1].subtract(P[0]).multiply(Q[1].subtract(Q[0])).mod(p)
    BigInteger B = P[1].add(P[0]).multiply(Q[1].add(Q[0])).mod(p)
    BigInteger C = BigInteger.TWO.multiply(P[3]).multiply(Q[3]).multiply(ED_D).mod(p)
    BigInteger D = BigInteger.TWO.multiply(P[2]).multiply(Q[2]).mod(p)
    BigInteger E = B.subtract(A), F = D.subtract(C), G = D.add(C), H = B.add(A)
    return [E.multiply(F).mod(p), G.multiply(H).mod(p), F.multiply(G).mod(p), E.multiply(H).mod(p)]
}

private List<BigInteger> edMul(BigInteger s, List<BigInteger> P) {
    List<BigInteger> Q = [BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO]
    List<BigInteger> R = P
    BigInteger k = s
    while (k.signum() > 0) {
        if (k.testBit(0)) Q = edAdd(Q, R)
        R = edAdd(R, R)
        k = k.shiftRight(1)
    }
    return Q
}

private boolean edEqual(List<BigInteger> P, List<BigInteger> Q) {
    BigInteger p = ED_P
    return P[0].multiply(Q[2]).subtract(Q[0].multiply(P[2])).mod(p).signum() == 0 &&
        P[1].multiply(Q[2]).subtract(Q[1].multiply(P[2])).mod(p).signum() == 0
}

private BigInteger edRecoverX(BigInteger y, int sign) {
    BigInteger p = ED_P
    if (y >= p) return null
    BigInteger x2 = y.multiply(y).subtract(BigInteger.ONE)
        .multiply(ED_D.multiply(y).multiply(y).add(BigInteger.ONE).modInverse(p)).mod(p)
    if (x2.signum() == 0) return sign ? null : BigInteger.ZERO
    BigInteger x = x2.modPow(p.add(BigInteger.valueOf(3)).shiftRight(3), p)
    if (x.multiply(x).subtract(x2).mod(p).signum() != 0) x = x.multiply(ED_SQRT_M1).mod(p)
    if (x.multiply(x).subtract(x2).mod(p).signum() != 0) return null
    if ((x.testBit(0) ? 1 : 0) != sign) x = p.subtract(x)
    return x
}

private byte[] edCompress(List<BigInteger> P) {
    BigInteger zinv = P[2].modInverse(ED_P)
    BigInteger x = P[0].multiply(zinv).mod(ED_P)
    BigInteger y = P[1].multiply(zinv).mod(ED_P)
    return edToLe(y.or(x.testBit(0) ? BigInteger.ONE.shiftLeft(255) : BigInteger.ZERO), 32)
}

private List<BigInteger> edDecompress(byte[] s) {
    BigInteger y = edLe(s)
    int sign = y.testBit(255) ? 1 : 0
    y = y.clearBit(255)
    BigInteger x = edRecoverX(y, sign)
    if (x == null) return null
    return [x, y, BigInteger.ONE, x.multiply(y).mod(ED_P)]
}

private List edExpand(byte[] seed) {
    byte[] h = edSha512([seed])
    BigInteger a = edLe(edSlice(h, 0, 32))
    a = a.and(BigInteger.ONE.shiftLeft(254).subtract(BigInteger.valueOf(8))).or(BigInteger.ONE.shiftLeft(254))
    return [a, edSlice(h, 32, 64)]
}

private byte[] edSha512(List<byte[]> parts) {
    def md = MessageDigest.getInstance("SHA-512")
    parts.each { md.update(it) }
    return md.digest()
}

private BigInteger edLe(byte[] b) {
    byte[] be = new byte[b.length]
    for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i]
    return new BigInteger(1, be)
}

private byte[] edToLe(BigInteger v, int len) {
    byte[] out = new byte[len]
    BigInteger x = v
    for (int i = 0; i < len; i++) {
        out[i] = (byte) x.and(BigInteger.valueOf(0xFF)).intValue()
        x = x.shiftRight(8)
    }
    return out
}

// java.util.Arrays is blocked by the Hubitat sandbox.
private byte[] edSlice(byte[] b, int from, int to) {
    byte[] out = new byte[to - from]
    for (int i = from; i < to; i++) out[i - from] = b[i]
    return out
}

private byte[] edConcat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length]
    for (int i = 0; i < a.length; i++) out[i] = a[i]
    for (int i = 0; i < b.length; i++) out[a.length + i] = b[i]
    return out
}

// ---------- X25519 (RFC 7748), pure Groovy ----------
// Raw 32-byte keys, so no Java key-spec classes are needed. One exchange per connection.

@Field static final BigInteger X_P = new BigInteger("57896044618658097711785492504343953926634992332820282019728792003956564819949")
@Field static final BigInteger X_A24 = new BigInteger("121665")

/** Public key (32 bytes) for a 32-byte private key. */
byte[] x25519PublicKey(byte[] priv) { x25519(priv, x9()) }

/** Shared secret (32 bytes). */
byte[] x25519Shared(byte[] priv, byte[] peerPublic) { x25519(priv, peerPublic) }

private byte[] x9() { byte[] b = new byte[32]; b[0] = 9; return b }

private byte[] x25519(byte[] scalar, byte[] uBytes) {
    byte[] k = new byte[32]
    for (int i = 0; i < 32; i++) k[i] = scalar[i]
    k[0] = (byte) (k[0] & 248)
    k[31] = (byte) ((k[31] & 127) | 64)
    byte[] u = new byte[32]
    for (int i = 0; i < 32; i++) u[i] = uBytes[i]
    u[31] = (byte) (u[31] & 127)
    BigInteger p = X_P
    BigInteger x1 = xLe(u).mod(p), x2 = BigInteger.ONE, z2 = BigInteger.ZERO, x3 = x1, z3 = BigInteger.ONE
    BigInteger kn = xLe(k)
    int swap = 0
    for (int t = 254; t >= 0; t--) {
        int kt = kn.testBit(t) ? 1 : 0
        swap ^= kt
        if (swap == 1) { def tx = x2; x2 = x3; x3 = tx; def tz = z2; z2 = z3; z3 = tz }
        swap = kt
        BigInteger A = x2.add(z2), AA = A.multiply(A).mod(p)
        BigInteger B = x2.subtract(z2), BB = B.multiply(B).mod(p)
        BigInteger E = AA.subtract(BB)
        BigInteger C = x3.add(z3), D = x3.subtract(z3)
        BigInteger DA = D.multiply(A).mod(p), CB = C.multiply(B).mod(p)
        x3 = DA.add(CB).pow(2).mod(p)
        z3 = x1.multiply(DA.subtract(CB).pow(2)).mod(p)
        x2 = AA.multiply(BB).mod(p)
        z2 = E.multiply(AA.add(X_A24.multiply(E))).mod(p)
    }
    if (swap == 1) { def tx = x2; x2 = x3; x3 = tx; def tz = z2; z2 = z3; z3 = tz }
    BigInteger r = x2.multiply(z2.modPow(p.subtract(BigInteger.TWO), p)).mod(p)
    byte[] out = new byte[32]
    for (int i = 0; i < 32; i++) out[i] = (byte) r.shiftRight(8 * i).and(BigInteger.valueOf(0xFF)).intValue()
    return out
}

private BigInteger xLe(byte[] b) {
    BigInteger v = BigInteger.ZERO
    for (int i = b.length - 1; i >= 0; i--) v = v.shiftLeft(8).or(BigInteger.valueOf(b[i] & 0xFF))
    return v
}

// ---------- SRP-6a client (HomeKit pair-setup: 3072-bit group, g = 5, SHA-512) ----------
// Mirrors srptools as used by pyatv: user "Pair-Setup", password = PIN. Values hashed unpadded
// unless noted (PAD = left-padded to 384 bytes). One run per pairing.

@Field static final BigInteger SRP_N = new BigInteger(
    "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
    "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
    "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
    "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
    "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
    "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
    "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
    "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16)
@Field static final int SRP_N_LEN = 384

/**
 * Client side of pair-setup M3. seedA: 32-byte client private. pin: 8-digit PIN as shown on the TV.
 * salt, serverPublic: from the Apple TV's M2. Returns [A, M1, M2, K] (byte[]); A and M1 go to the TV,
 * M2 is what the TV must send back, K is the session key for later steps.
 */
Map srpClient(byte[] seedA, String pin, byte[] salt, byte[] serverPublic) {
    BigInteger N = SRP_N, g = BigInteger.valueOf(5)
    BigInteger a = new BigInteger(1, seedA)
    BigInteger A = g.modPow(a, N)
    BigInteger B = new BigInteger(1, serverPublic)
    if (B.mod(N).signum() == 0) throw new IllegalArgumentException("invalid server public key")
    BigInteger k = srpHashInt([srpBytes(N), srpPad(g)])
    BigInteger u = srpHashInt([srpPad(A), srpPad(B)])
    byte[] inner = srpHash(["Pair-Setup:${pin}".toString().getBytes("UTF-8")])
    BigInteger x = srpHashInt([salt, inner])
    BigInteger S = B.subtract(k.multiply(g.modPow(x, N))).modPow(a.add(u.multiply(x)), N)
    byte[] K = srpHash([srpBytes(S)])
    BigInteger hNxorG = srpHashInt([srpBytes(N)]).xor(srpHashInt([srpBytes(g)]))
    byte[] M1 = srpHash([srpBytes(hNxorG), srpHash(["Pair-Setup".getBytes("UTF-8")]), salt, srpBytes(A), srpBytes(B), K])
    byte[] M2 = srpHash([srpBytes(A), M1, K])
    return [A: srpBytes(A), M1: M1, M2: M2, K: K]
}

private byte[] srpHash(List<byte[]> parts) {
    def md = MessageDigest.getInstance("SHA-512")
    parts.each { md.update(it) }
    return md.digest()
}

private BigInteger srpHashInt(List<byte[]> parts) { new BigInteger(1, srpHash(parts)) }

/** Minimal big-endian bytes, no sign byte (srptools int_to_bytes). */
private byte[] srpBytes(BigInteger v) {
    byte[] b = v.toByteArray()
    if (b.length > 1 && b[0] == 0) {
        byte[] out = new byte[b.length - 1]
        for (int i = 1; i < b.length; i++) out[i - 1] = b[i]
        return out
    }
    return b
}

/** Left-padded to the size of N. */
private byte[] srpPad(BigInteger v) {
    byte[] b = srpBytes(v)
    byte[] out = new byte[SRP_N_LEN]
    int off = SRP_N_LEN - b.length
    for (int i = 0; i < b.length; i++) out[off + i] = b[i]
    return out
}

// ---------- TLV8 (HomeKit pairing messages) ----------
// Map<Integer, byte[]> in insertion order. Values over 255 bytes are split into repeated tags
// and joined again on read. Empty values are skipped on write (same as pyatv).

byte[] tlv8Write(Map data) {
    def out = new java.io.ByteArrayOutputStream()
    data.each { k, v ->
        byte[] val = v as byte[]
        int pos = 0
        while (pos < val.length) {
            int size = Math.min(val.length - pos, 255)
            out.write((k as Integer) & 0xFF)
            out.write(size)
            out.write(val, pos, size)
            pos += size
        }
    }
    return out.toByteArray()
}

Map tlv8Read(byte[] data) {
    Map result = [:]
    int pos = 0
    while (pos + 1 < data.length) {
        int tag = data[pos] & 0xFF
        int len = data[pos + 1] & 0xFF
        byte[] val = cdSlice(data, pos + 2, Math.min(pos + 2 + len, data.length))
        result[tag] = result.containsKey(tag) ? cdConcat(result[tag] as byte[], val) : val
        pos += 2 + len
    }
    return result
}

// ---------- OPACK (Apple Companion messages) ----------
// Groovy types: null, Boolean, Integer/Long/BigInteger, Double/Float, String, byte[], UUID,
// List, Map. Mirrors pyatv's opack.py, including back-references for repeated values.

byte[] opackPack(Object data) {
    return opPack(data, [])
}

/** Returns [value, nextOffset]. */
List opackUnpack(byte[] data, int offset = 0) {
    return opUnpack(data, offset, [])
}

private byte[] opPack(Object data, List<byte[]> seen) {
    def out = new java.io.ByteArrayOutputStream()
    if (data == null) {
        out.write(0x04)
    } else if (data instanceof Boolean) {
        out.write(data ? 1 : 2)
    } else if (data instanceof UUID) {
        out.write(0x05)
        cdWriteLong(out, ((UUID) data).mostSignificantBits, 8, true)
        cdWriteLong(out, ((UUID) data).leastSignificantBits, 8, true)
    } else if (data instanceof Integer || data instanceof Long || data instanceof BigInteger) {
        BigInteger v = data as BigInteger
        if (v.signum() < 0) throw new IllegalArgumentException("negative ints not supported")
        if (v < 0x28) {
            out.write(v.intValue() + 8)
        } else {
            int n = v <= 0xFF ? 1 : v <= 0xFFFF ? 2 : v <= 0xFFFFFFFFL ? 4 : 8
            out.write(n == 1 ? 0x30 : n == 2 ? 0x31 : n == 4 ? 0x32 : 0x33)
            cdWriteBig(out, v, n)
        }
    } else if (data instanceof Float || data instanceof Double || data instanceof BigDecimal) {
        out.write(0x36)
        cdWriteLong(out, Double.doubleToLongBits((data as Number).doubleValue()), 8, false)
    } else if (data instanceof CharSequence) {
        byte[] s = data.toString().getBytes("UTF-8")
        cdWriteSized(out, s.length, 0x40, 0x20, 0x61)
        out.write(s, 0, s.length)
    } else if (data instanceof byte[]) {
        byte[] b = (byte[]) data
        if (b.length <= 0x20) {
            out.write(0x70 + b.length)
        } else {
            int n = b.length <= 0xFF ? 1 : b.length <= 0xFFFF ? 2 : 4
            out.write(n == 1 ? 0x91 : n == 2 ? 0x92 : 0x93)
            cdWriteBig(out, BigInteger.valueOf(b.length), n)
        }
        out.write(b, 0, b.length)
    } else if (data instanceof List) {
        List l = (List) data
        out.write(0xD0 + Math.min(l.size(), 0xF))
        l.each { byte[] p = opPack(it, seen); out.write(p, 0, p.length) }
        if (l.size() >= 0xF) out.write(0x03)
    } else if (data instanceof Map) {
        Map m = (Map) data
        out.write(0xE0 + Math.min(m.size(), 0xF))
        m.each { k, v ->
            byte[] pk = opPack(k, seen); out.write(pk, 0, pk.length)
            byte[] pv = opPack(v, seen); out.write(pv, 0, pv.length)
        }
        if (m.size() >= 0xF) out.write(0x03)
    } else {
        throw new IllegalArgumentException("cannot pack value: ${data}")
    }
    byte[] packed = out.toByteArray()
    int idx = seen.findIndexOf { cdEq(it, packed) }
    if (idx >= 0) {
        def ref = new java.io.ByteArrayOutputStream()
        if (idx < 0x21) {
            ref.write(0xA0 + idx)
        } else {
            int n = idx <= 0xFF ? 1 : idx <= 0xFFFF ? 2 : 4
            ref.write(0xC0 + n)
            cdWriteBig(ref, BigInteger.valueOf(idx), n)
        }
        return ref.toByteArray()
    }
    if (packed.length > 1) seen << packed
    return packed
}

private List opUnpack(byte[] d, int p, List seen) {
    int t = d[p] & 0xFF
    Object value
    int next
    boolean remember = true
    if (t == 0x01 || t == 0x02) {
        value = (t == 0x01); next = p + 1; remember = false
    } else if (t == 0x04) {
        value = null; next = p + 1; remember = false
    } else if (t == 0x05) {
        value = new UUID(cdReadLong(d, p + 1, 8, true), cdReadLong(d, p + 9, 8, true)); next = p + 17
    } else if (t == 0x06) {
        value = cdReadBig(d, p + 1, 8); next = p + 9
    } else if (t >= 0x08 && t <= 0x2F) {
        value = (long) (t - 8); next = p + 1; remember = false
    } else if (t == 0x35) {
        value = Float.intBitsToFloat((int) cdReadLong(d, p + 1, 4, false)); next = p + 5
    } else if (t == 0x36) {
        value = Double.longBitsToDouble(cdReadLong(d, p + 1, 8, false)); next = p + 9
    } else if ((t & 0xF0) == 0x30) {
        int n = 1 << (t & 0xF)
        BigInteger v = cdReadBig(d, p + 1, n)
        value = v.bitLength() < 64 ? v.longValue() : v; next = p + 1 + n
    } else if (t >= 0x40 && t <= 0x60) {
        int len = t - 0x40
        value = new String(cdSlice(d, p + 1, p + 1 + len), "UTF-8"); next = p + 1 + len
    } else if (t > 0x60 && t <= 0x64) {
        int n = t & 0xF
        int len = cdReadBig(d, p + 1, n).intValue()
        value = new String(cdSlice(d, p + 1 + n, p + 1 + n + len), "UTF-8"); next = p + 1 + n + len
    } else if (t >= 0x70 && t <= 0x90) {
        int len = t - 0x70
        value = cdSlice(d, p + 1, p + 1 + len); next = p + 1 + len
    } else if (t >= 0x91 && t <= 0x94) {
        int n = 1 << ((t & 0xF) - 1)
        int len = cdReadBig(d, p + 1, n).intValue()
        value = cdSlice(d, p + 1 + n, p + 1 + n + len); next = p + 1 + n + len
    } else if ((t & 0xF0) == 0xD0) {
        int count = t & 0xF
        List l = []
        int q = p + 1
        if (count == 0xF) {
            while ((d[q] & 0xFF) != 0x03) { def r = opUnpack(d, q, seen); l << r[0]; q = r[1] as int }
            q++
        } else {
            for (int i = 0; i < count; i++) { def r = opUnpack(d, q, seen); l << r[0]; q = r[1] as int }
        }
        value = l; next = q; remember = false
    } else if ((t & 0xE0) == 0xE0) {
        int count = t & 0xF
        Map m = [:]
        int q = p + 1
        if (count == 0xF) {
            while ((d[q] & 0xFF) != 0x03) {
                def k = opUnpack(d, q, seen); def v = opUnpack(d, k[1] as int, seen)
                m[k[0]] = v[0]; q = v[1] as int
            }
            q++
        } else {
            for (int i = 0; i < count; i++) {
                def k = opUnpack(d, q, seen); def v = opUnpack(d, k[1] as int, seen)
                m[k[0]] = v[0]; q = v[1] as int
            }
        }
        value = m; next = q; remember = false
    } else if (t >= 0xA0 && t <= 0xC0) {
        value = seen[t - 0xA0]; next = p + 1
    } else if (t >= 0xC1 && t <= 0xC4) {
        int n = t - 0xC0
        value = seen[cdReadBig(d, p + 1, n).intValue()]; next = p + 1 + n
    } else {
        throw new IllegalArgumentException("unknown OPACK type 0x${Integer.toHexString(t)}")
    }
    if (remember && !seen.any { cdEq(it, value) }) seen << value
    return [value, next]
}

// ---------- shared byte helpers (java.util.Arrays is blocked by the Hubitat sandbox) ----------

private boolean cdEq(a, b) {
    if (a instanceof byte[] && b instanceof byte[]) {
        byte[] x = (byte[]) a, y = (byte[]) b
        if (x.length != y.length) return false
        for (int i = 0; i < x.length; i++) if (x[i] != y[i]) return false
        return true
    }
    if (a instanceof byte[] || b instanceof byte[]) return false
    if (a instanceof Number && b instanceof Number) return (a as BigDecimal).compareTo(b as BigDecimal) == 0
    return a == b
}

private byte[] cdSlice(byte[] b, int from, int to) {
    byte[] out = new byte[to - from]
    for (int i = from; i < to; i++) out[i - from] = b[i]
    return out
}

private byte[] cdConcat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length]
    for (int i = 0; i < a.length; i++) out[i] = a[i]
    for (int i = 0; i < b.length; i++) out[a.length + i] = b[i]
    return out
}

private void cdWriteBig(java.io.ByteArrayOutputStream out, BigInteger v, int n) {
    for (int i = 0; i < n; i++) out.write(v.shiftRight(8 * i).intValue() & 0xFF)
}

private void cdWriteLong(java.io.ByteArrayOutputStream out, long v, int n, boolean bigEndian) {
    for (int i = 0; i < n; i++) {
        int shift = bigEndian ? 8 * (n - 1 - i) : 8 * i
        out.write((int) ((v >>> shift) & 0xFF))
    }
}

private void cdWriteSized(java.io.ByteArrayOutputStream out, int len, int shortBase, int shortMax, int longBase) {
    if (len <= shortMax) {
        out.write(shortBase + len)
    } else {
        int n = len <= 0xFF ? 1 : len <= 0xFFFF ? 2 : len <= 0xFFFFFF ? 3 : 4
        out.write(longBase + n - 1)
        cdWriteBig(out, BigInteger.valueOf(len), n)
    }
}

private BigInteger cdReadBig(byte[] d, int p, int n) {
    BigInteger v = BigInteger.ZERO
    for (int i = n - 1; i >= 0; i--) v = v.shiftLeft(8).or(BigInteger.valueOf(d[p + i] & 0xFF))
    return v
}

private long cdReadLong(byte[] d, int p, int n, boolean bigEndian) {
    long v = 0
    for (int i = 0; i < n; i++) {
        int b = d[p + (bigEndian ? i : n - 1 - i)] & 0xFF
        v = (v << 8) | b
    }
    return v
}

// ---------- crypto helpers ----------

byte[] hkdfSha512(String salt, String info, byte[] ikm) {
    def mac = javax.crypto.Mac.getInstance("HmacSHA512")
    // Empty salt = 64 zero bytes (RFC 5869); Java rejects an empty HMAC key.
    byte[] saltBytes = salt ? salt.getBytes("UTF-8") : new byte[64]
    mac.init(new javax.crypto.spec.SecretKeySpec(saltBytes, "HmacSHA512"))
    byte[] prk = mac.doFinal(ikm)
    mac.init(new javax.crypto.spec.SecretKeySpec(prk, "HmacSHA512"))
    mac.update(info.getBytes("UTF-8"))
    mac.update((byte) 1)
    return cdSlice(mac.doFinal(), 0, 32)
}

/** 12-byte nonce from an 8-byte label or counter: 4 zero bytes then the 8 bytes. */
private byte[] chachaNonce(nonce) {
    byte[] n8 = nonce instanceof String ? nonce.getBytes("UTF-8") : nonce as byte[]
    byte[] out = new byte[12]
    for (int i = 0; i < n8.length; i++) out[12 - n8.length + i] = n8[i]
    return out
}

byte[] chachaSeal(byte[] key, nonce, byte[] data, byte[] aad) {
    def c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
    c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
        new javax.crypto.spec.IvParameterSpec(chachaNonce(nonce)))
    if (aad) c.updateAAD(aad)
    return c.doFinal(data)
}

byte[] chachaOpen(byte[] key, nonce, byte[] data, byte[] aad) {
    def c = javax.crypto.Cipher.getInstance("ChaCha20-Poly1305")
    c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "ChaCha20"),
        new javax.crypto.spec.IvParameterSpec(chachaNonce(nonce)))
    if (aad) c.updateAAD(aad)
    return c.doFinal(data)
}

byte[] randomBytes(int n) {
    byte[] b = new byte[n]
    new java.security.SecureRandom().nextBytes(b)
    return b
}
