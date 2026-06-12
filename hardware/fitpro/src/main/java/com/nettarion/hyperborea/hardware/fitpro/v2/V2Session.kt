package com.nettarion.hyperborea.hardware.fitpro.v2

import com.nettarion.hyperborea.core.AppLogger
import com.nettarion.hyperborea.core.model.ConsoleKey
import com.nettarion.hyperborea.core.model.DeviceCommand
import com.nettarion.hyperborea.core.model.DeviceIdentity
import com.nettarion.hyperborea.core.model.DeviceInfo
import com.nettarion.hyperborea.core.model.DeviceType
import com.nettarion.hyperborea.core.model.ExerciseData
import com.nettarion.hyperborea.core.model.isBeltBased
import com.nettarion.hyperborea.hardware.fitpro.session.ExerciseDataAccumulator
import com.nettarion.hyperborea.hardware.fitpro.session.FitProSession
import com.nettarion.hyperborea.hardware.fitpro.session.GripHeartRateFilter
import com.nettarion.hyperborea.hardware.fitpro.session.SessionState
import com.nettarion.hyperborea.hardware.fitpro.transport.HidTransport
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class V2Session(
    private val transport: HidTransport,
    private val logger: AppLogger,
    private val scope: CoroutineScope,
    private val deviceInfo: DeviceInfo,
    private val accumulator: ExerciseDataAccumulator = ExerciseDataAccumulator(),
) : FitProSession {

    private val _exerciseData = MutableStateFlow<ExerciseData?>(null)
    override val exerciseData: StateFlow<ExerciseData?> = _exerciseData.asStateFlow()

    private val _deviceIdentity = MutableStateFlow<DeviceIdentity?>(null)
    override val deviceIdentity: StateFlow<DeviceIdentity?> = _deviceIdentity.asStateFlow()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected)
    override val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    // The V2 protocol has no console-keypad field — this never emits.
    override val consoleKeyPresses: SharedFlow<ConsoleKey> = MutableSharedFlow()

    private val _degradedReason = MutableStateFlow<String?>(null)
    override val degradedReason: StateFlow<String?> = _degradedReason.asStateFlow()

    /** Latest [V2FeatureId.WORKOUT_STATE] value reported by the console (raw [V2WorkoutMode] ordinal); null until first event. */
    private val _workoutMode = MutableStateFlow<Float?>(null)

    /**
     * The complete feature set the console declared, published once the multi-frame
     * supported-features list terminates (see [V2Message.Incoming.SupportedFeatures]).
     */
    private val _supportedFeatures = MutableStateFlow<Set<V2FeatureId>?>(null)

    /** Union of supported-features frames received so far (the list arrives in pieces). */
    private val featureAccumulator = mutableSetOf<V2FeatureId>()
    private val unknownFeatureCodes = mutableSetOf<Int>()

    /**
     * Equipment type derived from the supported-features set after the QueryFeatures handshake. The
     * V2 protocol has no explicit equipment-id field (V1's [DEVICE_TREADMILL]/[DEVICE_FITNESS_BIKE]
     * branch is absent), so we infer it: a belt-driven machine reports speed/grade features but no
     * flywheel-resistance features. Defaults to [DeviceType.BIKE] before [awaitDeviceType] resolves.
     */
    override var detectedDeviceType: DeviceType = DeviceType.BIKE
        private set

    private var receiveJob: Job? = null
    private var lastSentGrade = 0f
    private var lastSentSpeed = 0f
    private val gripHeartRate = GripHeartRateFilter()

    override suspend fun start() {
        if (_sessionState.value is SessionState.Streaming || _sessionState.value is SessionState.Connecting) return

        try {
            _sessionState.value = SessionState.Connecting
            transport.open()

            _sessionState.value = SessionState.Handshaking
            startReceiveLoop()   // must run before the query so the reply isn't raced

            // The console must declare its supported features before we subscribe — it rejects
            // subscriptions to features it doesn't implement. The feature set also tells us the
            // equipment type; transitionToWorkout() branches on this.
            val supported = querySupportedFeatures(QUERY_FEATURES_ATTEMPTS)
            if (supported != null) {
                detectedDeviceType = deriveDeviceType(supported)
                logger.i(TAG, "Detected device type: $detectedDeviceType (from ${supported.size} features)")
            } else {
                logger.w(TAG, "Console never declared supported features — assuming $detectedDeviceType, subscribing unfiltered")
            }
            configureSubscriptions(supported)
            _deviceIdentity.value = DeviceIdentity()

            // Bring the console up to the workout-active state the way the firmware expects.
            transitionToWorkout()

            accumulator.start()
            _sessionState.value = SessionState.Streaming
            logger.i(TAG, "V2 session started")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to start V2 session", e)
            receiveJob?.cancel()
            receiveJob = null
            try { transport.close() } catch (_: Exception) {}
            _sessionState.value = SessionState.Error(e.message ?: "V2 session failed", e)
        }
    }

    override suspend fun stop() {
        receiveJob?.cancel()
        receiveJob = null

        try {
            if (transport.isOpen) {
                haltForTeardown()
                // Return the console to idle before disconnecting
                transport.write(V2Codec.encode(
                    V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.NONE.raw),
                ))
                transport.write(V2Codec.encode(V2Message.Outgoing.Unsubscribe(V2FeatureId.subscribable)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(TAG, "Error during V2 session stop: ${e.message}")
        } finally {
            // Always release the USB connection, even when the teardown writes fail on a dead
            // link — skipping this leaks the connection (and the interface claim) until the GC
            // finalizer complains.
            try { transport.close() } catch (e: Exception) { logger.w(TAG, "Transport close failed: ${e.message}") }
        }

        accumulator.reset()
        featureAccumulator.clear()
        unknownFeatureCodes.clear()
        _supportedFeatures.value = null
        _exerciseData.value = null
        _deviceIdentity.value = null
        _degradedReason.value = null
        _sessionState.value = SessionState.Disconnected
        logger.i(TAG, "V2 session stopped")
    }

    /**
     * Belt machines must be explicitly stopped before the console drops to idle, or the belt keeps
     * running (the V1-confirmed bug; mirrored here for protocol parity). Command belt speed to 0 and
     * `PAUSED` — both halt the belt — then let the writes settle before teardown. V2 is event-driven
     * with no synchronous read-back (and no ready-to-disconnect signal), so unlike V1's graceful
     * teardown this is best-effort. Non-belt machines have nothing the app drives that keeps moving,
     * so this is a no-op for them.
     */
    private suspend fun haltForTeardown() {
        if (!detectedDeviceType.isBeltBased) return
        transport.write(V2Codec.encode(V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_KPH, 0f)))
        // Drop incline to 0 too, so an incline trainer doesn't park raised after teardown.
        transport.write(V2Codec.encode(V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, 0f)))
        transport.write(V2Codec.encode(
            V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.PAUSED.raw),
        ))
        delay(BELT_HALT_SETTLE_MS)
    }

    override suspend fun identify(): DeviceIdentity? {
        try {
            transport.open()
            startReceiveLoop()
            // V2 has no identity fields to read; one features query confirms the console is talking.
            val supported = querySupportedFeatures(attempts = 1)
            logger.i(TAG, if (supported != null) "Console replied with ${supported.size} features" else "Console didn't reply to features query")
            _deviceIdentity.value = DeviceIdentity()
            return _deviceIdentity.value
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            logger.e(TAG, "Identify failed", e)
            return null
        } finally {
            receiveJob?.cancel()
            receiveJob = null
            try { transport.close() } catch (_: Exception) {}
        }
    }

    override suspend fun calibrate() {
        throw UnsupportedOperationException("CalibrateIncline not supported on V2")
    }

    override suspend fun writeFeature(command: DeviceCommand) {
        if (_sessionState.value !is SessionState.Streaming) return

        val message = when (command) {
            is DeviceCommand.SetResistance -> V2Message.Outgoing.WriteFeature(
                V2FeatureId.TARGET_RESISTANCE,
                command.level.toFloat(),
            )
            is DeviceCommand.SetIncline -> {
                lastSentGrade = roundToStep(command.percent, deviceInfo.inclineStep)
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, lastSentGrade)
            }
            is DeviceCommand.SetTargetSpeed -> {
                lastSentSpeed = command.kph
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_KPH, command.kph)
            }
            is DeviceCommand.AdjustIncline -> {
                lastSentGrade += if (command.increase) deviceInfo.inclineStep else -deviceInfo.inclineStep
                lastSentGrade = lastSentGrade.coerceIn(deviceInfo.minIncline, deviceInfo.maxIncline)
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_GRADE, lastSentGrade)
            }
            is DeviceCommand.AdjustSpeed -> {
                lastSentSpeed += if (command.increase) deviceInfo.speedStep else -deviceInfo.speedStep
                lastSentSpeed = lastSentSpeed.coerceIn(0f, deviceInfo.maxSpeed)
                V2Message.Outgoing.WriteFeature(V2FeatureId.TARGET_KPH, lastSentSpeed)
            }
            is DeviceCommand.SetTargetPower -> V2Message.Outgoing.WriteFeature(
                V2FeatureId.GOAL_WATTS,
                command.watts.toFloat(),
            )
            is DeviceCommand.PauseWorkout -> {
                accumulator.pause()
                V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.PAUSED.raw)
            }
            is DeviceCommand.ResumeWorkout -> {
                accumulator.resume()
                V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, V2WorkoutMode.RUNNING.raw)
            }
            is DeviceCommand.CalibrateIncline -> {
                logger.w(TAG, "CalibrateIncline not supported on V2")
                return
            }
            is DeviceCommand.SetFanSpeed -> {
                logger.w(TAG, "SetFanSpeed not supported on V2")
                return
            }
            is DeviceCommand.SetVolume,
            is DeviceCommand.SetGear,
            is DeviceCommand.SetDistanceGoal,
            is DeviceCommand.SetWarmupTimeout,
            is DeviceCommand.SetCooldownTimeout,
            is DeviceCommand.SetPauseTimeout,
            is DeviceCommand.SetWarmUpMode,
            is DeviceCommand.SetCoolDownMode,
            is DeviceCommand.SetErgMode -> {
                logger.w(TAG, "${command::class.simpleName} not supported on V2")
                return
            }
        }

        try {
            transport.write(V2Codec.encode(message))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(TAG, "Failed to write feature", e)
        }
    }

    /**
     * Asks the console for its supported-features list, waiting [SUPPORTED_FEATURES_TIMEOUT_MS]
     * per attempt (it's the console's slowest reply). The list arrives as a frame series ending
     * in an empty terminator frame; if frames arrived but the terminator never did, the partial
     * union is better than nothing. Returns null only if the console never answered at all.
     */
    private suspend fun querySupportedFeatures(attempts: Int): Set<V2FeatureId>? {
        repeat(attempts) { attempt ->
            transport.write(V2Codec.encode(V2Message.Outgoing.QueryFeatures()))
            val features = withTimeoutOrNull(SUPPORTED_FEATURES_TIMEOUT_MS) {
                _supportedFeatures.filterNotNull().first()
            }
            if (features != null) return features
            logger.w(TAG, "No complete supported-features reply within ${SUPPORTED_FEATURES_TIMEOUT_MS}ms (attempt ${attempt + 1}/$attempts)")
        }
        if (featureAccumulator.isNotEmpty()) {
            logger.w(TAG, "Supported-features list never terminated — using ${featureAccumulator.size} accumulated features")
            return featureAccumulator.toSet()
        }
        return null
    }

    /**
     * Clears whatever subscriptions a previous client left active (an empty Unsubscribe), then
     * subscribes to the features we want — restricted to what the console declared it supports,
     * because consoles reject Subscribe commands naming features they don't implement. With a
     * null [supported] (console never answered the query) we subscribe unfiltered and let the
     * console sort it out.
     */
    private suspend fun configureSubscriptions(supported: Set<V2FeatureId>?) {
        transport.write(V2Codec.encode(V2Message.Outgoing.Unsubscribe(emptyList())))

        val wanted = V2FeatureId.subscribable.filter { supported == null || it in supported }
        if (wanted.isEmpty()) {
            logger.w(TAG, "Console supports none of the features we want — no subscriptions made")
            return
        }
        val batches = wanted.chunked(MAX_SUBSCRIBE_BATCH)
        logger.d(TAG, "Subscribing to ${wanted.size} features in ${batches.size} batches")
        for (batch in batches) {
            transport.write(V2Codec.encode(V2Message.Outgoing.Subscribe(batch)))
        }
    }

    private fun startReceiveLoop() {
        receiveJob = scope.launch {
            try {
                transport.incoming().collect { data ->
                    handleIncoming(data)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(TAG, "Receive loop error", e)
            }

            // Flow completed = device disconnected
            if (_sessionState.value is SessionState.Streaming) {
                logger.w(TAG, "Transport disconnected")
                _sessionState.value = SessionState.Disconnected
            }
        }
    }

    private var lastLogTimeMs = 0L

    private fun handleIncoming(data: ByteArray) {
        val message = V2Codec.decode(data) ?: return

        when (message) {
            is V2Message.Incoming.Event -> {
                applyEvent(message.feature, message.value)
                _exerciseData.value = accumulator.snapshot()

                val now = System.currentTimeMillis()
                if (now - lastLogTimeMs >= 1000L) {
                    lastLogTimeMs = now
                    val snap = _exerciseData.value
                    if (snap != null) {
                        logger.d(TAG, "power=${snap.power}W cadence=${snap.cadence}rpm speed=${snap.speed}kph resistance=${snap.resistance} incline=${snap.incline}%")
                    }
                }
            }
            is V2Message.Incoming.SupportedFeatures -> {
                if (message.isEndOfList) {
                    if (unknownFeatureCodes.isNotEmpty()) {
                        logger.i(TAG, "Console declared ${unknownFeatureCodes.size} feature ids we don't use: $unknownFeatureCodes")
                    }
                    logger.i(TAG, "Supported features complete: ${featureAccumulator.map { it.name }}")
                    _supportedFeatures.value = featureAccumulator.toSet()
                } else {
                    featureAccumulator += message.features
                    unknownFeatureCodes += message.unknownCodes
                    logger.d(TAG, "Supported-features frame: ${message.features.map { it.name }}" +
                        if (message.unknownCodes.isNotEmpty()) " + unknown ${message.unknownCodes}" else "")
                }
            }
            is V2Message.Incoming.Acknowledge ->
                logger.d(TAG, "ACK: ${message.type}")
            is V2Message.Incoming.Error ->
                logger.w(TAG, "Console rejected ${message.describe()}")
            is V2Message.Incoming.Unknown ->
                logger.d(TAG, "Unknown message: ${message.raw.size} bytes")
        }
    }

    private fun applyEvent(feature: V2FeatureId, value: Float) {
        when (feature) {
            V2FeatureId.WATTS -> accumulator.updatePower(value.toInt())
            V2FeatureId.RPM -> accumulator.updateCadence(value.toInt())
            V2FeatureId.CURRENT_KPH -> accumulator.updateSpeed(value)
            V2FeatureId.TARGET_RESISTANCE -> accumulator.updateResistance(value.toInt())
            V2FeatureId.CURRENT_GRADE -> accumulator.updateIncline(value)
            // Grip HR is a noisy analog contact reading — gate + smooth it, clearing on contact loss.
            // External BLE HRMs bypass this and are merged in the orchestrator.
            V2FeatureId.PULSE -> accumulator.updateHeartRate(gripHeartRate.update(value.toInt()))
            V2FeatureId.DISTANCE -> accumulator.updateDistance(value)
            V2FeatureId.CURRENT_CALORIES -> accumulator.updateCalories(value.toInt())
            V2FeatureId.RUNNING_TIME -> accumulator.updateElapsedTime(value.toLong())
            V2FeatureId.TARGET_KPH -> accumulator.updateTargetSpeed(value)
            V2FeatureId.TARGET_GRADE -> accumulator.updateTargetIncline(value)
            V2FeatureId.SYSTEM_MODE -> { /* System on/standby/sleep — not the workout state, and not exercise data */ }
            // Translated to V1 [com.nettarion.hyperborea.hardware.fitpro.v1.WorkoutMode] numbering
            // when pushed to the accumulator, so the orchestrator's workout-mode monitor (which
            // uses V1 codes for DMK / IDLE / RUNNING) reacts uniformly to V1 and V2 sessions.
            V2FeatureId.WORKOUT_STATE -> {
                _workoutMode.value = value
                accumulator.updateWorkoutMode(v2WorkoutStateToV1Code(V2WorkoutMode.fromRaw(value)))
            }
            V2FeatureId.MAX_RESISTANCE -> { /* Device capability, not exercise data */ }
            V2FeatureId.GOAL_WATTS -> accumulator.updateTargetPower(value.toInt())
        }
    }

    private fun roundToStep(value: Float, step: Float): Float =
        (value / step).roundToInt() * step

    /**
     * Brings the console up to the workout-active state the way the firmware expects. Two paths,
     * mirroring [com.nettarion.hyperborea.hardware.fitpro.v1.V1Session.transitionToActive]:
     *
     * - **Treadmill / incline trainer**: write `WARM_UP` and stop. The MCU gates belt motion on
     *   the physical Start key; writing `RUNNING` from the app alone would only time out the
     *   confirmation wait and surface as a (semantically wrong) degraded warning. Instead the
     *   orchestrator parks in
     *   [com.nettarion.hyperborea.core.orchestration.OrchestratorState.AwaitingConsoleStart] and
     *   the WORKOUT_STATE subscription pushes a `RUNNING` event once the user presses the key.
     * - **Bike / elliptical / rower**: drive the state machine ourselves —
     *   `NONE → WARM_UP → RUNNING`, confirming each step from the [V2FeatureId.WORKOUT_STATE]
     *   events. If the console never confirms, log a warning and continue degraded.
     */
    private suspend fun transitionToWorkout() {
        if (detectedDeviceType == DeviceType.TREADMILL) {
            writeWorkoutState(V2WorkoutMode.WARM_UP)
            confirmWorkoutMode("leave idle") { it != V2WorkoutMode.NONE && it != V2WorkoutMode.READY_TO_START }
            logger.i(TAG, "Console workout state: NONE → WARM_UP (awaiting physical Start key)")
            _degradedReason.value = null
            return
        }

        writeWorkoutState(V2WorkoutMode.WARM_UP)
        confirmWorkoutMode("leave idle") { it != V2WorkoutMode.NONE && it != V2WorkoutMode.READY_TO_START }
        writeWorkoutState(V2WorkoutMode.RUNNING)
        val running = confirmWorkoutMode("reach RUNNING") { it == V2WorkoutMode.RUNNING }
        logger.i(TAG, "Console workout state: NONE → WARM_UP → ${if (running) V2WorkoutMode.RUNNING else V2WorkoutMode.UNKNOWN}")
        _degradedReason.value =
            if (running) null
            else "The console didn't confirm the workout started — resistance/speed may not respond"
    }

    /**
     * Heuristic: V2 has no equipment-id field, so we infer type from the feature set the console
     * declared. Treadmills / incline trainers report belt-speed and grade features but no flywheel
     * resistance; bikes, ellipticals and rowers report resistance features.
     */
    private fun deriveDeviceType(features: Set<V2FeatureId>): DeviceType {
        val hasResistance = V2FeatureId.TARGET_RESISTANCE in features ||
            V2FeatureId.MAX_RESISTANCE in features
        val hasBeltSpeed = V2FeatureId.TARGET_KPH in features || V2FeatureId.CURRENT_KPH in features
        val hasGrade = V2FeatureId.TARGET_GRADE in features || V2FeatureId.CURRENT_GRADE in features
        return when {
            !hasResistance && (hasBeltSpeed || hasGrade) -> DeviceType.TREADMILL
            else -> DeviceType.BIKE
        }
    }

    /**
     * Translates V2's [V2WorkoutMode] ordinal to the V1
     * [com.nettarion.hyperborea.hardware.fitpro.v1.WorkoutMode] raw value the orchestrator's
     * workout-mode monitor expects (V1 numbering is the lingua franca because V1 is older). The
     * `OFF_MACHINE` state has no V1 equivalent — the closest semantic match is `DMK` (user not on
     * device / not driving telemetry), so the monitor's safety-pause path fires for both.
     */
    private fun v2WorkoutStateToV1Code(mode: V2WorkoutMode): Int = when (mode) {
        V2WorkoutMode.RUNNING -> V1_WORKOUT_MODE_RUNNING
        V2WorkoutMode.PAUSED -> V1_WORKOUT_MODE_PAUSE
        V2WorkoutMode.OFF_MACHINE -> V1_WORKOUT_MODE_DMK
        V2WorkoutMode.WARM_UP -> V1_WORKOUT_MODE_WARM_UP
        V2WorkoutMode.COOL_DOWN -> V1_WORKOUT_MODE_COOL_DOWN
        V2WorkoutMode.NONE,
        V2WorkoutMode.READY_TO_START,
        V2WorkoutMode.RESULTS -> V1_WORKOUT_MODE_IDLE
        V2WorkoutMode.UNKNOWN -> V1_WORKOUT_MODE_UNKNOWN
    }

    private suspend fun writeWorkoutState(mode: V2WorkoutMode) {
        transport.write(V2Codec.encode(V2Message.Outgoing.WriteFeature(V2FeatureId.WORKOUT_STATE, mode.raw)))
    }

    /** Waits (up to [STATE_CONFIRM_TIMEOUT_MS]) for a [V2FeatureId.WORKOUT_STATE] event satisfying [accept]. */
    private suspend fun confirmWorkoutMode(what: String, accept: (V2WorkoutMode) -> Boolean): Boolean {
        val ok = withTimeoutOrNull(STATE_CONFIRM_TIMEOUT_MS) {
            _workoutMode.filterNotNull().map { V2WorkoutMode.fromRaw(it) }.first { accept(it) }
            true
        } != null
        if (!ok) logger.w(TAG, "Console didn't $what — workout may be inactive; continuing")
        return ok
    }

    companion object {
        private const val TAG = "V2Session"

        // Belt-machine halt on stop: let the speed-0 + PAUSED writes settle before teardown.
        private const val BELT_HALT_SETTLE_MS = 200L
        private const val MAX_SUBSCRIBE_BATCH = 8
        // How long to wait for the console to confirm a WORKOUT_STATE transition before continuing degraded.
        private const val STATE_CONFIRM_TIMEOUT_MS = 5_000L
        // The features query is the console's slowest reply — give it longer than other commands,
        // and retry: subscriptions depend on its answer.
        private const val SUPPORTED_FEATURES_TIMEOUT_MS = 4_000L
        private const val QUERY_FEATURES_ATTEMPTS = 3

        // V1 [com.nettarion.hyperborea.hardware.fitpro.v1.WorkoutMode] raw codes used by the
        // orchestrator's workout-mode monitor — kept here as a translation target for V2's WORKOUT_STATE.
        private const val V1_WORKOUT_MODE_UNKNOWN = 0
        private const val V1_WORKOUT_MODE_IDLE = 1
        private const val V1_WORKOUT_MODE_RUNNING = 2
        private const val V1_WORKOUT_MODE_PAUSE = 3
        private const val V1_WORKOUT_MODE_DMK = 8
        private const val V1_WORKOUT_MODE_WARM_UP = 10
        private const val V1_WORKOUT_MODE_COOL_DOWN = 11
    }
}
