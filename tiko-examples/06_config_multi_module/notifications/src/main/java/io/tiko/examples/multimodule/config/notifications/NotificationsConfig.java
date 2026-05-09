package io.tiko.examples.multimodule.config.notifications;

import io.tiko.annotations.Configuration;

@Configuration(prefix = "notifications")
public record NotificationsConfig(String channel, boolean enabled) {}
