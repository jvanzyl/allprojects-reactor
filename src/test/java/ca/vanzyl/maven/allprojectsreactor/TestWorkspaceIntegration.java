/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ca.vanzyl.maven.allprojectsreactor;

import io.takari.maven.testing.TestProperties;
import io.takari.maven.testing.TestResources5;
import io.takari.maven.testing.executor.MavenExecution;
import io.takari.maven.testing.executor.MavenExecutionResult;
import io.takari.maven.testing.executor.MavenRuntime;
import io.takari.maven.testing.executor.MavenRuntime.MavenRuntimeBuilder;
import io.takari.maven.testing.executor.MavenVersions;
import io.takari.maven.testing.executor.junit.MavenPluginTest;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import static org.assertj.core.api.Assertions.assertThat;

@MavenVersions({"3.9.11", "3.9.16"})
class TestWorkspaceIntegration
{
    private static final String REQUIRE_PACKAGED = "-DreactorRequirePackaged";
    private static final String APPLICATION_SOURCE = "application/src/main/java/example/Application.java";
    private static final String REPOSITORY_GROUP = "ca/vanzyl/maven/allprojectsreactor/it";

    @RegisterExtension
    final TestResources5 resources = new TestResources5();

    private final MavenRuntime maven;
    private final MavenRuntime mavenWithoutExtension;

    TestWorkspaceIntegration(MavenRuntimeBuilder builder)
            throws Exception
    {
        builder.withCliOptions("-B", "-DskipTests", "-Dmaven.compiler.release=" + System.getProperty("java.specification.version"));
        mavenWithoutExtension = builder.build();
        maven = builder.withExtensions(new TestProperties().getRuntimeClasspath())
                .build();
    }

    @MavenPluginTest
    void testNewerClassesWithoutInstalling()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        MavenExecution build = maven(project);
        assertSuccess(build.execute("package", "-pl", "library", "-am"));

        updateMainLibrary(project);
        assertSuccess(build.execute("compile", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application"));

        assertNoArtifactsInstalled(project);
    }

    @MavenPluginTest
    void testNewerTestClassesWithoutInstalling()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        useLibrary(project, "example.TestLibrary");
        MavenExecution build = maven(project).withCliOptions("-Ddependency.type=test-jar", "-Ddependency.classifier=tests");
        assertSuccess(build.execute("package", "-pl", "library", "-am"));

        updateTestLibrary(project);
        assertSuccess(build.execute("test-compile", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application"));

        assertNoArtifactsInstalled(project);
    }

    @MavenPluginTest
    void testPackagedOnlyRejectsStaleJarDespiteInstalledSnapshot()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        MavenExecution build = maven(project);
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertInstalledJar(project, "");
        assertSuccess(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED));

        updateMainLibrary(project);
        assertSuccess(build.execute("compile", "-pl", "library"));
        assertPackagingRequired(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED), "requires a current packaged file");
        assertSuccess(build.execute("compile", "-pl", "application"));

        assertSuccess(build.execute("package", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED));
    }

    @MavenPluginTest
    void testPackagedOnlyRejectsStaleTestJarDespiteInstalledSnapshot()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        useLibrary(project, "example.TestLibrary");
        MavenExecution build = maven(project).withCliOptions("-Ddependency.type=test-jar", "-Ddependency.classifier=tests");
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertInstalledJar(project, "-tests");
        assertSuccess(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED));

        updateTestLibrary(project);
        assertSuccess(build.execute("test-compile", "-pl", "library"));
        assertPackagingRequired(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED), "requires a current packaged file");

        assertSuccess(build.execute("package", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED));
    }

    @MavenPluginTest
    void testPackagedOnlyRejectsMissingJarDespiteInstalledSnapshot()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        MavenExecution build = maven(project);
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertInstalledJar(project, "");
        assertSuccess(build.execute("clean", "-pl", "library"));

        assertPackagingRequired(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED), "requires a current packaged file");
    }

    @MavenPluginTest
    void testPackagedOnlyAllowsMavenToResolveActiveReactorClasses()
            throws Exception
    {
        File project = resources.getBasedir("reactor");

        assertSuccess(maven(project).execute("clean", "compile", "-pl", "application", "-am", REQUIRE_PACKAGED));

        assertApplicationCompiled(project);
        assertThat(workspaceJar(project, "")).doesNotExist();
        assertNoArtifactsInstalled(project);
    }

    @MavenPluginTest
    void testStaleShadedJarRequiresRepackaging()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        useLibrary(project, "relocated.Library");
        MavenExecution build = maven(project).withCliOption("-Pshade");
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertRelocated(workspaceJar(project, ""), "Library");
        assertRelocated(installedJar(project, ""), "Library");
        assertSuccess(build.execute("compile", "-pl", "application"));

        updateMainLibrary(project);
        assertSuccess(build.execute("compile", "-pl", "library"));
        assertPackagingRequired(build.execute("compile", "-pl", "application"), "Shaded workspace artifact");
        assertPackagingRequired(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED), "Shaded workspace artifact");

        assertSuccess(build.execute("package", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application"));
    }

    @MavenPluginTest
    void testMissingShadedJarDoesNotUseInstalledSnapshot()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        useLibrary(project, "relocated.Library");
        MavenExecution build = maven(project).withCliOption("-Pshade");
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertRelocated(installedJar(project, ""), "Library");
        Files.delete(workspaceJar(project, ""));

        assertPackagingRequired(build.execute("compile", "-pl", "application"), "Shaded workspace artifact");
        assertPackagingRequired(build.execute("compile", "-pl", "application", REQUIRE_PACKAGED), "Shaded workspace artifact");

        // Without the extension's guard, Maven silently accepts the installed copy.
        assertSuccess(mavenWithoutExtension.forProject(project)
                .withCliOptions("-Pshade", "-Dmaven.repo.local=" + localRepository(project))
                .execute("compile", "-pl", "application"));
    }

    @MavenPluginTest
    void testStaleAttachedShadedJarRequiresRepackaging()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        useLibrary(project, "relocated.Library");
        MavenExecution build = maven(project).withCliOptions("-Pshade", "-Dshade.attached=true", "-Ddependency.classifier=shaded");
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertRelocated(workspaceJar(project, "-shaded"), "Library");
        assertRelocated(installedJar(project, "-shaded"), "Library");
        assertSuccess(build.execute("compile", "-pl", "application"));

        updateMainLibrary(project);
        assertSuccess(build.execute("compile", "-pl", "library"));
        assertPackagingRequired(build.execute("compile", "-pl", "application"), "Shaded workspace artifact");

        assertSuccess(build.execute("package", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application"));
    }

    @MavenPluginTest
    void testStaleShadedTestJarRequiresRepackaging()
            throws Exception
    {
        File project = resources.getBasedir("reactor");
        useLibrary(project, "relocated.TestLibrary");
        MavenExecution build = maven(project).withCliOptions("-Pshade", "-Ddependency.type=test-jar", "-Ddependency.classifier=tests");
        assertSuccess(build.execute("install", "-pl", "library", "-am"));
        assertRelocated(workspaceJar(project, "-tests"), "TestLibrary");
        assertRelocated(installedJar(project, "-tests"), "TestLibrary");
        assertSuccess(build.execute("compile", "-pl", "application"));

        updateTestLibrary(project);
        assertSuccess(build.execute("test-compile", "-pl", "library"));
        assertPackagingRequired(build.execute("compile", "-pl", "application"), "Shaded workspace artifact");

        assertSuccess(build.execute("package", "-pl", "library"));
        assertSuccess(build.execute("compile", "-pl", "application"));
    }

    /// Configures Maven to use this fixture's isolated repository, including deliberately installed stale snapshots.
    private MavenExecution maven(File project)
    {
        return maven.forProject(project)
                .withCliOption("-Dmaven.repo.local=" + localRepository(project));
    }

    /// Changes the application to use the requested main, test, or relocated library class.
    private static void useLibrary(File project, String className)
            throws IOException
    {
        Path source = project.toPath().resolve(APPLICATION_SOURCE);
        Files.writeString(source, Files.readString(source).replace("example.Library", className));
    }

    /// Changes the main library API so the application needs newly compiled classes.
    private static void updateMainLibrary(File project)
            throws IOException
    {
        updateLibrary(project, "main", "Library");
    }

    /// Changes the test library API so the application needs newly compiled test classes.
    private static void updateTestLibrary(File project)
            throws IOException
    {
        updateLibrary(project, "test", "TestLibrary");
    }

    /// Changes the library API and its caller, deletes the old class, and backdates archives for the next build.
    private static void updateLibrary(File project, String sourceSet, String className)
            throws IOException
    {
        Path source = project.toPath().resolve("library/src/" + sourceSet + "/java/example/" + className + ".java");
        Files.writeString(source, Files.readString(source).replace("value()", "updatedValue()"));
        Path application = project.toPath().resolve(APPLICATION_SOURCE);
        Files.writeString(application, Files.readString(application).replace(".value()", ".updatedValue()"));
        // Force recompilation even on filesystems with coarse timestamp resolution.
        String output = sourceSet.equals("main") ? "classes" : "test-classes";
        Files.delete(project.toPath().resolve("library/target/" + output + "/example/" + className + ".class"));
        makeArchivesOlder(project);
    }

    /// Backdates packaged archives so recompiled output is newer without sleeps or future timestamps.
    private static void makeArchivesOlder(File project)
            throws IOException
    {
        try (var files = Files.list(project.toPath().resolve("library/target"))) {
            for (Path archive : files.filter(path -> path.toString().endsWith(".jar")).toList()) {
                Files.setLastModifiedTime(archive, FileTime.fromMillis(1000));
            }
        }
    }

    /// Locates the repository used only by this fixture's Maven builds.
    private static Path localRepository(File project)
    {
        return project.toPath().resolve("repository");
    }

    /// Locates a library JAR in the workspace; the suffix selects the main or a classified artifact.
    private static Path workspaceJar(File project, String suffix)
    {
        return project.toPath().resolve("library/target/library-1-SNAPSHOT" + suffix + ".jar");
    }

    /// Locates the corresponding library snapshot in the fixture's isolated local repository.
    private static Path installedJar(File project, String suffix)
    {
        return localRepository(project).resolve(REPOSITORY_GROUP + "/library/1-SNAPSHOT/library-1-SNAPSHOT" + suffix + ".jar");
    }

    /// Verifies that the reactor's group directory does not exist in the repository, proving nothing was installed.
    private static void assertNoArtifactsInstalled(File project)
    {
        assertThat(localRepository(project).resolve(REPOSITORY_GROUP)).doesNotExist();
    }

    /// Verifies that an installed snapshot exists for Maven to fall back to.
    private static void assertInstalledJar(File project, String suffix)
    {
        assertThat(installedJar(project, suffix)).isRegularFile();
    }

    /// Verifies that the application build produced its class file.
    private static void assertApplicationCompiled(File project)
    {
        assertThat(project.toPath().resolve("application/target/classes/example/Application.class")).isRegularFile();
    }

    /// Verifies that Shade relocated the class and removed its original package entry from the JAR.
    private static void assertRelocated(Path archive, String className)
            throws IOException
    {
        assertThat(archive).isRegularFile();
        try (JarFile jar = new JarFile(archive.toFile())) {
            assertThat(jar.stream().map(ZipEntry::getName))
                    .contains("relocated/" + className + ".class")
                    .doesNotContain("example/" + className + ".class");
        }
    }

    /// Requires Maven to report a successful build with no errors.
    private static void assertSuccess(MavenExecutionResult result)
    {
        assertThat(String.join("\n", result.getLog()))
                .contains("BUILD SUCCESS")
                .doesNotContain("[ERROR]");
    }

    /// Requires the expected repackaging error so an unrelated build failure cannot satisfy the test.
    private static void assertPackagingRequired(MavenExecutionResult result, String message)
    {
        assertThat(String.join("\n", result.getLog()))
                .contains("[ERROR]", message, "mvn package -pl :library -am")
                .doesNotContain("BUILD SUCCESS");
    }
}
