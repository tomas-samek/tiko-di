package io.tiko.kafka.runtime;

import io.tiko.Container;
import io.tiko.ContainerInitializationException;
import io.tiko.ErrorHandler;
import io.tiko.EventBus;
import io.tiko.EventCallback;
import io.tiko.kafka.KafkaConfig;
import io.tiko.kafka.KafkaEgressError;
import io.tiko.kafka.KafkaSerializer;
import io.tiko.kafka.NamedKafkaSerializer;
import io.tiko.kafka.client.ApacheKafkaConsumerClient;
import io.tiko.kafka.client.ApacheKafkaProducerClient;
import io.tiko.kafka.client.KafkaConsumerClient;
import io.tiko.kafka.client.KafkaProducerClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.function.BiFunction;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;

/**
 * Runtime helper shared by every generated {@code KafkaTransportBootstrap}. Owns the
 * lifetime of consumer runners and the singleton producer client; resolves serializers
 * via {@code ServiceLoader<NamedKafkaSerializer>}.
 *
 * <p>The producer/consumer factories are injectable so unit tests can swap in
 * {@link io.tiko.kafka.test.FakeKafkaBroker}.
 */
public final class KafkaBootstrapSupport {

    private final Container container;
    private final List<GeneratedSourceDescriptor> sources;
    private final List<GeneratedSinkDescriptor> sinks;

    private final BiFunction<KafkaConfig, String, KafkaConsumerClient> consumerFactory;
    private final java.util.function.Function<KafkaConfig, KafkaProducerClient> producerFactory;

    private final List<KafkaConsumerRunner> runners = new ArrayList<>();
    private KafkaProducerClient producer;

    public KafkaBootstrapSupport(
            Container container, List<GeneratedSourceDescriptor> sources, List<GeneratedSinkDescriptor> sinks) {
        this(container, sources, sinks, ApacheKafkaConsumerClient::new, ApacheKafkaProducerClient::new);
    }

    /** Test-only constructor accepting custom client factories (e.g., {@code FakeKafkaBroker}). */
    public KafkaBootstrapSupport(
            Container container,
            List<GeneratedSourceDescriptor> sources,
            List<GeneratedSinkDescriptor> sinks,
            BiFunction<KafkaConfig, String, KafkaConsumerClient> consumerFactory,
            java.util.function.Function<KafkaConfig, KafkaProducerClient> producerFactory) {
        this.container = container;
        this.sources = sources;
        this.sinks = sinks;
        this.consumerFactory = consumerFactory;
        this.producerFactory = producerFactory;
    }

    public void start() {
        KafkaConfig config = container.get(KafkaConfig.class);
        EventBus eventBus = container.getEventBus();
        ErrorHandler errorHandler = resolveErrorHandler(container);
        Map<String, KafkaSerializer> named = loadNamedSerializers();

        // Outbound — subscribe one callback per @KafkaSink.
        if (producer == null && !sinks.isEmpty()) {
            producer = producerFactory.apply(config);
        }
        for (GeneratedSinkDescriptor sink : sinks) {
            KafkaSerializer serializer = resolveSerializer(sink.serializerClass(), config, named);
            eventBus.subscribe(asEventType(sink.eventType()), wrapSinkCallback(sink, serializer, errorHandler));
        }

        // Inbound — one runner per source.
        for (GeneratedSourceDescriptor source : sources) {
            String group = source.consumerGroup().isEmpty() ? config.consumerGroup() : source.consumerGroup();
            KafkaSerializer serializer = resolveSerializer(source.serializerClass(), config, named);
            KafkaConsumerClient client = consumerFactory.apply(config, group);
            KafkaConsumerRunner runner =
                    new ThreadPerTopicRunner(source, client, container, eventBus, errorHandler, serializer, config);
            runners.add(runner);
            runner.start();
        }
    }

    public void shutdown() {
        for (KafkaConsumerRunner r : runners) {
            try {
                r.stop();
            } catch (Exception ignored) {
                /* best-effort */
            }
        }
        runners.clear();
        if (producer != null) {
            try {
                producer.close();
            } catch (Exception ignored) {
                /* best-effort */
            }
            producer = null;
        }
    }

    // --- helpers ------------------------------------------------------------------

    private <T> EventCallback<T> wrapSinkCallback(
            GeneratedSinkDescriptor sink, KafkaSerializer serializer, ErrorHandler errorHandler) {
        return (T event) -> {
            try {
                Object payload = sink.dispatcher().dispatch(container, event);
                byte[] bytes = serializer.serialize(payload);
                String key = sink.keyExtractor().extract(payload);
                // The send callback surfaces broker-side failures (record-too-large, topic
                // authorization, delivery timeout) that never throw synchronously — without
                // it the rejection is silent egress loss (#342).
                producer.send(
                        new ProducerRecord<>(sink.topic(), null, key, bytes, new RecordHeaders()), (md, failure) -> {
                            if (failure != null) {
                                routeEgressError(errorHandler, new KafkaEgressError(sink.topic(), event, failure));
                            }
                        });
            } catch (Exception ex) {
                routeEgressError(errorHandler, new KafkaEgressError(sink.topic(), event, ex));
            }
        };
    }

    /**
     * Routes an egress error without letting a throwing ErrorHandler propagate — the send
     * callback runs on the producer's I/O thread, which must never die on user code.
     */
    private static void routeEgressError(ErrorHandler errorHandler, KafkaEgressError error) {
        try {
            errorHandler.onError(error);
        } catch (Exception handlerFailure) {
            LoggerHolder.LOG.log(
                    System.Logger.Level.WARNING,
                    "ErrorHandler threw while handling a Kafka egress error",
                    handlerFailure);
        }
    }

    private static Map<String, KafkaSerializer> loadNamedSerializers() {
        Map<String, KafkaSerializer> result = new HashMap<>();
        for (NamedKafkaSerializer named : ServiceLoader.load(NamedKafkaSerializer.class)) {
            result.put(named.name(), named.serializer());
        }
        return result;
    }

    private static KafkaSerializer resolveSerializer(
            Class<? extends KafkaSerializer> declared, KafkaConfig config, Map<String, KafkaSerializer> named) {
        if (declared != KafkaSerializer.Default.class) {
            try {
                return declared.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new ContainerInitializationException(
                        "@KafkaSource/@KafkaSink serializer "
                                + declared.getName()
                                + " could not be instantiated. It must have a public no-arg constructor.",
                        e);
            }
        }
        KafkaSerializer impl = named.get(config.serializer());
        if (impl == null) {
            throw new ContainerInitializationException("tiko.kafka.serializer = '"
                    + config.serializer()
                    + "' but no NamedKafkaSerializer with that name was found via ServiceLoader. "
                    + "Known names: "
                    + named.keySet());
        }
        return impl;
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> asEventType(Class<?> eventType) {
        return (Class<T>) eventType;
    }

    /**
     * Returns the {@link ErrorHandler} configured on the container.
     *
     * <p>{@link Container#getErrorHandler()} is now part of the API so no reflection is needed.
     * Custom {@code Container} implementations that do not override the method receive the
     * JUL-backed default defined on the interface.
     */
    private static ErrorHandler resolveErrorHandler(Container container) {
        return container.getErrorHandler();
    }

    /** Lazy holder: defers System.LoggerFinder resolution until the first failure path runs. */
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko.kafka");
    }
}
