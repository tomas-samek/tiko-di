package io.tiko.examples.basic;

import io.tiko.Scope;
import io.tiko.annotations.Component;

/**
 * The single unnamed implementation of Speaker.
 * Demonstrates that container.get(Interface.class) resolves to the unnamed "default" impl.
 */
@Component(scope = Scope.SINGLETON)
public class DefaultSpeaker implements Speaker {
    @Override
    public String introduce() {
        return "Hi, I'm the default speaker.";
    }
}
