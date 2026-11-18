package io.tiko.coroutines

import io.tiko.NoActiveEventScopeException
import io.tiko.UnitOfWork
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Supplier
import java.util.stream.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** #677: `inEventScope` runs its block in one unit of work across suspension, and the unit closes once. */
class InEventScopeTest : BootedContainerTest() {

    @Test
    fun sameInstanceBeforeAndAfterSuspension() {
        val (before, after) = runBlocking {
            unitOfWork.inEventScope {
                val before = requests.currentId()
                delay(10)
                before to requests.currentId()
            }
        }

        assertThat(after).isEqualTo(before)
    }

    @Test
    fun sameInstanceAfterADispatcherChange() {
        val seen = runBlocking {
            unitOfWork.inEventScope {
                val here = requests.currentId()
                val onIo = withContext(Dispatchers.IO) { requests.currentId() }
                val onDefaultAfterSuspension = withContext(Dispatchers.Default) {
                    delay(5)
                    requests.currentId()
                }
                listOf(here, onIo, onDefaultAfterSuspension, requests.currentId())
            }
        }

        assertThat(seen.toSet()).`as`("one unit on every dispatcher: $seen").hasSize(1)
    }

    /** kotlinx installs the element again on the thread that already has the unit. */
    @Test
    fun sameInstanceUnderWithContextOnTheSameDispatcher() {
        val seen = runBlocking {
            unitOfWork.inEventScope {
                val before = requests.currentId()
                val inside = withContext(CoroutineName("renamed")) { requests.currentId() }
                listOf(before, inside, requests.currentId())
            }
        }

        assertThat(seen.toSet()).hasSize(1)
    }

    @Test
    fun theThreadIsCleanAfterTheBlock() {
        runBlocking { unitOfWork.inEventScope { requests.currentId() } }

        assertThat(unitOpenHere()).isFalse()
        assertThatThrownBy { requests.currentId() }.isInstanceOf(NoActiveEventScopeException::class.java)
    }

    @Test
    fun returnsTheBlocksValue() {
        val value = runBlocking {
            unitOfWork.inEventScope {
                delay(1)
                42
            }
        }

        assertThat(value).isEqualTo(42)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("waysTheBlockEnds")
    fun closesOnceWhenTheBlock(ends: String, run: (UnitOfWork, () -> String) -> String) {
        val id = run(unitOfWork) { requests.currentId() }

        assertThat(closeLog.closed()).`as`("the unit of a block that $ends").containsExactly(id)
        assertThat(lifecycle).hasSize(2)
        assertThat(lifecycle[1]).isEqualTo(lifecycle[0].replace("started", "ending"))
    }

    @Test
    fun aCoroutineCancelledBeforeItRanOpensNoUnit() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launch(start = CoroutineStart.LAZY) { unitOfWork.inEventScope { requests.currentId() } }
        runBlocking { job.cancelAndJoin() }

        assertThat(lifecycle).isEmpty()
        assertThat(closeLog.closed()).isEmpty()
    }

    @Test
    fun twoConcurrentCallsGetTwoUnits() {
        val ids = runBlocking(Dispatchers.Default) {
            List(2) {
                async {
                    unitOfWork.inEventScope {
                        val before = requests.currentId()
                        delay(20)
                        before to requests.currentId()
                    }
                }
            }.awaitAll()
        }

        assertThat(ids).allMatch { (before, after) -> before == after }
        assertThat(ids[0].first).isNotEqualTo(ids[1].first)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("placesWithAUnitOpen")
    fun nestedCallIsRejected(place: String, nestedCallIn: (UnitOfWork, () -> String) -> List<Any?>) {
        val (failure, idBefore, idAfter) = nestedCallIn(unitOfWork) { requests.currentId() }

        assertThat(failure as Throwable?)
            .`as`("inEventScope $place")
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already open")
        assertThat(idAfter).`as`("the open unit is still usable").isEqualTo(idBefore)
    }

    @Test
    fun aUnitOfWorkThatCannotCarryAUnitSaysSo() {
        val handWritten = object : UnitOfWork {
            override fun runInEventScope(runnable: Runnable) = runnable.run()

            override fun <T> supplyInEventScope(supplier: Supplier<T>): T = supplier.get()
        }

        assertThatThrownBy { runBlocking { handWritten.inEventScope { } } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot carry a unit of work")
    }

    @Test
    fun oneLifecyclePairPerCall() {
        runBlocking {
            unitOfWork.inEventScope { delay(1) }
            unitOfWork.inEventScope { withContext(Dispatchers.IO) { delay(1) } }
        }

        assertThat(lifecycle).hasSize(4)
        assertThat(lifecycle.map { it.substringBefore(' ') }).containsExactly("started", "ending", "started", "ending")
        assertThat(lifecycle[0].substringAfter(' ')).isNotEqualTo(lifecycle[2].substringAfter(' '))
    }

    companion object {
        @JvmStatic
        fun waysTheBlockEnds(): Stream<Arguments> {
            val completes: (UnitOfWork, () -> String) -> String = { unitOfWork, currentId ->
                runBlocking {
                    unitOfWork.inEventScope {
                        delay(5)
                        currentId()
                    }
                }
            }
            val throws: (UnitOfWork, () -> String) -> String = { unitOfWork, currentId ->
                val seen = AtomicReference<String>()
                val outcome = runCatching {
                    runBlocking {
                        unitOfWork.inEventScope {
                            seen.set(currentId())
                            delay(5)
                            error("the block failed")
                        }
                    }
                }
                assertThat(outcome.exceptionOrNull()).hasMessage("the block failed")
                seen.get()
            }
            val cancelled: (UnitOfWork, () -> String) -> String = { unitOfWork, currentId ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val seen = CompletableDeferred<String>()
                val job = scope.launch {
                    unitOfWork.inEventScope {
                        seen.complete(currentId())
                        delay(60_000)
                    }
                }
                runBlocking {
                    val id = seen.await()
                    job.cancelAndJoin()
                    id
                }
            }
            val cancelledWithCleanup: (UnitOfWork, () -> String) -> String = { unitOfWork, currentId ->
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val seen = CompletableDeferred<String>()
                val inCleanup = AtomicReference<String>()
                val job = scope.launch {
                    unitOfWork.inEventScope {
                        try {
                            seen.complete(currentId())
                            delay(60_000)
                        } finally {
                            withContext(NonCancellable) {
                                delay(20)
                                inCleanup.set(currentId())
                            }
                        }
                    }
                }
                runBlocking {
                    val id = seen.await()
                    job.cancelAndJoin()
                    assertThat(inCleanup.get()).`as`("the cleanup after cancellation is still in the unit").isEqualTo(id)
                    id
                }
            }
            return Stream.of(
                Arguments.of("completes", completes),
                Arguments.of("throws", throws),
                Arguments.of("is cancelled while suspended", cancelled),
                Arguments.of("is cancelled and cleans up in a suspending NonCancellable block", cancelledWithCleanup),
            )
        }

        @JvmStatic
        fun placesWithAUnitOpen(): Stream<Arguments> {
            val insideInEventScope: (UnitOfWork, () -> String) -> List<Any?> = { unitOfWork, currentId ->
                runBlocking {
                    unitOfWork.inEventScope {
                        val before = currentId()
                        val failure = runCatching { unitOfWork.inEventScope { } }.exceptionOrNull()
                        listOf(failure, before, currentId())
                    }
                }
            }
            val insideRunInEventScope: (UnitOfWork, () -> String) -> List<Any?> = { unitOfWork, currentId ->
                unitOfWork.supplyInEventScope {
                    val before = currentId()
                    val failure = runCatching { runBlocking { unitOfWork.inEventScope { } } }.exceptionOrNull()
                    listOf(failure, before, currentId())
                }
            }
            val insideAChildsUnit: (UnitOfWork, () -> String) -> List<Any?> = { unitOfWork, currentId ->
                runBlocking {
                    unitOfWork.inEventScope {
                        async {
                            val before = currentId()
                            val failure = runCatching { unitOfWork.inEventScope { } }.exceptionOrNull()
                            listOf(failure, before, currentId())
                        }.await()
                    }
                }
            }
            return Stream.of(
                Arguments.of("inside inEventScope", insideInEventScope),
                Arguments.of("inside runInEventScope on the same thread", insideRunInEventScope),
                Arguments.of("inside a child coroutine's unit", insideAChildsUnit),
            )
        }
    }
}
