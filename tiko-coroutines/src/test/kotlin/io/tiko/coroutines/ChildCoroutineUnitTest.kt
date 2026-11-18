package io.tiko.coroutines

import java.util.stream.Stream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * #677, #683: a `launch` or `async` started inside a unit of work is concurrent work, so it runs
 * in a unit of its own. `withContext` and the other scoping functions are the same work, so they
 * keep the unit.
 */
class ChildCoroutineUnitTest : BootedContainerTest() {

    @ParameterizedTest(name = "{0}")
    @MethodSource("concurrentChildren")
    fun aChildRunsInAUnitOfItsOwn(construct: String, idInChild: suspend CoroutineScope.(suspend () -> String) -> String) {
        val (parent, child, parentAfter) = runBlocking {
            unitOfWork.inEventScope {
                val parent = requests.currentId()
                val child = idInChild {
                    val before = requests.currentId()
                    delay(5)
                    assertThat(requests.currentId()).`as`("the child's unit across its own suspension").isEqualTo(before)
                    before
                }
                Triple(parent, child, requests.currentId())
            }
        }

        assertThat(child).`as`(construct).isNotEqualTo(parent)
        assertThat(parentAfter).`as`("the parent's unit is untouched").isEqualTo(parent)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopingFunctions")
    fun aScopingFunctionKeepsTheUnit(construct: String, idInside: suspend CoroutineScope.(suspend () -> String) -> String) {
        val (parent, inside) = runBlocking {
            unitOfWork.inEventScope {
                requests.currentId() to idInside { requests.currentId() }
            }
        }

        assertThat(inside).`as`(construct).isEqualTo(parent)
        assertThat(unitsStarted()).isEqualTo(1)
    }

    @Test
    fun eachElementOfMapAsyncGetsAUnit() {
        val (parent, children) = runBlocking {
            unitOfWork.inEventScope {
                requests.currentId() to (1..5).map { async(Dispatchers.Default) { requests.currentId() } }.awaitAll()
            }
        }

        assertThat(children.toSet()).`as`("one unit per element").hasSize(5).doesNotContain(parent)
        assertThat(unitsStarted()).isEqualTo(6)
    }

    @Test
    fun aGrandchildGetsAUnitOfItsOwn() {
        val ids = runBlocking {
            unitOfWork.inEventScope {
                val parent = requests.currentId()
                val (child, grandchild) = async {
                    requests.currentId() to async { requests.currentId() }.await()
                }.await()
                listOf(parent, child, grandchild)
            }
        }

        assertThat(ids.toSet()).hasSize(3)
    }

    @Test
    fun aChildsUnitClosesWhenTheChildCompletesAndTheParentsStaysOpen() {
        runBlocking {
            unitOfWork.inEventScope {
                val parent = requests.currentId()
                val child = async { requests.currentId() }.await()

                eventually("the child's unit closes") { closeLog.closed().contains(child) }
                assertThat(closeLog.closed()).doesNotContain(parent)
                assertThat(requests.currentId()).isEqualTo(parent)
            }
        }
    }

    /** A coroutine of another scope that is given the unit's context is still a child: it gets a copy. */
    @Test
    fun aChildThatOutlivesItsParentKeepsItsOwnUnit() {
        val longLived = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val parentIsDone = CompletableDeferred<Unit>()
        val childIds = CompletableDeferred<Pair<String, String>>()

        val parent = runBlocking {
            unitOfWork.inEventScope {
                longLived.launch(coroutineContext.minusKey(Job)) {
                    val before = requests.currentId()
                    parentIsDone.await()
                    childIds.complete(before to requests.currentId())
                }
                requests.currentId()
            }
        }
        assertThat(closeLog.closed()).`as`("the parent's unit closed with the parent").containsExactly(parent)
        parentIsDone.complete(Unit)
        val (before, after) = runBlocking { childIds.await() }

        assertThat(after).`as`("the child's unit outlived the parent's").isEqualTo(before).isNotEqualTo(parent)
        runBlocking { eventually("the child's unit closes") { closeLog.closed() == listOf(parent, before) } }
    }

    @Test
    fun aLazyChildThatNeverRunsOpensNoUnit() {
        runBlocking {
            unitOfWork.inEventScope {
                val never = launch(start = CoroutineStart.LAZY) { requests.currentId() }
                never.cancel()
            }
        }

        assertThat(unitsStarted()).`as`("the parent's unit only").isEqualTo(1)
    }

    companion object {
        private fun case(name: String, block: suspend CoroutineScope.(suspend () -> String) -> String): Arguments =
            Arguments.of(name, block)

        @JvmStatic
        fun concurrentChildren(): Stream<Arguments> = Stream.of(
            case("async") { id -> async { id() }.await() },
            case("launch") { id ->
                val seen = CompletableDeferred<String>()
                launch { seen.complete(id()) }
                seen.await()
            },
            case("async on another dispatcher") { id -> async(Dispatchers.IO) { id() }.await() },
            case("async, UNDISPATCHED") { id -> async(start = CoroutineStart.UNDISPATCHED) { id() }.await() },
            case("async, Unconfined") { id -> async(Dispatchers.Unconfined) { id() }.await() },
        )

        @JvmStatic
        fun scopingFunctions(): Stream<Arguments> = Stream.of(
            case("withContext on another dispatcher") { id -> withContext(Dispatchers.IO) { id() } },
            case("coroutineScope") { id -> coroutineScope { id() } },
        )
    }
}
