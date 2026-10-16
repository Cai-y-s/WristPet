package com.example.wristpet.pet

/** Foreground-only behavior state machine; contains no Android or rendering code. */
class PetBehavior {
    enum class State { IDLE, HAPPY, CURIOUS, SLEEPY, SLEEPING, EVOLVING }

    var state: State = State.IDLE
        private set
    var stateElapsedMs: Long = 0L
        private set
    var idleElapsedMs: Long = 0L
        private set

    /** Next foreground-idle time at which the pet may act curious. */
    private var nextCuriousAtIdleMs = FIRST_CURIOSITY_AFTER_MS
    private var curiousSequence = 0

    val isAwake: Boolean
        get() = state != State.SLEEPING

    fun onInteraction() {
        idleElapsedMs = 0L
        nextCuriousAtIdleMs = FIRST_CURIOSITY_AFTER_MS
        curiousSequence = 0
        enter(State.HAPPY)
    }

    /** Begin a one-shot evolution celebration. The caller only invokes this on a real stage transition. */
    fun onEvolution() {
        idleElapsedMs = 0L
        enter(State.EVOLVING)
    }

    /** Called when the screen returns to the foreground; greeting is visual-only. */
    fun onAppResumed() {
        idleElapsedMs = 0L
        nextCuriousAtIdleMs = FIRST_CURIOSITY_AFTER_MS
        curiousSequence = 0
        if (state != State.EVOLVING) {
            enter(State.CURIOUS)
        }
    }

    fun tick(deltaMs: Long) {
        if (deltaMs <= 0L) return
        stateElapsedMs += deltaMs
        when (state) {
            State.HAPPY -> if (stateElapsedMs >= HAPPY_MS) enter(State.IDLE)
            State.EVOLVING -> if (stateElapsedMs >= EVOLUTION_MS) enter(State.HAPPY)
            State.IDLE -> {
                idleElapsedMs += deltaMs
                when {
                    idleElapsedMs >= SLEEP_AFTER_MS -> enter(State.SLEEPING)
                    idleElapsedMs >= SLEEPY_AFTER_MS -> enter(State.SLEEPY)
                    idleElapsedMs >= nextCuriousAtIdleMs -> {
                        curiousSequence += 1
                        val delay = when (curiousSequence % 3) {
                            1 -> 21_000L
                            2 -> 18_000L
                            else -> 24_000L
                        }
                        nextCuriousAtIdleMs = idleElapsedMs + delay
                        enter(State.CURIOUS)
                    }
                }
            }
            State.CURIOUS -> {
                idleElapsedMs += deltaMs
                when {
                    idleElapsedMs >= SLEEP_AFTER_MS -> enter(State.SLEEPING)
                    idleElapsedMs >= SLEEPY_AFTER_MS -> enter(State.SLEEPY)
                    stateElapsedMs >= CURIOUS_MS -> enter(State.IDLE)
                }
            }
            State.SLEEPY -> {
                idleElapsedMs += deltaMs
                if (idleElapsedMs >= SLEEP_AFTER_MS) enter(State.SLEEPING)
            }
            State.SLEEPING -> Unit
        }
    }

    /** Stop the foreground animation clock when the app is paused. */
    fun onAppPaused() {
        enter(State.IDLE)
        idleElapsedMs = 0L
        nextCuriousAtIdleMs = FIRST_CURIOSITY_AFTER_MS
        curiousSequence = 0
    }

    private fun enter(next: State) {
        state = next
        stateElapsedMs = 0L
    }

    companion object {
        const val HAPPY_MS = 2_600L
        const val EVOLUTION_MS = 3_000L
        const val CURIOUS_MS = 2_200L
        const val FIRST_CURIOSITY_AFTER_MS = 12_000L
        const val SLEEPY_AFTER_MS = 45_000L
        const val SLEEP_AFTER_MS = 120_000L
    }
}
