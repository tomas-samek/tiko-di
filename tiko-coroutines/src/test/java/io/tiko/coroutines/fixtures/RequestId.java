package io.tiko.coroutines.fixtures;

/** Interface contract for the EVENT-scoped {@link FreshRequestId}: one value per unit of work. */
public interface RequestId {
    String value();
}
