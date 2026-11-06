import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** TEMPORARY diagnostic for #581: times Kafka container startups. Removed before merge. */
public class Bench {
    public static void main(String[] args) throws Exception {
        var variant = args[0];
        var n = Integer.parseInt(args[1]);
        var label = args.length > 2 ? args[2] : "";
        for (int i = 0; i < n; i++) {
            GenericContainer<?> c = switch (variant) {
                case "cp" -> new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));
                case "apache" -> new KafkaContainer(DockerImageName.parse("apache/kafka:3.9.2"));
                case "native" -> new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.9.2"));
                default -> throw new IllegalArgumentException(variant);
            };
            c.withStartupTimeout(Duration.ofMinutes(5));
            long t0 = System.nanoTime();
            try {
                c.start();
            } catch (RuntimeException e) {
                System.out.printf(
                        "RESULT %s %s run %d: FAILED after %.2f s: %s%n",
                        variant, label, i + 1, (System.nanoTime() - t0) / 1e9, e);
                dump(c);
                c.stop();
                continue;
            }
            long t1 = System.nanoTime();
            var bs = c instanceof ConfluentKafkaContainer ck
                    ? ck.getBootstrapServers()
                    : ((KafkaContainer) c).getBootstrapServers();
            try (var admin = Admin.create(Map.<String, Object>of("bootstrap.servers", bs))) {
                admin.createTopics(List.of(new NewTopic("t" + i, 1, (short) 1)))
                        .all()
                        .get(30, TimeUnit.SECONDS);
            }
            long t2 = System.nanoTime();
            if ((t1 - t0) / 1e9 > 30) {
                dump(c);
            }
            c.stop();
            System.out.printf(
                    "RESULT %s %s run %d: start %.2f s, first createTopic %.2f s%n",
                    variant, label, i + 1, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
        }
    }

    private static void dump(GenericContainer<?> c) {
        try {
            var st = c.getCurrentContainerInfo().getState();
            System.out.println("STATE running=" + st.getRunning() + " exit=" + st.getExitCodeLong() + " oom="
                    + st.getOOMKilled() + " error=" + st.getError());
            var logs = c.getLogs();
            var tail = logs.length() > 6000 ? logs.substring(logs.length() - 6000) : logs;
            for (var line : tail.split("\n")) {
                System.out.println("LOGTAIL " + line);
            }
        } catch (RuntimeException e) {
            System.out.println("LOGTAIL unavailable: " + e);
        }
    }
}
