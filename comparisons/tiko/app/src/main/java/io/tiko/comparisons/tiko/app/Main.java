package io.tiko.comparisons.tiko.app;

import io.tiko.Container;
import io.tiko.Tiko;
import io.tiko.comparisons.tiko.modulea.User;
import io.tiko.comparisons.tiko.modulea.UserService;
import io.tiko.comparisons.tiko.moduleb.NotificationService;

public class Main {

  public static void main(String[] args) {
    try (Container container = Tiko.create()) {
      UserService userService = container.get(UserService.class);
      NotificationService notificationService = container.get(NotificationService.class);

      User charlie = userService.createUser("Charlie", "charlie@example.com");
      User diana = userService.createUser("Diana", "diana@example.com");
      notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
      notificationService.sendWelcomeNotification(diana.email(), diana.name());

      System.out.println("Tiko example completed: "
          + userService.getUserCount() + " users, "
          + notificationService.getNotificationCount() + " notifications");
    }
  }
}
