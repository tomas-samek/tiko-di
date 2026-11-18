package io.tiko.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** #685: the unit of work a component is handed opens its units in the application's container. */
class ContainerUnitOfWorkTest {

    /** A container that only opens units: it runs the task and notes that it was asked. */
    private static final class Units {
        final List<String> calls = new ArrayList<>();
        final Container container = (Container) Proxy.newProxyInstance(
                Container.class.getClassLoader(), new Class<?>[] {Container.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    return switch (method.getName()) {
                        case "runInEventScope" -> {
                            ((Runnable) args[0]).run();
                            yield null;
                        }
                        case "supplyInEventScope" -> ((Supplier<?>) args[0]).get();
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
    }

    private static List<String> openOneOfEach(ContainerUnitOfWork unitOfWork) {
        var ran = new ArrayList<String>();
        unitOfWork.runInEventScope(() -> ran.add("task"));
        ran.add(unitOfWork.supplyInEventScope(() -> "value"));
        return ran;
    }

    @Test
    void opensTheUnitInItsOwnContainerWhenNoApplicationIsNamed() {
        var module = new Units();

        var ran = openOneOfEach(
                new ContainerUnitOfWork(module.container, TikoOptions.builder().build()));

        assertThat(ran).containsExactly("task", "value");
        assertThat(module.calls).containsExactly("runInEventScope", "supplyInEventScope");
    }

    @Test
    void opensTheUnitInTheApplicationsContainer() {
        var module = new Units();
        var application = new Units();
        var options = TikoOptions.builder().build();
        options.applicationContainer(application.container);

        var ran = openOneOfEach(new ContainerUnitOfWork(module.container, options));

        assertThat(ran).containsExactly("task", "value");
        assertThat(application.calls).containsExactly("runInEventScope", "supplyInEventScope");
        assertThat(module.calls).isEmpty();
    }

    /** The application's container builds its modules, and their components, while it is being constructed. */
    @Test
    void looksTheApplicationUpOnEveryCall() {
        var module = new Units();
        var application = new Units();
        var options = TikoOptions.builder().build();
        var unitOfWork = new ContainerUnitOfWork(module.container, options);

        options.applicationContainer(application.container);
        unitOfWork.runInEventScope(() -> {});

        assertThat(application.calls).containsExactly("runInEventScope");
        assertThat(module.calls).isEmpty();
    }

    @Test
    void worksWithoutOptions() {
        var module = new Units();

        var ran = openOneOfEach(new ContainerUnitOfWork(module.container, null));

        assertThat(ran).containsExactly("task", "value");
        assertThat(module.calls).containsExactly("runInEventScope", "supplyInEventScope");
    }
}
