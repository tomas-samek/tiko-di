package io.tiko.processor.core.ksp;

import static io.tiko.processor.core.ksp.KspFacts.cls;
import static io.tiko.processor.core.ksp.KspFacts.fact;
import static io.tiko.processor.core.ksp.KspFacts.field;
import static io.tiko.processor.core.ksp.KspFacts.method;
import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.processor.core.ProcessingEnv;
import io.tiko.processor.core.RecordShapes;
import io.tiko.processor.core.ksp.KspFacts.Fact;
import io.tiko.processor.core.ksp.KspFacts.Threw;
import io.tiko.processor.core.model.AnnotationRef;
import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.DeclKind;
import io.tiko.processor.core.model.FieldDecl;
import io.tiko.processor.core.model.MethodDecl;
import io.tiko.processor.core.model.ParamDecl;
import io.tiko.processor.core.model.SourceLanguage;
import io.tiko.processor.core.model.TypeRef;
import io.tiko.processor.core.model.Visibility;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * One fact per row about how the KSP adapter maps Kotlin and Java declarations: classes, members,
 * fields, annotation values, types, the type oracle and the env. All rows read one compilation of
 * the fixtures below (see {@link KspFacts}).
 */
class KspDeclarationFactsTest {

    private static final String OPTION = "tiko.facts";

    private static final String KOTLIN = """
            package f

            import io.tiko.Scope
            import io.tiko.annotations.Component
            import io.tiko.annotations.Named
            import kotlin.reflect.KClass

            enum class Color { RED, GREEN }

            annotation class Inner(val label: String)

            annotation class Rich(
                val names: Array<String>,
                val inner: Inner,
                val inners: Array<Inner>,
                val color: Color,
                val type: KClass<*>,
                val types: Array<KClass<*>>,
                val count: Int = 7,
            )

            @Repeatable
            annotation class Tag(val value: String)

            typealias Names = List<String>

            interface Shape {
                fun area(): Double
                fun describe(): String = "shape"
            }

            abstract class Base {
                abstract fun template(): Int
                open fun hook() {}
            }

            @Rich(
                names = ["a", "b"],
                inner = Inner("x"),
                inners = [Inner("y"), Inner("z")],
                color = Color.GREEN,
                type = String::class,
                types = [String::class, Shape::class],
            )
            @Tag("one")
            @Tag("two")
            @Component(scope = Scope.SINGLETON)
            class Circle(@Named("r") val radius: Double, label: String) : Base(), Shape {
                val diameter: Double get() = radius * 2
                var name: String = label
                override fun area(): Double = radius
                override fun template(): Int = 1
                @Throws(java.io.IOException::class) fun risky() {}
                fun <T : Comparable<T>> pick(a: T, b: T): T = a
                fun units(): List<Unit> = listOf()
                fun wild(
                    a: List<out Number>,
                    b: Comparable<in String>,
                    c: List<*>,
                    d: Array<*>,
                    e: Names,
                    f: Array<List<String>>,
                ) {}
                fun never(): Nothing = throw IllegalStateException()
                private fun secret() {}
                protected fun guarded() {}
                fun Double.scaled(): Double = this * 2
                companion object {
                    @JvmStatic fun create(): Circle = Circle(1.0, "c")
                    const val UNIT = "cm"
                    fun plain() {}
                }
            }

            object Registry {
                val entries: List<String> = listOf()
            }

            @JvmInline value class Meters(val raw: Double)

            @JvmRecord data class Span(val start: Int, val label: String)

            data class PlainData(val start: Int)

            class Accessors {
                val plain: String = ""
                var mutable: Int = 0
                val isReady: Boolean = true
                internal val hidden: String = ""
                @get:JvmName("label") val tag: String = ""
                var guarded: String = ""
                    private set
                private val secretProp: String = ""
                @JvmField val field: String = ""
                fun plainFun() {}
                companion object {
                    const val LIMIT = 1
                }
            }

            interface WithProps {
                val id: String
                var note: String?
                val described: String get() = "x"
            }

            class Props(override val id: String) : WithProps {
                override var note: String? = null
            }

            @Tag("top")
            fun topLevelHelper() {}
            """;

    /**
     * Compiled in its own run, so KSP reads it from class files: {@code JvmRecord} has SOURCE
     * retention, and only the {@code java.lang.Record} superclass tells the record (#587).
     */
    private static final String LIBRARY = """
            package lib

            @JvmRecord data class LibSpan(val start: Int, val label: String)

            data class LibData(val start: Int)
            """;

    private static final String JAVA = """
            package f;

            import java.lang.annotation.Repeatable;

            public class Legacy {
                public static final int LIMIT = 3;
                protected String name;
                public static Legacy of() { return new Legacy(); }
                public void touch() {}
                String pkg() { return ""; }
                public java.util.List<? extends Number> numbers() { return null; }
                public static class Nested {}
                public class Inner {}
                interface NestedPort {}
            }

            interface Port {
                default void open() {}
                void close();
            }

            record Point(int x, int y) {}

            @Repeatable(Roles.class)
            @interface Role { String value(); }

            @interface Roles { Role[] value(); }

            @Roles({@Role("a"), @Role("b")})
            class Staff {}

            class Varargs {
                void ports(Port... ports) {}
                void boxed(Integer... values) {}
                void primitives(int... values) {}
            }
            """;

    private static Map<String, Object> results;

    @BeforeAll
    static void compile() {
        results = KspFacts.collect(
                facts(),
                Map.of(OPTION, "on"),
                List.of(KspCompiler.library(KspCompiler.kotlin("Lib.kt", LIBRARY))),
                KspCompiler.kotlin("Facts.kt", KOTLIN),
                KspCompiler.java("Legacy.java", JAVA),
                KspCompiler.java("r/Records.java", RecordShapes.SOURCE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("facts")
    void kspDeclarationsMapToTheNeutralModel(Fact fact) {
        assertThat(results).containsEntry(fact.name(), fact.expected());
    }

    static List<Fact> facts() {
        var facts = new ArrayList<>(declarationFacts());
        facts.addAll(recordShapeFacts());
        return facts;
    }

    /**
     * The {@link RecordShapes}: a record's components on KSP are its header's, as javac reads them,
     * or unknown where a declared canonical constructor can't be told apart from another
     * constructor taking only accessor-matching parameters. Unknown components are never guessed:
     * reading them throws, and the processors reject the record first. That divergence from javac
     * is intended.
     */
    private static List<Fact> recordShapeFacts() {
        var facts = new ArrayList<Fact>();
        RecordShapes.HEADERS.forEach((recordName, header) -> {
            var simpleName = recordName.substring(recordName.lastIndexOf('.') + 1);
            if (RecordShapes.UNKNOWN_ON_KSP.contains(recordName)) {
                facts.add(fact(
                        "recordComponentsOf" + simpleName + "AreUnknownOnKsp",
                        env -> cls(env, recordName).recordComponentsKnown(),
                        false));
                facts.add(fact(
                        "recordComponentsOf" + simpleName + "AreNeverGuessedOnKsp",
                        env -> components(env, recordName),
                        new Threw(IllegalStateException.class)));
            } else {
                facts.add(fact(
                        "recordComponentsOf" + simpleName + "AreKnown",
                        env -> cls(env, recordName).recordComponentsKnown(),
                        true));
                facts.add(fact(
                        "recordComponentsOf" + simpleName + "AreTheHeader",
                        env -> components(env, recordName),
                        header));
            }
        });
        return facts;
    }

    private static List<Fact> declarationFacts() {
        return List.of(
                // ----- types -----
                fact(
                        "unitReturnIsVoid",
                        env -> method(env, "f.Base", "hook").returnType().isVoid(),
                        true),
                fact(
                        "javaVoidReturnIsVoid",
                        env -> method(env, "f.Legacy", "touch").returnType().isVoid(),
                        true),
                fact(
                        "valueReturnIsNotVoid",
                        env -> method(env, "f.Circle", "area").returnType().isVoid(),
                        false),
                fact("unitTypeArgumentIsNotVoid", env -> unitsArgument(env).isVoid(), false),
                fact("unitTypeArgumentIsKotlinUnit", env -> unitsArgument(env).displayName(), "kotlin.Unit"),
                fact(
                        "constructorReturnIsVoid",
                        env -> constructor(env).returnType().isVoid(),
                        true),
                fact("constructorJvmNameIsInit", env -> constructor(env).jvmName(), "<init>"),
                fact("nonNullDoubleIsPrimitive", env -> radius(env).type().isPrimitive(), true),
                fact("primitiveErasedName", env -> radius(env).type().erasedName(), "double"),
                fact(
                        "primitiveHasNoDeclaration",
                        env -> radius(env).type().declaration().isPresent(),
                        false),
                fact(
                        "classTypeDeclaration",
                        env -> label(env).type().declaration().map(ClassDecl::qualifiedName),
                        java.util.Optional.of("java.lang.String")),
                fact("classTypeErasedName", env -> label(env).type().erasedName(), "java.lang.String"),
                fact(
                        "typeVariableErasesToItsBound",
                        env -> pick(env).returnType().erasedName(),
                        "java.lang.Comparable"),
                fact(
                        "typeVariableHasNoTypeArguments",
                        env -> pick(env).returnType().typeArguments(),
                        List.of()),
                fact(
                        "typeVariablesCarryTheirBounds",
                        env -> pick(env).typeVariables().get(0).bounds().toString(),
                        "[java.lang.Comparable<T>]"),
                fact("typeVariableName", env -> pick(env).typeVariables().get(0).name(), "T"),
                fact(
                        "javaWildcardRendersAsJavac",
                        env -> method(env, "f.Legacy", "numbers").returnType().displayName(),
                        "java.util.List<? extends java.lang.Number>"),
                fact(
                        "typeRefToStringIsDisplayName",
                        env -> method(env, "f.Legacy", "numbers").returnType().toString(),
                        "java.util.List<? extends java.lang.Number>"),
                fact(
                        "javaPoetKeepsJavaPoetSpacing",
                        env -> unitsType(env).javaPoet().toString(),
                        "java.util.List<kotlin.Unit>"),
                fact(
                        "covariantTypeArgumentIsExtendsWildcard",
                        env -> wildArgument(env, 0).displayName(),
                        "? extends java.lang.Number"),
                fact(
                        "contravariantTypeArgumentIsSuperWildcard",
                        env -> wildArgument(env, 1).displayName(),
                        "? super java.lang.String"),
                fact(
                        "starTypeArgumentIsUnboundWildcard",
                        env -> wildArgument(env, 2).displayName(),
                        "?"),
                fact("starArrayIsObjectArray", env -> wild(env, 3).displayName(), "java.lang.Object[]"),
                fact("arrayHasNoTypeArguments", env -> wild(env, 3).typeArguments(), List.of()),
                fact(
                        "typeAliasRendersItsExpansion",
                        env -> wild(env, 4).displayName(),
                        "java.util.List<java.lang.String>"),
                fact(
                        "typeAliasDeclarationIsItsTarget",
                        env -> wild(env, 4).declaration().map(ClassDecl::qualifiedName),
                        java.util.Optional.of("java.util.List")),
                fact(
                        "arrayOfParameterizedErasesItsComponent",
                        env -> wild(env, 5).erasedName(),
                        "java.util.List[]"),
                fact(
                        "nothingReturnIsTheVoidBox",
                        env -> method(env, "f.Circle", "never").returnType().displayName(),
                        "java.lang.Void"),
                // ----- classes -----
                fact(
                        "superclass",
                        env -> cls(env, "f.Circle").superclass().map(TypeRef::displayName),
                        java.util.Optional.of("f.Base")),
                fact("interfaces", env -> names(cls(env, "f.Circle").interfaces()), List.of("f.Shape")),
                fact(
                        "directSupertypes",
                        env -> names(cls(env, "f.Circle").directSupertypes()),
                        List.of("f.Base", "f.Shape")),
                fact(
                        "objectSuperclassIsObject",
                        env -> cls(env, "f.Registry").superclass().map(TypeRef::displayName),
                        java.util.Optional.of("java.lang.Object")),
                fact(
                        "interfaceHasNoSuperclass",
                        env -> cls(env, "f.Shape").superclass().isPresent(),
                        false),
                fact(
                        "enumSuperclassIsEnum",
                        env -> cls(env, "f.Color").superclass().map(TypeRef::displayName),
                        java.util.Optional.of("java.lang.Enum<f.Color>")),
                fact("enumKind", env -> cls(env, "f.Color").kind(), DeclKind.ENUM),
                fact("enumConstants", env -> cls(env, "f.Color").enumConstants(), List.of("RED", "GREEN")),
                fact("annotationKind", env -> cls(env, "f.Rich").kind(), DeclKind.ANNOTATION),
                fact("interfaceKind", env -> cls(env, "f.Shape").kind(), DeclKind.INTERFACE),
                fact("javaRecordKind", env -> cls(env, "f.Point").kind(), DeclKind.RECORD),
                fact(
                        "javaRecordComponents",
                        env -> cls(env, "f.Point").recordComponents().stream()
                                .map(FieldDecl::name)
                                .toList(),
                        List.of("x", "y")),
                fact(
                        "javaRecordComponentType",
                        env -> cls(env, "f.Point")
                                .recordComponents()
                                .get(0)
                                .type()
                                .displayName(),
                        "int"),
                fact(
                        "javaRecordComponentIsNotStatic",
                        env -> cls(env, "f.Point").recordComponents().get(0).isStatic(),
                        false),
                fact(
                        "javaRecordComponentLocation",
                        env -> cls(env, "f.Point")
                                .recordComponents()
                                .get(0)
                                .location()
                                .enclosingClassName(),
                        java.util.Optional.of("f.Point")),
                fact(
                        "javaRecordComponentAnnotations",
                        env -> cls(env, "f.Point").recordComponents().get(0).annotations(),
                        List.of()),
                fact(
                        "javaRecordComponentEquality",
                        env -> cls(env, "f.Point")
                                        .recordComponents()
                                        .get(0)
                                        .equals(cls(env, "f.Point")
                                                .recordComponents()
                                                .get(0))
                                && cls(env, "f.Point").recordComponents().get(0).hashCode()
                                        == cls(env, "f.Point")
                                                .recordComponents()
                                                .get(0)
                                                .hashCode(),
                        true),
                fact(
                        "javaRecordComponentToString",
                        env -> cls(env, "f.Point").recordComponents().get(0).toString(),
                        "f.Point.x"),
                fact(
                        "parameterEquality",
                        env -> radius(env).equals(radius(env))
                                && radius(env).hashCode() == radius(env).hashCode(),
                        true),
                fact(
                        "fieldHashCodeFollowsEquality",
                        env -> field(env, "f.Circle", "name").hashCode()
                                == field(env, "f.Circle", "name").hashCode(),
                        true),
                fact(
                        "kotlinClassHasNoRecordComponents",
                        env -> cls(env, "f.Circle").recordComponents(),
                        List.of()),
                fact(
                        "kotlinClassRecordComponentsAreKnown",
                        env -> cls(env, "f.Circle").recordComponentsKnown(),
                        true),
                fact("jvmRecordComponentsAreKnown", env -> cls(env, "f.Span").recordComponentsKnown(), true),
                fact("abstractClassIsAbstract", env -> cls(env, "f.Base").isAbstract(), true),
                fact("interfaceIsAbstract", env -> cls(env, "f.Shape").isAbstract(), true),
                fact("kotlinClassIsFinalByDefault", env -> cls(env, "f.Circle").isFinal(), true),
                fact("abstractClassIsNotFinal", env -> cls(env, "f.Base").isFinal(), false),
                fact("javaClassIsNotFinal", env -> cls(env, "f.Legacy").isFinal(), false),
                fact("jvmRecordIsARecord", env -> cls(env, "f.Span").kind(), DeclKind.RECORD),
                fact("plainDataClassIsAClass", env -> cls(env, "f.PlainData").kind(), DeclKind.CLASS),
                fact(
                        "jvmRecordComponentsArePrimaryConstructorParameters",
                        env -> cls(env, "f.Span").recordComponents().stream()
                                .map(component -> component.name() + ":"
                                        + component.type().displayName())
                                .toList(),
                        List.of("start:int", "label:java.lang.String")),
                fact(
                        "jvmRecordSuperclassIsRecord",
                        env -> cls(env, "f.Span").superclass().map(TypeRef::displayName),
                        java.util.Optional.of("java.lang.Record")),
                fact("libraryJvmRecordIsARecord", env -> cls(env, "lib.LibSpan").kind(), DeclKind.RECORD),
                fact(
                        "libraryPlainDataClassIsAClass",
                        env -> cls(env, "lib.LibData").kind(),
                        DeclKind.CLASS),
                fact(
                        "libraryJvmRecordComponentsAreKnown",
                        env -> cls(env, "lib.LibSpan").recordComponentsKnown(),
                        true),
                fact(
                        "libraryJvmRecordComponentsArePrimaryConstructorParameters",
                        env -> cls(env, "lib.LibSpan").recordComponents().stream()
                                .map(component -> component.name() + ":"
                                        + component.type().displayName())
                                .toList(),
                        List.of("start:int", "label:java.lang.String")),
                fact(
                        "libraryJvmRecordSuperclassIsRecord",
                        env -> cls(env, "lib.LibSpan").superclass().map(TypeRef::displayName),
                        java.util.Optional.of("java.lang.Record")),
                fact(
                        "libraryJvmRecordComponentAccessorsAreRecordAccessors",
                        env -> cls(env, "lib.LibSpan").methods().stream()
                                .filter(method -> List.of("start", "label", "getStart", "getLabel")
                                        .contains(method.name()))
                                .map(method -> method.name() + "/" + method.jvmName())
                                .sorted()
                                .toList(),
                        List.of("label/label", "start/start")),
                fact("valueClassIsValueClass", env -> cls(env, "f.Meters").isValueClass(), true),
                fact(
                        "classpathValueClassIsValueClass",
                        env -> cls(env, "kotlin.time.Duration").isValueClass(),
                        true),
                fact("plainClassIsNoValueClass", env -> cls(env, "f.Circle").isValueClass(), false),
                fact("javaClassIsNoValueClass", env -> cls(env, "f.Legacy").isValueClass(), false),
                fact(
                        "classpathDataClassIsKotlinDataClass",
                        env -> cls(env, "kotlin.Pair").isKotlinDataClass(),
                        true),
                fact(
                        "dataClassIsKotlinDataClass",
                        env -> cls(env, "f.PlainData").isKotlinDataClass(),
                        true),
                fact(
                        "jvmRecordDataClassIsKotlinDataClass",
                        env -> cls(env, "f.Span").isKotlinDataClass(),
                        true),
                fact(
                        "plainClassIsNoKotlinDataClass",
                        env -> cls(env, "f.Circle").isKotlinDataClass(),
                        false),
                fact("javaClassIsNoKotlinDataClass", env -> cls(env, "f.Legacy").isKotlinDataClass(), false),
                fact(
                        "javaStaticNestedClassIsStatic",
                        env -> cls(env, "f.Legacy.Nested").isStatic(),
                        true),
                fact(
                        "javaInnerClassIsNotStatic",
                        env -> cls(env, "f.Legacy.Inner").isStatic(),
                        false),
                fact(
                        "javaNestedInterfaceIsStatic",
                        env -> cls(env, "f.Legacy.NestedPort").isStatic(),
                        true),
                fact("companionIsStatic", env -> cls(env, "f.Circle.Companion").isStatic(), true),
                fact("topLevelClassIsNotStatic", env -> cls(env, "f.Circle").isStatic(), false),
                fact(
                        "companionEnclosingClass",
                        env -> cls(env, "f.Circle.Companion").enclosingClass().map(ClassDecl::qualifiedName),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "topLevelClassHasNoEnclosingClass",
                        env -> cls(env, "f.Circle").enclosingClass().isPresent(),
                        false),
                fact(
                        "nestedClassName",
                        env -> cls(env, "f.Circle.Companion").className().toString(),
                        "f.Circle.Companion"),
                fact("kotlinSourceLanguage", env -> cls(env, "f.Circle").language(), SourceLanguage.KOTLIN),
                fact("javaSourceLanguage", env -> cls(env, "f.Legacy").language(), SourceLanguage.JAVA),
                fact("classpathLanguage", env -> cls(env, "java.lang.String").language(), SourceLanguage.CLASSPATH),
                fact(
                        "sourcePathIsTheKotlinFile",
                        env -> cls(env, "f.Circle").sourcePath().map(path -> path.endsWith("/Facts.kt")),
                        java.util.Optional.of(true)),
                fact(
                        "classpathClassHasNoSourcePath",
                        env -> cls(env, "java.lang.String").sourcePath().isPresent(),
                        false),
                fact(
                        "javaClassHasNoPrimaryConstructor",
                        env -> cls(env, "f.Legacy").primaryConstructor().isPresent(),
                        false),
                fact(
                        "classAsTypeIsItsType",
                        env -> cls(env, "f.Circle").asType().displayName(),
                        "f.Circle"),
                fact(
                        "classEqualityIsByName",
                        env -> cls(env, "f.Circle").equals(cls(env, "f.Circle"))
                                && cls(env, "f.Circle").hashCode()
                                        == cls(env, "f.Circle").hashCode(),
                        true),
                fact("classToStringIsItsName", env -> cls(env, "f.Circle").toString(), "f.Circle"),
                fact(
                        "allMethodsIncludeInheritedOnes",
                        env -> cls(env, "f.Circle").allMethods().stream()
                                .map(MethodDecl::name)
                                .filter(List.of("hook", "describe", "area")::contains)
                                .sorted()
                                .toList(),
                        List.of("area", "describe", "hook")),
                fact(
                        "allMethodsLeaveOutConstructors",
                        env -> cls(env, "f.Circle").allMethods().stream().anyMatch(MethodDecl::isConstructor),
                        false),
                // ----- property accessors -----
                fact(
                        "propertiesContributeTheirJvmAccessors",
                        env -> cls(env, "f.Accessors").methods().stream()
                                .map(MethodDecl::name)
                                .sorted()
                                .toList(),
                        List.of(
                                "getGuarded",
                                "getHidden",
                                "getMutable",
                                "getPlain",
                                "getTag",
                                "isReady",
                                "plainFun",
                                "setMutable")),
                fact(
                        "accessorJvmNameFollowsJvmName",
                        env -> method(env, "f.Accessors", "getTag").jvmName(),
                        "label"),
                fact(
                        "internalAccessorJvmNameIsMangled",
                        env -> method(env, "f.Accessors", "getHidden").jvmName().startsWith("getHidden$"),
                        true),
                fact(
                        "setterTakesTheValueAndReturnsVoid",
                        env -> {
                            var setter = method(env, "f.Accessors", "setMutable");
                            return setter.returnType().isVoid() + " "
                                    + setter.parameters().get(0).type().displayName();
                        },
                        "true int"),
                fact(
                        "getterReturnsThePropertyType",
                        env -> method(env, "f.Accessors", "isReady")
                                .returnType()
                                .displayName(),
                        "boolean"),
                fact(
                        "abstractPropertyAccessorsAreAbstract",
                        env -> cls(env, "f.WithProps").methods().stream()
                                .filter(MethodDecl::isAbstract)
                                .map(MethodDecl::name)
                                .sorted()
                                .toList(),
                        List.of("getId", "getNote", "setNote")),
                fact(
                        "interfacePropertyWithBodyIsDefault",
                        env -> method(env, "f.WithProps", "getDescribed").isDefault(),
                        true),
                fact(
                        "nullablePropertyAccessorKeepsNullability",
                        env -> method(env, "f.WithProps", "getNote")
                                .returnType()
                                .nullability(),
                        io.tiko.processor.core.model.Nullability.NULLABLE),
                fact(
                        "allMethodsIncludeInheritedAccessorsOnce",
                        env -> cls(env, "f.Props").allMethods().stream()
                                .map(MethodDecl::name)
                                .filter(name -> name.startsWith("get") || name.startsWith("set"))
                                .sorted()
                                .toList(),
                        List.of("getDescribed", "getId", "getNote", "setNote")),
                fact(
                        "mappedBuiltInPropertiesAreNoAccessors",
                        env -> cls(env, "f.Color").allMethods().stream()
                                .map(MethodDecl::name)
                                .filter(List.of("getName", "getOrdinal")::contains)
                                .toList(),
                        List.of()),
                fact(
                        "jvmRecordComponentAccessorsAreRecordAccessors",
                        env -> cls(env, "f.Span").methods().stream()
                                .filter(method -> List.of("start", "label", "getStart", "getLabel")
                                        .contains(method.name()))
                                .map(method -> method.name() + "/" + method.jvmName())
                                .sorted()
                                .toList(),
                        List.of("label/label", "start/start")),
                fact(
                        "plainDataClassPropertyKeepsItsGetter",
                        env -> {
                            var getter = method(env, "f.PlainData", "getStart");
                            return getter.name() + "/" + getter.jvmName();
                        },
                        "getStart/getStart"),
                fact(
                        "accessorEqualityAcrossLookups",
                        env -> method(env, "f.Accessors", "getPlain").equals(method(env, "f.Accessors", "getPlain")),
                        true),
                // ----- fields -----
                fact(
                        "kotlinFieldsAreBackingFieldProperties",
                        env -> cls(env, "f.Circle").fields().stream()
                                .map(FieldDecl::name)
                                .toList(),
                        List.of("radius", "name")),
                fact(
                        "kotlinFieldType",
                        env -> field(env, "f.Circle", "name").type().displayName(),
                        "java.lang.String"),
                fact(
                        "instancePropertyIsNotStatic",
                        env -> field(env, "f.Circle", "radius").isStatic(),
                        false),
                fact(
                        "companionConstIsStatic",
                        env -> field(env, "f.Circle.Companion", "UNIT").isStatic(),
                        true),
                fact(
                        "objectPropertyIsStatic",
                        env -> field(env, "f.Registry", "entries").isStatic(),
                        true),
                fact("javaStaticField", env -> field(env, "f.Legacy", "LIMIT").isStatic(), true),
                fact("javaInstanceField", env -> field(env, "f.Legacy", "name").isStatic(), false),
                fact(
                        "enumEntriesAreStaticFields",
                        env -> cls(env, "f.Color").fields().stream()
                                .map(FieldDecl::isStatic)
                                .toList(),
                        List.of(true, true)),
                fact(
                        "enumEntryFieldType",
                        env -> field(env, "f.Color", "RED").type().displayName(),
                        "f.Color"),
                fact(
                        "fieldAnnotationsOfPlainField",
                        env -> field(env, "f.Legacy", "name").annotations(),
                        List.of()),
                fact(
                        "fieldEquality",
                        env -> field(env, "f.Circle", "name").equals(field(env, "f.Circle", "name")),
                        true),
                fact("fieldToString", env -> field(env, "f.Circle", "name").toString(), "f.Circle.name"),
                // ----- methods -----
                fact(
                        "jvmStaticCompanionMemberIsStatic",
                        env -> method(env, "f.Circle.Companion", "create").isStatic(),
                        true),
                fact(
                        "plainCompanionMemberIsNotStatic",
                        env -> method(env, "f.Circle.Companion", "plain").isStatic(),
                        false),
                fact(
                        "javaStaticMethodIsStatic",
                        env -> method(env, "f.Legacy", "of").isStatic(),
                        true),
                fact("topLevelFunctionIsStatic", env -> topLevel(env).isStatic(), true),
                fact(
                        "extensionFunctionIsExtension",
                        env -> method(env, "f.Circle", "scaled").isExtension(),
                        true),
                fact(
                        "memberFunctionIsNotExtension",
                        env -> method(env, "f.Circle", "area").isExtension(),
                        false),
                fact(
                        "javaMethodIsNotExtension",
                        env -> method(env, "f.Legacy", "touch").isExtension(),
                        false),
                fact("javaVarargIsItsArray", env -> varargType(env, "ports"), "f.Port[]"),
                fact("javaBoxedVarargIsBoxedArray", env -> varargType(env, "boxed"), "java.lang.Integer[]"),
                fact("javaPrimitiveVarargIsPrimitiveArray", env -> varargType(env, "primitives"), "int[]"),
                fact("topLevelFunctionHasNoOwner", env -> topLevel(env).owner().isPresent(), false),
                fact(
                        "abstractMemberIsAbstract",
                        env -> method(env, "f.Base", "template").isAbstract(),
                        true),
                fact(
                        "interfaceMemberWithoutBodyIsAbstract",
                        env -> method(env, "f.Shape", "area").isAbstract(),
                        true),
                fact(
                        "kotlinInterfaceMemberWithBodyIsDefault",
                        env -> method(env, "f.Shape", "describe").isDefault(),
                        true),
                fact(
                        "kotlinInterfaceMemberWithoutBodyIsNotDefault",
                        env -> method(env, "f.Shape", "area").isDefault(),
                        false),
                fact(
                        "javaDefaultMethodIsDefault",
                        env -> method(env, "f.Port", "open").isDefault(),
                        true),
                fact(
                        "classMemberIsNotDefault",
                        env -> method(env, "f.Circle", "area").isDefault(),
                        false),
                fact(
                        "throwsAnnotationGivesThrownTypes",
                        env -> names(method(env, "f.Circle", "risky").thrownTypes()),
                        List.of("java.io.IOException")),
                fact("noThrownTypes", env -> method(env, "f.Circle", "area").thrownTypes(), List.of()),
                fact("privateMember", env -> method(env, "f.Circle", "secret").visibility(), Visibility.PRIVATE),
                fact(
                        "protectedMember",
                        env -> method(env, "f.Circle", "guarded").visibility(),
                        Visibility.PROTECTED),
                fact("javaPackageMember", env -> method(env, "f.Legacy", "pkg").visibility(), Visibility.PACKAGE),
                fact(
                        "memberOwner",
                        env -> method(env, "f.Circle", "area").owner().map(ClassDecl::qualifiedName),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "parameterNames",
                        env -> constructor(env).parameters().stream()
                                .map(ParamDecl::name)
                                .toList(),
                        List.of("radius", "label")),
                fact(
                        "parameterAnnotation",
                        env -> radius(env)
                                .annotation("io.tiko.annotations.Named")
                                .map(named -> named.string("value")),
                        java.util.Optional.of("r")),
                fact(
                        "parameterAnnotationList",
                        env -> radius(env).annotations().stream()
                                .map(AnnotationRef::annotationName)
                                .toList(),
                        List.of("io.tiko.annotations.Named")),
                fact(
                        "parameterAnnotationsByType",
                        env -> radius(env)
                                .annotationsByType("io.tiko.annotations.Named")
                                .size(),
                        1),
                fact(
                        "methodAnnotationsByTypeWhenAbsent",
                        env -> method(env, "f.Circle", "area").annotationsByType("f.Tag"),
                        List.of()),
                fact(
                        "methodAnnotationList",
                        env -> method(env, "f.Circle", "risky").annotations().stream()
                                .map(AnnotationRef::annotationName)
                                .toList(),
                        List.of("kotlin.jvm.Throws")),
                fact(
                        "methodAnnotationLookup",
                        env -> topLevel(env).annotation("f.Tag").map(tag -> tag.string("value")),
                        java.util.Optional.of("top")),
                fact(
                        "methodToStringIsItsKey",
                        env -> method(env, "f.Circle", "area").toString().startsWith("f.Circle#"),
                        true),
                fact("parameterToStringIsItsKey", env -> radius(env).toString().endsWith("(0"), true),
                // ----- locations -----
                fact(
                        "classLocationEnclosingClass",
                        env -> cls(env, "f.Circle").location().enclosingClassName(),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "memberLocationEnclosingClass",
                        env -> method(env, "f.Circle", "area").location().enclosingClassName(),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "parameterLocationEnclosingClass",
                        env -> radius(env).location().enclosingClassName(),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "fieldLocationEnclosingClass",
                        env -> field(env, "f.Circle", "name").location().enclosingClassName(),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "annotationLocationEnclosingClass",
                        env -> rich(env).location().enclosingClassName(),
                        java.util.Optional.of("f.Circle")),
                fact(
                        "topLevelFunctionLocationHasNoEnclosingClass",
                        env -> topLevel(env).location().enclosingClassName(),
                        java.util.Optional.empty()),
                // ----- annotation values -----
                fact("stringArrayValue", env -> rich(env).strings("names"), List.of("a", "b")),
                fact(
                        "nestedAnnotationValue",
                        env -> ((AnnotationRef) rich(env).value("inner").orElseThrow()).string("label"),
                        "x"),
                fact(
                        "nestedAnnotationName",
                        env -> ((AnnotationRef) rich(env).value("inner").orElseThrow()).annotationName(),
                        "f.Inner"),
                fact(
                        "nestedAnnotationArray",
                        env -> ((List<?>) rich(env).value("inners").orElseThrow())
                                .stream()
                                        .map(item -> ((AnnotationRef) item).string("label"))
                                        .toList(),
                        List.of("y", "z")),
                fact("enumValueIsItsName", env -> rich(env).enumName("color"), "GREEN"),
                fact(
                        "classValueIsTypeRef",
                        env -> rich(env).type("type").map(TypeRef::displayName),
                        java.util.Optional.of("java.lang.String")),
                fact("classArrayValue", env -> names(rich(env).types("types")), List.of("java.lang.String", "f.Shape")),
                fact("declaredDefaultIsIncluded", env -> rich(env).integer("count"), 7),
                fact("javaAnnotationDefaultIsIncluded", env -> component(env).bool("exposeSelf"), true),
                fact("javaEnumValue", env -> component(env).enumName("scope"), "SINGLETON"),
                fact("javaEmptyArrayDefault", env -> component(env).strings("profiles"), List.of()),
                fact("unknownMemberIsAbsent", env -> rich(env).value("missing").isPresent(), false),
                fact("resolvedClassValueIsNotUnresolved", env -> rich(env).isUnresolved("types"), false),
                fact("nonClassMemberIsNotUnresolved", env -> rich(env).isUnresolved("count"), false),
                fact(
                        "isUnresolvedRejectsUnknownMember",
                        env -> rich(env).isUnresolved("missing"),
                        new Threw(NoSuchElementException.class)),
                fact(
                        "annotationsInSourceOrder",
                        env -> cls(env, "f.Circle").annotations().stream()
                                .map(AnnotationRef::annotationName)
                                .toList(),
                        List.of("f.Rich", "f.Tag", "f.Tag", "io.tiko.annotations.Component")),
                fact(
                        "kotlinRepeatableAnnotationsByType",
                        env -> cls(env, "f.Circle").annotationsByType("f.Tag").stream()
                                .map(tag -> tag.string("value"))
                                .toList(),
                        List.of("one", "two")),
                fact(
                        "javaRepeatableContainerByType",
                        env -> cls(env, "f.Staff").annotationsByType("f.Role").stream()
                                .map(role -> role.string("value"))
                                .toList(),
                        List.of("a", "b")),
                fact(
                        "absentAnnotation",
                        env -> cls(env, "f.Circle").annotation("f.Nope").isPresent(),
                        false),
                fact(
                        "annotationEquality",
                        env -> rich(env).equals(rich(env))
                                && rich(env).hashCode() == rich(env).hashCode(),
                        true),
                fact("annotationToString", env -> rich(env).toString(), "@f.Rich on f.Circle@0"),
                // ----- type oracle -----
                fact(
                        "subtypeIsAssignable",
                        env -> env.types()
                                .isAssignable(
                                        cls(env, "f.Circle").asType(),
                                        cls(env, "f.Shape").asType()),
                        true),
                fact(
                        "supertypeIsNotAssignable",
                        env -> env.types()
                                .isAssignable(
                                        cls(env, "f.Shape").asType(),
                                        cls(env, "f.Circle").asType()),
                        false),
                fact(
                        "parameterizedIsAssignableToItsErasure",
                        env -> env.types()
                                .isAssignable(unitsType(env), env.types().erasure(unitsType(env))),
                        true),
                fact(
                        "erasureDropsTypeArguments",
                        env -> env.types().erasure(unitsType(env)).displayName(),
                        "java.util.List"),
                fact(
                        "erasureOfTypeVariableIsItsBound",
                        env -> env.types().erasure(pick(env).returnType()).displayName(),
                        "java.lang.Comparable"),
                fact(
                        "sameTypeAcrossLookups",
                        env -> env.types()
                                .isSameType(
                                        label(env).type(),
                                        cls(env, "java.lang.String").asType()),
                        true),
                fact(
                        "differentTypesAreNotSame",
                        env -> env.types()
                                .isSameType(
                                        label(env).type(), cls(env, "f.Circle").asType()),
                        false),
                fact(
                        "findClassByJavaName",
                        env -> env.types().findClass("java.lang.Object").map(ClassDecl::qualifiedName),
                        java.util.Optional.of("java.lang.Object")),
                fact(
                        "findClassOfParameterizedTextIsEmpty",
                        env -> env.types()
                                .findClass("java.util.List<java.lang.String>")
                                .isPresent(),
                        false),
                fact(
                        "findClassOfUnknownIsEmpty",
                        env -> env.types().findClass("f.Nope").isPresent(),
                        false),
                // ----- env -----
                fact("optionsPassThrough", env -> env.options().get(OPTION), "on"),
                fact(
                        "classpathProbeFindsNothing",
                        env -> env.classpath()
                                .readResource("META-INF/MANIFEST.MF")
                                .isPresent(),
                        false),
                fact(
                        "sourceVersionIsTheRunningJdk",
                        env -> env.sourceVersion().release(),
                        Runtime.version().feature()),
                fact(
                        "classesAnnotatedWith",
                        env -> env.classesAnnotatedWith("io.tiko.annotations.Component").stream()
                                .map(ClassDecl::qualifiedName)
                                .toList(),
                        List.of("f.Circle")),
                fact("classesAnnotatedWithUnknownAnnotation", env -> env.classesAnnotatedWith("f.Nope"), List.of()));
    }

    /** The display name of the vararg parameter of a {@code f.Varargs} method. */
    private static String varargType(ProcessingEnv env, String methodName) {
        return method(env, "f.Varargs", methodName).parameters().get(0).type().displayName();
    }

    private static MethodDecl constructor(ProcessingEnv env) {
        return cls(env, "f.Circle").primaryConstructor().orElseThrow();
    }

    private static ParamDecl radius(ProcessingEnv env) {
        return constructor(env).parameters().get(0);
    }

    private static ParamDecl label(ProcessingEnv env) {
        return constructor(env).parameters().get(1);
    }

    private static MethodDecl pick(ProcessingEnv env) {
        return method(env, "f.Circle", "pick");
    }

    private static TypeRef unitsType(ProcessingEnv env) {
        return method(env, "f.Circle", "units").returnType();
    }

    private static TypeRef unitsArgument(ProcessingEnv env) {
        return unitsType(env).typeArguments().get(0);
    }

    private static TypeRef wild(ProcessingEnv env, int index) {
        return method(env, "f.Circle", "wild").parameters().get(index).type();
    }

    private static TypeRef wildArgument(ProcessingEnv env, int index) {
        return wild(env, index).typeArguments().get(0);
    }

    private static MethodDecl topLevel(ProcessingEnv env) {
        return env.methodsAnnotatedWith("f.Tag").get(0);
    }

    private static AnnotationRef rich(ProcessingEnv env) {
        return cls(env, "f.Circle").annotation("f.Rich").orElseThrow();
    }

    private static AnnotationRef component(ProcessingEnv env) {
        return cls(env, "f.Circle").annotation("io.tiko.annotations.Component").orElseThrow();
    }

    /** A record's components as {@code name:type}, in order. */
    private static List<String> components(ProcessingEnv env, String recordName) {
        return cls(env, recordName).recordComponents().stream()
                .map(component -> component.name() + ":" + component.type().displayName())
                .toList();
    }

    private static List<String> names(List<TypeRef> types) {
        return types.stream().map(TypeRef::displayName).toList();
    }
}
