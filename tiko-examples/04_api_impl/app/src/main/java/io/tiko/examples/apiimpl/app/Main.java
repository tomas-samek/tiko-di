package io.tiko.examples.apiimpl.app;

import io.tiko.Container;
import io.tiko.examples.apiimpl.api.User;
import io.tiko.examples.apiimpl.api.UserService;
import io.tiko.runtime.Tiko;

/**
 * The app sees only the api jar at compile time — note that the imports cover only
 * {@code io.tiko.examples.apiimpl.api.*}. The {@code UserServiceImpl} class is on the
 * runtime classpath only, supplied by {@code module-impl}, and reached through
 * {@link Container#get(Class)} via interface dispatch.
 */
public final class Main {

    public static void main(String[] args) {
        try (Container container = Tiko.create()) {
            UserService users = container.get(UserService.class);
            System.out.println("UserService impl class: " + users.getClass().getName());

            User alice = users.register("Alice", "alice@example.com");
            User bob = users.register("Bob", "bob@example.com");

            System.out.println("registered: " + alice);
            System.out.println("registered: " + bob);
            System.out.println("lookup #1 : " + users.get(alice.id()));
            System.out.println("total     : " + users.count());
        }
    }
}
