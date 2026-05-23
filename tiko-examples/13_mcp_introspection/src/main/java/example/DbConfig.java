package example;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

@Configuration(prefix = "database")
public record DbConfig(
        String url, String username, @Default("10") int poolSize) {}
