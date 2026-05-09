# Tiko DI - BOM (Bill of Materials)

This module provides centralized dependency management for **external users** of the Tiko DI framework.

## What is a BOM?

A Bill of Materials (BOM) is a Maven POM that provides a centralized place to manage dependency versions. It allows users to import all Tiko DI dependencies without specifying individual versions.

## Architecture Note

⚠️ **Important:** The BOM is a **standalone module** designed for external consumption. It does NOT have a parent relationship with `tiko-parent` to avoid circular dependencies during the build.

- **Internal modules** (tiko-api, tiko-processor, etc.) inherit dependency versions from `tiko-parent`
- **External projects** import `tiko-bom` to get consistent dependency versions
- Versions are kept in sync manually between `tiko-parent/pom.xml` and `tiko-bom/pom.xml`

## Usage

### In Your Project

Add the Tiko BOM to your project's `dependencyManagement` section:

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-bom</artifactId>
            <version>0.1.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

Then add dependencies without specifying versions:

```xml
<dependencies>
    <!-- Core Tiko DI -->
    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-api</artifactId>
        <!-- Version managed by BOM -->
    </dependency>

    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-processor</artifactId>
        <scope>provided</scope>
        <!-- Version managed by BOM -->
    </dependency>

    <dependency>
        <groupId>io.tiko</groupId>
        <artifactId>tiko-runtime</artifactId>
        <!-- Version managed by BOM -->
    </dependency>

</dependencies>
```

## Benefits

1. **Version Consistency** - All Tiko DI modules guaranteed to be compatible
2. **Simplified Dependency Management** - No need to specify versions for each dependency
3. **Easy Upgrades** - Update one BOM version to upgrade all Tiko modules
4. **Third-party Dependencies** - BOM also manages versions of libraries used by Tiko (SLF4J, etc.)

## Managed Dependencies

The BOM manages versions for:

### Tiko DI Modules
- `tiko-api` - Core annotations and interfaces
- `tiko-processor` - Annotation processor
- `tiko-runtime` - Runtime container (includes the in-memory `LocalEventBus`)
- `tiko-config` - YAML-backed configuration injection

### Third-party Dependencies
- Google Auto Service - Annotation processor registration
- JavaPoet - Code generation
- SLF4J - Logging facade
- JUnit Jupiter - Testing framework
- AssertJ - Fluent assertions
- Mockito - Mocking framework

## Example: Minimal Tiko DI Project

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <version>0.1.0</version>

    <properties>
        <maven.compiler.source>17</maven.compiler.source>
        <maven.compiler.target>17</maven.compiler.target>
    </properties>

    <dependencyManagement>
        <dependencies>
            <!-- Import Tiko BOM -->
            <dependency>
                <groupId>io.tiko</groupId>
                <artifactId>tiko-bom</artifactId>
                <version>0.1.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <!-- No versions needed! -->
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-processor</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>io.tiko</groupId>
            <artifactId>tiko-runtime</artifactId>
        </dependency>
    </dependencies>
</project>
```

## For Tiko DI Developers

**The BOM is NOT used internally by Tiko DI modules.** Instead:

- `tiko-parent/pom.xml` manages dependency versions for all internal modules
- `tiko-bom/pom.xml` is a **standalone artifact** for external users

### When Adding a New Dependency

1. Add the version property to `tiko-parent/pom.xml` `<properties>` section
2. Add the dependency to `tiko-parent/pom.xml` `<dependencyManagement>` section
3. **Also update** `tiko-bom/pom.xml` with the same version
4. Internal modules inherit from parent; external users import the BOM

### Version Synchronization

⚠️ **Important:** Keep versions synchronized between:
- `tiko-parent/pom.xml` (used internally)
- `tiko-bom/pom.xml` (used by external projects)

The BOM has its own version properties to avoid circular dependency issues:

```xml
<!-- tiko-bom/pom.xml -->
<properties>
    <tiko.version>0.1.0</tiko.version>
    <auto-service.version>1.1.1</auto-service.version>
    <!-- ... etc -->
</properties>
```

### Why This Design?

This pattern (separate BOM for external use) is used by:
- **Spring Boot** - `spring-boot-dependencies` BOM is standalone
- **Jackson** - `jackson-bom` is standalone
- **Micronaut** - `micronaut-bom` is standalone

It avoids the circular dependency:
```
❌ tiko-parent → imports tiko-bom → has parent tiko-parent (circular!)
✅ tiko-parent → manages deps internally
✅ tiko-bom → standalone, used by external projects only
```
