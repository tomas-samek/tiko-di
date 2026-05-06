package io.tiko.comparisons.plain.app;

import io.tiko.comparisons.plain.modulea.UserRepository;
import io.tiko.comparisons.plain.modulea.UserService;
import io.tiko.comparisons.plain.moduleb.EmailSender;
import io.tiko.comparisons.plain.moduleb.NotificationService;

public class Bench {

  public static void main(String[] args) {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    System.err.println("iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns");

    for (int i = 0; i < iterations; i++) {
      long t0 = System.nanoTime();
      // No container to construct.
      long t1 = System.nanoTime();

      UserRepository userRepository = new UserRepository();
      userRepository.init();
      UserService userService = new UserService(userRepository);
      userService.init();
      long t2 = System.nanoTime();

      EmailSender emailSender = new EmailSender();
      emailSender.init();
      NotificationService notificationService = new NotificationService(emailSender);
      notificationService.init();
      long t3 = System.nanoTime();

      // No container to close.
      long t4 = System.nanoTime();

      if (userService == null || notificationService == null) {
        throw new IllegalStateException("null component");
      }

      System.err.printf("%d,%d,%d,%d,%d,%d%n",
          i, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0);
    }
  }
}
