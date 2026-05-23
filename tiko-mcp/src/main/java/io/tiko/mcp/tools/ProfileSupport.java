package io.tiko.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * Profile-matching helpers shared by tools that filter or resolve components
 * by active profile. A component or factory method with an empty
 * {@code profiles[]} is treated as active under all profiles (the same
 * benevolent-default convention the runtime container applies).
 */
final class ProfileSupport {

    private ProfileSupport() {}

    @SuppressWarnings("unchecked")
    static boolean profileMatches(Map<String, Object> entry, String profile) {
        var v = entry.get("profiles");
        if (!(v instanceof List<?> list) || list.isEmpty()) return true;
        return list.stream().anyMatch(profile::equals);
    }
}
