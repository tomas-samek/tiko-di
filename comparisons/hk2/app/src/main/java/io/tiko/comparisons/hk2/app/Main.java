package io.tiko.comparisons.hk2.app;

import io.tiko.comparisons.hk2.modulea.ModuleABinder;
import io.tiko.comparisons.hk2.modulea.User;
import io.tiko.comparisons.hk2.modulea.UserService;
import io.tiko.comparisons.hk2.moduleb.ModuleBBinder;
import io.tiko.comparisons.hk2.moduleb.NotificationService;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;

public class Main {

  public static void main(String[] args) {
    ServiceLocator locator = ServiceLocatorUtilities.bind(new ModuleABinder(), new ModuleBBinder());
    try {
      UserService userService = locator.getService(UserService.class);
      NotificationService notificationService = locator.getService(NotificationService.class);

      User charlie = userService.createUser("Charlie", "charlie@example.com");
      User diana = userService.createUser("Diana", "diana@example.com");
      notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
      notificationService.sendWelcomeNotification(diana.email(), diana.name());

      System.out.println("HK2 example completed: "
          + userService.getUserCount() + " users, "
          + notificationService.getNotificationCount() + " notifications");
    } finally {
      locator.shutdown();
    }
  }
}
