package io.tiko.examples.apiimpl.api;

/**
 * Public-facing service contract. The app depends on this interface; the implementation
 * is supplied by {@code module-impl} and resolved at runtime via interface dispatch in
 * {@code container.get(UserService.class)}.
 */
public interface UserService {

    User register(String name, String email);

    User get(Long id);

    int count();
}
