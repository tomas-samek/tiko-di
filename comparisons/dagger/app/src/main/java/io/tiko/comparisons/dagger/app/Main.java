package io.tiko.comparisons.dagger.app;

import io.tiko.comparisons.dagger.modulea.User;
import io.tiko.comparisons.dagger.modulea.UserService;
import io.tiko.comparisons.dagger.moduleb.NotificationService;

public class Main {

  public static void main(String[] args) {
    AppComponent component = DaggerAppComponent.create();
    UserService userService = component.userService();
    NotificationService notificationService = component.notificationService();

    User charlie = userService.createUser("Charlie", "charlie@example.com");
    User diana = userService.createUser("Diana", "diana@example.com");
    notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
    notificationService.sendWelcomeNotification(diana.email(), diana.name());

    System.out.println("Dagger example completed: "
        + userService.getUserCount() + " users, "
        + notificationService.getNotificationCount() + " notifications");
  }
}
