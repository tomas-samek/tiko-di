---
paths:
  - "**/pom.xml"
---

# Maven dependencies

## Adding Dependencies

When introducing a new third-party dependency, look up the latest stable version
on Maven Central (or `mvnrepository.com`) before writing the `<version>` into
the pom. Don't copy a version from the first Stack Overflow result or a sibling
project — those go stale.

- Pin the version in `tiko-bom/pom.xml` and the root `pom.xml`
  `<dependencyManagement>` (the project is BOM-managed).
- Confirm the artifact's license is compatible (Apache 2.0, MIT, BSD, EPL all
  fine; LGPL/GPL/SSPL need a separate discussion).
- For existing dependencies, use `mvn versions:display-dependency-updates` to
  spot bumps, and `mvn versions:set -DprocessAllModules=true` for the bump
  itself. Don't string-replace versions in `pom.xml` by hand.
