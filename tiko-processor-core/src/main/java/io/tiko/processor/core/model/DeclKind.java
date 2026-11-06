package io.tiko.processor.core.model;

/** What kind of type declaration a {@link ClassDecl} is. The {@code KOTLIN_*} kinds never occur on javac. */
public enum DeclKind {
    CLASS,
    INTERFACE,
    RECORD,
    ENUM,
    ANNOTATION,
    KOTLIN_OBJECT,
    KOTLIN_COMPANION
}
