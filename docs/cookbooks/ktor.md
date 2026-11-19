# Ktor with Tiko — one unit of work per request

> Runnable example: [`tiko-examples-gradle/kotlin-ktor-exposed/`](../../tiko-examples-gradle/kotlin-ktor-exposed/)
> (Ktor 3.6.1 on the CIO engine, Kotlin 2.4.21, Gradle + KSP).

## Why Tiko doesn't ship a Ktor module

Tiko's scope is **compile-time DI + event orchestration**. An HTTP server is
something you plug in, and Ktor already has its own way to compose an
application: modules, plugins and routing. What a Ktor route needs from Tiko
is small: the components it calls, and a unit of work around the request.
That is a constructor parameter and one function call, so it stays in your
code, where you can read it, instead of in a Tiko plugin you would have to
configure.

## What you'll learn

1. How a route runs its work in **one unit of work across suspension** with
   `inEventScope`.
2. Where **blocking code** (JDBC) goes: on `Dispatchers.IO`, inside the unit.
3. What happens to the unit when the **client goes away**.
4. What **parallel work** in a request means: every `async` is a unit of
   work of its own.
5. How to **test** the routes with Ktor's `testApplication` and `@TikoTest`.

## Dependencies

```kotlin
dependencies {
    implementation("io.github.tomas-samek:tiko-api:<version>")
    implementation("io.github.tomas-samek:tiko-runtime:<version>")
    implementation("io.github.tomas-samek:tiko-coroutines:<version>")   // inEventScope
    ksp("io.github.tomas-samek:tiko-processor:<version>")

    implementation("io.ktor:ktor-server-core:3.6.1")
    implementation("io.ktor:ktor-server-cio:3.6.1")
}
```

`tiko-coroutines` is built and tested with `kotlinx-coroutines-core` 1.11.0.
Tiko's BOM does not pin that version; Ktor and your own build decide which one
runs.

## One unit of work per request

A unit of work is what `Scope.EVENT` components live for. In this example the
EVENT-scoped component is the request's id:

```kotlin
interface RequestId { val value: String }

@Component(scope = Scope.EVENT)
class RandomRequestId : RequestId {
    override val value: String = UUID.randomUUID().toString()
}
```

`TicketService` is a SINGLETON that takes `RequestId`; Tiko hands it a
generated proxy that resolves the current unit's instance on every call.

The Ktor module takes what it uses: something that opens units of work, and
the service.

```kotlin
fun Application.ticketModule(unitOfWork: UnitOfWork, tickets: TicketService) {
    routing {
        post("/tickets") {
            val request = call.receive<OpenTicket>()
            val (ticket, requestId) = unitOfWork.inEventScope {
                val opened = withContext(Dispatchers.IO) { tickets.open(request) }
                // Back on the route's dispatcher, still in the request's unit of work.
                opened to tickets.requestId()
            }
            call.response.header("X-Request-Id", requestId)
            call.respond(HttpStatusCode.Created, ticket)
        }
    }
}
```

`inEventScope` opens the unit, runs the block and closes the unit before it
returns:

- The EVENT-scoped instances are the same before and after every suspension
  and on every dispatcher. The id the service reads on `Dispatchers.IO` is the
  id the route reads after it.
- The unit closes once: when the block completes, when it throws, and when the
  route's coroutine is cancelled.
- Calling `inEventScope` where a unit is already open throws
  `IllegalStateException`. A unit of work does not nest.

`UnitOfWork` is a built-in dependency, so a component can take it in its
constructor. The container is a `UnitOfWork` too, which is what `main` passes
here:

```kotlin
Tiko.create(ConfigSources.classpath("application.yaml")).use { container ->
    val tickets = container.get(TicketService::class.java)
    embeddedServer(CIO, port = port) { ticketModule(container, tickets) }.start(wait = false)
    // ...
}
```

## Blocking code in a route

`TicketService` and its Exposed repository are blocking code. A route must not
block the threads Ktor serves requests on, so it calls the service on
`Dispatchers.IO`. Do that **inside** `inEventScope`: the unit travels with the
coroutine, so the service sees the request's EVENT-scoped instances on the IO
thread.

`runInEventScope` and `supplyInEventScope` are for code that runs to its end
on the calling thread; their block cannot suspend. In a route, use
`inEventScope`.

## When the client goes away

Ktor lets a route run to its end when its client disconnects, unless told
otherwise. The example installs Ktor's `HttpRequestLifecycle` plugin so the
route's coroutine is cancelled instead:

```kotlin
install(HttpRequestLifecycle) { cancelCallOnClose = true }
```

With it, a disconnect cancels the route, and the request's unit of work closes
with it: its EVENT-scoped components run their `@PreDestroy`, and a transaction
bound to the unit rolls back the way the
[persistence cookbook](persistence.md#transactioncontext--decorator) sets it
up. Without it, the unit closes when the route finishes. Both behaviours were
measured on Ktor 3.6.1 with the CIO engine; `TicketApiTest` keeps the first one
pinned with a real socket that hangs up mid-request.

Cancellation takes effect where the coroutine suspends. Blocking code that is
already running on `Dispatchers.IO` finishes its call first.

## Parallel work in a request

A `launch` or `async` started inside `inEventScope` is concurrent work, and
concurrent work is a unit of work of its own, as it is for an async event
handler. A unit of work is single-threaded: its EVENT-scoped components do not
have to be thread-safe, and a transaction is never shared between threads.

```kotlin
get("/tickets/summary") {
    val summary = unitOfWork.inEventScope {
        val reporters = withContext(Dispatchers.IO) { tickets.reporters() }
        val counts = reporters.map { reporter -> async(Dispatchers.IO) { tickets.ticketsOf(reporter) } }
        TicketSummary(tickets.requestId(), counts.awaitAll())
    }
    call.respond(summary)
}
```

- **`map { async { ... } }` opens one unit per element.** Each count above has
  its own `RequestId`, different from the request's and from the other counts'.
  With an EVENT-scoped connection, each would have its own connection and its
  own transaction.
- **A child sees none of the parent's EVENT-scoped instances.** Hand it the
  values it needs, as the lambda is handed `reporter`.
- **A child's unit closes when the child completes.** If the request needs
  what a child does at the end of its unit, a commit for one, do it in the
  child's block and not in a destroy hook: code that awaited the child can run a
  moment before the child's unit has finished closing.
- **`withContext`, `coroutineScope` and `supervisorScope` are not children.**
  They are the same work and keep the request's unit.
- **The upstream of `flowOn` runs in a coroutine of its own**, so in a unit of
  its own. A flow collected without `flowOn` runs in the collector's unit.
- **Events published from a child descend from the same origin** as events
  published from the request, so a handler's `Event<?>` chain reads the same.

## Handlers of what a request publishes

`TicketService.open` publishes `TicketOpened`, and `TicketNotifications` handles it with a
`suspend fun`:

```kotlin
@EventHandler
suspend fun onOpened(event: TicketOpened) {
    notifier.send("ticket ${event.ticket.id} opened by ${event.ticket.reporter} (request ${event.requestId})")
}
```

A `suspend` handler always runs as a coroutine of the container, off the request and in a unit
of work of its own, so the response does not wait for the notification. The handler is handed
what it needs in the event: `TicketOpened` carries the request's id, because the handler's own
`RequestId` would be another one. See
[`suspend` handlers](../events.md#suspend-handlers-kotlin).

## Testing

`TicketApiTest` runs the real routes in Ktor's `testApplication` against a
`@TikoTest` container:

```kotlin
@TikoTest
class TicketApiTest {
    @Test
    fun openedTicketIsStoredAndAnnounced(container: Container, bus: RecordingEventBus) = testApplication {
        application { ticketModule(container, container.get(TicketService::class.java)) }
        // drive the routes with `client`, assert on `bus`
    }
}
```

A `suspend` handler runs after the route has answered, so a test that asserts on what it did
waits first: `bus.awaitAsyncDispatch(Duration.ofSeconds(10))`.

The recording bus also sees the unit lifecycle events
(`EventStartedEvent` / `EventEndingEvent`), which is how the tests tell one
unit of work from another. Ktor's test engine has no connection to close, so
the client-disconnect test starts the CIO engine on a free port.

## Simplifications this cookbook makes

- **The server is started by `main`,** next to the container, and stopped in a
  `finally`. It is not a component of the container.
- **One transaction per repository call.** The repository opens an Exposed
  `transaction` per call; nothing here binds a transaction to the unit of work.
  The [persistence cookbook](persistence.md) shows that binding with raw JDBC.
- **No authentication, no request validation beyond one check, no metrics**
  beyond the unit lifecycle events.
