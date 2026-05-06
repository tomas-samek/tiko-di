package io.tiko.comparisons.avaje.modulea;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

@Singleton
public class UserService {

    private final UserRepository repository;

    public UserService(UserRepository repository) {
        this.repository = repository;
        System.out.println("[Module A] UserService constructed");
    }

    @PostConstruct
    public void init() {
        System.out.println("[Module A] UserService initialized with " + repository.count() + " users");
    }

    public User createUser(String name, String email) {
        User user = repository.save(new User(null, name, email));
        System.out.println("[Module A] Created user: " + user);
        return user;
    }

    public User getUser(Long id) {
        return repository.findById(id);
    }

    public int getUserCount() {
        return repository.count();
    }
}
