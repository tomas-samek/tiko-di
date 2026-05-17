package io.tiko.runtime;

import java.text.MessageFormat;

/**
 * Internal logging helper for tiko-runtime framework code. Pre-formats messages via
 * {@link MessageFormat} only when the level is loggable, avoiding allocation for
 * filtered-out levels.
 *
 * <p>Two shapes:
 *
 * <ul>
 *   <li>Parameterised — {@link #log(System.Logger, System.Logger.Level, String, Object...)}</li>
 *   <li>Parameterised + throwable — {@link #log(System.Logger, System.Logger.Level, Throwable, String, Object...)}</li>
 * </ul>
 *
 * <p>For expensive-to-format messages without parameters, prefer the SDK's supplier
 * form directly: {@code LOG.log(level, () -> "computed " + expensive())}.
 *
 * <p><strong>MessageFormat gotcha:</strong> single quotes are special in patterns.
 * Escape by doubling — {@code "deduped ''{0}''"} renders as {@code "deduped 'value'"}.
 * For messages without parameters, the helper bypasses {@code MessageFormat} entirely
 * (no-args branch) so stray quotes are passed through verbatim.
 */
final class TikoLog {

    private TikoLog() {}

    static void log(System.Logger logger, System.Logger.Level level, String pattern, Object... args) {
        if (!logger.isLoggable(level)) return;
        logger.log(level, args.length == 0 ? pattern : MessageFormat.format(pattern, args), (Throwable) null);
    }

    static void log(System.Logger logger, System.Logger.Level level, Throwable thrown, String pattern, Object... args) {
        if (!logger.isLoggable(level)) return;
        logger.log(level, args.length == 0 ? pattern : MessageFormat.format(pattern, args), thrown);
    }
}
