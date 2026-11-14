package io.tiko.examples.basic.crossscope;

/** A long-lived object that reads the session of whichever unit of work is calling it. */
public class SessionReporter {

    private final UnitSession session;

    public SessionReporter(UnitSession session) {
        this.session = session;
    }

    public int currentSessionNumber() {
        return session.number();
    }
}
