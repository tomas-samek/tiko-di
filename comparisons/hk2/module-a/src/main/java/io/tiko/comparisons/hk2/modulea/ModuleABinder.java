package io.tiko.comparisons.hk2.modulea;

import jakarta.inject.Singleton;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

public class ModuleABinder extends AbstractBinder {

    @Override
    protected void configure() {
        bindAsContract(UserRepository.class).in(Singleton.class);
        bindAsContract(UserService.class).in(Singleton.class);
    }
}
