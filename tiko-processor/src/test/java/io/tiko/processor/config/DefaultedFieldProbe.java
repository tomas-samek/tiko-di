package io.tiko.processor.config;

/** A field type whose static initializer leaves a mark, for {@link ConfigTypeNotInitializedTest}. */
public final class DefaultedFieldProbe {

    /** The system property the static initializer sets. */
    static final String MARK = "tiko.test.initialized.DefaultedFieldProbe";

    static {
        System.setProperty("tiko.test.initialized.DefaultedFieldProbe", "true");
    }

    private DefaultedFieldProbe() {}
}
