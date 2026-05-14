package io.tiko.examples.basic.expose;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * Singleton bean explicitly exposing only {@link Gamma}, even though it also implements
 * {@link Delta}. Verifies the opt-in restriction hides the non-exposed interface from
 * {@code container.get(Class)}.
 */
@Component(
        scope = Scope.SINGLETON,
        expose = {Gamma.class})
public class RestrictedBean implements Gamma, Delta {}
