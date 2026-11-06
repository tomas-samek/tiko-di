package io.tiko.kafka.processor;

import com.google.auto.service.AutoService;
import io.tiko.kafka.annotations.KafkaSink;
import io.tiko.kafka.annotations.KafkaSource;
import io.tiko.processor.core.javac.JavacProcessingEnv;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Generated;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;

/**
 * The javac entry point of the Kafka annotation processor. Independent of
 * {@code TikoAnnotationProcessor}: both register via {@code @AutoService} and both run
 * on the user's annotation-processor path.
 *
 * <p>A thin frontend shell: each round it wraps javac in a {@link JavacProcessingEnv} and hands
 * it to the {@link KafkaPipeline}, which collects, validates and generates. This class only
 * decides what javac needs decided: which annotations to claim and when to call the pipeline.
 */
@AutoService(Processor.class)
public final class KafkaAnnotationProcessor extends AbstractProcessor {

    private final KafkaPipeline pipeline = new KafkaPipeline();

    /**
     * Supported only so the processor can claim it on its generated bootstrap in the rounds
     * after generation; javac's {@code -Xlint:processing} otherwise reports it as unclaimed,
     * which fails {@code -Werror} builds.
     */
    private static final String GENERATED_FQN = Generated.class.getCanonicalName();

    /**
     * Claims the compiling JDK's latest source version so users on newer JDKs compiling at
     * a higher {@code --release} don't get javac's "Supported source version ... less than
     * -source" warning (fatal under {@code -Werror}).
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(KafkaSource.class.getCanonicalName(), KafkaSink.class.getCanonicalName(), GENERATED_FQN);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        var env = JavacProcessingEnv.of(processingEnv, roundEnv);
        if (roundEnv.processingOver()) {
            // No-op on javac: the output went out in the round that carried the bridges, if any.
            pipeline.finish(env);
            return false;
        }
        // Rounds after generation: claim @Generated on the previous round's output.
        if (pipeline.finished()) return true;
        // Only foreign @Generated sources this round: nothing to bridge, and not ours to claim.
        if (!KafkaPipeline.isEntryPointRound(
                name -> annotations.stream().anyMatch(a -> a.getQualifiedName().contentEquals(name)))) return false;

        pipeline.process(env);
        if (pipeline.failed()) return false;
        // Generate in this round, not at processingOver(): see KafkaPipeline's timing notes.
        pipeline.finish(env);
        return true; // Claim @KafkaSource / @KafkaSink
    }
}
