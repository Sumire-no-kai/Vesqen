package io.github.sumirenokai.vesqen.reports

import java.util.Collections
import java.util.IdentityHashMap

/** One stack frame: code location only. A name that fails [CrashCapture]'s filter is null. */
internal data class CrashFrame(val className: String?, val methodName: String?, val line: Int?)

internal data class CrashException(
    /** The exception's class name, never its message. */
    val type: String?,
    val frames: List<CrashFrame>,
    /** Frames below the kept top of the stack. */
    val omittedFrames: Int,
) {
    init { require(omittedFrames >= 0) }
}

/**
 * #113: an uncaught exception as Vesqen recorded it. [exceptions] starts with the thrown exception
 * and follows its cause chain. No message text, thread name, file name or other free text.
 */
internal data class CrashRecord(
    /** Local file name; never exported. */
    val id: String,
    val occurredAtEpochMs: Long,
    val appVersion: String,
    val versionCode: Int,
    val exceptions: List<CrashException>,
    val causesOmitted: Boolean,
) {
    init { require(occurredAtEpochMs >= 0 && versionCode >= 0 && exceptions.isNotEmpty()) }
}

/** RECORD_DISCARDED describes this process run: an unreadable crash record was deleted. */
internal enum class CrashHistoryAvailability { AVAILABLE, STORAGE_UNAVAILABLE, RECORD_DISCARDED }

internal data class CrashHistorySnapshot(
    val records: List<CrashRecord> = emptyList(),
    val availability: CrashHistoryAvailability = CrashHistoryAvailability.AVAILABLE,
)

/**
 * Turns a Throwable into a bounded [CrashRecord] and filters it for storage and export.
 *
 * This runs inside the crash handler, so it depends on nothing but this file: loading the report
 * vocabulary there could itself fail. It reads only class names, method names and line numbers.
 * Messages, localized messages, toString(), suppressed exceptions, file names and thread names are
 * never read. Class and method names come from compiled code; the allowlists below still drop any
 * frame text that is not a plain JVM identifier, so a synthetic frame cannot carry a path or title.
 */
internal object CrashCapture {
    /** The thrown exception and up to three causes. */
    const val MAX_EXCEPTIONS = 4
    /** Frames kept from the top of each exception's stack. */
    const val MAX_FRAMES = 24
    const val MAX_CLASS_NAME_LENGTH = 200
    const val MAX_METHOD_NAME_LENGTH = 100
    /** Package, class, nested, lambda and R8 names. No spaces, slashes, colons or non-ASCII text. */
    const val CLASS_NAME_PATTERN = "[A-Za-z0-9_\$.-]+"
    const val METHOD_NAME_PATTERN = "<init>|<clinit>|[A-Za-z0-9_\$-]+"

    private val className = Regex(CLASS_NAME_PATTERN)
    private val methodName = Regex(METHOD_NAME_PATTERN)

    fun record(crash: Throwable, occurredAtEpochMs: Long, appVersion: String, versionCode: Int, id: String): CrashRecord {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val chain = generateSequence(crash) { it.cause }.takeWhile(seen::add).take(MAX_EXCEPTIONS + 1).toList()
        return filtered(CrashRecord(
            id = id,
            occurredAtEpochMs = occurredAtEpochMs,
            appVersion = appVersion,
            versionCode = versionCode,
            exceptions = chain.take(MAX_EXCEPTIONS).map { exception ->
                val stack = exception.stackTrace
                CrashException(
                    type = exception.javaClass.name,
                    frames = stack.take(MAX_FRAMES).map { CrashFrame(it.className, it.methodName, it.lineNumber) },
                    omittedFrames = (stack.size - MAX_FRAMES).coerceAtLeast(0),
                )
            },
            causesOmitted = chain.size > MAX_EXCEPTIONS,
        ))
    }

    /** Applied when a record is captured, read back and exported. Over-limit parts are dropped. */
    fun filtered(raw: CrashRecord): CrashRecord = raw.copy(
        exceptions = raw.exceptions.take(MAX_EXCEPTIONS).map { exception ->
            CrashException(
                type = className(exception.type),
                frames = exception.frames.take(MAX_FRAMES).map { frame ->
                    CrashFrame(
                        className = className(frame.className),
                        methodName = frame.methodName?.takeIf { it.length <= MAX_METHOD_NAME_LENGTH && methodName.matches(it) },
                        // Negative values mean unknown (-1) or a native method (-2).
                        line = frame.line?.takeIf { it >= 0 },
                    )
                },
                omittedFrames = exception.omittedFrames + (exception.frames.size - MAX_FRAMES).coerceAtLeast(0),
            )
        },
        causesOmitted = raw.causesOmitted || raw.exceptions.size > MAX_EXCEPTIONS,
    )

    private fun className(value: String?): String? =
        value?.takeIf { it.length <= MAX_CLASS_NAME_LENGTH && className.matches(it) }
}
