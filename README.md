# allprojects-reactor

`allprojects-reactor` is a Maven build extension that lets Maven use existing build outputs from other modules in the same checkout, even when `-pl` limits the build to specific modules.

## Problem

In a large multi-module build, a common workflow is:

```sh
mvn package -pl app -am -DskipTests
mvn test -pl app
```

The first command builds `app` and its upstream dependencies, producing JARs and class directories under each module's `target/` directory.

The second command runs only `app` tests. By default, Maven uses workspace dependencies only from modules selected for the current build. Without `-am`, the upstream modules are not selected, so Maven looks for their artifacts in the local and remote repositories instead of using their existing `target/` outputs.

That means `mvn test -pl app` can fail with unresolved in-repo dependencies unless those dependencies were installed, even though their JARs or classes were just built in `target/`.

Using `-am` on the second command works, but it also executes lifecycle phases for upstream modules. In large builds this can be expensive and is unnecessary work when those modules are unchanged, especially when it runs tests for modules that are only needed as dependencies.

Installing upstream modules introduces another problem when worktrees share a local Maven repository. If the branches use the same snapshot versions, installing from one worktree can overwrite artifacts used by a build in another worktree, causing it to use code from the wrong branch.

## Solution

The extension lets Maven use existing JARs and compiled classes from modules excluded by `-pl`. Those modules supply dependencies without being rebuilt or having their tests run.

With the extension enabled, this workflow becomes possible:

```sh
mvn package -pl app -am -DskipTests
mvn test -pl app
```

The second command runs tests only for `app`, using its upstream modules' existing `target/` outputs. This lets you iterate on `app` without rebuilding or installing its unchanged dependencies.

## Usage

Install the extension from this checkout:

```sh
mvn install
```

Then add it to the root project's `.mvn/extensions.xml`, replacing `VERSION` with the extension version you installed:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
  <extension>
    <groupId>ca.vanzyl.maven</groupId>
    <artifactId>allprojects-reactor</artifactId>
    <version>VERSION</version>
  </extension>
</extensions>
```

If the project already has extensions, add this as another `<extension>` entry.

## Requiring Packaged Artifacts

By default, the extension can use compiled classes directly from `target/classes` or `target/test-classes` for ordinary JAR dependencies. Those modules do not need to be packaged first.

Set `-DreactorRequirePackaged` when a dependency requires additional processing during packaging. For example, a build might modify bytecode, merge resources, or bundle other libraries into the JAR. The compiled output directories may not contain the results of those steps.

With this option, the extension protects your build by checking for missing or stale packaged artifacts. If an artifact is missing or older than its compiled output, the build stops and tells you to run `package` for that module. This prevents the build from silently using compiled classes that lack packaging changes or an older copy from the local repository.

POM dependencies are read directly from the module's `pom.xml` and do not need packaging.

For example, package the upstream modules before running tests for `app`:

```sh
mvn package -pl app -am -DskipTests
mvn test -pl app -DreactorRequirePackaged
```

After recompiling an upstream module, package it again before running the second command. The freshness check compares timestamps of compiled output and packaged artifacts. It does not detect source edits that have not been compiled or changes to libraries bundled into an artifact.

For dependencies shaded by the Maven Shade plugin, these checks run automatically whenever compiled output is present. If a shaded JAR is missing or stale, the build stops and tells you to repackage it rather than using compiled classes that lack the shading changes.

Maven can preserve a JAR's modification time when repackaging produces identical contents. Recompiling unchanged sources can therefore make a current JAR appear stale. If the build still reports a stale artifact after `package`, use `clean package` for that module and its dependencies, for example `mvn clean package -pl :<module> -am -DskipTests`.

When you use `-pl` without `-am`, these checks protect dependencies from upstream modules left out of the build. Modules included in the build can still be resolved by Maven's own reactor reader, which runs before this extension and can use compiled classes even with `-DreactorRequirePackaged`.

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

- Stale outputs: if an upstream module's sources changed after the last build, `mvn test -pl app` may use stale `target/` outputs. By default, compiled output with a newer modification time than its packaged artifact takes precedence. This checks output files and directories, not source freshness; upstream modules still need to be compiled after source changes.
- Missing outputs: if an upstream module has not been compiled or packaged yet, resolution may still fail.
- Generated sources/resources: modules that require lifecycle steps to generate classes or resources still need those steps run before they can be consumed.
- Attached artifacts/classifiers: the extension looks for attached artifacts known to the Maven project and previously packaged classifier files such as `target/${finalName}-tests.jar`. Unusual packaging or custom plugin behavior may require more handling.
- Build reproducibility: this is best for local developer iteration. CI should usually run a complete, explicit build graph so it does not depend on previous `target/` contents.
- Local repo confusion: if matching artifacts exist in the local repository, Maven may resolve from there depending on workspace reader ordering and artifact availability. For testing this behavior, use a clean local repo or verify the target artifacts are absent.
- Archives before `package`: non-classpath artifacts such as `tar.gz` or `zip` distributions only exist once their module has run `package`. A reactor dependency on one (for example the `provided` dependencies the provisio plugin adds for build ordering) cannot be resolved by `test-compile`, with or without this extension.
- Using compiled classes directly: by default, the extension can resolve main JAR artifacts from `target/classes` and test artifacts from `target/test-classes` when packaged artifacts are missing or older than the compiled output. Packaged artifacts remain preferred when they are at least as new as the output. Use `-DreactorRequirePackaged` to require packaged files and reject missing or stale workspace archives, as described in [Requiring Packaged Artifacts](#requiring-packaged-artifacts).
- Shaded artifacts: for shaded main and test JARs produced by a configured `maven-shade-plugin` execution, compiled output cannot substitute for shading. If that output is newer than the archive, or the archive is missing, workspace resolution fails with a message to run `mvn package -pl :<module> -am`. This prevents falling back to an installed copy. If repackaging does not clear the error, use `clean package` as described in [Requiring Packaged Artifacts](#requiring-packaged-artifacts). The check covers the module's own output timestamps; changes to bundled dependencies and other packaging plugins are not detected.

## Current Behavior

The extension resolves:

- POM artifacts from module `pom.xml` files.
- Packaged artifacts from `target/${finalName}.${extension}` when present.
- Packaged classified artifacts from `target/${finalName}-${classifier}.${extension}` when present.
- Main JAR-like artifacts from `target/classes` when present.
- Test artifacts from `target/test-classes` when present.

The extension does not execute upstream modules. It only makes their existing outputs available to Maven's dependency resolution.

## Testing

Run `mvn verify` to run the unit tests and Maven integration tests. The integration tests use Takari's test harness with Maven 3.9.11 and 3.9.16, and keep fixture projects and isolated local repositories under `target/test-projects`.

The fixtures run the Maven Shade plugin to verify relocated main, attached, and test JARs. They also cover newer compiled classes, packaged-only resolution, stale or missing archives despite installed snapshots, recovery after repackaging, and Maven's own reactor reader handling active modules.
