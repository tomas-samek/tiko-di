package io.tiko;

/**
 * Package-private fallback returned by {@link Container#getErrorHandler()} when a user-supplied
 * {@code Container} implementation does not override the default. The runtime module ships a
 * richer {@code DefaultErrorHandler} via {@code TikoOptions}; this fallback exists so non-runtime
 * {@code Container} implementations remain functional without a binary-incompatible API change.
 */
final class FallbackErrorHandler implements ErrorHandler {

    static final FallbackErrorHandler INSTANCE = new FallbackErrorHandler();

    // Lazy holder: defers System.LoggerFinder resolution until the first error fires.
    private static final class LoggerHolder {
        static final System.Logger LOG = System.getLogger("io.tiko");
    }

    private FallbackErrorHandler() {}

    @Override
    public void onError(ErrorContext context) {
        LoggerHolder.LOG.log(
                System.Logger.Level.WARNING,
                context.getClass().getSimpleName() + ": " + context.cause(),
                context.cause());
    }
}
