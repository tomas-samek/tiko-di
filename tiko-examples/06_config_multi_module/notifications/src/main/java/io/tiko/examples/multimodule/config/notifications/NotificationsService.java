package io.tiko.examples.multimodule.config.notifications;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

@Component(scope = Scope.SINGLETON)
public class NotificationsService {

    private final NotificationsConfig config;

    @Inject
    public NotificationsService(NotificationsConfig config) {
        this.config = config;
    }

    public NotificationsConfig config() {
        return config;
    }
}
