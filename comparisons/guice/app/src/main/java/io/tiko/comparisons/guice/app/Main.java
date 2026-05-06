package io.tiko.comparisons.guice.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.tiko.comparisons.guice.modulea.ModuleAGuice;
import io.tiko.comparisons.guice.modulea.User;
import io.tiko.comparisons.guice.modulea.UserService;
import io.tiko.comparisons.guice.moduleb.ModuleBGuice;
import io.tiko.comparisons.guice.moduleb.NotificationService;

public class Main {

  public static void main(String[] args) {
    Injector injector = Guice.createInjector(new ModuleAGuice(), new ModuleBGuice());
    UserService userService = injector.getInstance(UserService.class);
    NotificationService notificationService = injector.getInstance(NotificationService.class);

    User charlie = userService.createUser("Charlie", "charlie@example.com");
    User diana = userService.createUser("Diana", "diana@example.com");
    notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
    notificationService.sendWelcomeNotification(diana.email(), diana.name());

    System.out.println("Guice example completed: "
        + userService.getUserCount() + " users, "
        + notificationService.getNotificationCount() + " notifications");
  }
}
