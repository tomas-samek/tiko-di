package io.tiko.examples.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TicketServiceTest {

    @Test
    void createReturnsTicketWithGeneratedIdAndTitle() {
        var svc = new TicketService();
        var ticket = svc.create(new CreateTicketRequest("first"));

        assertThat(ticket.id()).isNotNull();
        assertThat(ticket.title()).isEqualTo("first");
        assertThat(ticket.createdAt()).isNotNull();
    }

    @Test
    void findReturnsTheCreatedTicket() {
        var svc = new TicketService();
        var created = svc.create(new CreateTicketRequest("second"));

        var found = svc.find(created.id());

        assertThat(found).contains(created);
    }

    @Test
    void findReturnsEmptyForUnknownId() {
        var svc = new TicketService();
        assertThat(svc.find(java.util.UUID.randomUUID())).isEmpty();
    }

    @Test
    void createRejectsBlankTitle() {
        var svc = new TicketService();
        assertThatThrownBy(() -> svc.create(new CreateTicketRequest("")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("title must not be blank");
    }
}
