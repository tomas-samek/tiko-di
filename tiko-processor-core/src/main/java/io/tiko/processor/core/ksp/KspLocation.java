package io.tiko.processor.core.ksp;

import com.google.devtools.ksp.symbol.KSNode;
import io.tiko.processor.core.model.Location;
import java.util.Optional;

/**
 * A KSP position: the node a message is reported on (its file and line come from KSP), found again
 * in the current round, and the Java name of the nearest enclosing class, read when the location
 * was made. A {@code null} node reports without a position.
 */
record KspLocation(KspRef<? extends KSNode> node, String enclosingClass) implements Location {

    /** The node, valid in the current round; {@code null} when the location has none. */
    KSNode currentNode() {
        return node == null ? null : node.get();
    }

    @Override
    public Optional<String> enclosingClassName() {
        return Optional.ofNullable(enclosingClass);
    }
}
