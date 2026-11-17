package io.tiko.processor.config;

/** A field type whose static initializer leaves a mark, for {@link ConfigTypeNotInitializedTest}. */
public final class PlainFieldProbe {

    /** The system property the static initializer sets. */
    static final String MARK = "tiko.test.initialized.PlainFieldProbe";

    static {
        System.setProperty("tiko.test.initialized.PlainFieldProbe", "true");
    }

    private PlainFieldProbe() {}
}
