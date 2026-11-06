package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.processing.KSPLogger;
import com.google.devtools.ksp.symbol.KSNode;
import io.tiko.processor.core.Diagnostics;
import io.tiko.processor.core.model.Location;

/**
 * {@link Diagnostics} over KSP's {@link KSPLogger}, reported on the location's node: KSP prints its
 * file and line. Notes go out as {@code info}.
 */
final class KspDiagnostics implements Diagnostics {

    private final KSPLogger logger;
    private boolean errors;

    KspDiagnostics(KSPLogger logger) {
        this.logger = logger;
    }

    @Override
    public void error(Location at, String message) {
        logger.error(message, node(at));
        errors = true;
    }

    @Override
    public void warning(Location at, String message) {
        logger.warn(message, node(at));
    }

    @Override
    public void note(Location at, String message) {
        logger.info(message, node(at));
    }

    @Override
    public boolean hasErrors() {
        return errors;
    }

    private static KSNode node(Location at) {
        return switch (at) {
            case null -> null;
            case KspLocation ksp -> ksp.currentNode();
            default -> throw new IllegalArgumentException("Not a KSP location: " + at);
        };
    }
}
