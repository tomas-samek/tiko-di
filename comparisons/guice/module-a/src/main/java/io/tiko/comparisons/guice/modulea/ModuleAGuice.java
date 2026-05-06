package io.tiko.comparisons.guice.modulea;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

public class ModuleAGuice extends AbstractModule {

    @Provides
    @Singleton
    UserRepository provideUserRepository() {
        UserRepository repository = new UserRepository();
        repository.init();
        return repository;
    }

    @Provides
    @Singleton
    UserService provideUserService(UserRepository repository) {
        UserService service = new UserService(repository);
        service.init();
        return service;
    }
}
