package io.github.sumirenokai.vesqen.playback

enum class OutputDeclaration {
    SYSTEM_MIXED,
    BIT_PERFECT_AVAILABLE,
    BIT_PERFECT_REQUESTED,
    BIT_PERFECT_ACTIVE,
    BIT_PERFECT_VERIFIED,
    BIT_PERFECT_FAILED,
}

/** The text Audio Proof publishes for a declaration, e.g. "BIT PERFECT ACTIVE". */
internal val OutputDeclaration.telemetryLabel: String
    get() = name.replace('_', ' ')
