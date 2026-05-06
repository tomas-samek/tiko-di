package io.tiko.comparisons.dagger.moduleb;

import dagger.Module;
import dagger.Provides;
import javax.inject.Singleton;

@Module
public class ModuleBDagger {

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
