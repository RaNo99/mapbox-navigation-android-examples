package com.mapbox.navigation.examples.aaos

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log
import com.mapbox.dash.sdk.CoroutineMiddleware
import com.mapbox.dash.sdk.MiddlewareContext
import com.mapbox.dash.sdk.PlatformContext
import com.mapbox.dash.sdk.audiofocus.MapGptAudioFocusManager
import com.mapbox.navigation.mapgpt.shared.api.ConversationState
import com.mapbox.navigation.mapgpt.shared.api.InteractionHistoryElement
import com.mapbox.navigation.mapgpt.shared.api.MapGptContextDTO
import com.mapbox.navigation.mapgpt.shared.api.MapGptService
import com.mapbox.navigation.mapgpt.shared.api.MapGptStreamingRequest
import com.mapbox.navigation.mapgpt.shared.api.MapGptUserContextDTO
import com.mapbox.navigation.mapgpt.shared.api.SessionFrame
import com.mapbox.navigation.mapgpt.shared.api.SessionState
import com.mapbox.navigation.mapgpt.shared.common.PlatformSettingsFactory
import com.mapbox.navigation.mapgpt.shared.common.SharedLog
import com.mapbox.navigation.mapgpt.shared.common.SharedSettingsObserver
import com.mapbox.navigation.mapgpt.shared.common.getString
import com.mapbox.navigation.mapgpt.shared.common.setString
import com.mapbox.navigation.mapgpt.shared.language.Language
import com.mapbox.navigation.mapgpt.shared.language.LanguageRepository
import com.mapbox.navigation.mapgpt.shared.reachability.SharedReachability
import com.mapbox.navigation.mapgpt.shared.textplayer.Announcement
import com.mapbox.navigation.mapgpt.shared.textplayer.DashVoice
import com.mapbox.navigation.mapgpt.shared.textplayer.MapGptVoicePlayerOptions
import com.mapbox.navigation.mapgpt.shared.textplayer.Player
import com.mapbox.navigation.mapgpt.shared.textplayer.PlayerFactory
import com.mapbox.navigation.mapgpt.shared.textplayer.options.PlayerOptions
import com.mapbox.navigation.mapgpt.shared.userinput.SpeechRecognizerOwner
import com.mapbox.navigation.mapgpt.shared.userinput.UserInputMiddlewareContext
import com.mapbox.navigation.mapgpt.shared.userinput.UserInputState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class ExampleMapGptCarManager(
    context: Context,
    mapboxAccessToken: String
) : CoroutineMiddleware<MiddlewareContext>() {
    private val mapGptService = MapGptService.create(mapboxAccessToken)
    private val sharedReachability = SharedReachability()
    private val settings = PlatformSettingsFactory.createPersistentSettings()
    private val sessionChangedLogger = SharedSettingsObserver { key, oldValue, newValue ->
        SharedLog.i(TAG) { "sessionId changed: $key, $oldValue, $newValue" }
    }

    private val language = MutableStateFlow(Language(Locale.US.toLanguageTag()))
    private val languageRepository = LanguageRepository()
    private val playerFactory = PlayerFactory(context, languageRepository)
    private val player: Player = playerFactory.createDefault(
        MapGptVoicePlayerOptions(
            mapboxAccessToken,
            setOf(DashVoice.voice1),
        )
    )

    private var priorityAnnouncement =
        Announcement.Priority(text = "In a quarter mile turn right onto Elm street")
    private val userInputContext = UserInputMiddlewareContext(
        platformContext = PlatformContext(context),
        language = language,
        isReachable = sharedReachability.isReachable,
        audioFocusManager = MapGptAudioFocusManager(
            context = context,
            playerOptions = PlayerOptions.Builder()
                .contentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .usage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .focusGain(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .build(),
        ),
    )

    val userInputOwner: SpeechRecognizerOwner = SpeechRecognizerOwner()

    private val _state = MutableStateFlow(AppState())
    val state = _state.asStateFlow()

    override fun onAttached(middlewareContext: MiddlewareContext) {
        super.onAttached(middlewareContext)
        settings.registerObserver("sessionId", sessionChangedLogger)
        observeSessionState()
        observeUserInputState()
        observeSessionFrames()
        observeConversationState()
        observeRespondingChunks()
        observeNetworkStatus()
        connectToService()

        player.prefetch(priorityAnnouncement)
        userInputOwner.onAttached(userInputContext)
        userInputOwner.state.onEach { userInputState ->
            _state.update { it.copy(userInputState = userInputState) }
        }.launchIn(mainScope)
    }

    override fun onDetached(middlewareContext: MiddlewareContext) {
        settings.unregisterObserver(sessionChangedLogger)
        mapGptService.close()
        player.clear()
        userInputOwner.onDetached(userInputContext)
        super.onDetached(middlewareContext)
    }

    fun onClearSessionId() {
        settings.erase("sessionId")
        _state.update {
            it.copy(appEvents = emptyList())
        }
        connectToService()
    }

    fun onPlayTBTInstruction() {
        player.play(priorityAnnouncement)
    }

    fun onMutePlayerChange(mute: Boolean) {
        player.isMuted = mute
        _state.update { it.copy(isPlayerMuted = mute) }
    }

    fun onPostPrompts(prompt: String) {
        val userContext = MapGptContextDTO(
            userContext = MapGptUserContextDTO(
                lat = "37.7694777",
                lon = "-122.486135",
                placeName = "Golden Gate Park, San Francisco, CA",
            ),
        )

        mainScope.launch {
            runCatching {
                val request = MapGptStreamingRequest(
                    prompt = prompt,
                    context = userContext,
                    profileId = PROFILE_ID
                )
                mapGptService.postPromptsForStreaming(
                    request,
                )
                Log.i(TAG, "POST request sent")
            }.onFailure { throwable ->
                Log.e(TAG, "POST request failed", throwable)
                _state.update {
                    it.copy(
                        appEvents = it.appEvents + AppEvent.Error(throwable.message.toString()),
                        canPost = true,
                    )
                }
            }
        }
    }

    fun onStartListening() {
        player.clear()
        userInputOwner.startListening()
    }

    fun onPermissionsGranted(granted: Boolean) {
        _state.update { it.copy(permissionsGranted = granted) }
    }

    private fun connectToService() {
        val reconnectSessionId = settings.getString("sessionId")
        mapGptService.connect(
            reconnectSessionId = reconnectSessionId,
        )
    }

    private fun observeSessionFrames() {
        mainScope.launch {
            mapGptService.sessionFrame.collect { frame ->
                Log.i(TAG, "session frame: ${frame.toJsonString()}")
                _state.update {
                    it.copy(
                        appEvents = it.appEvents + AppEvent.Frame(frame),
                    )
                }
            }
        }
        mainScope.launch {
            mapGptService.sessionError.collect { error ->
                Log.e(TAG, "session error: ${error.message}")
                error.printStackTrace()
                _state.update {
                    it.copy(
                        appEvents = it.appEvents + AppEvent.Error("error: ${error.message}"),
                        canPost = true,
                    )
                }
            }
        }
    }

    private fun observeSessionState() {
        mainScope.launch {
            mapGptService.sessionState.collect { sessionState ->
                Log.i(TAG, "session state: $sessionState")
                when (sessionState) {
                    is SessionState.Connected -> {
                        _state.update {
                            it.copy(
                                sessionState = sessionState,
                                sessionId = sessionState.sessionId,
                            )
                        }
                        settings.setString("sessionId", sessionState.sessionId)
                    }

                    is SessionState.Connecting -> {
                        _state.update {
                            it.copy(
                                sessionState = sessionState,
                            )
                        }
                    }

                    SessionState.Disconnected -> {
                        _state.update {
                            it.copy(
                                sessionId = null,
                                sessionState = sessionState,
                            )
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeRespondingChunks() {
        mainScope.launch {
            mapGptService.conversationStatus.filterIsInstance<ConversationState.Responding>()
                .flatMapLatest { it.bufferedConversation.chunks }
                .collect { chunk ->
                    player.play(Announcement.Regular(text = chunk.data.content))
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeConversationState() {
        mainScope.launch {
            mapGptService.conversationState.flatMapLatest { conversationState ->
                when (conversationState) {
                    ConversationState.Idle -> {
                        mapGptService.interactionHistory.map { history ->
                            val bufferedConversation = when (val element = history.lastOrNull()) {
                                is InteractionHistoryElement.Output -> {
                                    element.bufferedConversation
                                }

                                else -> {
                                    null
                                }
                            }
                            "[Idle] ${bufferedConversation?.bufferedText?.value ?: ""}"
                        }
                    }

                    is ConversationState.ProcessingInput -> flowOf("[Processing]")
                    is ConversationState.Responding ->
                        conversationState.bufferedConversation.bufferedText.map {
                            "[Responding] $it"
                        }
                }
            }.collect { conversationText ->
                Log.i(TAG, "conversation text: $conversationText")
                _state.update {
                    it.copy(conversationState = conversationText)
                }
            }
        }

        mainScope.launch {
            mapGptService.interactionHistory.collect { history ->
                when (val element = history.lastOrNull()) {
                    is InteractionHistoryElement.Input -> {
                        val requestEvent = AppEvent.Request(text = element.text)
                        _state.update {
                            it.copy(
                                appEvents = it.appEvents + requestEvent,
                            )
                        }
                    }

                    else -> {
                        // no-op
                    }
                }
            }
        }

        mainScope.launch {
            mapGptService.activeConversationEvents(
                type = SessionFrame.SendEvent.Body.Entity::class,
            ).collect {
                Log.d(TAG, "active conversation entity received: $it")
            }
        }
    }

    private fun observeUserInputState() {
        mainScope.launch {
            mapGptService.sessionState.combine(
                mapGptService.conversationState,
            ) { session: SessionState, conversation: ConversationState ->
                session is SessionState.Connected && conversation == ConversationState.Idle
            }.distinctUntilChanged().collect { canPost ->
                Log.i(TAG, "can post: $canPost")
                _state.update {
                    it.copy(canPost = canPost)
                }
            }
        }

        mainScope.launch {
            state.map { it.userInputState }
                .distinctUntilChanged()
                .filterIsInstance<UserInputState.Result>()
                .collect {
                    onPostPrompts(it.text)
                    userInputOwner.stopListening()
                }
        }
    }

    private fun observeNetworkStatus() {
        sharedReachability.networkStatus.onEach { networkStatus ->
            _state.update { it.copy(networkStatus = networkStatus) }
        }.launchIn(mainScope)
    }

    private companion object {
        private const val TAG = "ExampleMapGptService"
        /**
         * Update your profileId here.
         */
        private const val PROFILE_ID = "mapbox://mapgpt-profiles/haekjlee/poc"
    }
}