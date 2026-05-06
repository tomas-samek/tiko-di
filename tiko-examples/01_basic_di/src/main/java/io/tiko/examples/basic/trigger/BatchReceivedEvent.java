package io.tiko.examples.basic.trigger;

import java.util.List;

public record BatchReceivedEvent(List<String> items) {}
