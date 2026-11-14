package io.tiko.processor.ksp;

import static org.assertj.core.api.Assertions.assertThat;

import com.tschuchort.compiletesting.KotlinCompilation;
import io.tiko.processor.core.ksp.KspCompiler;
import io.tiko.processor.core.ksp.KspProgram;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * #704: kotlinc compiles a parameter of a Kotlin interface member with declaration-site variance
 * into a wildcard type ({@code List<Item>} becomes {@code List<? extends Item>}, a function type
 * {@code Function1<? super String, Unit>}, {@code Map<String, Any>} becomes {@code Map<String, ?>}).
 * A proxy that declared those parameters as written did not override the member. The proxy overrides
 * such members by their erasure, which matches whatever wildcards kotlinc chose.
 */
class KotlinProxyVarianceTest {

    @Test
    void eventProxyOverridesMembersWhoseParametersHaveDeclarationSiteVariance() throws Exception {
        var source = KspCompiler.kotlin(
                "Cart.kt",
                "package app",
                "",
                "import io.tiko.Scope",
                "import io.tiko.annotations.Component",
                "",
                "interface Item",
                "",
                "interface Cart {",
                "    fun addAll(items: List<Item>)",
                "    fun onDone(callback: (String) -> Unit)",
                "    fun put(attrs: Map<String, Any>)",
                "    fun <T : Any> pick(options: List<T>): T",
                "    fun count(vararg groups: List<Item>): Int",
                "    fun names(): List<Item>",
                "    fun summary(): String",
                "}",
                "",
                "@Component(scope = Scope.EVENT)",
                "class CartImpl : Cart {",
                "    private val log = mutableListOf<String>()",
                "    override fun addAll(items: List<Item>) { log += \"items=${items.size}\" }",
                "    override fun onDone(callback: (String) -> Unit) { callback(\"done\"); log += \"callback\" }",
                "    override fun put(attrs: Map<String, Any>) { log += \"attrs=${attrs.keys}\" }",
                "    override fun <T : Any> pick(options: List<T>): T = options.first()",
                "    override fun count(vararg groups: List<Item>): Int = groups.size",
                "    override fun names(): List<Item> = emptyList()",
                "    override fun summary(): String = log.joinToString(\"|\")",
                "}",
                "",
                "@Component(scope = Scope.SINGLETON)",
                "class Holder(val cart: Cart) {",
                "    fun exercise(): String {",
                "        cart.addAll(listOf(object : Item {}))",
                "        cart.onDone { }",
                "        cart.put(mapOf(\"k\" to 1))",
                "        val picked = cart.pick(listOf(\"x\", \"y\"))",
                "        val groups = cart.count(emptyList(), emptyList())",
                "        return cart.summary() + \"|\" + picked + \"|\" + cart.names().size + \"|\" + groups",
                "    }",
                "}");

        var result = KspCompiler.compile(new TikoSymbolProcessorProvider(), source);

        assertThat(result.exitCode()).as(result.messages()).isEqualTo(KotlinCompilation.ExitCode.OK);
        try (var program = KspProgram.boot(result)) {
            var holder = program.get("app.Holder");
            var outcome = new AtomicReference<Object>();
            program.runInEventScope(() -> outcome.set(exercise(holder)));
            assertThat(outcome.get()).isEqualTo("items=1|callback|attrs=[k]|x|0|2");
        }
    }

    private static Object exercise(Object holder) {
        try {
            return holder.getClass().getMethod("exercise").invoke(holder);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Holder.exercise() failed", e);
        }
    }
}
