package com.vitalis.core

/**
 * A randomized micro-action the user is asked to perform so the library can
 * confirm a live person is present (active-tier liveness, see feature doc §4/F4).
 */
enum class ChallengeType {
    BLINK,
    HEAD_TURN_LEFT,
    HEAD_TURN_RIGHT,
    SMILE
}
