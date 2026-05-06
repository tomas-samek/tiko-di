package io.tiko.examples.multimodule.moduleb;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;

/**
 * Service for sending notifications.
 */
@Component(scope = Scope.SINGLETON)
public class NotificationService {

    private final EmailSender emailSender;
    private int notificationCount = 0;

    @Inject
    public NotificationService(EmailSender emailSender) {
        this.emailSender = emailSender;
        System.out.println("[Module B] NotificationService constructed");
    }

    @PostConstruct
    public void init() {
        System.out.println("[Module B] NotificationService initialized");
    }

    public void sendWelcomeNotification(String email, String name) {
        System.out.println("[Module B] Sending welcome notification to: " + name);
        emailSender.sendWelcomeEmail(email, name);
        notificationCount++;
    }

    public int getNotificationCount() {
        return notificationCount;
    }
}
