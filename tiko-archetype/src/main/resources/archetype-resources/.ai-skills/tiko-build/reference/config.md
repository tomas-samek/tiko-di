# tiko-build reference — typed configuration

> Read this when: declaring `@Configuration` records or writing override YAML.

## Typed config: keys are exact

`tiko-config` binds YAML to `@Configuration` records by the **exact component
name** — no kebab-case/snake_case normalization, no Spring-style relaxation.
`poolSize` binds from `poolSize`, never `pool-size` or `pool_size`. A wrong key
fails the build with a `ConfigValidationException` naming the bad path and (for
a near-miss) suggesting the right key (`Did you mean 'db.poolSize'?`). Write the
config to match the record and it binds on the first attempt.

This is the full quickstart pair, 1:1 — copy the shape, not the prose. The
records (`prefix = "app"` → nested sections, camelCase fields, `@Default` for
optionals):

```java
@Configuration(prefix = "app")
public record AppConfig(ServerConfig server, DbConfig db) {}

public record ServerConfig(@Default("0") int port) {}

public record DbConfig(
        String url,
        String user,
        String password,
        @Default("4") int poolSize) {}
```

The `application.yml` that binds against them — section names match the prefix
and field names, `${VAR:default}` for environment overrides, `poolSize`
camelCase exactly as declared:

```yaml
app:               # @Configuration(prefix = "app")
  server:
    port: ${SERVER_PORT:8080}
  db:
    url: ${DB_URL:jdbc:h2:mem:quickstart;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}
    user: ${DB_USER:sa}
    password: ${DB_PASSWORD:}
    poolSize: 4    # exact key — NOT pool-size / pool_size
```

Read it with `Tiko.create(ConfigSources.classpath("application.yml"))` and
inject `AppConfig` (or a nested record) as a constructor parameter.

**Packages & file name.** `@Configuration` / `@Key` / `@Default` live in
`io.tiko.annotations`; `ConfigSources` is `io.tiko.config.ConfigSources` (the
`tiko-config` module — add it as a dependency). The config file name is **your
choice** — whatever you pass to `ConfigSources.classpath(...)`; pick **one** name and
use it consistently (this skill uses `application.yml`). That is separate from each
module's own defaults, which merge from its jar's `META-INF/tiko/defaults.yaml` (e.g.
`tiko-kafka` ships `tiko.kafka.*` defaults there) — see the Kafka section in
[`reference/kafka.md`](kafka.md).
