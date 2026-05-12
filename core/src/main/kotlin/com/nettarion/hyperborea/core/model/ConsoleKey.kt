package com.nettarion.hyperborea.core.model

/**
 * A physical-console keypad press (resistance ±, incline ±, speed ±), abstracted away from the
 * equipment-specific key codes. The hardware adapter exposes a stream of these — one per press — on
 * [com.nettarion.hyperborea.core.adapter.HardwareAdapter.consoleKeyPresses].
 *
 * Note this is observe-only: the equipment's own controller already acts on these keys (it changes
 * the resistance/incline/speed itself and the new value flows up through normal polling), so nothing
 * drives the hardware from this stream. It exists for UI/diagnostics — e.g. flashing an on-screen
 * indicator when a bezel button is pressed.
 */
enum class ConsoleKey {
    RESISTANCE_UP,
    RESISTANCE_DOWN,
    INCLINE_UP,
    INCLINE_DOWN,
    SPEED_UP,
    SPEED_DOWN,
}
