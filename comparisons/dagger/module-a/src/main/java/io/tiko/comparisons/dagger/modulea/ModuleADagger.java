package io.tiko.comparisons.dagger.modulea;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class ModuleADagger {

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
