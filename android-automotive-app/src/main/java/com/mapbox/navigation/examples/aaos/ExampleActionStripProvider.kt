package com.mapbox.navigation.examples.aaos

import android.util.Log
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import com.mapbox.androidauto.action.MapboxScreenActionStripProvider
import com.mapbox.androidauto.freedrive.FreeDriveActionStrip
import com.mapbox.navigation.base.ExperimentalPreviewMapboxNavigationAPI
import com.mapbox.navigation.mapgpt.shared.userinput.UserInputOwner
import com.mapbox.navigation.mapgpt.shared.userinput.UserInputState

@OptIn(ExperimentalPreviewMapboxNavigationAPI::class)
class ExampleActionStripProvider(
    private val userInputOwner: UserInputOwner,
) : MapboxScreenActionStripProvider() {
    override fun getFreeDrive(screen: Screen): ActionStrip {
        Log.i(TAG, "getFreeDrive ActionStrip")
        val builder = FreeDriveActionStrip(screen)
        return ActionStrip.Builder()
            .addAction(builder.buildSettingsAction())
            .addAction(builder.buildFeedbackAction())
            .addAction(builder.buildSearchAction())
            .addAction(
                Action.Builder()
                    .setIcon(
                        CarIcon.Builder(
                            IconCompat.createWithResource(
                                screen.carContext,
                                R.drawable.ic_example_mic_24
                            )
                        ).build()
                    )
                    .setOnClickListener {
                        if (userInputOwner.state.value == UserInputState.Idle) {
                            userInputOwner.startListening()
                        } else {
                            userInputOwner.stopListening()
                        }
                    }
                    .build()
            )
            .build()
    }

    private companion object {
        private const val TAG = "ExampleActionStripProvider"
    }
}
