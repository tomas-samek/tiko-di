package io.tiko.comparisons.tiko.moduleb;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.PostConstruct;

@Component(scope = Scope.SINGLETON)
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
