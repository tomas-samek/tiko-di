package io.tiko.comparisons.hk2.moduleb;

import jakarta.inject.Singleton;
import org.glassfish.hk2.utilities.binding.AbstractBinder;

public class ModuleBBinder extends AbstractBinder {

    @Override
    protected void configure() {
        bindAsContract(EmailSender.class).in(Singleton.class);
        bindAsContract(NotificationService.class).in(Singleton.class);
    }
}
