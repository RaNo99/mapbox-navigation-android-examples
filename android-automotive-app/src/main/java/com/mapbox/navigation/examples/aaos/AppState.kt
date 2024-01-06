package com.mapbox.navigation.examples.aaos

import com.mapbox.navigation.mapgpt.shared.api.SessionFrame
import com.mapbox.navigation.mapgpt.shared.api.SessionState
import com.mapbox.navigation.mapgpt.shared.reachability.NetworkStatus
import com.mapbox.navigation.mapgpt.shared.userinput.UserInputState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

data class AppState(
    val permissionsGranted: Boolean = false,
    val isPlayerMuted: Boolean = false,
    val sessionId: String? = null,
    val appEvents: List<AppEvent> = emptyList(),
    val canPost: Boolean = true,
    val sessionState: SessionState = SessionState.Disconnected,
    val conversationState: String = "",
    val networkStatus: NetworkStatus = NetworkStatus.NotReachable,
    val userInputState: UserInputState = UserInputState.Idle,
)

sealed class AppEvent {

    val timeMillis = System.currentTimeMillis()
    val displayTime = getFormattedTimeString()

    data class Request(
        val text: String,
    ) : AppEvent()

    data class Frame(
        val value: SessionFrame,
    ) : AppEvent()

    data class Error(
        val value: String,
    ) : AppEvent()
}

private fun getFormattedTimeString(): String {
    val time = Calendar.getInstance().time
    val formatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    return formatter.format(time)
}
