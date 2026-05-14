package io.tiko.examples.http;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory ticket store. HTTP-free by design — a future native HTTP transport
 * could route requests straight at this bean without any rewiring.
 */
@Component(scope = Scope.SINGLETON)
public class TicketService {

    private final Map<UUID, Ticket> store = new ConcurrentHashMap<>();

    public Ticket create(CreateTicketRequest req) {
        if (req.title() == null || req.title().isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        var ticket = new Ticket(UUID.randomUUID(), req.title(), Instant.now());
        store.put(ticket.id(), ticket);
        return ticket;
    }

    public Optional<Ticket> find(UUID id) {
        return Optional.ofNullable(store.get(id));
    }
}
