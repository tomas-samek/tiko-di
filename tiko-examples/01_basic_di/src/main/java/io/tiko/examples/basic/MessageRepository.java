package io.tiko.examples.basic;

import io.tiko.annotations.Component;
import io.tiko.Scope;

import io.tiko.annotations.PostConstruct;
import io.tiko.annotations.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple SINGLETON repository demonstrating lifecycle methods.
 * No dependencies - simplest possible component.
 */
@Component(scope = Scope.SINGLETON)
public class MessageRepository {

    private final Map<Long, String> messages = new ConcurrentHashMap<>();
    private long nextId = 1;

    public MessageRepository() {
        System.out.println("[MessageRepository] Constructor called");
    }

    @PostConstruct
    public void initialize() {
        System.out.println("[MessageRepository] @PostConstruct - Initializing repository");
        // Pre-populate with sample data
        messages.put(nextId++, "Welcome to Tiko DI");
        messages.put(nextId++, "Hello World");
    }

    @PreDestroy
    public void cleanup() {
        System.out.println("[MessageRepository] @PreDestroy - Cleaning up repository");
        messages.clear();
    }

    public Long save(String message) {
        Long id = nextId++;
        messages.put(id, message);
        System.out.println("[MessageRepository] Saved message " + id + ": " + message);
        return id;
    }

    public String findById(Long id) {
        return messages.get(id);
    }

    public int count() {
        return messages.size();
    }
}
