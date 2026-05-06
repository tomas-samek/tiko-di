package io.tiko.comparisons.tiko.modulea;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component(scope = Scope.SINGLETON)
public class UserRepository {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private long nextId = 1;

    @PostConstruct
    public void init() {
        System.out.println("[Module A] UserRepository initialized");
        save(new User(null, "Alice", "alice@example.com"));
        save(new User(null, "Bob", "bob@example.com"));
    }

    public User save(User user) {
        Long id = user.id() != null ? user.id() : nextId++;
        User saved = new User(id, user.name(), user.email());
        users.put(id, saved);
        System.out.println("[Module A] Saved user: " + saved);
        return saved;
    }

    public User findById(Long id) {
        return users.get(id);
    }

    public int count() {
        return users.size();
    }
}
