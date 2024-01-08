package com.mapbox.navigation.examples.aaos.stt

import androidx.annotation.RestrictTo
import androidx.lifecycle.Lifecycle.Event.ON_CREATE
import androidx.lifecycle.Lifecycle.Event.ON_PAUSE
import androidx.lifecycle.Lifecycle.Event.ON_RESUME
import androidx.lifecycle.Lifecycle.Event.ON_START
import androidx.lifecycle.Lifecycle.Event.ON_STOP
import androidx.lifecycle.Lifecycle.State
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import com.mapbox.navigation.examples.aaos.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** @suppress */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP_PREFIX)
class ApplicationLifecycleObserver : LifecycleObserver {

    private val _appLifecycle: MutableStateFlow<State> = MutableStateFlow(State.INITIALIZED)
    val appLifecycle: StateFlow<State> = _appLifecycle

    val appForeground: Flow<Boolean> = _appLifecycle
        .map {
            Log.i("LifecycleObserver") { "State: $it" }
            when (it) {
                State.STARTED, State.RESUMED -> true
                else -> false
            }
        }
        .distinctUntilChanged()

    @OnLifecycleEvent(ON_CREATE)
    fun onAppCreated() {
        _appLifecycle.value = State.CREATED
    }

    @OnLifecycleEvent(ON_START)
    fun onAppStarted() {
        _appLifecycle.value = State.STARTED
    }

    @OnLifecycleEvent(ON_RESUME)
    fun onAppResumed() {
        _appLifecycle.value = State.RESUMED
    }

    @OnLifecycleEvent(ON_PAUSE)
    fun onAppPaused() {
        _appLifecycle.value = State.STARTED
    }

    @OnLifecycleEvent(ON_STOP)
    fun onAppStopped() {
        _appLifecycle.value = State.CREATED
    }
}
