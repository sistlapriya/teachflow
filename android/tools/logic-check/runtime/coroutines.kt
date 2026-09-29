@file:Suppress("unused", "UNUSED_PARAMETER")
// Minimal *functional* coroutine/flow runtime for running the executor synchronously on a JVM.
// Nothing here suspends: delay() sleeps briefly, and first() on a StateFlow calls a test hook
// (the simulated user) instead of blocking.
package kotlinx.coroutines
import kotlin.coroutines.CoroutineContext
interface CoroutineScope
interface Job : CoroutineContext.Element { val isActive: Boolean; fun cancel() }
fun CoroutineScope(context: CoroutineContext): CoroutineScope = TODO()
fun SupervisorJob(): Job = TODO()
abstract class CoroutineDispatcher : CoroutineContext.Element
abstract class MainCoroutineDispatcher : CoroutineDispatcher() { abstract val immediate: MainCoroutineDispatcher }
object Dispatchers { val Main: MainCoroutineDispatcher get() = TODO(); val Default: CoroutineDispatcher get() = TODO(); val IO: CoroutineDispatcher get() = TODO() }
fun CoroutineScope.launch(block: suspend CoroutineScope.() -> Unit): Job = TODO()
fun CoroutineScope.cancel(): Unit = TODO()
suspend fun delay(ms: Long) { Thread.sleep(ms / 15) }
suspend fun <T> withContext(ctx: CoroutineContext, block: suspend CoroutineScope.() -> T): T = TODO()
