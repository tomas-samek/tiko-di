package io.tiko.comparisons.guice.moduleb;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

public class ModuleBGuice extends AbstractModule {

    @Provides
    @Singleton
    EmailSender provideEmailSender() {
        EmailSender sender = new EmailSender();
        sender.init();
        return sender;
    }

    @Provides
    @Singleton
    NotificationService provideNotificationService(EmailSender emailSender) {
        NotificationService service = new NotificationService(emailSender);
        service.init();
        return service;
    }
}
