@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package io.tiko.coroutines

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.stream.Stream
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * The behaviour of kotlinx.coroutines that the unit-of-work element rests on (#677). The element
 * is built on [CopyableThreadContextElement], which kotlinx marks experimental and delicate, so
 * this class states what Tiko needs from it. A kotlinx release that changes one of these fails
 * the build here, with the construct named, before it changes when a unit of work opens or closes.
 */
@Timeout(60)
class CoroutineContextElementContractTest {

    /**
     * Writes down every call kotlinx makes on it. A copy shares the log and is named after how
     * far it is from the element the test created: "handed over", then "unit", "child".
     */
    private class Recording(
        val name: String,
        val log: MutableList<String>,
        val jobs: MutableList<Job?> = CopyOnWriteArrayList(),
    ) : CopyableThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<Recording>

        override val key: CoroutineContext.Key<Recording> get() = Key

        override fun updateThreadContext(context: CoroutineContext) {
            log += "$name installed on ${thread()}"
            jobs += context[Job]
        }

        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) {
            log += "$name taken off ${thread()}"
        }

        override fun copyForChild(): CopyableThreadContextElement<Unit> {
            log += "$name copied"
            return Recording(NAMES[NAMES.indexOf(name) + 1], log, jobs)
        }

        override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext {
            log += "$name merged with ${(overwritingElement as Recording).name}"
            return overwritingElement
        }
    }

    private val log = CopyOnWriteArrayList<String>()

    private fun handedOver() = Recording("handed over", log)

    /** Runs [block] in a coroutine whose element is "unit", with the log emptied of how it got there. */
    private fun inCoroutineWithTheElement(block: suspend CoroutineScope.() -> Unit) {
        runBlocking(handedOver()) {
            log.clear()
            block()
        }
    }

    /**
     * The fact the element's design starts from: it cannot carry state made before the coroutine
     * exists, because the coroutine runs with a copy.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("handingAnElementOver")
    fun anElementHandedToACoroutineIsCopiedAndTheCopyRuns(construct: String, run: (CoroutineContext) -> Unit) {
        run(handedOver())

        assertThat(log).`as`(construct).contains("handed over copied").anyMatch { it.startsWith("unit installed") }
        assertThat(log).`as`("the element the caller made is never installed").noneMatch {
            it.startsWith("handed over installed")
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("concurrentChildren")
    fun aConcurrentChildGetsACopyOfTheElement(construct: String, start: suspend CoroutineScope.() -> Unit) {
        inCoroutineWithTheElement(start)

        assertThat(log).`as`(construct).contains("unit copied").anyMatch { it.startsWith("child installed") }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopingFunctions")
    fun aScopingFunctionKeepsTheElement(construct: String, run: suspend CoroutineScope.() -> Unit) {
        inCoroutineWithTheElement(run)

        assertThat(log).`as`(construct).doesNotContain("unit copied").noneMatch { it.startsWith("child") }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("givenAnotherElement")
    fun aCoroutineGivenAnotherElementOfTheKeyIsAskedToMerge(
        construct: String,
        run: suspend CoroutineScope.(CoroutineContext) -> Unit,
    ) {
        inCoroutineWithTheElement { run(Recording("other", log)) }

        assertThat(log).`as`(construct).contains("unit merged with other")
    }

    @Test
    fun aContextChangeOnTheSameDispatcherInstallsTheElementAgainOnTheThreadThatHasIt() {
        inCoroutineWithTheElement {
            val outer = thread()
            withContext(CoroutineName("renamed")) { log += "inside on ${thread()}" }

            assertThat(log)
                .containsExactly("unit installed on $outer", "inside on $outer", "unit taken off $outer")
        }
    }

    @Test
    fun theElementIsInstalledOnEveryResumeAndTakenOffOnEverySuspension() {
        runBlocking {
            launch(Dispatchers.Default + handedOver()) {
                delay(5)
                delay(5)
            }.join()
        }

        // The thread a coroutine left takes the element off on its way out, which can be after
        // the coroutine has been resumed elsewhere, and after whoever joined it has gone on.
        fun calls() = log.filter { it.startsWith("unit ") }
        fun installs() = calls().count { it.startsWith("unit installed") }
        runBlocking {
            withTimeout(10_000) {
                while (calls().count { it.startsWith("unit taken off") } != installs()) {
                    delay(1)
                }
            }
        }

        assertThat(installs()).`as`("the start and two resumes").isGreaterThanOrEqualTo(3)
        calls().groupBy { it.substringAfter("thread ") }.forEach { (thread, onThread) ->
            assertThat(onThread.map { it.substringBefore(" thread") })
                .`as`("on thread $thread every install is followed by its take-off")
                .isEqualTo(List(onThread.size) { if (it % 2 == 0) "unit installed on" else "unit taken off" })
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("waysToEnd")
    fun theJobTheElementSeesIsTheCoroutinesOwnAndCompletesOnce(
        way: String,
        body: suspend CoroutineScope.(CountDownLatch) -> Unit,
        cancel: Boolean,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val element = handedOver()
        val completions = CopyOnWriteArrayList<String>()
        val running = CountDownLatch(1)

        val job = scope.launch(element) { body(running) }
        runBlocking {
            running.await()
            element.jobs.first()!!.invokeOnCompletion { cause -> completions += cause?.javaClass?.simpleName ?: "none" }
            if (cancel) job.cancelAndJoin() else job.join()
        }

        assertThat(element.jobs).`as`(way).isNotEmpty.allMatch { it === job }
        assertThat(completions).`as`("the completion handler has run when join returns").hasSize(1)
    }

    /** The copy is made when the coroutine is created, so a copy must not open anything. */
    @Test
    fun aLazyCoroutineCancelledBeforeItStartsCopiesTheElementButNeverInstallsIt() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launch(handedOver(), start = CoroutineStart.LAZY) { log += "ran" }
        runBlocking { job.cancelAndJoin() }

        assertThat(log).containsExactly("handed over copied")
    }

    companion object {
        private val NAMES = listOf("handed over", "unit", "child", "grandchild")

        private fun case(name: String, block: suspend CoroutineScope.() -> Unit): Arguments = Arguments.of(name, block)

        @JvmStatic
        fun handingAnElementOver(): Stream<Arguments> {
            val runBlockingCase: (CoroutineContext) -> Unit = { element -> runBlocking(element) { } }
            val withContextCase: (CoroutineContext) -> Unit = { element -> runBlocking { withContext(element) { } } }
            val launchCase: (CoroutineContext) -> Unit = { element -> runBlocking { launch(element) { }.join() } }
            return Stream.of(
                Arguments.of("runBlocking(element)", runBlockingCase),
                Arguments.of("withContext(element)", withContextCase),
                Arguments.of("launch(element)", launchCase),
            )
        }

        @JvmStatic
        fun concurrentChildren(): Stream<Arguments> = Stream.of(
            case("launch") { launch { }.join() },
            case("async") { async { }.await() },
            case("launch on another dispatcher") { launch(Dispatchers.IO) { }.join() },
            case("launch, UNDISPATCHED") { launch(start = CoroutineStart.UNDISPATCHED) { }.join() },
            case("launch, Unconfined") { launch(Dispatchers.Unconfined) { }.join() },
        )

        @JvmStatic
        fun scopingFunctions(): Stream<Arguments> = Stream.of(
            case("withContext on another dispatcher") { withContext(Dispatchers.IO) { } },
            case("withContext on the same dispatcher") { withContext(CoroutineName("renamed")) { } },
            case("coroutineScope") { coroutineScope { } },
            case("supervisorScope") { supervisorScope { } },
            case("withTimeout") { withTimeout(1_000) { } },
        )

        @JvmStatic
        fun givenAnotherElement(): Stream<Arguments> {
            val withContextCase: suspend CoroutineScope.(CoroutineContext) -> Unit = { other -> withContext(other) { } }
            val launchCase: suspend CoroutineScope.(CoroutineContext) -> Unit = { other -> launch(other) { }.join() }
            return Stream.of(
                Arguments.of("withContext(element)", withContextCase),
                Arguments.of("launch(element)", launchCase),
            )
        }

        @JvmStatic
        fun waysToEnd(): Stream<Arguments> {
            val completes: suspend CoroutineScope.(CountDownLatch) -> Unit = { running ->
                running.countDown()
                delay(20)
            }
            val throws: suspend CoroutineScope.(CountDownLatch) -> Unit = { running ->
                running.countDown()
                delay(20)
                error("the block failed")
            }
            val suspended: suspend CoroutineScope.(CountDownLatch) -> Unit = { running ->
                running.countDown()
                delay(60_000)
            }
            return Stream.of(
                Arguments.of("completes", completes, false),
                Arguments.of("throws", throws, false),
                Arguments.of("is cancelled while suspended", suspended, true),
            )
        }
    }
}

/** The calling thread by id: in debug mode kotlinx appends the coroutine's name to the thread's name. */
private fun thread(): String = "thread ${Thread.currentThread().threadId()}"
