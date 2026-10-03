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

import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.PluginManagement;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAllProjectsReactorWorkspaceReader
{
    private static final FileTime BEFORE_PACKAGE = FileTime.fromMillis(1000);
    private static final FileTime PACKAGE_TIME = FileTime.fromMillis(2000);
    private static final FileTime AFTER_PACKAGE = FileTime.fromMillis(3000);

    private static final Artifact MAIN_JAR = artifact("jar", "", "jar");
    private static final Artifact TEST_JAR = artifact("jar", "tests", "test-jar");

    @TempDir
    Path directory;

    @Test
    void testNewerMainOutputOverridesOldJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "nested/Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("nested/Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testNewerTestOutputOverridesOldTestJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("test-classes", "TestExample.class");
        archive("example-1-SNAPSHOT-tests.jar");
        Files.setLastModifiedTime(output.resolve("TestExample.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(TEST_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testDeletedOutputOverridesOldJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "nested/Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.delete(output.resolve("nested/Example.class"));
        Files.setLastModifiedTime(output.resolve("nested"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testUpToDateJarRemainsPreferred()
            throws IOException
    {
        MavenProject project = project();
        output("classes", "Example.class");
        Path archive = archive("example-1-SNAPSHOT.jar");

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testJarRemainsPreferredWhenOutputTimestampsAreEqual()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        Path archive = archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), PACKAGE_TIME);
        Files.setLastModifiedTime(output, PACKAGE_TIME);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testKnownArtifactAlsoUsesNewerOutput()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        Path archive = archive("custom.jar");
        project.getArtifact().setFile(archive.toFile());
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testPackagedOnlyModeRejectsStaleJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a current packaged file")
                .hasMessageContaining("reactorRequirePackaged")
                .hasMessageContaining("mvn package -pl :example -am");
    }

    @Test
    void testPackagedOnlyModeAcceptsCurrentJar()
            throws IOException
    {
        MavenProject project = project();
        output("classes", "Example.class");
        Path archive = archive("example-1-SNAPSHOT.jar");

        assertThat(packagedOnlyReader(project).findArtifact(MAIN_JAR))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testPackagedOnlyModeRejectsStaleTestJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("test-classes", "TestExample.class");
        archive("example-1-SNAPSHOT-tests.jar");
        Files.setLastModifiedTime(output.resolve("TestExample.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(TEST_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reactorRequirePackaged");
    }

    @Test
    void testPackagedOnlyModeRejectsStaleCustomJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT-custom.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(jar("custom")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reactorRequirePackaged");
    }

    @Test
    void testPackagedOnlyModeRejectsMissingJarWithoutCompiledOutput()
    {
        MavenProject project = project();

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a current packaged file")
                .hasMessageContaining("mvn package -pl :example -am");
    }

    @Test
    void testPackagedOnlyModeDoesNotAcceptDirectoryAsPackagedArtifact()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        project.getArtifact().setFile(output.toFile());

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a current packaged file");
    }

    @Test
    void testPackagedOnlyVersionDiscoveryOmitsMissingJar()
    {
        MavenProject project = project();

        assertThat(packagedOnlyReader(project).findVersions(MAIN_JAR)).isEmpty();
    }

    @Test
    void testPackagedOnlyVersionDiscoveryOmitsStaleJar()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(packagedOnlyReader(project).findVersions(MAIN_JAR)).isEmpty();
    }

    @Test
    void testPackagedOnlyVersionDiscoveryIncludesCurrentJar()
            throws IOException
    {
        MavenProject project = project();
        output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");

        assertThat(packagedOnlyReader(project).findVersions(MAIN_JAR))
                .containsExactly("1-SNAPSHOT");
    }

    @Test
    void testShadedVersionDiscoveryOmitsStaleJar()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of());
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findVersions(MAIN_JAR)).isEmpty();
    }

    @Test
    void testCustomClassifierKeepsArchive()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        Path archive = archive("example-1-SNAPSHOT-shaded.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(jar("shaded")))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testDistributionKeepsArchive()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");
        Path archive = archive("example-1-SNAPSHOT.tar.gz");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(artifact("tar.gz", "", "tar.gz")))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testMissingArchiveUsesClasses()
            throws IOException
    {
        MavenProject project = project();
        Path output = output("classes", "Example.class");

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testPackagedOnlyModeRejectsMissingJarWithCompiledOutput()
            throws IOException
    {
        MavenProject project = project();
        output("classes", "Example.class");

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reactorRequirePackaged")
                .hasMessageContaining("mvn package -pl :example -am");
    }

    @Test
    void testStaleShadedMainArtifactRequiresPackaging()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of());
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> reader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Shaded workspace artifact example:example:jar:1-SNAPSHOT must be repackaged")
                .hasMessageContaining("mvn package -pl :example -am");
    }

    @Test
    void testDisablingClassResolutionDoesNotAllowStaleShadedArtifact()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of());
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> packagedOnlyReader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be repackaged")
                .hasMessageContaining("mvn package -pl :example -am");
    }

    @Test
    void testMissingShadedMainArtifactRequiresPackaging()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of());
        output("classes", "Example.class");

        assertThatThrownBy(() -> reader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be repackaged");
    }

    @Test
    void testFreshShadedMainArtifactRemainsUsable()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of());
        output("classes", "Example.class");
        Path archive = archive("example-1-SNAPSHOT.jar");

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testAttachedShadedArtifactDoesNotChangeMainArtifactPolicy()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("shadedArtifactAttached", "true", "shadedClassifierName", "bundled"));
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        archive("example-1-SNAPSHOT-bundled.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
        assertThatThrownBy(() -> reader(project).findArtifact(jar("bundled")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be repackaged");
    }

    @Test
    void testShadedTestArtifactRequiresPackaging()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("shadeTestJar", "true"));
        Path output = output("test-classes", "TestExample.class");
        archive("example-1-SNAPSHOT-tests.jar");
        Files.setLastModifiedTime(output.resolve("TestExample.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> reader(project).findArtifact(TEST_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be repackaged");
    }

    @Test
    void testAttachedShadedTestArtifactRequiresPackaging()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("shadeTestJar", "true", "shadedArtifactAttached", "true"));
        Path output = output("test-classes", "TestExample.class");
        archive("example-1-SNAPSHOT-shaded-tests.jar");
        Files.setLastModifiedTime(output.resolve("TestExample.class"), AFTER_PACKAGE);
        Artifact shadedTestJar = artifact("jar", "shaded-tests", "test-jar");

        assertThatThrownBy(() -> reader(project).findArtifact(shadedTestJar))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be repackaged");
    }

    @Test
    void testSkippedShadeExecutionAllowsNewerClasses()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("skip", "true"));
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testSeparateShadeOutputFileAllowsNewerClasses()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("outputFile", "target/separate.jar"));
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testSeparateShadeFinalNameAllowsNewerClasses()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("finalName", "separate"));
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testExecutionConfigurationOverridesPluginConfiguration()
            throws IOException
    {
        MavenProject project = project();
        Plugin plugin = shade(project, Map.of("shadedArtifactAttached", "true"));
        plugin.getExecutions().getFirst().setConfiguration(configuration(Map.of("shadedArtifactAttached", "false")));
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThatThrownBy(() -> reader(project).findArtifact(MAIN_JAR))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be repackaged");
    }

    @Test
    void testUnboundShadeExecutionAllowsNewerClasses()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of()).getExecutions().getFirst().setPhase("none");
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testManagedShadePluginAllowsNewerClasses()
            throws IOException
    {
        MavenProject project = project();
        Plugin plugin = shade(project, Map.of());
        project.getBuild().getPlugins().clear();
        PluginManagement management = new PluginManagement();
        management.addPlugin(plugin);
        project.getBuild().setPluginManagement(management);
        Path output = output("classes", "Example.class");
        archive("example-1-SNAPSHOT.jar");
        Files.setLastModifiedTime(output.resolve("Example.class"), AFTER_PACKAGE);

        assertThat(reader(project).findArtifact(MAIN_JAR))
                .isEqualTo(output.toFile());
    }

    @Test
    void testAttachedShadeArtifactWithCustomFinalName()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("shadedArtifactAttached", "true", "finalName", "custom"));
        output("classes", "Example.class");
        Path archive = archive("custom.jar");

        assertThat(reader(project).findArtifact(jar("shaded")))
                .isEqualTo(archive.toFile());
    }

    @Test
    void testAttachedShadeArtifactWithCustomArtifactId()
            throws IOException
    {
        MavenProject project = project();
        shade(project, Map.of("shadedArtifactAttached", "true", "shadedArtifactId", "alternate"));
        output("classes", "Example.class");
        Path archive = archive("alternate-1-SNAPSHOT-shaded.jar");

        assertThat(reader(project).findArtifact(jar("shaded")))
                .isEqualTo(archive.toFile());
    }

    /// Adds a Shade execution at package time with the supplied plugin configuration.
    private static Plugin shade(MavenProject project, Map<String, String> options)
    {
        Plugin plugin = new Plugin();
        plugin.setGroupId("org.apache.maven.plugins");
        plugin.setArtifactId("maven-shade-plugin");
        plugin.setConfiguration(configuration(options));
        PluginExecution execution = new PluginExecution();
        execution.setId("shade");
        execution.setPhase("package");
        execution.addGoal("shade");
        plugin.addExecution(execution);
        project.getBuild().addPlugin(plugin);
        return plugin;
    }

    /// Converts option names and values into Maven's plugin configuration tree.
    private static Xpp3Dom configuration(Map<String, String> options)
    {
        Xpp3Dom configuration = new Xpp3Dom("configuration");
        options.forEach((name, value) -> {
            Xpp3Dom child = new Xpp3Dom(name);
            child.setValue(value);
            configuration.addChild(child);
        });
        return configuration;
    }

    /// Creates a JAR project whose build outputs are inside the test's temporary directory.
    private MavenProject project()
    {
        Model model = new Model();
        model.setGroupId("example");
        model.setArtifactId("example");
        model.setVersion("1-SNAPSHOT");
        model.setPackaging("jar");
        Build build = new Build();
        build.setDirectory(directory.resolve("target").toString());
        build.setFinalName("example-1-SNAPSHOT");
        build.setOutputDirectory(directory.resolve("target/classes").toString());
        build.setTestOutputDirectory(directory.resolve("target/test-classes").toString());
        model.setBuild(build);
        MavenProject project = new MavenProject(model);
        project.setArtifact(new DefaultArtifact("example", "example", "1-SNAPSHOT", "compile", "jar", null, new DefaultArtifactHandler("jar")));
        return project;
    }

    /// Creates a reader that can use compiled classes directly.
    private static AllProjectsReactorWorkspaceReader reader(MavenProject project)
    {
        return reader(project, false);
    }

    /// Creates a reader that requires current packaged artifacts.
    private static AllProjectsReactorWorkspaceReader packagedOnlyReader(MavenProject project)
    {
        return reader(project, true);
    }

    /// Models a discovered upstream project excluded from the active build by -pl.
    private static AllProjectsReactorWorkspaceReader reader(MavenProject project, boolean requirePackaged)
    {
        DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
        request.getUserProperties().setProperty("reactorRequirePackaged", Boolean.toString(requirePackaged));
        MavenSession session = new MavenSession(null, new DefaultRepositorySystemSession(), request, new DefaultMavenExecutionResult());
        // The dependency is discovered but excluded from the selected execution set by -pl.
        session.setProjects(List.of());
        session.setAllProjects(List.of(project));
        return new AllProjectsReactorWorkspaceReader(session);
    }

    /// Requests a library JAR with the given classifier.
    private static Artifact jar(String classifier)
    {
        return artifact("jar", classifier, "jar");
    }

    /// Creates a dependency request matching the test project's coordinates.
    private static Artifact artifact(String extension, String classifier, String type)
    {
        return new org.eclipse.aether.artifact.DefaultArtifact("example", "example", classifier, extension, "1-SNAPSHOT")
                .setProperties(Map.of("type", type));
    }

    /// Creates placeholder compiled output with files and directories dated before packaging.
    private Path output(String name, String file)
            throws IOException
    {
        Path output = directory.resolve("target").resolve(name);
        Path path = output.resolve(file);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "compiled output");
        try (var paths = Files.walk(output)) {
            for (Path entry : paths.toList()) {
                Files.setLastModifiedTime(entry, BEFORE_PACKAGE);
            }
        }
        return output;
    }

    /// Creates a placeholder archive dated at packaging time for file-resolution tests.
    private Path archive(String name)
            throws IOException
    {
        Path path = directory.resolve("target").resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "packaged output");
        Files.setLastModifiedTime(path, PACKAGE_TIME);
        return path;
    }
}
