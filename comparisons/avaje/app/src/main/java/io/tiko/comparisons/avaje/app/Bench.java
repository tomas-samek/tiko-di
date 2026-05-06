package io.tiko.comparisons.avaje.app;

import io.avaje.inject.BeanScope;
import io.tiko.comparisons.avaje.modulea.UserService;
import io.tiko.comparisons.avaje.moduleb.NotificationService;

public class Bench {

  public static void main(String[] args) {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    System.err.println("iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns");

    for (int i = 0; i < iterations; i++) {
      long t0 = System.nanoTime();
      BeanScope scope = BeanScope.builder().build();
      long t1 = System.nanoTime();
      UserService userService = scope.get(UserService.class);
      long t2 = System.nanoTime();
      NotificationService notificationService = scope.get(NotificationService.class);
      long t3 = System.nanoTime();
      scope.close();
      long t4 = System.nanoTime();

      if (userService == null || notificationService == null) {
        throw new IllegalStateException("null component");
      }

      System.err.printf("%d,%d,%d,%d,%d,%d%n",
          i, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0);
    }
  }
}
