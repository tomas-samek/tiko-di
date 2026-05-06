package io.tiko.comparisons.guice.app;

import com.google.inject.Guice;
import com.google.inject.Injector;
import io.tiko.comparisons.guice.modulea.ModuleAGuice;
import io.tiko.comparisons.guice.modulea.UserService;
import io.tiko.comparisons.guice.moduleb.ModuleBGuice;
import io.tiko.comparisons.guice.moduleb.NotificationService;

public class Bench {

  public static void main(String[] args) {
    int iterations = args.length > 0 ? Integer.parseInt(args[0]) : 5;
    System.err.println("iter,create_ns,first_get_a_ns,first_get_b_ns,close_ns,total_ns");

    for (int i = 0; i < iterations; i++) {
      long t0 = System.nanoTime();
      Injector injector = Guice.createInjector(new ModuleAGuice(), new ModuleBGuice());
      long t1 = System.nanoTime();
      UserService userService = injector.getInstance(UserService.class);
      long t2 = System.nanoTime();
      NotificationService notificationService = injector.getInstance(NotificationService.class);
      long t3 = System.nanoTime();
      // Guice has no close()/shutdown() in core. Record 0.
      long t4 = System.nanoTime();

      if (userService == null || notificationService == null) {
        throw new IllegalStateException("null component");
      }

      System.err.printf("%d,%d,%d,%d,%d,%d%n",
          i, t1 - t0, t2 - t1, t3 - t2, t4 - t3, t4 - t0);
    }
  }
}
