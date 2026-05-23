package example;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;

@Component(scope = Scope.REQUEST)
public class OrderRepository implements Orders {

    private final DbConfig config;

    @Inject
    public OrderRepository(DbConfig config) {
        this.config = config;
    }

    @Override
    public void save(String id, long amountCents) {
        // Pretend to persist to config.url()
    }
}
