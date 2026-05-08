package ${package};

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * Minimal {@link Component} produced by the tiko-archetype scaffold. Replace with your own.
 *
 * <p>{@code Tiko.create()} discovers this class at compile time and wires a singleton
 * instance into the generated container.</p>
 */
@Component(scope = Scope.SINGLETON)
public class Greeter {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
