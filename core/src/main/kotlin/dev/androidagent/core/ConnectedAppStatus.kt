package dev.androidagent.core

import dev.heymike.connect.Capability

enum class ConnectedAppState {
    /** Mike cannot use the app. The default for every app. */
    OFF,

    /** The user turned the connection on. */
    ON,

    /** The app is signed by someone other than the publisher the user once trusted. Off until reviewed. */
    PUBLISHER_CHANGED,
}

/**
 * What the Connected apps screen says about one app. It is derived from the saved
 * grant and the abilities the app offers now, and nothing else, so a screen cannot
 * show a switch that disagrees with what [ConnectedAppsGateway] will allow.
 */
data class ConnectedAppStatus(
    val state: ConnectedAppState,
    /** Abilities Mike can use right now: on in the grant and unchanged since the user saw them. */
    val allowed: Int,
    /** Abilities the app offers that the user never turned on or off; null while the app's list is unknown. */
    val waiting: Int?,
) {
    val isOn: Boolean get() = state == ConnectedAppState.ON
}

/**
 * [abilities] is the app's current list, or null when it has not been read. The
 * list is read on demand: discovering an app reads its manifest and never starts it.
 */
fun connectedAppStatus(
    provider: ConnectProvider,
    grants: List<ConnectionGrant>,
    abilities: List<Capability>?,
): ConnectedAppStatus {
    val grant = grants.find { it.packageName == provider.packageName && it.signer == provider.signer }
    if (grant == null) {
        val otherPublisher = grants.any { it.packageName == provider.packageName }
        return ConnectedAppStatus(if (otherPublisher) ConnectedAppState.PUBLISHER_CHANGED else ConnectedAppState.OFF, 0, null)
    }
    val state = if (grant.enabled) ConnectedAppState.ON else ConnectedAppState.OFF
    if (abilities == null) {
        return ConnectedAppStatus(state, if (grant.enabled) grant.capabilities.count { it.enabled } else 0, null)
    }
    val known = abilities.associateWith { cap ->
        grant.capabilities.find { it.name == cap.name && it.policyHash == cap.policyHash() }
    }
    return ConnectedAppStatus(
        state = state,
        allowed = if (grant.enabled) known.values.count { it?.enabled == true } else 0,
        waiting = known.values.count { it == null },
    )
}

/** The hub row under Settings: one line that says whether any app is on. */
fun connectedAppsSummary(grants: List<ConnectionGrant>): String {
    val on = grants.filter { it.enabled }
    return when (on.size) {
        0 -> "Off · choose which apps Mike can use"
        1 -> "1 app on"
        else -> "${on.size} apps on"
    }
}
