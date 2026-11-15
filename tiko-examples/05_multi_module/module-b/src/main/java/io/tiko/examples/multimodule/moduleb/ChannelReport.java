package io.tiko.examples.multimodule.moduleb;

import io.tiko.Picker;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.examples.multimodule.modulea.Channel;
import java.util.List;

/** Module B's view of the channels: the same application-wide list Module A's catalog gives. */
@Component(scope = Scope.SINGLETON)
public class ChannelReport {

    private final Picker<Channel> channels;

    @Inject
    public ChannelReport(Picker<Channel> channels) {
        this.channels = channels;
    }

    public List<String> names() {
        return channels.list().stream().map(Channel::name).toList();
    }
}
