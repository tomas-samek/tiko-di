package io.tiko.examples.basic.expose;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * Singleton bean implementing two interfaces under the permissive default (no expose).
 * Used to verify (a) every implemented interface is routable, (b) all routes resolve to
 * the same scope-cached instance.
 */
@Component(scope = Scope.SINGLETON)
public class MultiInterfaceBean implements Alpha, Beta {
    @Override
    public String label() {
        return "multi";
    }

    @Override
    public int count() {
        return 42;
    }
}
