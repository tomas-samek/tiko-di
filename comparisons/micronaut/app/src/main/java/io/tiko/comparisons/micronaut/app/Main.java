package io.tiko.comparisons.micronaut.app;

import io.micronaut.context.BeanContext;
import io.tiko.comparisons.micronaut.modulea.User;
import io.tiko.comparisons.micronaut.modulea.UserService;
import io.tiko.comparisons.micronaut.moduleb.NotificationService;

public class Main {

  public static void main(String[] args) {
    try (BeanContext context = BeanContext.run()) {
      UserService userService = context.getBean(UserService.class);
      NotificationService notificationService = context.getBean(NotificationService.class);

      User charlie = userService.createUser("Charlie", "charlie@example.com");
      User diana = userService.createUser("Diana", "diana@example.com");
      notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
      notificationService.sendWelcomeNotification(diana.email(), diana.name());

      System.out.println("Micronaut example completed: "
          + userService.getUserCount() + " users, "
          + notificationService.getNotificationCount() + " notifications");
    }
  }
}
