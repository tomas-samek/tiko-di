package example.profiles;

import io.tiko.Scope;
import io.tiko.annotations.Component;

@Component(
        scope = Scope.SINGLETON,
        profiles = {"dev"})
public final class DevGreeter implements IGreeter {
    @Override
    public String greet(String name) {
        return "[dev] hello " + name;
    }
}
