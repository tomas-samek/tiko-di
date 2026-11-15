package io.tiko.processor.core.ksp;

import static io.tiko.processor.core.ksp.KspFacts.fact;
import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.processor.core.model.ClassDecl;
import io.tiko.processor.core.model.TypeRef;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * #747: a supertype written through a {@code typealias} is the type the alias stands for. Read by its
 * alias declaration it is neither interface nor class, so an aliased interface was taken for the
 * superclass and the component did not provide it.
 */
class KspTypeAliasSupertypeTest {

    private static final String SOURCE = """
            package app

            interface UserRepository

            open class BaseRepository

            typealias Repo = UserRepository

            typealias Base = BaseRepository

            typealias AliasOfAlias = Repo

            class DbRepo : Base(), Repo

            class OtherRepo : AliasOfAlias
            """;

    private static ClassDecl find(io.tiko.processor.core.ProcessingEnv env, String name) {
        return env.types().findClass(name).orElseThrow();
    }

    @Test
    void aliasedSupertypesAreTheInterfaceAndTheSuperclassTheyStandFor() {
        var results = KspFacts.collect(
                List.of(
                        fact(
                                "interfaces",
                                env -> find(env, "app.DbRepo").interfaces().stream()
                                        .map(TypeRef::displayName)
                                        .toList(),
                                List.of("app.UserRepository")),
                        fact(
                                "superclass",
                                env -> find(env, "app.DbRepo")
                                        .superclass()
                                        .map(TypeRef::displayName)
                                        .orElse("none"),
                                "app.BaseRepository"),
                        fact(
                                "alias of an alias",
                                env -> find(env, "app.OtherRepo").interfaces().stream()
                                        .map(TypeRef::displayName)
                                        .toList(),
                                List.of("app.UserRepository")),
                        fact(
                                "superclass beside an aliased interface only",
                                env -> find(env, "app.OtherRepo")
                                        .superclass()
                                        .map(TypeRef::displayName)
                                        .orElse("none"),
                                "java.lang.Object")),
                Map.of(),
                KspCompiler.kotlin("Repos.kt", SOURCE));

        assertThat(results)
                .containsEntry("interfaces", List.of("app.UserRepository"))
                .containsEntry("superclass", "app.BaseRepository")
                .containsEntry("alias of an alias", List.of("app.UserRepository"))
                .containsEntry("superclass beside an aliased interface only", "java.lang.Object");
    }
}
