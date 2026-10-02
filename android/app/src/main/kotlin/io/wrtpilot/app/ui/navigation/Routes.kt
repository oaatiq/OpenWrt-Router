package io.wrtpilot.app.ui.navigation

import kotlinx.serialization.Serializable

/* Type-safe navigation destinations. */

@Serializable
data object WelcomeRoute

/** Add a router ([editId] = 0) or edit a saved one. */
@Serializable
data class RouterFormRoute(val editId: Long = 0)

@Serializable
data object HomeRoute

/** Device list, optionally pre-filtered ("online", "blocked", "paused"). */
@Serializable
data class DevicesRoute(val filter: String = "all")

@Serializable
data class DeviceRoute(val mac: String)

@Serializable
data object GroupsRoute

@Serializable
data class GroupRoute(val id: String)

@Serializable
data object QosRoute

@Serializable
data object SettingsRoute

@Serializable
data object RoutersRoute
