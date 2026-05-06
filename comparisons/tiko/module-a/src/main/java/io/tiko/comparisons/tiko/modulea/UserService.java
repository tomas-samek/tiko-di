package io.tiko.comparisons.tiko.modulea;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;

@Component(scope = Scope.SINGLETON)
public class UserService {

    private final UserRepository repository;

    @Inject
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
