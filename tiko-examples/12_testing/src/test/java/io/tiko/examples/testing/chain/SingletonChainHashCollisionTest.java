package io.tiko.examples.testing.chain;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.test.TikoTest;
import org.junit.jupiter.api.Test;

/**
 * Resolving a singleton chain whose storage keys share a ConcurrentHashMap bin must not
 * trip the map's recursive-update detection (#338). The two fixture keys have identical
 * string hashes ({@code Aa}/{@code BB} suffix), and test mode resolves lazily, so the
 * head's creation runs while the tail is still absent from the same bin — the exact
 * shape that made nested {@code computeIfAbsent} throw
 * {@code IllegalStateException("Recursive update")}.
 */
@TikoTest
class SingletonChainHashCollisionTest {

    @Test
    void lazySingletonChainWithCollidingKeysResolves(SingletonChainAa head) {
        assertThat(head).isNotNull();
        assertThat(head.dep()).isNotNull();
    }
}
