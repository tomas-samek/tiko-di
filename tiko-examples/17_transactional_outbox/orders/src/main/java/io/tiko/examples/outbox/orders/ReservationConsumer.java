package io.tiko.examples.outbox.orders;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventTrigger;
import io.tiko.examples.outbox.Messages;
import io.tiko.examples.outbox.Messages.ReservationDecided;
import io.tiko.kafka.annotations.KafkaSource;
import java.sql.SQLException;

@Component(scope = Scope.SINGLETON)
public class ReservationConsumer {
    private final OrderStore orders;

    public ReservationConsumer(OrderStore orders) {
        this.orders = orders;
    }

    @KafkaSource(topic = Messages.RESULTS)
    @EventTrigger(eventName = "ReservationRecorded")
    public ReservationDecided receive(ReservationDecided result) {
        try {
            orders.record(result);
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot record reservation for " + result.orderId(), failure);
        }
        return result;
    }
}
