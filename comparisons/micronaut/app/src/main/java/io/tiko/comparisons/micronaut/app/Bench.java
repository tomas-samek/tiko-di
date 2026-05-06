package io.tiko.comparisons.micronaut.app;

import io.micronaut.context.BeanContext;
import io.tiko.comparisons.micronaut.modulea.UserService;
import io.tiko.comparisons.micronaut.moduleb.NotificationService;

public class Bench {

  public static void main(String[] args) {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    System.err.println("iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns");

    for (int i = 0; i < iterations; i++) {
      long t0 = System.nanoTime();
      BeanContext context = BeanContext.run();
      long t1 = System.nanoTime();
      UserService userService = context.getBean(UserService.class);
      long t2 = System.nanoTime();
      NotificationService notificationService = context.getBean(NotificationService.class);
      long t3 = System.nanoTime();
      context.close();
      long t4 = System.nanoTime();

      if (userService == null || notificationService == null) {
        throw new IllegalStateException("null component");
      }

      System.err.printf("%d,%d,%d,%d,%d,%d%n",
          i, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0);
    }
  }
}
