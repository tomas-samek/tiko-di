package io.tiko.examples.multimodule.app;

import io.tiko.Container;
import io.tiko.runtime.Tiko;
import io.tiko.examples.multimodule.modulea.User;
import io.tiko.examples.multimodule.modulea.UserService;
import io.tiko.examples.multimodule.moduleb.NotificationService;

/**
 * Main application demonstrating multi-module DI.
 * <p>
 * This application aggregates components from:
 * - Module A: UserService, UserRepository
 * - Module B: NotificationService, EmailSender
 * <p>
 * Each module has its own generated container (TikoContainerImpl_xxx).
 * The AggregatingContainer coordinates them transparently.
 */
public class Main {

  public static void main(String[] args) {
    System.out.println("=".repeat(70));
    System.out.println("Multi-Module DI Example");
    System.out.println("=".repeat(70));
    System.out.println();

    System.out.println("1. INITIALIZING AGGREGATED CONTAINER");
    System.out.println("-".repeat(70));

    try (Container container = Tiko.create()) {

      System.out.println();
      System.out.println("2. RETRIEVING COMPONENTS FROM DIFFERENT MODULES");
      System.out.println("-".repeat(70));

      // Get UserService from Module A
      UserService userService = container.get(UserService.class);
      System.out.println("✓ Retrieved UserService from Module A");

      // Get NotificationService from Module B
      NotificationService notificationService = container.get(NotificationService.class);
      System.out.println("✓ Retrieved NotificationService from Module B");

      System.out.println();
      System.out.println("3. CREATING USERS IN MODULE A");
      System.out.println("-".repeat(70));

      // Create users using Module A's UserService
      User charlie = userService.createUser("Charlie", "charlie@example.com");
      User diana = userService.createUser("Diana", "diana@example.com");

      System.out.println();
      System.out.println("4. SENDING NOTIFICATIONS WITH MODULE B");
      System.out.println("-".repeat(70));

      // Use Module B's NotificationService to send welcome emails
      notificationService.sendWelcomeNotification(charlie.email(), charlie.name());
      notificationService.sendWelcomeNotification(diana.email(), diana.name());

      System.out.println();
      System.out.println("5. CROSS-MODULE COLLABORATION");
      System.out.println("-".repeat(70));
      System.out.println("Module A created " + userService.getUserCount() + " users");
      System.out.println("Module B sent " + notificationService.getNotificationCount() + " notifications");

      System.out.println();
      System.out.println("6. VERIFYING DATA");
      System.out.println("-".repeat(70));

      User retrieved = userService.getUser(charlie.id());
      System.out.println("Retrieved user " + charlie.id() + ": " + retrieved);

      System.out.println();
      System.out.println("7. SHUTTING DOWN");
      System.out.println("-".repeat(70));
    }

    System.out.println();
    System.out.println("=".repeat(70));
    System.out.println("Multi-module example completed successfully");
    System.out.println("=".repeat(70));
  }
}
