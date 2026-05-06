package io.tiko.comparisons.plain.app;

import io.tiko.comparisons.plain.modulea.User;
import io.tiko.comparisons.plain.modulea.UserRepository;
import io.tiko.comparisons.plain.modulea.UserService;
import io.tiko.comparisons.plain.moduleb.EmailSender;
import io.tiko.comparisons.plain.moduleb.NotificationService;

public class Main {

  public static void main(String[] args) {
    UserRepository userRepository = new UserRepository();
    userRepository.init();
    UserService userService = new UserService(userRepository);
    userService.init();

    EmailSender emailSender = new EmailSender();
    emailSender.init();
    NotificationService notificationService = new NotificationService(emailSender);
    notificationService.init();

    User charlie = userService.createUser("Charlie", "charlie@example.com");
    User diana = userService.createUser("Diana", "diana@example.com");
    notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
    notificationService.sendWelcomeNotification(diana.email(), diana.name());

    System.out.println("Plain example completed: "
        + userService.getUserCount() + " users, "
        + notificationService.getNotificationCount() + " notifications");
  }
}
