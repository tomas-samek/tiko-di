package io.tiko.examples.multimodule.modulea;

import io.tiko.Picker;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.util.List;

/**
 * Lists every channel of the application. Module A knows only its own {@link InAppChannel} at
 * compile time; the injected {@code Picker<Channel>} asks the application's container, so the
 * channels other modules add are listed too.
 */
@Component(scope = Scope.SINGLETON)
public class ChannelCatalog {

    private final Picker<Channel> channels;

    @Inject
    public ChannelCatalog(Picker<Channel> channels) {
        this.channels = channels;
    }

    public List<String> names() {
        return channels.list().stream().map(Channel::name).toList();
    }
}
