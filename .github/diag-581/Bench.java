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

public class Bench {
    public static void main(String[] args) throws Exception {
        var variant = args[0];
        var n = Integer.parseInt(args[1]);
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
                System.out.printf("RESULT %s run %d: FAILED after %.2f s: %s%n", variant, i + 1, (System.nanoTime() - t0) / 1e9, e);
                try {
                    System.out.println("LOGTAIL " + tail(c.getLogs()));
                } catch (RuntimeException logs) {
                    System.out.println("LOGTAIL unavailable: " + logs);
                }
                c.stop();
                continue;
            }
            long t1 = System.nanoTime();
            String bs = variant.equals("cp") ? ((ConfluentKafkaContainer) c).getBootstrapServers() : ((KafkaContainer) c).getBootstrapServers();
            try (var admin = Admin.create(Map.<String, Object>of("bootstrap.servers", bs))) {
                admin.createTopics(List.of(new NewTopic("t" + i, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
            }
            long t2 = System.nanoTime();
            c.stop();
            System.out.printf("RESULT %s run %d: start %.2f s, first createTopic %.2f s%n", variant, i + 1, (t1 - t0) / 1e9, (t2 - t1) / 1e9);
        }
    }

    private static String tail(String logs) {
        return logs.length() > 4000 ? logs.substring(logs.length() - 4000) : logs;
    }
}
