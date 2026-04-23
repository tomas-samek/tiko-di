package io.tiko.examples.basic;

import io.tiko.Provider;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

@Component(scope = Scope.SINGLETON)
public class TicketBooth {

    private final Provider<Ticket> ticketProvider;

    @Inject
    public TicketBooth(Provider<Ticket> ticketProvider) {
        this.ticketProvider = ticketProvider;
    }

    public Ticket issue() {
        return ticketProvider.get();
    }
}
