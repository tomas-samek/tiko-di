package io.tiko.comparisons.hk2.moduleb;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;

@Singleton
public class EmailSender {

    @PostConstruct
    public void init() {
        System.out.println("[Module B] EmailSender initialized");
    }

    public void sendWelcomeEmail(String email, String name) {
        System.out.println("[Module B] Sending welcome email to " + email);
        System.out.println("           Subject: Welcome " + name + "!");
        System.out.println("           Body: Thanks for joining us!");
    }
}
