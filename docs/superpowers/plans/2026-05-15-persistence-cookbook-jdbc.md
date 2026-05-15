# Persistence Cookbook (raw JDBC + HikariCP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship `tiko-examples/10_persistence_jdbc/` (runnable HTTP + batch example with shared REQUEST-scoped JDBC transactions) plus `docs/cookbooks/persistence.md` and `docs/cookbooks/README.md` (cookbook track index).

**Architecture:** Single Maven module under `tiko-examples/`. Raw JDBC + HikariCP wired via `@Produces` factories. REQUEST scope = one DB transaction; SINGLETON `OrderRepository` injects auto-proxied `Connection`. Both `HttpEntry` (Javalin) and `BatchEntry` share one repository layer and one `TransactionalScope.run()` helper.

**Tech Stack:** Java 21, Tiko (api/processor/runtime/config), HikariCP, H2 (default DB; PostgreSQL-compatible mode), Javalin 6.7.0, Jackson, JUnit 5, AssertJ.

---

## File structure

```
tiko-examples/10_persistence_jdbc/
├── pom.xml
├── src/main/java/io/tiko/examples/persistence/
│   ├── config/
│   │   └── DbConfig.java                  (@Configuration record)
│   ├── infra/
│   │   ├── HikariDataSourceFactory.java   (SINGLETON @Component, @Produces DataSource)
│   │   ├── JdbcConnectionProvider.java    (REQUEST @Component, @Produces Connection)
│   │   ├── TransactionContext.java        (REQUEST @Component, AutoCloseable)
│   │   ├── TransactionalScope.java        (utility — opens REQUEST scope + commit/rollback)
│   │   └── SchemaInitializer.java         (SINGLETON @Component, @PostConstruct)
│   ├── domain/
│   │   ├── Order.java                     (record)
│   │   ├── OrderItem.java                 (record)
│   │   └── CreateOrderRequest.java        (DTO record)
│   ├── repo/
│   │   └── OrderRepository.java           (SINGLETON, Connection auto-proxied)
│   ├── http/
│   │   ├── HttpEntry.java                 (main)
│   │   └── OrderHttpRoutes.java           (bridge, not @Component)
│   └── batch/
│       ├── BatchEntry.java                (main)
│       ├── CurrentOrderContext.java       (EVENT @Component)
│       └── BatchAuditLogger.java          (SINGLETON @Component, @EventHandler)
├── src/main/resources/
│   ├── schema.sql
│   └── application.yml
└── src/test/java/io/tiko/examples/persistence/
    ├── repo/OrderRepositoryTest.java
    ├── http/HttpEntryIT.java
    └── batch/BatchEntryIT.java
docs/cookbooks/README.md                   (cookbook index)
docs/cookbooks/persistence.md              (the cookbook)
```

Modified outside the module:
- `pom.xml` (root) — add `hikari.version` + `h2.version` properties + `<dependencyManagement>` entries.
- `tiko-examples/pom.xml` — add `<module>10_persistence_jdbc</module>`.
- `docs/roadmap.md` — add a "What ships today" entry.

---

## Task 1: Module skeleton + reactor wiring

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/pom.xml`
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/.gitkeep`
- Create: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/.gitkeep`
- Modify: `pom.xml` (root) — add HikariCP + H2 versions and dependencyManagement entries.
- Modify: `tiko-examples/pom.xml` — add new module.

- [ ] **Step 1: Look up current HikariCP + H2 versions on Maven Central**

Verify with `gh.exe` or browser at https://central.sonatype.com/. Use the latest stable release. As of 2026-05-15 the candidates are HikariCP `6.2.1` and H2 `2.3.232`. If these resolve in Maven Central, use them; otherwise pick the latest stable shown.

- [ ] **Step 2: Modify root `pom.xml` — add version properties**

In `pom.xml` `<properties>` block, after `<javalin.version>6.7.0</javalin.version>`:

```xml
<hikari.version>6.2.1</hikari.version>
<h2.version>2.3.232</h2.version>
```

- [ ] **Step 3: Modify root `pom.xml` — add dependencyManagement entries**

In `pom.xml` `<dependencyManagement><dependencies>` block, after the Javalin entry:

```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>${hikari.version}</version>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <version>${h2.version}</version>
</dependency>
```

- [ ] **Step 4: Modify `tiko-examples/pom.xml`**

Add `<module>10_persistence_jdbc</module>` to the `<modules>` section, after `<module>09_http_javalin</module>`.

- [ ] **Step 5: Create `tiko-examples/10_persistence_jdbc/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>io.tiko.examples</groupId>
        <artifactId>tiko-examples</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>10_persistence_jdbc</artifactId>
    <packaging>jar</packaging>
    <name>10 - Persistence (raw JDBC + HikariCP)</name>
    <description>REQUEST-scoped JDBC transactions wrapping HTTP and batch entry points. Paired with docs/cookbooks/persistence.md.</description>

    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-processor</artifactId>
            <version>${project.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-runtime</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-config</artifactId>
            <version>${project.version}</version>
        </dependency>

        <dependency>
            <groupId>com.zaxxer</groupId>
            <artifactId>HikariCP</artifactId>
        </dependency>
        <dependency>
            <groupId>com.h2database</groupId>
            <artifactId>h2</artifactId>
        </dependency>

        <dependency>
            <groupId>io.javalin</groupId>
            <artifactId>javalin</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-simple</artifactId>
        </dependency>

        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-shade-plugin</artifactId>
                <version>${maven-shade.version}</version>
                <executions>
                    <execution>
                        <phase>package</phase>
                        <goals>
                            <goal>shade</goal>
                        </goals>
                        <configuration>
                            <transformers>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                    <mainClass>io.tiko.examples.persistence.http.HttpEntry</mainClass>
                                </transformer>
                                <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                            </transformers>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 6: Create `.gitkeep` files** to ensure empty src/main and src/test directories are tracked.

Run: `New-Item tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/.gitkeep -ItemType File -Force` (PowerShell). Same for the test path.

- [ ] **Step 7: Verify the module resolves**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc validate`
Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add pom.xml tiko-examples/pom.xml tiko-examples/10_persistence_jdbc/
git commit -m "feat(examples): scaffold 10_persistence_jdbc module"
```

---

## Task 2: Resources — `schema.sql` and `application.yml`

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/resources/schema.sql`
- Create: `tiko-examples/10_persistence_jdbc/src/main/resources/application.yml`

- [ ] **Step 1: Create `schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS orders (
    id          UUID        PRIMARY KEY,
    customer    VARCHAR(255) NOT NULL,
    status      VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS order_items (
    order_id    UUID        NOT NULL,
    line_no     INT         NOT NULL,
    sku         VARCHAR(64) NOT NULL,
    qty         INT         NOT NULL,
    PRIMARY KEY (order_id, line_no),
    FOREIGN KEY (order_id) REFERENCES orders(id)
);
```

- [ ] **Step 2: Create `application.yml`**

```yaml
# Persistence cookbook example. H2 in-memory by default (no Docker needed).
# For PostgreSQL: override url/user/password via env or a sibling YAML.
db:
  url: ${DB_URL:jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL}
  user: ${DB_USER:sa}
  password: ${DB_PASSWORD:}
  poolSize: 4
```

- [ ] **Step 3: Verify compile (no Java code yet, but resources must be syntactically clean)**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc process-resources`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/resources/
git commit -m "feat(examples): schema.sql + application.yml for persistence example"
```

---

## Task 3: Domain records + DbConfig

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/domain/Order.java`
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/domain/OrderItem.java`
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/domain/CreateOrderRequest.java`
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/config/DbConfig.java`

- [ ] **Step 1: Create `OrderItem.java`**

```java
package io.tiko.examples.persistence.domain;

/** One line of a purchase order. */
public record OrderItem(int lineNo, String sku, int qty) {}
```

- [ ] **Step 2: Create `Order.java`**

```java
package io.tiko.examples.persistence.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A purchase order with its line items. Persisted across two tables
 * ({@code orders} + {@code order_items}) so that a transaction failing
 * mid-way through inserting items rolls the parent {@code orders} row
 * back too — the cookbook's "why a transaction matters" demonstration.
 */
public record Order(UUID id, String customer, String status, Instant createdAt, List<OrderItem> items) {}
```

- [ ] **Step 3: Create `CreateOrderRequest.java`**

```java
package io.tiko.examples.persistence.domain;

import java.util.List;

/** JSON body for POST /orders. */
public record CreateOrderRequest(String customer, List<OrderItem> items) {}
```

- [ ] **Step 4: Create `DbConfig.java`**

```java
package io.tiko.examples.persistence.config;

import io.tiko.annotations.Configuration;
import io.tiko.annotations.Default;

/**
 * Typed binding for the {@code db} section of {@code application.yml}.
 * Loaded via {@code Tiko.create(ConfigSources.classpath("application.yml"))}.
 */
@Configuration(prefix = "db")
public record DbConfig(String url, String user, String password, @Default("4") int poolSize) {}
```

- [ ] **Step 5: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc compile`
Expected: `BUILD SUCCESS`. The Tiko processor sees `DbConfig` as a `@Configuration` record and generates a binder for it.

- [ ] **Step 6: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/domain/ tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/config/
git commit -m "feat(examples): domain records + DbConfig for persistence example"
```

---

## Task 4: `HikariDataSourceFactory` + `SchemaInitializer`

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/HikariDataSourceFactory.java`
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/SchemaInitializer.java`

- [ ] **Step 1: Create `HikariDataSourceFactory.java`**

```java
package io.tiko.examples.persistence.infra;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import io.tiko.examples.persistence.config.DbConfig;
import javax.sql.DataSource;

/**
 * Produces the application-wide HikariCP-backed {@link DataSource}. The
 * resulting {@code HikariDataSource} is {@link AutoCloseable}, so Tiko
 * drains the pool automatically at container shutdown.
 */
@Component(scope = Scope.SINGLETON)
public class HikariDataSourceFactory {

    private final DbConfig config;

    @Inject
    public HikariDataSourceFactory(DbConfig config) {
        this.config = config;
    }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.url());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.poolSize());
        hc.setAutoCommit(false);
        return new HikariDataSource(hc);
    }
}
```

- [ ] **Step 2: Create `SchemaInitializer.java`**

```java
package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * Loads {@code schema.sql} from the classpath and executes it at container
 * start. Idempotent: the script uses {@code CREATE TABLE IF NOT EXISTS}.
 *
 * <p>Production setups should use Flyway or Liquibase instead — this is a
 * teaching simplification, deliberately minimal.
 */
@Component(scope = Scope.SINGLETON)
public class SchemaInitializer {

    private final DataSource ds;

    @Inject
    public SchemaInitializer(DataSource ds) {
        this.ds = ds;
    }

    @PostConstruct
    public void initialize() throws SQLException, IOException {
        String script;
        try (InputStream in = SchemaInitializer.class.getResourceAsStream("/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql not found on classpath");
            try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                script = reader.lines().collect(Collectors.joining("\n"));
            }
        }
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String stmt : script.split(";")) {
                String trimmed = stmt.trim();
                if (!trimmed.isEmpty()) st.execute(trimmed);
            }
            c.commit();
        }
    }
}
```

- [ ] **Step 3: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/
git commit -m "feat(examples): HikariCP DataSource factory + schema initializer"
```

---

## Task 5: `JdbcConnectionProvider` — REQUEST-scoped `Connection` producer

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/JdbcConnectionProvider.java`

- [ ] **Step 1: Create `JdbcConnectionProvider.java`**

```java
package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.annotations.Produces;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * Produces a REQUEST-scoped {@link Connection}. Each REQUEST scope opens a
 * fresh pool connection with {@code autoCommit=false} and returns it on
 * scope teardown (Tiko's implicit-AutoCloseable handling closes the
 * connection, which Hikari intercepts to return it to the pool).
 *
 * <p>Because {@code java.sql.Connection} is an interface, SINGLETON
 * consumers (like {@code OrderRepository}) can inject {@code Connection}
 * directly — the Tiko annotation processor generates an auto-proxy that
 * resolves to the current scope's connection on every method call.
 */
@Component(scope = Scope.REQUEST)
public class JdbcConnectionProvider {

    private final DataSource ds;

    @Inject
    public JdbcConnectionProvider(DataSource ds) {
        this.ds = ds;
    }

    @Produces(scope = Scope.REQUEST)
    public Connection connection() throws SQLException {
        var c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc compile`
Expected: `BUILD SUCCESS`. Processor reports the new REQUEST-scoped component.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/JdbcConnectionProvider.java
git commit -m "feat(examples): REQUEST-scoped Connection producer"
```

---

## Task 6: `TransactionContext` — TDD

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/TransactionContext.java`
- Create: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/infra/TransactionContextTest.java`

This is the first TDD task. We test the rollback safety net (close() without commit() rolls back) against a real H2 connection — no mocks per CLAUDE.md's "integration tests must hit a real database".

- [ ] **Step 1: Write the failing test `TransactionContextTest.java`**

```java
package io.tiko.examples.persistence.infra;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link TransactionContext} semantics directly against H2.
 * Two cases: explicit commit persists; close-without-commit rolls back.
 */
class TransactionContextTest {

    private static final String URL = "jdbc:h2:mem:txctx;DB_CLOSE_DELAY=-1";

    private Connection setupConn;

    @BeforeEach
    void setUp() throws SQLException {
        setupConn = DriverManager.getConnection(URL);
        try (Statement st = setupConn.createStatement()) {
            st.execute("DROP TABLE IF EXISTS t");
            st.execute("CREATE TABLE t (id INT PRIMARY KEY)");
            setupConn.commit();
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        setupConn.close();
    }

    @Test
    void commitPersistsRow() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(false);
            try (TransactionContext tx = new TransactionContext(c)) {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO t VALUES (1)");
                }
                tx.commit();
            }
        }
        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    void closeWithoutCommitRollsBack() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(false);
            try (TransactionContext tx = new TransactionContext(c)) {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO t VALUES (2)");
                }
                // No commit() — close() must roll back.
            }
        }
        assertThat(rowCount()).isEqualTo(0);
    }

    @Test
    void explicitRollbackDiscardsInsert() throws Exception {
        try (Connection c = DriverManager.getConnection(URL)) {
            c.setAutoCommit(false);
            try (TransactionContext tx = new TransactionContext(c)) {
                try (Statement st = c.createStatement()) {
                    st.execute("INSERT INTO t VALUES (3)");
                }
                tx.rollback();
            }
        }
        assertThat(rowCount()).isEqualTo(0);
    }

    private int rowCount() throws SQLException {
        try (Statement st = setupConn.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM t")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
```

- [ ] **Step 2: Run the test — expect failure (class doesn't exist yet)**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: compile failure (`cannot find symbol: TransactionContext`).

- [ ] **Step 3: Create `TransactionContext.java`**

```java
package io.tiko.examples.persistence.infra;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * REQUEST-scoped transaction boundary owner. Wraps the same
 * REQUEST-scoped {@link Connection} (no proxy needed — same scope) and
 * exposes {@link #commit()} and {@link #rollback()}.
 *
 * <p>Implements {@link AutoCloseable}: at REQUEST scope teardown, Tiko's
 * implicit-AutoCloseable handling invokes {@link #close()}. If neither
 * {@code commit()} nor {@code rollback()} ran, {@code close()} rolls
 * back — the safety net for handler code that forgot to commit. We do
 * <strong>not</strong> call {@code connection.close()} here: Tiko's
 * implicit-AutoCloseable handling on the {@code @Produces} Connection
 * returns it to the Hikari pool (reverse-creation order:
 * {@code TransactionContext} depends on {@code Connection}, so this
 * tears down first, then Tiko closes the connection).
 *
 * <p>The intended commit path is {@code TransactionalScope.run(...)}.
 */
@Component(scope = Scope.REQUEST)
public class TransactionContext implements AutoCloseable {

    private final Connection connection;
    private boolean committed = false;
    private boolean rolledBack = false;

    @Inject
    public TransactionContext(Connection connection) {
        this.connection = connection;
    }

    public void commit() throws SQLException {
        connection.commit();
        committed = true;
    }

    public void rollback() throws SQLException {
        connection.rollback();
        rolledBack = true;
    }

    @Override
    public void close() throws SQLException {
        if (!committed && !rolledBack) {
            connection.rollback();
        }
    }
}
```

- [ ] **Step 4: Run the test — expect pass**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0` for `TransactionContextTest`. `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/TransactionContext.java tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/infra/TransactionContextTest.java
git commit -m "feat(examples): TransactionContext with rollback safety net + unit tests"
```

---

## Task 7: `TransactionalScope` utility

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/TransactionalScope.java`

- [ ] **Step 1: Create `TransactionalScope.java`**

```java
package io.tiko.examples.persistence.infra;

import io.tiko.Container;
import java.sql.SQLException;
import java.util.function.Supplier;

/**
 * Opens a Tiko REQUEST scope, resolves the {@link TransactionContext},
 * runs the user's work, and commits on success or rolls back on
 * exception. Both the HTTP and batch entry points use this helper — it
 * generalises across transports.
 *
 * <p>Not a {@code @Component}: it depends on {@link Container} (not
 * DI-injectable) and is invoked at the framework boundary (route
 * registration, batch loop).
 */
public final class TransactionalScope {

    private TransactionalScope() {}

    public static <T> T run(Container container, Supplier<T> work) {
        return container.supplyInRequestScope(() -> {
            var tx = container.get(TransactionContext.class);
            try {
                T result = work.get();
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(tx, e);
                throw e;
            } catch (Throwable t) {
                rollbackQuietly(tx, t);
                throw new RuntimeException(t);
            }
        });
    }

    private static void rollbackQuietly(TransactionContext tx, Throwable original) {
        try {
            tx.rollback();
        } catch (SQLException sx) {
            original.addSuppressed(sx);
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/infra/TransactionalScope.java
git commit -m "feat(examples): TransactionalScope helper — REQUEST scope + commit/rollback"
```

---

## Task 8: `OrderRepository` — TDD

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/repo/OrderRepository.java`
- Create: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/repo/OrderRepositoryTest.java`

The repository is the load-bearing demonstration of Tiko's auto-proxy on a REQUEST-scoped `java.sql.Connection` injected into a SINGLETON. The test boots a real container against H2, opens a REQUEST scope manually, and exercises insert/find. The crucial assertion: verify the row landed in **committed** state by querying via a separate connection (outside Tiko).

- [ ] **Step 1: Write the failing test `OrderRepositoryTest.java`**

```java
package io.tiko.examples.persistence.repo;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.runtime.Tiko;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link OrderRepository} against a real H2 in-memory DB.
 * Cross-connection verification ensures the row is committed, not
 * just visible to the inserting transaction.
 */
class OrderRepositoryTest {

    private Container container;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (container != null) container.shutdown();
        // H2 keep-alive via DB_CLOSE_DELAY=-1 means the DB lives across tests;
        // clean up rows to keep tests independent.
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
                Statement st = c.createStatement()) {
            st.execute("DELETE FROM order_items");
            st.execute("DELETE FROM orders");
        }
    }

    @Test
    void insertedOrderIsVisibleViaFindById() {
        UUID id = UUID.randomUUID();
        Order toInsert = new Order(id, "alice", "NEW", Instant.now(),
                List.of(new OrderItem(1, "sku-1", 2), new OrderItem(2, "sku-2", 3)));

        TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                repo.insert(toInsert);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        Order found = TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                return repo.findById(id).orElseThrow();
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });

        assertThat(found.id()).isEqualTo(id);
        assertThat(found.customer()).isEqualTo("alice");
        assertThat(found.items()).hasSize(2);
        assertThat(found.items()).extracting(OrderItem::sku).containsExactly("sku-1", "sku-2");
    }

    @Test
    void findByIdReturnsEmptyForUnknownOrder() {
        var result = TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                return repo.findById(UUID.randomUUID());
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        assertThat(result).isEmpty();
    }

    @Test
    void insertedRowIsCommittedNotJustVisibleInSession() throws Exception {
        UUID id = UUID.randomUUID();
        Order toInsert = new Order(id, "bob", "NEW", Instant.now(),
                List.of(new OrderItem(1, "sku-x", 1)));

        TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            try {
                repo.insert(toInsert);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return null;
        });

        // Open a completely independent connection (outside Tiko) and verify.
        try (Connection c = DriverManager.getConnection("jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT customer FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("bob");
        }
    }
}
```

- [ ] **Step 2: Run the test — expect failure (class doesn't exist)**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: compile failure (`cannot find symbol: OrderRepository`).

- [ ] **Step 3: Create `OrderRepository.java`**

```java
package io.tiko.examples.persistence.repo;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.Inject;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * SINGLETON repository operating on a REQUEST-scoped {@link Connection}.
 * The {@code connection} field looks like a captured-at-construction
 * object, but it is a Tiko-generated auto-proxy: every method call
 * resolves to the current REQUEST scope's connection. Calling the
 * repository outside an active REQUEST scope fails with a scope error.
 */
@Component(scope = Scope.SINGLETON)
public class OrderRepository {

    private static final String INSERT_ORDER =
            "INSERT INTO orders (id, customer, status, created_at) VALUES (?, ?, ?, ?)";
    private static final String INSERT_ITEM =
            "INSERT INTO order_items (order_id, line_no, sku, qty) VALUES (?, ?, ?, ?)";
    private static final String SELECT_ORDER =
            "SELECT id, customer, status, created_at FROM orders WHERE id = ?";
    private static final String SELECT_ITEMS =
            "SELECT line_no, sku, qty FROM order_items WHERE order_id = ? ORDER BY line_no";

    private final Connection connection;

    @Inject
    public OrderRepository(Connection connection) {
        this.connection = connection;
    }

    public void insert(Order order) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_ORDER)) {
            ps.setObject(1, order.id());
            ps.setString(2, order.customer());
            ps.setString(3, order.status());
            ps.setTimestamp(4, Timestamp.from(order.createdAt()));
            ps.executeUpdate();
        }
        try (PreparedStatement ps = connection.prepareStatement(INSERT_ITEM)) {
            for (OrderItem item : order.items()) {
                ps.setObject(1, order.id());
                ps.setInt(2, item.lineNo());
                ps.setString(3, item.sku());
                ps.setInt(4, item.qty());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public Optional<Order> findById(UUID id) throws SQLException {
        Order base;
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ORDER)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                base = new Order(
                        rs.getObject(1, UUID.class),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant(),
                        List.of());
            }
        }
        List<OrderItem> items = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(SELECT_ITEMS)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(new OrderItem(rs.getInt(1), rs.getString(2), rs.getInt(3)));
                }
            }
        }
        return Optional.of(new Order(base.id(), base.customer(), base.status(), base.createdAt(), items));
    }
}
```

- [ ] **Step 4: Run the test — expect pass**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 6` (3 from `TransactionContextTest` + 3 from `OrderRepositoryTest`), all passing. `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/repo/ tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/repo/
git commit -m "feat(examples): OrderRepository with auto-proxied Connection + tests"
```

---

## Task 9: `OrderHttpRoutes` — HTTP bridge

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/http/OrderHttpRoutes.java`

- [ ] **Step 1: Create `OrderHttpRoutes.java`**

```java
package io.tiko.examples.persistence.http;

import io.javalin.http.Context;
import io.tiko.Container;
import io.tiko.examples.persistence.domain.CreateOrderRequest;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.examples.persistence.repo.OrderRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

/**
 * Bridge between Javalin's HTTP machinery and the persistence layer.
 * Not a {@code @Component}: it depends on {@link Container} (not
 * DI-injectable). Constructed once in {@link HttpEntry} and held for
 * the server's lifetime; per-request resolution of repositories happens
 * via {@code container.get(...)} inside the open REQUEST scope.
 */
public final class OrderHttpRoutes {

    private final Container container;

    public OrderHttpRoutes(Container container) {
        this.container = container;
    }

    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateOrderRequest.class);
        if (req.customer() == null || req.customer().isBlank()) {
            throw new IllegalArgumentException("customer must not be blank");
        }
        for (OrderItem item : req.items()) {
            if (item.qty() < 0) {
                throw new IllegalArgumentException("qty must not be negative (line " + item.lineNo() + ")");
            }
        }
        var order = new Order(UUID.randomUUID(), req.customer(), "NEW", Instant.now(), req.items());
        try {
            container.get(OrderRepository.class).insert(order);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        ctx.status(201).json(order);
    }

    public void handleGet(Context ctx) {
        var id = UUID.fromString(ctx.pathParam("id"));
        try {
            container.get(OrderRepository.class).findById(id)
                    .ifPresentOrElse(o -> ctx.status(200).json(o), () -> ctx.status(404));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/http/OrderHttpRoutes.java
git commit -m "feat(examples): OrderHttpRoutes bridge"
```

---

## Task 10: `HttpEntry` — main bootstrap

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/http/HttpEntry.java`

- [ ] **Step 1: Create `HttpEntry.java`**

```java
package io.tiko.examples.persistence.http;

import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.runtime.Tiko;

/**
 * HTTP entry point. Routes each request through {@code TransactionalScope.run(...)}
 * so one HTTP request = one DB transaction. The shutdown hook stops
 * Javalin before {@code container.shutdown()} so in-flight requests
 * drain before {@code @PreDestroy} runs.
 */
public final class HttpEntry {

    private HttpEntry() {}

    public static void main(String[] args) {
        Container container = Tiko.create(ConfigSources.classpath("application.yml"));
        var routes = new OrderHttpRoutes(container);

        Javalin app = Javalin.create();
        app.post("/orders", ctx -> TransactionalScope.run(container, () -> {
            routes.handleCreate(ctx);
            return null;
        }));
        app.get("/orders/{id}", ctx -> TransactionalScope.run(container, () -> {
            routes.handleGet(ctx);
            return null;
        }));
        app.start(portFromEnv());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.stop();
            container.shutdown();
        }, "tiko-persistence-shutdown"));
    }

    private static int portFromEnv() {
        String value = System.getenv("TIKO_HTTP_PORT");
        if (value == null || value.isBlank()) return 8080;
        return Integer.parseInt(value.trim());
    }
}
```

- [ ] **Step 2: Verify compile + package**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc package -DskipTests`
Expected: `BUILD SUCCESS`. Shaded jar at `tiko-examples/10_persistence_jdbc/target/10_persistence_jdbc-0.1.0.jar`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/http/HttpEntry.java
git commit -m "feat(examples): HttpEntry — Javalin bootstrap with transactional route wrapping"
```

---

## Task 11: Integration test — POST happy path

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/http/HttpEntryIT.java`

- [ ] **Step 1: Create test scaffold + first test**

```java
package io.tiko.examples.persistence.http;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.runtime.Tiko;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration test for the HTTP entry point: real Tiko
 * container + real Javalin on a random port + real HTTP via
 * {@link HttpClient}. The load-bearing assertion in every scenario is
 * a cross-connection JDBC query that proves what was (or wasn't)
 * committed independent of any Tiko-side state.
 */
class HttpEntryIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JDBC_URL = "jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Container container;
    private Javalin app;
    private int port;
    private HttpClient client;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
        var routes = new OrderHttpRoutes(container);
        app = Javalin.create();
        app.post("/orders", ctx -> TransactionalScope.run(container, () -> { routes.handleCreate(ctx); return null; }));
        app.get("/orders/{id}", ctx -> TransactionalScope.run(container, () -> { routes.handleGet(ctx); return null; }));
        app.start(0);
        port = app.port();
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (app != null) app.stop();
        if (container != null) container.shutdown();
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("DELETE FROM order_items");
            st.execute("DELETE FROM orders");
        }
    }

    @Test
    void postCreatesOrderAndReturns201() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"customer\":\"alice\",\"items\":[{\"lineNo\":1,\"sku\":\"sku-1\",\"qty\":2}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(201);
        JsonNode body = JSON.readTree(resp.body());
        String id = body.get("id").asText();
        assertThat(body.get("customer").asText()).isEqualTo("alice");

        // Load-bearing: a separate connection (outside Tiko) sees the committed row.
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT customer FROM orders WHERE id = '" + id + "'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("alice");
        }
    }
}
```

- [ ] **Step 2: Run the test**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 7` (6 from earlier + 1 new). `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/http/HttpEntryIT.java
git commit -m "test(examples): HttpEntryIT POST happy path"
```

---

## Task 12: Integration test — rollback on mid-handler exception (load-bearing)

**Files:**
- Modify: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/http/HttpEntryIT.java` — append one new `@Test` method.

This is the cookbook's central assertion: a partial insert (orders row + first item) followed by a validation failure produces a 500 response **and** leaves *nothing* committed.

- [ ] **Step 1: Append test method**

In `HttpEntryIT.java`, after `postCreatesOrderAndReturns201`, add:

```java
    @Test
    void postFailingMidTransactionRollsBackEverything() throws Exception {
        // Negative qty on the second item triggers the bridge's validation
        // AFTER the orders row + first item would have been queued. The
        // INSERTs run before the exception, the exception triggers
        // TransactionalScope to roll back. Nothing must be committed.
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"customer\":\"poison\",\"items\":["
                                        + "{\"lineNo\":1,\"sku\":\"a\",\"qty\":1},"
                                        + "{\"lineNo\":2,\"sku\":\"b\",\"qty\":-1}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(resp.statusCode()).isEqualTo(500);

        // Load-bearing: NOTHING from this request should be in the DB.
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM orders WHERE customer = 'poison'")) {
            rs.next();
            assertThat(rs.getInt(1)).as("orders row must be rolled back").isEqualTo(0);
        }
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM order_items WHERE sku IN ('a','b')")) {
            rs.next();
            assertThat(rs.getInt(1)).as("any inserted items must be rolled back").isEqualTo(0);
        }
    }
```

Note: the current `handleCreate` validates *before* the repository insert (the `qty < 0` check runs in a loop before `container.get(OrderRepository.class).insert(...)`). To make the "mid-transaction" claim accurate, move the validation **after** the order-insert but **before** all item-inserts complete. We'll modify the bridge in the next step so the partial insert actually happens.

- [ ] **Step 2: Modify `OrderHttpRoutes.handleCreate` to allow partial insert before failure**

Replace the validation loop + insert in `OrderHttpRoutes.java` with code that inserts incrementally and trips on negative qty mid-loop. Update the method to (replace entirely):

```java
    public void handleCreate(Context ctx) {
        var req = ctx.bodyAsClass(CreateOrderRequest.class);
        if (req.customer() == null || req.customer().isBlank()) {
            throw new IllegalArgumentException("customer must not be blank");
        }
        var order = new Order(UUID.randomUUID(), req.customer(), "NEW", Instant.now(), req.items());
        try {
            var repo = container.get(OrderRepository.class);
            // Insert the orders row + items one-at-a-time so a poison item
            // mid-batch leaves the orders row already INSERTed in the tx.
            // The cookbook's rollback test depends on this: the
            // TransactionalScope.run wrapper must roll BOTH back together.
            repo.insertHeader(order);
            for (OrderItem item : req.items()) {
                if (item.qty() < 0) {
                    throw new IllegalArgumentException("qty must not be negative (line " + item.lineNo() + ")");
                }
                repo.insertItem(order.id(), item);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        ctx.status(201).json(order);
    }
```

- [ ] **Step 3: Add `insertHeader` and `insertItem` to `OrderRepository`**

In `OrderRepository.java`, replace the single `insert(Order)` method with these two (and keep the rest of the class unchanged):

```java
    public void insertHeader(Order order) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_ORDER)) {
            ps.setObject(1, order.id());
            ps.setString(2, order.customer());
            ps.setString(3, order.status());
            ps.setTimestamp(4, Timestamp.from(order.createdAt()));
            ps.executeUpdate();
        }
    }

    public void insertItem(UUID orderId, OrderItem item) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(INSERT_ITEM)) {
            ps.setObject(1, orderId);
            ps.setInt(2, item.lineNo());
            ps.setString(3, item.sku());
            ps.setInt(4, item.qty());
            ps.executeUpdate();
        }
    }

    /** Convenience: insert header + all items. Used by tests + batch entry. */
    public void insert(Order order) throws SQLException {
        insertHeader(order);
        for (OrderItem item : order.items()) {
            insertItem(order.id(), item);
        }
    }
```

- [ ] **Step 4: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 8`. The new test passes; existing tests (`OrderRepositoryTest`'s `insertedOrderIsVisibleViaFindById` and `insertedRowIsCommittedNotJustVisibleInSession`) continue to pass because `insert(Order)` still exists with the same signature.

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/http/HttpEntryIT.java tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/http/OrderHttpRoutes.java tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/repo/OrderRepository.java
git commit -m "test(examples): HttpEntryIT rollback on mid-handler exception"
```

---

## Task 13: Integration test — GET happy + 404

**Files:**
- Modify: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/http/HttpEntryIT.java` — append two test methods.

- [ ] **Step 1: Append test methods**

In `HttpEntryIT.java`, after `postFailingMidTransactionRollsBackEverything`, add:

```java
    @Test
    void getReturnsCreatedOrder() throws Exception {
        HttpResponse<String> postResp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"customer\":\"carol\",\"items\":[{\"lineNo\":1,\"sku\":\"sku-x\",\"qty\":5}]}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(postResp.statusCode()).isEqualTo(201);
        String id = JSON.readTree(postResp.body()).get("id").asText();

        HttpResponse<String> getResp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders/" + id))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(getResp.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(getResp.body());
        assertThat(body.get("customer").asText()).isEqualTo("carol");
        assertThat(body.get("items").get(0).get("sku").asText()).isEqualTo("sku-x");
    }

    @Test
    void getReturns404ForUnknownId() throws Exception {
        HttpResponse<String> resp = client.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://localhost:" + port + "/orders/" + java.util.UUID.randomUUID()))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
    }
```

- [ ] **Step 2: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 10`. All passing.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/http/HttpEntryIT.java
git commit -m "test(examples): HttpEntryIT GET happy path + 404"
```

---

## Task 14: `CurrentOrderContext` + `BatchAuditLogger` — EVENT-scope demo components

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/CurrentOrderContext.java`
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/BatchAuditLogger.java`

- [ ] **Step 1: Create the `CurrentOrder` interface**

The proxy mechanism requires an interface — create it first so the impl in the next step compiles:

```java
// File: tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/CurrentOrder.java
package io.tiko.examples.persistence.batch;

import java.util.UUID;

/** Read-only view of the current order being processed by the batch loop. */
public interface CurrentOrder {
    UUID orderId();
}
```

- [ ] **Step 2: Create `CurrentOrderContext.java`**

```java
package io.tiko.examples.persistence.batch;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import java.util.UUID;

/**
 * Per-event state for the batch flow: the order id being processed in
 * the current iteration. EVENT-scoped, so each {@code runInEventScope}
 * gets its own instance. A SINGLETON consumer (see {@code BatchAuditLogger})
 * can inject {@link CurrentOrder} directly via constructor — Tiko's
 * annotation processor generates an auto-proxy that resolves to the
 * current EVENT scope's instance on every method call.
 */
@Component(scope = Scope.EVENT)
public class CurrentOrderContext implements CurrentOrder {

    private UUID orderId;

    public void setOrderId(UUID orderId) {
        this.orderId = orderId;
    }

    @Override
    public UUID orderId() {
        return orderId;
    }
}
```

- [ ] **Step 3: Create `BatchAuditLogger.java`**

```java
package io.tiko.examples.persistence.batch;

import io.tiko.Scope;
import io.tiko.annotations.Component;
import io.tiko.annotations.EventHandler;
import io.tiko.annotations.Inject;
import io.tiko.events.EventStartedEvent;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Sync subscriber to {@link EventStartedEvent} that records the EVENT-scoped
 * {@link CurrentOrder}'s id on each iteration. Proves two things at once:
 * (a) Tiko's auto-proxy works for EVENT-scoped beans injected into SINGLETONs,
 * (b) the batch loop actually opens N distinct EVENT scopes inside one REQUEST.
 *
 * <p>Lives in main sources (not test) so the annotation processor wires it
 * like any other subscriber. {@link #captured()} returns a defensive snapshot
 * for tests to assert against.
 */
@Component(scope = Scope.SINGLETON)
public class BatchAuditLogger {

    private static final Logger LOG = Logger.getLogger("io.tiko.examples.persistence.batch");

    private final CurrentOrder current; // auto-proxied to the current EVENT scope's CurrentOrderContext
    private final List<UUID> seen = new CopyOnWriteArrayList<>();

    @Inject
    public BatchAuditLogger(CurrentOrder current) {
        this.current = current;
    }

    @EventHandler
    public void onEventStarted(EventStartedEvent event) {
        UUID id = current.orderId();
        if (id != null) {
            seen.add(id);
            LOG.info(() -> "[batch-audit] processing order " + id);
        }
    }

    /** Defensive snapshot. */
    public List<UUID> captured() {
        return List.copyOf(seen);
    }
}
```

- [ ] **Step 4: Verify compile**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc compile`
Expected: `BUILD SUCCESS`. Processor reports a new SINGLETON, a new EVENT-scoped component, a new event handler, and emits a proxy class for `CurrentOrder` (the interface dispatch into the EVENT-scoped `CurrentOrderContext`).

- [ ] **Step 5: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/
git commit -m "feat(examples): CurrentOrderContext (EVENT) + BatchAuditLogger (SINGLETON, auto-proxy)"
```

---

## Task 15: `BatchEntry` — batch main bootstrap

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/BatchEntry.java`

- [ ] **Step 1: Create `BatchEntry.java`**

```java
package io.tiko.examples.persistence.batch;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.examples.persistence.infra.TransactionalScope;
import io.tiko.examples.persistence.repo.OrderRepository;
import io.tiko.runtime.Tiko;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Batch entry point: processes N orders in ONE REQUEST scope, with each
 * order getting its own EVENT scope. All N inserts commit together or
 * all roll back together — all-or-none semantics.
 *
 * <p>Run with {@code java -cp <jar> io.tiko.examples.persistence.batch.BatchEntry}
 * (the shaded jar's default main class is {@code HttpEntry}).
 */
public final class BatchEntry {

    private BatchEntry() {}

    public static void main(String[] args) {
        Container container = Tiko.create(ConfigSources.classpath("application.yml"));
        try {
            int processed = processBatch(container, sampleFixture());
            System.out.println("[batch] committed " + processed + " orders");
        } finally {
            container.shutdown();
        }
    }

    /**
     * Process a batch of orders inside one REQUEST scope (= one transaction)
     * with one EVENT scope per order. Returns the number successfully
     * committed (always {@code orders.size()} on success, since the
     * helper throws on poison records).
     */
    public static int processBatch(Container container, List<Order> orders) {
        return TransactionalScope.run(container, () -> {
            var repo = container.get(OrderRepository.class);
            for (Order o : orders) {
                container.runInEventScope(() -> {
                    var ctx = container.get(CurrentOrderContext.class);
                    ctx.setOrderId(o.id());
                    try {
                        repo.insert(o);
                    } catch (SQLException e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            return orders.size();
        });
    }

    private static List<Order> sampleFixture() {
        return List.of(
                new Order(UUID.randomUUID(), "alice", "NEW", Instant.now(),
                        List.of(new OrderItem(1, "sku-1", 2))),
                new Order(UUID.randomUUID(), "bob", "NEW", Instant.now(),
                        List.of(new OrderItem(1, "sku-2", 1), new OrderItem(2, "sku-3", 4))),
                new Order(UUID.randomUUID(), "carol", "NEW", Instant.now(),
                        List.of(new OrderItem(1, "sku-4", 7))));
    }
}
```

- [ ] **Step 2: Verify compile + package**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc package -DskipTests`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/main/java/io/tiko/examples/persistence/batch/BatchEntry.java
git commit -m "feat(examples): BatchEntry — one REQUEST + N EVENTs sharing one transaction"
```

---

## Task 16: Integration test — batch all-success + audit assertion

**Files:**
- Create: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/batch/BatchEntryIT.java`

- [ ] **Step 1: Create test scaffold + first test**

```java
package io.tiko.examples.persistence.batch;

import static org.assertj.core.api.Assertions.assertThat;

import io.tiko.Container;
import io.tiko.config.ConfigSources;
import io.tiko.examples.persistence.domain.Order;
import io.tiko.examples.persistence.domain.OrderItem;
import io.tiko.runtime.Tiko;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BatchEntryIT {

    private static final String JDBC_URL = "jdbc:h2:mem:tiko;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private Container container;

    @BeforeEach
    void setUp() {
        container = Tiko.create(ConfigSources.classpath("application.yml"));
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (container != null) container.shutdown();
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", ""); Statement st = c.createStatement()) {
            st.execute("DELETE FROM order_items");
            st.execute("DELETE FROM orders");
        }
    }

    @Test
    void batchCommitsAllOrdersAndAuditLoggerCapturedEach() throws Exception {
        List<Order> orders = makeOrders(5);

        int committed = BatchEntry.processBatch(container, orders);
        assertThat(committed).isEqualTo(5);

        // Cross-connection check: all 5 orders + their items are in the DB.
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(5);
        }

        // Auto-proxy demonstration: audit logger saw EVENT-scoped CurrentOrder
        // resolve to a different order id on each of the 5 iterations.
        List<UUID> seen = container.get(BatchAuditLogger.class).captured();
        assertThat(seen).hasSize(5);
        assertThat(seen).containsExactlyElementsOf(orders.stream().map(Order::id).toList());
    }

    private List<Order> makeOrders(int n) {
        var out = new ArrayList<Order>();
        for (int i = 0; i < n; i++) {
            out.add(new Order(UUID.randomUUID(), "customer-" + i, "NEW", Instant.now(),
                    List.of(new OrderItem(1, "sku-" + i, 1))));
        }
        return out;
    }
}
```

- [ ] **Step 2: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 11`. All passing.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/batch/BatchEntryIT.java
git commit -m "test(examples): BatchEntryIT all-success + audit auto-proxy assertion"
```

---

## Task 17: Integration test — batch poison record / all-or-none rollback

**Files:**
- Modify: `tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/batch/BatchEntryIT.java`

- [ ] **Step 1: Append test method**

In `BatchEntryIT.java`, after `batchCommitsAllOrdersAndAuditLoggerCapturedEach`, add:

```java
    @Test
    void batchRollsBackEverythingWhenAnyOrderFails() throws Exception {
        // 5 orders, index 2 is poison: a duplicate primary key (line_no=1 twice).
        UUID poisonId = UUID.randomUUID();
        List<Order> orders = new ArrayList<>(makeOrders(5));
        orders.set(2, new Order(poisonId, "poison", "NEW", Instant.now(),
                List.of(new OrderItem(1, "sku-dup-a", 1), new OrderItem(1, "sku-dup-b", 1))));

        try {
            BatchEntry.processBatch(container, orders);
            org.junit.jupiter.api.Assertions.fail("expected an exception from the poison record");
        } catch (RuntimeException expected) {
            // Expected — primary-key violation on order_items.
        }

        // Cross-connection check: nothing committed. Not the poison row, not
        // the 2 orders that came before it, not the 2 after.
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM orders")) {
            rs.next();
            assertThat(rs.getInt(1)).as("the whole batch must roll back, all-or-none").isEqualTo(0);
        }
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
                Statement st = c.createStatement();
                var rs = st.executeQuery("SELECT COUNT(*) FROM order_items")) {
            rs.next();
            assertThat(rs.getInt(1)).as("no items survived either").isEqualTo(0);
        }
    }
```

- [ ] **Step 2: Run the tests**

Run: `W:/tools/apache-maven/bin/mvn -pl tiko-examples/10_persistence_jdbc test`
Expected: `Tests run: 12`. All passing.

- [ ] **Step 3: Commit**

```bash
git add tiko-examples/10_persistence_jdbc/src/test/java/io/tiko/examples/persistence/batch/BatchEntryIT.java
git commit -m "test(examples): BatchEntryIT all-or-none rollback on poison record"
```

---

## Task 18: Cookbook index + `persistence.md`

**Files:**
- Create: `docs/cookbooks/README.md`
- Create: `docs/cookbooks/persistence.md`

- [ ] **Step 1: Create `docs/cookbooks/README.md`**

```markdown
# Tiko cookbooks

Recommended integrations for areas Tiko deliberately doesn't ship. Each
cookbook is a docs page paired with a runnable numbered example under
`tiko-examples/`. The cookbook documents the *why* and the wiring; the
example proves it compiles, runs, and stays green under CI.

## Available

- [Persistence (raw JDBC + HikariCP)](persistence.md) — `tiko-examples/10_persistence_jdbc/`. REQUEST-scoped JDBC transactions wrapping both an HTTP entry point and a batch flow with shared repositories. Demonstrates the auto-proxy mechanism on a JDK interface (`java.sql.Connection`) and the concrete REQUEST-vs-EVENT scope distinction.

## Planned

- **Security** — auth/authz at the HTTP boundary. Likely leverages whatever HTTP server you've picked (Javalin in `09_http_javalin`).
- **Resilience** — Resilience4j integration (retry, circuit breaker, bulkhead) around `@Component` boundaries.
- **Kafka surfacing** — cross-references to `08_kafka_order_warehouse` and a "when to reach for distributed events" narrative.
- **Non-goals + recommended integrations** — single top-level page naming the boundary of what Tiko owns and the recommended pairing for each non-goal.

The cookbook track exists because reviewers consistently read silence on
persistence/security/resilience as "framework is incomplete" rather than
"framework is deliberately small". Cookbooks close that documentation gap
without expanding Tiko's surface.
```

- [ ] **Step 2: Create `docs/cookbooks/persistence.md`**

```markdown
# Persistence with Tiko — raw JDBC + HikariCP

> Runnable example: [`tiko-examples/10_persistence_jdbc/`](../../tiko-examples/10_persistence_jdbc/).

## Why Tiko doesn't ship persistence

Tiko's scope is **compile-time DI + event orchestration**. Persistence is
intentionally out of scope:

- The persistence space is big — JDBC, JPA/Hibernate, JOOQ, JDBI, Spring
  Data, R2DBC — each with its own release cadence and CVE pressure.
  A small team can't keep first-class integration modules honest across
  all of them.
- Tying Tiko to a single persistence library would force every adopter
  into that choice. Tying Tiko to all of them turns Tiko into a 1%-resourced
  Spring Boot competitor instead of an orthogonal alternative.

What Tiko *does* offer is the wiring patterns: `@Produces` factories,
REQUEST scope = transaction lifetime, auto-proxy of REQUEST-scoped
resources into SINGLETON consumers. This cookbook shows that wiring with
raw JDBC + HikariCP — the lowest layer, easiest to follow. Higher-level
libraries layer on top of the same scaffolding.

## What you'll learn

1. **REQUEST = one DB transaction.** Open a REQUEST scope; everything
   inside it runs in one transaction; commit on clean exit, roll back
   on exception.
2. **EVENT = single message being processed.** Inside one batch (one
   REQUEST), multiple messages each get their own EVENT scope and their
   own per-message state — but share the one outer transaction.
3. **Auto-proxy on `java.sql.Connection`.** A SINGLETON repository can
   inject the REQUEST-scoped Connection directly; Tiko's annotation
   processor generates a proxy that resolves to the current scope on
   every method call.
4. **Transaction decorator pattern.** A single helper
   (`TransactionalScope.run(...)`) opens the scope, commits on success,
   rolls back on exception. Both HTTP and batch entries use it.

## Library choice

**Raw JDBC + HikariCP.** Universal, no codegen, no ORM. Every Java
developer knows the API. The cookbook's job is to teach the *Tiko-side*
wiring, not the persistence library — picking the lowest layer keeps
the persistence noise out of the way.

For higher-level abstractions on top of this wiring, see "Beyond raw JDBC"
at the bottom of this page.

## DataSource wiring

The pool is a SINGLETON `@Component` that produces a `DataSource` via
`@Produces`:

```java
@Component(scope = Scope.SINGLETON)
public class HikariDataSourceFactory {
    private final DbConfig config;

    @Inject HikariDataSourceFactory(DbConfig config) { this.config = config; }

    @Produces(scope = Scope.SINGLETON)
    public DataSource dataSource() {
        var hc = new HikariConfig();
        hc.setJdbcUrl(config.url());
        hc.setUsername(config.user());
        hc.setPassword(config.password());
        hc.setMaximumPoolSize(config.poolSize());
        hc.setAutoCommit(false);
        return new HikariDataSource(hc);
    }
}
```

`DbConfig` is a `@Configuration` record bound from `application.yml`.
`HikariDataSource` implements `AutoCloseable`, so Tiko drains the pool
at container shutdown automatically.

## REQUEST-scoped Connection + auto-proxy

```java
@Component(scope = Scope.REQUEST)
public class JdbcConnectionProvider {
    private final DataSource ds;

    @Inject JdbcConnectionProvider(DataSource ds) { this.ds = ds; }

    @Produces(scope = Scope.REQUEST)
    public Connection connection() throws SQLException {
        var c = ds.getConnection();
        c.setAutoCommit(false);
        return c;
    }
}
```

The interesting part — repositories inject `Connection` directly:

```java
@Component(scope = Scope.SINGLETON)
public class OrderRepository {
    private final Connection connection;   // ← proxy

    @Inject OrderRepository(Connection connection) { this.connection = connection; }

    public Optional<Order> findById(UUID id) throws SQLException {
        try (var ps = connection.prepareStatement("SELECT ...")) { ... }
    }
}
```

`java.sql.Connection` is an interface. The Tiko annotation processor
notices that a SINGLETON consumer wants a REQUEST-scoped bean, and
generates a per-method delegating proxy. Every call on the proxy
resolves to the current REQUEST scope's `Connection`. The repository
looks like it captured a connection at construction time; it didn't,
and that's the point.

If you call repository methods outside an active REQUEST scope, the
proxy fails with a scope-resolution error — the right behaviour: you
asked for a request-scoped resource without an open request.

## TransactionContext + decorator

Commit/rollback responsibility lives in a tiny REQUEST-scoped bean:

```java
@Component(scope = Scope.REQUEST)
public class TransactionContext implements AutoCloseable {
    private final Connection connection;
    private boolean committed = false;
    private boolean rolledBack = false;

    @Inject TransactionContext(Connection connection) { this.connection = connection; }

    public void commit() throws SQLException { connection.commit(); committed = true; }
    public void rollback() throws SQLException { connection.rollback(); rolledBack = true; }

    @Override public void close() throws SQLException {
        if (!committed && !rolledBack) connection.rollback();
        // Tiko's implicit-AutoCloseable on the @Produces Connection returns it to the pool.
    }
}
```

The `committed`/`rolledBack` flags are the safety net: if handler code
forgets to commit, scope teardown rolls back rather than silently
leaving the transaction dangling.

The intended commit path is a thin static helper:

```java
public final class TransactionalScope {
    public static <T> T run(Container container, Supplier<T> work) {
        return container.supplyInRequestScope(() -> {
            var tx = container.get(TransactionContext.class);
            try {
                T result = work.get();
                tx.commit();
                return result;
            } catch (RuntimeException e) {
                rollbackQuietly(tx, e); throw e;
            } catch (Throwable t) {
                rollbackQuietly(tx, t); throw new RuntimeException(t);
            }
        });
    }
}
```

Why a utility instead of a Javalin-specific decorator: this generalises
across transports. The batch entry uses the same `run(...)`.

## HTTP single-request flow

```java
app.post("/orders", ctx -> TransactionalScope.run(container, () -> {
    routes.handleCreate(ctx);
    return null;
}));
```

One HTTP request = one REQUEST scope = one transaction. REQUEST and
EVENT collapse to the same lifetime here — there's no batching, just
one operation per request. The route handler does its work via
auto-proxied repositories; commit happens on success, rollback on any
thrown exception.

## Batch flow — where REQUEST and EVENT do different jobs

```java
TransactionalScope.run(container, () -> {
    var repo = container.get(OrderRepository.class);
    for (Order o : orders) {
        container.runInEventScope(() -> {
            var ctx = container.get(CurrentOrderContext.class);
            ctx.setOrderId(o.id());
            repo.insert(o);
        });
    }
    return orders.size();
});
```

**One REQUEST → one transaction → N EVENT scopes inside.** The
distinction earns its keep here:

- The `Connection` is REQUEST-scoped, so all N inserts run on the same
  connection in one transaction. Either every order commits, or none of
  them do.
- `CurrentOrderContext` is EVENT-scoped, so each iteration gets its own
  instance with its own `orderId`. The `BatchAuditLogger` SINGLETON
  injects a `CurrentOrder` proxy and reads the current iteration's id —
  no parameter threading.

This is also the first place in the examples tree where auto-proxy is
shown on an **EVENT-scoped** bean (REQUEST-scoped auto-proxy was already
shown in this cookbook's repository pattern). The same processor
mechanism handles both.

## Async handlers + explicit REQUEST scope

`@EventHandler(async = true)` runs on Tiko's framework executor — a
different thread, no enclosing REQUEST scope. If the async handler
needs to touch the DB, it opens its own:

```java
@EventHandler(async = true)
public void onSomeEvent(SomeEvent e) {
    TransactionalScope.run(container, () -> {
        // persistence work — gets its own connection + transaction
        return null;
    });
}
```

No auto-elevation. This matches Tiko's "no runtime magic" positioning:
the transaction boundary is visible at the call site, not implied by
ambient state.

## Simplifications this cookbook makes

- **Schema management** — `src/main/resources/schema.sql` loaded by a
  `@PostConstruct` runner. Production should use **Flyway** or
  **Liquibase**.
- **Test database** — H2 in-memory with `MODE=PostgreSQL`. Production
  tests should use **Testcontainers PostgreSQL** for prod-like
  semantics (H2 covers most basics but not every PG-ism).
- **No connection-leak diagnostics** beyond what HikariCP gives you out
  of the box. Production setups configure `leakDetectionThreshold`.
- **No metrics** beyond Tiko's built-in `RequestStartedEvent` /
  `RequestEndingEvent`. Wire Micrometer or your metrics library of
  choice to those events.

## Beyond raw JDBC

For higher-level abstractions on top of the wiring this cookbook
teaches, the recommended pointers are:

- **[JOOQ](https://www.jooq.org/)** — type-safe SQL DSL with generated
  code. Strongest philosophical neighbor for Tiko: compile-time +
  generated, no runtime reflection. Trade-off: needs a Maven codegen
  step.
- **[JDBI 3](https://jdbi.org/)** — annotation-driven SQL mapper,
  lighter than full ORM. Trade-off: runtime reflection on mapper
  interfaces.
- **[Hibernate](https://hibernate.org/)** — full ORM. The most popular
  Java persistence library. Trade-off: reflection-heavy, the most
  distant fit for Tiko's "no runtime reflection" positioning. Included
  as a pointer because it's the dominant choice, not as a
  recommendation.

Whatever you pick, the wiring stays the same shape: a SINGLETON
`@Produces` factory for the connection/session source, a REQUEST-scoped
`@Produces` for the per-request handle, an auto-proxied interface
injected into SINGLETON repositories.
```

- [ ] **Step 3: Verify Spotless gate still clean**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" spotless:check`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Commit**

```bash
git add docs/cookbooks/
git commit -m "docs(cookbooks): persistence (raw JDBC + HikariCP) + cookbook index"
```

---

## Task 19: Final reactor build + roadmap entry + push + PR

**Files:**
- Modify: `docs/roadmap.md` — add a "What ships today" entry.

- [ ] **Step 1: Modify `docs/roadmap.md`**

In `docs/roadmap.md`, in the `## What ships today` block, AFTER the existing entries, add:

```markdown
- ✅ Persistence cookbook + example — `docs/cookbooks/persistence.md` paired with `tiko-examples/10_persistence_jdbc/`. REQUEST-scoped JDBC transactions across HTTP and batch flows; first cookbook in the cookbook track for friction points Tiko deliberately doesn't ship.
```

- [ ] **Step 2: Run the full reactor build**

Run: `W:/tools/apache-maven/bin/mvn -pl "!tiko-bom" install`
Expected: `BUILD SUCCESS`. Reactor summary includes `10 - Persistence (raw JDBC + HikariCP)`.

- [ ] **Step 3: Confirm working tree clean**

Run: `git status`
Expected: nothing to commit.

- [ ] **Step 4: Commit the roadmap edit (if there were uncommitted changes from Step 1)**

```bash
git add docs/roadmap.md
git commit -m "docs(roadmap): persistence cookbook + example shipped"
```

- [ ] **Step 5: Push the branch**

```bash
git push -u origin feat/persistence-cookbook
```

- [ ] **Step 6: Open the PR**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr create \
    --title "feat(examples): persistence cookbook + 10_persistence_jdbc" \
    --body "$(cat <<'EOF'
## Summary

First entry in the cookbook track for friction points Tiko deliberately
doesn't ship (spec at
`docs/superpowers/specs/2026-05-15-persistence-cookbook-jdbc-design.md`,
plan at `docs/superpowers/plans/2026-05-15-persistence-cookbook-jdbc.md`).

Ships `tiko-examples/10_persistence_jdbc/` — REQUEST-scoped JDBC
transactions wrapping both an HTTP entry point and a batch flow with
shared repositories — paired with `docs/cookbooks/persistence.md` and
the new `docs/cookbooks/README.md` index.

### Key pieces

- **`TransactionalScope.run(container, work)`** — opens a REQUEST scope,
  commits on success, rolls back on exception. Both HTTP and batch use
  it.
- **`OrderRepository` (SINGLETON) injects `Connection` directly** —
  Tiko's annotation processor generates an auto-proxy on the
  `java.sql.Connection` interface that resolves per-method to the
  current REQUEST scope's connection.
- **Batch flow demonstrates REQUEST vs EVENT scope concretely** — one
  REQUEST scope wraps N EVENT scopes; all N inserts commit together or
  all roll back together; a SINGLETON `BatchAuditLogger` injects an
  EVENT-scoped `CurrentOrder` via auto-proxy.
- **Rollback test is cross-connection** — every assertion about
  committed-vs-rolled-back state queries via a separate JDBC connection
  outside Tiko, so the assertion can't be fooled by transaction-local
  visibility.

### Test plan

- [x] `TransactionContextTest` — commit/rollback semantics against real H2.
- [x] `OrderRepositoryTest` — insert/find + cross-connection commit verification.
- [x] `HttpEntryIT` — POST happy + mid-transaction-failure rollback + GET happy + 404.
- [x] `BatchEntryIT` — all-success + auto-proxy audit assertion + all-or-none poison-record rollback.
- [x] Full reactor `mvn -pl '!tiko-bom' install` green.
- [x] Spotless gate clean.
EOF
)"
```

- [ ] **Step 7: Watch CI**

```bash
"C:/Program Files/GitHub CLI/gh.exe" pr checks --watch
```

Expected: all checks pass. If any fail, diagnose the specific failure (most likely Spotless formatting — fix with `mvn -pl '!tiko-bom' spotless:apply` and push again).

- [ ] **Step 8: Hand off for manual merge**

Per project policy (branch protection), the user merges in the GitHub UI. After confirmation:

```bash
git checkout main
git pull --ff-only
git branch -d feat/persistence-cookbook
git fetch --prune origin
```

---

## Done

`10_persistence_jdbc` builds, runs (HTTP + batch), passes 12 tests, and is
documented in `docs/cookbooks/persistence.md` with `docs/cookbooks/README.md`
as the cookbook track index. The first cookbook is shipped; subsequent
cookbooks (security, resilience, Kafka surfacing, Non-goals meta-doc)
follow the same shape: docs page + paired numbered example.
