package com.mapbox.navigation.examples.aaos.stt

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

class UserInputContext(
    /**
     * Platform specific context.
     */
    val context: Context,

    /**
     * The assigned language for the user input.
     */
    val language: StateFlow<Language>,

    /**
     * The current reachability of the device. Returns true if the device is reachable from the
     * internet, false otherwise. Used for detecting when offline services are needed.
     */
    val isReachable: StateFlow<Boolean>,
)
