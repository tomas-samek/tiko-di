package ${package};

import io.tiko.Container;
import io.tiko.Tiko;

/**
 * Tiny entry point produced by the tiko-archetype scaffold. Replace with your own.
 *
 * <p>Run: {@code mvn exec:java}.</p>
 */
public final class Main {

    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            Greeter greeter = container.get(Greeter.class);
            System.out.println(greeter.greet("world"));
        }
    }

    private Main() {
    }
}
