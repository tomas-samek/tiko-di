package io.tiko.comparisons.guice.modulea;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UserRepository {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private long nextId = 1;

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
