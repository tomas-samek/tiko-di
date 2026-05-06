package io.tiko.examples.apiimpl.impl;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.examples.apiimpl.api.User;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Internal collaborator — no public interface, intentionally not exposed in module-api.
 * Demonstrates that impl-only @Components can be plain classes; only the
 * <em>app-facing</em> bean ({@link UserServiceImpl}) needs an interface for dispatch.
 */
@Component(scope = Scope.SINGLETON)
public class UserRepository {

    private final Map<Long, User> users = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);

    public User save(String name, String email) {
        long id = nextId.getAndIncrement();
        User user = new User(id, name, email);
        users.put(id, user);
        return user;
    }

    public User findById(Long id) {
        return users.get(id);
    }

    public int count() {
        return users.size();
    }
}
