package androidx.compose.runtime

import kotlin.reflect.KProperty

// JVM stub of Compose snapshot state: a plain volatile cell. Real Compose snapshot isolation/notifications are NOT modelled.
interface State<out T> { val value: T }
interface MutableState<T> : State<T> { override var value: T }

private class VolatileState<T>(initial: T) : MutableState<T> {
    @Volatile override var value: T = initial
}

fun <T> mutableStateOf(value: T): MutableState<T> = VolatileState(value)
fun mutableIntStateOf(value: Int): MutableState<Int> = VolatileState(value)
fun mutableFloatStateOf(value: Float): MutableState<Float> = VolatileState(value)

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value
@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) { this.value = value }
