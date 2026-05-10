package io.tiko.examples.basic;

import io.tiko.Picker;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.util.List;
import java.util.Optional;

/**
 * Demonstrates {@code Picker<T>} — the typed runtime lookup primitive for cases
 * the compile-time qualifiers ({@code @Pick}, {@code @Named}) cannot express:
 * runtime-driven lookups, iteration over all impls, or cross-module queries.
 */
@Component(scope = Scope.SINGLETON)
public class PickerConsumer {

    private final Picker<Greeter> greeters;

    @Inject
    public PickerConsumer(Picker<Greeter> greeters) {
        this.greeters = greeters;
    }

    /** All registered greeter impls, in declaration order. */
    public List<Greeter> all() {
        return greeters.list();
    }

    /** Resolves a greeter by its {@code @Component(name = ...)} qualifier. */
    public Optional<Greeter> byLanguageName(String language) {
        return greeters.byName(language);
    }

    /** Resolves a greeter by its concrete impl class. */
    public <T extends Greeter> Optional<T> byImplClass(Class<T> impl) {
        return greeters.byImplClass(impl);
    }
}
