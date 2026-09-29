@file:Suppress("unused", "UNUSED_PARAMETER")
package kotlinx.coroutines.flow
fun interface FlowCollector<in T> { suspend fun emit(value: T) }
interface Flow<out T> { suspend fun collect(collector: FlowCollector<T>) }
interface SharedFlow<out T> : Flow<T>
interface StateFlow<out T> : SharedFlow<T> { val value: T }
interface MutableSharedFlow<T> : SharedFlow<T> { fun tryEmit(value: T): Boolean }
interface MutableStateFlow<T> : StateFlow<T>, MutableSharedFlow<T> { override var value: T }
private class Impl<T>(override var value: T) : MutableStateFlow<T> {
    override fun tryEmit(value: T): Boolean { this.value = value; return true }
    override suspend fun collect(collector: FlowCollector<T>) { collector.emit(value) }
}
private class SharedImpl<T> : MutableSharedFlow<T> {
    override fun tryEmit(value: T) = true
    override suspend fun collect(collector: FlowCollector<T>) {}
}
fun <T> MutableStateFlow(v: T): MutableStateFlow<T> = Impl(v)
fun <T> MutableSharedFlow(replay: Int = 0, extraBufferCapacity: Int = 0): MutableSharedFlow<T> = SharedImpl()
fun <T> MutableStateFlow<T>.asStateFlow(): StateFlow<T> = this
fun <T> MutableSharedFlow<T>.asSharedFlow(): SharedFlow<T> = this
inline fun <T> MutableStateFlow<T>.update(function: (T) -> T) { value = function(value) }
object TestHooks { var onWait: (() -> Unit)? = null }
suspend fun <T> Flow<T>.first(predicate: suspend (T) -> Boolean): T {
    val sf = this as StateFlow<T>
    var n = 0
    while (!predicate(sf.value)) {
        (TestHooks.onWait ?: error("first() would block forever")).invoke()
        if (++n > 5) error("still waiting after the simulated user acted")
    }
    return sf.value
}
