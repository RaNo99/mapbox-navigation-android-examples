package com.mapbox.navigation.examples.aaos.stt

/**
 * This class is used to represent the state of the user input. It can be used for speech
 * input or manual keyboard input.
 */
sealed class UserInputState {

    override fun toString(): String = this::class.simpleName ?: ""

    object Idle : UserInputState()

    data class Listening(val text: String?) : UserInputState() {

        override fun toString(): String = "${super.toString()}(text=$text)"
    }

    data class Error(val reason: String) : UserInputState() {

        override fun toString(): String = "${super.toString()}(reason=$reason)"
    }

    data class Result(val text: String) : UserInputState() {

        override fun toString(): String = "${super.toString()}(text=$text)"
    }

    companion object {
        const val ERROR_REASON_NO_RESULTS = "no_results"
    }
}
