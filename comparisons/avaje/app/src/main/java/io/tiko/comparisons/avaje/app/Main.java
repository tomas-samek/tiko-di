package io.tiko.comparisons.avaje.app;

import io.avaje.inject.BeanScope;
import io.tiko.comparisons.avaje.modulea.User;
import io.tiko.comparisons.avaje.modulea.UserService;
import io.tiko.comparisons.avaje.moduleb.NotificationService;

public class Main {

  public static void main(String[] args) {
    try (BeanScope scope = BeanScope.builder().build()) {
      UserService userService = scope.get(UserService.class);
      NotificationService notificationService = scope.get(NotificationService.class);

      User charlie = userService.createUser("Charlie", "charlie@example.com");
      User diana = userService.createUser("Diana", "diana@example.com");
      notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
      notificationService.sendWelcomeNotification(diana.email(), diana.name());

      System.out.println("Avaje Inject example completed: "
          + userService.getUserCount() + " users, "
          + notificationService.getNotificationCount() + " notifications");
    }
  }
}
