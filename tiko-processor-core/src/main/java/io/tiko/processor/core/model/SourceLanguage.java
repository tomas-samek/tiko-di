package io.tiko.processor.core.model;

/** Where a declaration comes from: a source file in this compilation, or compiled bytecode on the classpath. */
public enum SourceLanguage {
    JAVA,
    KOTLIN,
    CLASSPATH
}
