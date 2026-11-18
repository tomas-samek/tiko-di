package io.tiko.runtime;

import io.tiko.Event;
import io.tiko.events.EventEndingEvent;
import io.tiko.events.EventStartedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * A unit of work that is not bound to one {@code Runnable} (#677). It is {@linkplain #install()
 * put on a thread}, {@linkplain #restore taken off}, put on another thread, and {@linkplain
 * #close() closed} exactly once. Work that leaves its thread and comes back on another one, a
 * coroutine, keeps its unit this way: the same EVENT-scoped instances and the same origin chain
 * wherever it runs.
 *
 * <p>A unit of work is single-threaded. The unit is on one thread at a time: a second thread that
 * installs it while a first one has it gets an {@link IllegalStateException}. The thread that has
 * it may install it again; that changes nothing and is undone by its own {@code restore}.
 *
 * <p>The unit publishes one {@code EventStartedEvent} / {@code EventEndingEvent} pair, the first
 * when it is first installed and the second when it closes, both while it is installed, so a
 * handler of either sees the unit's instances. A unit that is never installed publishes nothing.
 *
 * <p>For the runtime and Tiko's own modules; applications open a unit of work with
 * {@code runInEventScope(...)}.
 */
public final class CarriedUnit {

    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.events");
    }

    /** What a thread had before the unit was installed on it. */
    public static final class Displaced {

        /** What an install by the thread that already has the unit returns: nothing was replaced. */
        private static final Displaced NOTHING = new Displaced(null, null);

        private final UnitFrame[] frames;
        private final Event<?> origin;

        private Displaced(UnitFrame[] frames, Event<?> origin) {
            this.frames = frames;
            this.origin = origin;
        }
    }

    private final List<DetachedUnitFrame> modules;
    private final UnitFrame[] frames;
    private final Event<?> origin;
    private final Consumer<Object> lifecycle;
    private final String eventId = UUID.randomUUID().toString();

    // Guarded by this: which thread has the unit, and how far its close has come.
    private Thread holder;
    private boolean started;
    private boolean closeRequested;
    private boolean tornDown;

    private volatile Instant startedAt;

    /**
     * @param modules the containers the unit is open in, in the order their frames are installed
     * @param origin the event the unit's work descends from, or {@code null}
     * @param lifecycle publishes one unit lifecycle event
     */
    CarriedUnit(List<DetachedUnitFrame> modules, Event<?> origin, Consumer<Object> lifecycle) {
        this.modules = List.copyOf(modules);
        this.frames = new UnitFrame[this.modules.size()];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = UnitFrame.opened();
        }
        this.origin = origin;
        this.lifecycle = lifecycle;
    }

    /**
     * Puts the unit on the calling thread: its frame becomes each module's EVENT frame, and its
     * origin the current one. The first install publishes {@code EventStartedEvent}.
     *
     * @return what the thread had, to hand to {@link #restore} on the same thread
     * @throws IllegalStateException if the unit is closed, or another thread has it
     */
    public Displaced install() {
        boolean first;
        synchronized (this) {
            if (closeRequested) {
                throw new IllegalStateException("This unit of work is closed; it cannot be installed on a thread.");
            }
            var current = Thread.currentThread();
            if (holder == current) {
                return Displaced.NOTHING;
            }
            if (holder != null) {
                throw new IllegalStateException("This unit of work is installed on thread '" + holder.getName()
                        + "'. A unit of work is single-threaded: it is on one thread at a time.");
            }
            holder = current;
            first = !started;
            started = true;
        }
        var displaced = swapIn();
        if (first) {
            startedAt = Instant.now();
            publish(new EventStartedEvent(eventId, startedAt));
        }
        return displaced;
    }

    /**
     * Takes the unit off the calling thread and puts back what {@code displaced} holds. If the
     * unit was closed from another thread meanwhile, it is torn down here first.
     *
     * @param displaced what {@link #install()} returned on this thread
     */
    public void restore(Displaced displaced) {
        if (displaced == Displaced.NOTHING) {
            return;
        }
        boolean closedMeanwhile = claimTeardown();
        try {
            if (closedMeanwhile) {
                tearDown();
            }
        } finally {
            swapOut(displaced);
            if (!release()) {
                // close() arrived between the check above and the release: it left the teardown to
                // this thread, which still counts as having the unit.
                tearDownOnThisThread();
            }
        }
    }

    /**
     * Closes the unit: publishes {@code EventEndingEvent} and tears down the EVENT-scoped
     * components of every module, last module first. Only the first call does anything.
     *
     * <p>Called on the thread that has the unit, the teardown runs in place and the caller still
     * {@linkplain #restore restores}. Called while no thread has it, the unit is installed for the
     * teardown and taken off again. Called while another thread has it, the teardown is left to
     * that thread, which runs it when it takes the unit off. A unit that was never installed has
     * nothing to tear down.
     */
    public void close() {
        boolean inPlace;
        synchronized (this) {
            if (closeRequested) {
                return;
            }
            closeRequested = true;
            if (!started) {
                tornDown = true;
                return;
            }
            var current = Thread.currentThread();
            if (holder != null && holder != current) {
                return; // the thread that has the unit tears it down in restore()
            }
            tornDown = true;
            inPlace = holder == current;
            holder = current;
        }
        if (inPlace) {
            tearDown();
        } else {
            tearDownOnThisThread();
        }
    }

    /** Whether {@link #close()} has been called. */
    public synchronized boolean isClosed() {
        return closeRequested;
    }

    /** Whether the holding thread has to tear the unit down: closed, and nobody has done it yet. */
    private synchronized boolean claimTeardown() {
        if (closeRequested && !tornDown) {
            tornDown = true;
            return true;
        }
        return false;
    }

    /** Gives the unit up, unless a teardown was left to this thread meanwhile. */
    private synchronized boolean release() {
        if (closeRequested && !tornDown) {
            tornDown = true;
            return false;
        }
        holder = null;
        return true;
    }

    /** Installs the unit, which this thread already holds by the books, tears it down and takes it off. */
    private void tearDownOnThisThread() {
        var displaced = swapIn();
        try {
            tearDown();
        } finally {
            swapOut(displaced);
            synchronized (this) {
                holder = null;
            }
        }
    }

    private Displaced swapIn() {
        var previous = new UnitFrame[frames.length];
        for (int i = 0; i < frames.length; i++) {
            previous[i] = modules.get(i).swapFrame(frames[i]);
        }
        return new Displaced(previous, EventChainContext.enter(origin));
    }

    private void swapOut(Displaced displaced) {
        EventChainContext.exit(displaced.origin);
        for (int i = frames.length - 1; i >= 0; i--) {
            modules.get(i).swapFrame(displaced.frames[i]);
        }
    }

    /** Ends the unit while it is installed on the calling thread; a failing module does not stop the others. */
    private void tearDown() {
        var end = Instant.now();
        publish(new EventEndingEvent(eventId, end, Duration.between(startedAt, end)));
        RuntimeException failure = null;
        for (int i = frames.length - 1; i >= 0; i--) {
            try {
                modules.get(i).closeFrame();
            } catch (RuntimeException e) {
                failure = BoundedExecution.retainFailure(failure, e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /** A failing lifecycle publish, a bus decorator or a handler, must not keep the unit from running or closing. */
    private void publish(Object event) {
        try {
            lifecycle.accept(event);
        } catch (RuntimeException e) {
            LoggerHolder.LOG.log(System.Logger.Level.WARNING, "Unit-of-work lifecycle publish threw", e);
        }
    }
}
