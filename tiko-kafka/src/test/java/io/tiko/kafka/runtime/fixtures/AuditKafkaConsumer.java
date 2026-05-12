package io.tiko.kafka.runtime.fixtures;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.kafka.KafkaContext;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.header.Header;

@Component(scope = Scope.SINGLETON)
public class AuditKafkaConsumer {

    public AuditRecorded fromKafka(AuditPayload payload, KafkaContext ctx) {
        Header h = ctx.headers().lastHeader("X-Correlation-Id");
        String corr = h == null ? null : new String(h.value(), StandardCharsets.UTF_8);
        return new AuditRecorded(payload.id(), payload.action(), corr);
    }
}
