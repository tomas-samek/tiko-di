package io.tiko.examples.apiimpl.impl;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.examples.apiimpl.api.User;
import io.tiko.examples.apiimpl.api.UserService;

/**
 * Concrete implementation of {@link UserService}. The processor sees this @Component
 * and generates a factory; because the class implements exactly one interface and has
 * no name, {@code container.get(UserService.class)} dispatches here automatically.
 */
@Component(scope = Scope.SINGLETON)
public class UserServiceImpl implements UserService {

    private final UserRepository repository;

    @Inject
    public UserServiceImpl(UserRepository repository) {
        this.repository = repository;
    }

    @Override
    public User register(String name, String email) {
        return repository.save(name, email);
    }

    @Override
    public User get(Long id) {
        return repository.findById(id);
    }

    @Override
    public int count() {
        return repository.count();
    }
}
