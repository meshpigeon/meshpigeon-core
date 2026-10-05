package dev.meshpigeon.core.common

/**
 * The result of an operation that can fail in a way a client must handle
 * (`kotlin.Result` cannot be a declared return type in common code without
 * opt-in, and it boxes a `Throwable` rather than a typed error).
 */
sealed interface Outcome<out T> {
    data class Success<out T>(val value: T) : Outcome<T>
    data class Failure(val error: CoreError) : Outcome<Nothing>
}

/**
 * A typed failure. Transport, protocol and storage errors all refine this.
 *
 * Open rather than sealed: each of those hierarchies is sealed in its own
 * module, and a sealed interface cannot be extended outside the file that
 * declares it.
 */
interface CoreError {
    /** Human-readable, for logs and bug reports. Never parsed. */
    val message: String

    /** The underlying exception, when there is one. */
    val cause: Throwable? get() = null
}

/** A failure with no richer structure: a message, and optionally a cause. */
data class MessageError(
    override val message: String,
    override val cause: Throwable? = null,
) : CoreError

/** Transforms the success value, leaving failures untouched. */
inline fun <T, R> Outcome<T>.map(transform: (T) -> R): Outcome<R> = when (this) {
    is Outcome.Success -> Outcome.Success(transform(value))
    is Outcome.Failure -> this
}

/** Returns the success value, or `null` on failure. */
fun <T> Outcome<T>.valueOrNull(): T? = (this as? Outcome.Success)?.value

/** Returns the success value, or [fallback] on failure. */
fun <T> Outcome<T>.valueOr(fallback: T): T = valueOrNull() ?: fallback

/** Runs [block] on success. */
inline fun <T> Outcome<T>.onSuccess(block: (T) -> Unit): Outcome<T> {
    if (this is Outcome.Success) block(value)
    return this
}

/** Runs [block] on failure. */
inline fun <T> Outcome<T>.onFailure(block: (CoreError) -> Unit): Outcome<T> {
    if (this is Outcome.Failure) block(error)
    return this
}