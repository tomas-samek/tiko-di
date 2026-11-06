package io.tiko.processor.core.ksp;

import static io.tiko.processor.core.ksp.KspFacts.cls;
import static io.tiko.processor.core.ksp.KspFacts.fact;
import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.ksp.KspFacts.Fact;
import io.tiko.processor.core.ksp.KspFacts.Threw;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One fact per row about types that do not resolve on KSP, for example a class another processor
 * has yet to generate: the type is erroneous, its declaration is a member-less placeholder named
 * as written, and unresolved class literals read as absent while {@code isUnresolved} reports
 * them. The fixture does not compile; the facts are read in the first round regardless.
 */
class KspUnresolvedFactsTest {

    private static final String KOTLIN = """
            package u

            import kotlin.reflect.KClass

            annotation class Refs(val types: Array<KClass<*>>, val one: KClass<*>)

            @MissingAnno
            @Refs(types = [String::class, Missing::class], one = Missing::class)
            class Uses(val gone: Missing, val list: List<Missing>, val qualified: a.b.Gone) : MissingBase()
            """;

    private static Map<String, Object> results;

    @BeforeAll
    static void compile() {
        results = KspFacts.collect(facts(), Map.of(), KspCompiler.kotlin("Broken.kt", KOTLIN));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facts")
    void unresolvedTypesMapToErrorTypesAndPlaceholders(Fact fact) {
        assertThat(results).containsEntry(fact.name(), fact.expected());
    }

    static List<Fact> facts() {
        return List.of(
                fact("unresolvedTypeIsError", env -> gone(env).isError(), true),
                fact("unresolvedTypeIsNamedAsWritten", env -> gone(env).displayName(), "Missing"),
                fact("unresolvedTypeErasedName", env -> gone(env).erasedName(), "Missing"),
                fact("unresolvedTypeIsNotVoid", env -> gone(env).isVoid(), false),
                fact(
                        "unresolvedTypeArgumentIsError",
                        env -> cls(env, "u.Uses")
                                .primaryConstructor()
                                .orElseThrow()
                                .parameters()
                                .get(1)
                                .type()
                                .typeArguments()
                                .get(0)
                                .isError(),
                        true),
                fact(
                        "unresolvedSuperclassIsStillPresent",
                        env -> cls(env, "u.Uses").superclass().map(TypeRef::isError),
                        Optional.of(true)),
                fact(
                        "unresolvedIsNeverAssignable",
                        env -> env.types()
                                .isAssignable(
                                        gone(env), cls(env, "java.lang.Object").asType()),
                        false),
                fact("unresolvedIsNeverTheSameType", env -> env.types().isSameType(gone(env), gone(env)), false),
                fact(
                        "qualifiedUnresolvedTypeIsNamedAsWritten",
                        env -> qualified(env).displayName(),
                        "a.b.Gone"),
                fact(
                        "qualifiedPlaceholderClassName",
                        env -> qualified(env)
                                .declaration()
                                .orElseThrow()
                                .className()
                                .toString(),
                        "a.b.Gone"),
                fact(
                        "unresolvedAnnotationIsNamedAsWritten",
                        env -> cls(env, "u.Uses").annotations().get(0).annotationName(),
                        "MissingAnno"),
                // ----- the placeholder declaration -----
                fact("placeholderName", env -> placeholder(env).qualifiedName(), "Missing"),
                fact("placeholderClassName", env -> placeholder(env).className().toString(), "Missing"),
                fact("placeholderKind", env -> placeholder(env).kind(), DeclKind.CLASS),
                fact("placeholderVisibility", env -> placeholder(env).visibility(), Visibility.PUBLIC),
                fact("placeholderIsNotAbstract", env -> placeholder(env).isAbstract(), false),
                fact("placeholderIsNotFinal", env -> placeholder(env).isFinal(), false),
                fact("placeholderIsNotStatic", env -> placeholder(env).isStatic(), false),
                fact("placeholderLanguage", env -> placeholder(env).language(), SourceLanguage.CLASSPATH),
                fact("placeholderHasNoAnnotations", env -> placeholder(env).annotations(), List.of()),
                fact("placeholderAnnotationLookup", env -> placeholder(env).annotation("u.Refs"), Optional.empty()),
                fact("placeholderAnnotationsByType", env -> placeholder(env).annotationsByType("u.Refs"), List.of()),
                fact("placeholderHasNoConstructors", env -> placeholder(env).constructors(), List.of()),
                fact(
                        "placeholderHasNoPrimaryConstructor",
                        env -> placeholder(env).primaryConstructor(),
                        Optional.empty()),
                fact("placeholderHasNoMethods", env -> placeholder(env).methods(), List.of()),
                fact("placeholderHasNoInheritedMethods", env -> placeholder(env).allMethods(), List.of()),
                fact("placeholderHasNoFields", env -> placeholder(env).fields(), List.of()),
                fact("placeholderHasNoRecordComponents", env -> placeholder(env).recordComponents(), List.of()),
                fact("placeholderHasNoEnumConstants", env -> placeholder(env).enumConstants(), List.of()),
                fact("placeholderHasNoSupertypes", env -> placeholder(env).directSupertypes(), List.of()),
                fact("placeholderHasNoSuperclass", env -> placeholder(env).superclass(), Optional.empty()),
                fact("placeholderHasNoInterfaces", env -> placeholder(env).interfaces(), List.of()),
                fact("placeholderHasNoSourcePath", env -> placeholder(env).sourcePath(), Optional.empty()),
                fact("placeholderHasNoEnclosingClass", env -> placeholder(env).enclosingClass(), Optional.empty()),
                fact(
                        "placeholderTypeIsTheErroneousType",
                        env -> placeholder(env).asType().isError(),
                        true),
                fact(
                        "placeholderLocationNamesIt",
                        env -> placeholder(env).location().enclosingClassName(),
                        Optional.of("Missing")),
                fact(
                        "placeholdersOfOneNameAreEqual",
                        env -> placeholder(env).equals(placeholder(env))
                                && placeholder(env).hashCode()
                                        == placeholder(env).hashCode(),
                        true),
                fact("placeholderToString", env -> placeholder(env).toString(), "Missing"),
                fact(
                        "superclassPlaceholderTypeIsErroneous",
                        env -> cls(env, "u.Uses")
                                .superclass()
                                .orElseThrow()
                                .declaration()
                                .orElseThrow()
                                .asType()
                                .isError(),
                        true),
                // ----- unresolved class literals -----
                fact(
                        "unresolvedArrayElementIsOmitted",
                        env -> refs(env).types("types").stream()
                                .map(TypeRef::displayName)
                                .toList(),
                        List.of("java.lang.String")),
                fact("arrayWithUnresolvedElementIsUnresolved", env -> refs(env).isUnresolved("types"), true),
                fact("unresolvedSingleValueReadsAsEmpty", env -> refs(env).types("one"), List.of()),
                fact("unresolvedSingleValueIsUnresolved", env -> refs(env).isUnresolved("one"), true),
                fact("unresolvedSingleTypeIsAbsent", env -> refs(env).type("one"), Optional.empty()),
                fact(
                        "placeholderWithoutTypeRejectsAsType",
                        env -> new KspErrorClassDecl("Gone", null).asType(),
                        new Threw(UnsupportedOperationException.class)));
    }

    private static TypeRef gone(ProcessingEnv env) {
        return cls(env, "u.Uses")
                .primaryConstructor()
                .orElseThrow()
                .parameters()
                .get(0)
                .type();
    }

    private static TypeRef qualified(ProcessingEnv env) {
        return cls(env, "u.Uses")
                .primaryConstructor()
                .orElseThrow()
                .parameters()
                .get(2)
                .type();
    }

    private static ClassDecl placeholder(ProcessingEnv env) {
        return gone(env).declaration().orElseThrow();
    }

    private static AnnotationRef refs(ProcessingEnv env) {
        return cls(env, "u.Uses").annotation("u.Refs").orElseThrow();
    }
}
