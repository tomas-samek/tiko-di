package io.tiko.comparisons.spring.app;

import io.tiko.comparisons.spring.modulea.User;
import io.tiko.comparisons.spring.modulea.UserService;
import io.tiko.comparisons.spring.moduleb.NotificationService;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public class Main {

  public static void main(String[] args) {
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(AppConfig.class)) {
      UserService userService = context.getBean(UserService.class);
      NotificationService notificationService = context.getBean(NotificationService.class);

      User charlie = userService.createUser("Charlie", "charlie@example.com");
      User diana = userService.createUser("Diana", "diana@example.com");
      notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
      notificationService.sendWelcomeNotification(diana.email(), diana.name());

      System.out.println("Spring example completed: "
          + userService.getUserCount() + " users, "
          + notificationService.getNotificationCount() + " notifications");
    }
  }
}
