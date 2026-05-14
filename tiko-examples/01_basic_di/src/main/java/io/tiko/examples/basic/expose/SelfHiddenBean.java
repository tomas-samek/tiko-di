package io.tiko.examples.basic.expose;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * Singleton bean exposed only via {@link Epsilon}; {@code exposeSelf = false} hides the
 * impl class from {@code container.get(SelfHiddenBean.class)}.
 */
@Component(
        scope = Scope.SINGLETON,
        expose = {Epsilon.class},
        exposeSelf = false)
public class SelfHiddenBean implements Epsilon {}
