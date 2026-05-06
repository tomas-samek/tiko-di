package io.tiko.examples.events;

import java.util.List;

public record BatchSubmitted(List<OrderPlaced> orders) {}
