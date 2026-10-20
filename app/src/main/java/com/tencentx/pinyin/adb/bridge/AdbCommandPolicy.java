package com.tencentx.pinyin.adb.bridge;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Command policy for the remote ADB bridge.
 * <p>
 * Three modes are supported:
 * <ul>
 *     <li>{@link Mode#ALLOWLIST}: a command is allowed only when it matches a
 *         prefix in the allowlist. An empty allowlist rejects everything.</li>
 *     <li>{@link Mode#DENYLIST}: a command is allowed unless it matches a prefix
 *         in the denylist.</li>
 *     <li>{@link Mode#OPEN}: every command is allowed.</li>
 * </ul>
 * Matching is by prefix. The default mode is {@link Mode#ALLOWLIST} with an
 * empty list, i.e. <strong>reject everything</strong>.
 */
public final class AdbCommandPolicy {

    public enum Mode {
        ALLOWLIST,
        DENYLIST,
        OPEN;

        @NonNull
        public String getValue() {
            return name();
        }

        @NonNull
        static Mode fromValue(@Nullable final String value) {
            if (value == null || value.isBlank()) {
                return ALLOWLIST;
            }
            for (final Mode m : values()) {
                if (m.name().equalsIgnoreCase(value.strip())) {
                    return m;
                }
            }
            return ALLOWLIST;
        }
    }

    private AdbCommandPolicy() {
    }

    /**
     * Whether the given command is allowed by the policy.
     *
     * @param command  the command to check
     * @param mode     the policy mode
     * @param allowlist newline-separated allowlist prefixes (used in {@link Mode#ALLOWLIST})
     * @param denylist  newline-separated denylist prefixes (used in {@link Mode#DENYLIST})
     *
     * @return {@code true} if the command is allowed
     */
    public static boolean isAllowed(@Nullable final String command,
                                    @NonNull final Mode mode,
                                    @Nullable final String allowlist,
                                    @Nullable final String denylist) {
        if (command == null || command.isBlank()) {
            return false;
        }
        switch (mode) {
            case OPEN:
                return true;
            case DENYLIST:
                return !matchesAnyPrefix(command, splitLines(denylist));
            case ALLOWLIST:
            default:
                return matchesAnyPrefix(command, splitLines(allowlist));
        }
    }

    private static boolean matchesAnyPrefix(@NonNull final String command,
                                            @NonNull final List<String> prefixes) {
        for (final String prefix : prefixes) {
            if (!prefix.isBlank() && command.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    private static List<String> splitLines(@Nullable final String text) {
        final List<String> result = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return result;
        }
        for (final String line : text.split("\\R", -1)) {
            final String s = line.strip();
            if (!s.isEmpty()) {
                result.add(s);
            }
        }
        return result;
    }
}
