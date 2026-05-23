package example.profiles;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(
        scope = Scope.SINGLETON,
        profiles = {"prod"})
public final class ProdGreeter implements IGreeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name;
    }
}
