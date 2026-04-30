# allprojects-reactor

`allprojects-reactor` is a Maven build extension that makes all discovered reactor projects available for workspace artifact resolution, even when `-pl` restricts the set of projects that Maven executes.

## Problem

In a large multi-module build, a common workflow is:

```sh
mvn package -pl app -am -DskipTests
mvn test -pl app
```

The first command builds `app` and its upstream dependencies, producing JARs and class directories under each module's `target/` directory.

The second command intends to run only `app` tests. However, Maven's built-in reactor workspace reader only indexes `session.getProjects()`, which is the selected execution set after `-pl` filtering. Without `-am`, upstream modules are not in that set, so Maven does not resolve them from the reactor/workspace. It falls back to the local and remote repositories instead.

That means `mvn test -pl app` can fail with unresolved in-repo dependencies unless those dependencies were installed, even though their JARs or classes were just built in `target/`.

Using `-am` on the second command works, but it also executes lifecycle phases for upstream modules. In large projects this can be expensive, especially when it runs tests for modules that are only needed as dependencies.

## Solution

This extension contributes an additional `MavenWorkspaceReader` component.

The built-in Maven reactor reader indexes:

```text
session.getProjects()
```

This extension indexes:

```text
session.getAllProjects()
```

That separates the two concepts:

```text
projects selected for execution: session.getProjects()
projects available for workspace resolution: session.getAllProjects()
```

With the extension enabled, this workflow becomes possible:

```sh
mvn package -pl app -am -DskipTests
mvn test -pl app
```

The second command executes only `app`, but dependencies that are part of the same multi-module checkout can resolve from their previously built `target/` outputs.

## Usage

Install or publish the extension artifact:

```sh
mvn install
```

Then add it to the root project's `.mvn/extensions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>ca.vanzyl.maven</groupId>
    <artifactId>allprojects-reactor</artifactId>
    <version>&lt;latest&gt;</version>
  </extension>
</extensions>
```

If the project already has extensions, add this as another `<extension>` entry.

## Why This Helps Large Projects

Large builds often have many upstream modules. Rebuilding or retesting all of them just to run tests in one selected module wastes time when the upstream outputs already exist.

This extension is useful when you want to:

- Build a dependency closure once with `-am`.
- Iterate on tests in one selected module with `-pl`.
- Avoid installing internal snapshot artifacts into the local repository.
- Avoid running tests and other lifecycle work in upstream modules during focused test runs.
- Keep dependency resolution tied to the checkout instead of remote repositories.

## Things To Watch For

This extension intentionally makes Maven willing to use existing workspace outputs from projects that are not being executed. That is useful, but it has tradeoffs.

- Stale outputs: if an upstream module's sources changed after the last build, `mvn test -pl app` may use stale `target/classes` or stale `target/*.jar` files.
- Missing outputs: if an upstream module has not been compiled or packaged yet, resolution may still fail.
- Generated sources/resources: modules that require lifecycle steps to generate classes or resources still need those steps run before they can be consumed.
- Attached artifacts/classifiers: the extension looks for attached artifacts known to the Maven project and previously packaged classifier files such as `target/${finalName}-tests.jar`. Unusual packaging or custom plugin behavior may require more handling.
- Build reproducibility: this is best for local developer iteration. CI should usually run a complete, explicit build graph so it does not depend on previous `target/` contents.
- Local repo confusion: if matching artifacts exist in the local repository, Maven may resolve from there depending on workspace reader ordering and artifact availability. For testing this behavior, use a clean local repo or verify the target artifacts are absent.
- Loose class fallback: by default, the extension can resolve main JAR artifacts from `target/classes` and test artifacts from `target/test-classes` when packaged artifacts are missing. Disable this with `-Dallprojects-reactor.resolveClasses=false` if you only want packaged artifacts to be used.

## Current Behavior

The extension resolves:

- POM artifacts from module `pom.xml` files.
- Packaged artifacts from `target/${finalName}.${extension}` when present.
- Packaged classified artifacts from `target/${finalName}-${classifier}.${extension}` when present.
- Main JAR-like artifacts from `target/classes` when present.
- Test artifacts from `target/test-classes` when present.

The extension does not execute upstream modules. It only makes their existing outputs available to Maven's dependency resolution.
