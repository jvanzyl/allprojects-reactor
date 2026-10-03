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

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Makes all discovered reactor projects available for workspace resolution, even when {@code -pl} restricts execution.
 * <p>
 * Installed on the repository session by {@link AllProjectsReactorLifecycleParticipant} before the projects are read,
 * so the project index is built lazily from the session on first use.
 */
final class AllProjectsReactorWorkspaceReader
        implements MavenWorkspaceReader
{
    private static final String REQUIRE_PACKAGED_PROPERTY = "reactorRequirePackaged";

    private static final Collection<String> COMPILE_PHASE_TYPES = new HashSet<>(Arrays.asList(
            "jar", "ejb-client", "war", "rar", "ejb3", "par", "sar", "wsr", "har", "app-client"));

    private static final WorkspaceRepository REPOSITORY = new WorkspaceRepository("all-projects-reactor");

    private final MavenSession session;
    private final boolean requirePackaged;
    private volatile ProjectIndex index;

    AllProjectsReactorWorkspaceReader(MavenSession session)
    {
        this.session = session;
        this.requirePackaged = Boolean.parseBoolean(
                session.getUserProperties().getProperty(
                        REQUIRE_PACKAGED_PROPERTY,
                        session.getSystemProperties().getProperty(REQUIRE_PACKAGED_PROPERTY, "false")));
    }

    @Override
    public WorkspaceRepository getRepository()
    {
        return REPOSITORY;
    }

    @Override
    public File findArtifact(Artifact artifact)
    {
        MavenProject project = index().projectsByGav().get(ArtifactUtils.key(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
        if (project == null) {
            return null;
        }

        File file = find(project, artifact, true);
        if (file == null && project != project.getExecutionProject()) {
            file = find(project.getExecutionProject(), artifact, true);
        }
        return file;
    }

    @Override
    public List<String> findVersions(Artifact artifact)
    {
        List<MavenProject> projects = index().projectsByGa().get(ArtifactUtils.versionlessKey(
                artifact.getGroupId(), artifact.getArtifactId()));
        if (projects == null) {
            return Collections.emptyList();
        }

        List<String> versions = new ArrayList<>();
        for (MavenProject project : projects) {
            // Version discovery is combined across readers, even when an earlier reader can resolve the artifact.
            if (find(project, artifact, false) != null) {
                versions.add(project.getVersion());
            }
        }
        return Collections.unmodifiableList(versions);
    }

    @Override
    public Model findModel(Artifact artifact)
    {
        MavenProject project = index().projectsByGav().get(ArtifactUtils.key(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
        return project == null ? null : project.getModel();
    }

    private ProjectIndex index()
    {
        List<MavenProject> projects = session.getAllProjects();
        if (projects == null || projects.isEmpty()) {
            projects = session.getProjects();
        }
        if (projects == null) {
            return ProjectIndex.EMPTY;
        }

        // Maven replaces the project lists whenever it rebuilds the project graph
        ProjectIndex current = index;
        if (current == null || current.projects() != projects) {
            current = ProjectIndex.of(projects);
            index = current;
        }
        return current;
    }

    private File find(MavenProject project, Artifact requestedArtifact, boolean failIfPackagingRequired)
    {
        if ("pom".equals(requestedArtifact.getExtension())) {
            return project.getFile();
        }

        Artifact projectArtifact = findMatchingArtifact(project, requestedArtifact);
        ShadedArtifacts.Output shaded = ShadedArtifacts.find(project, requestedArtifact);
        File packagedArtifact = shaded == null ? determinePackagedArtifactFile(project, requestedArtifact) : shaded.artifact();
        if (projectArtifact != null && projectArtifact.getFile() != null && projectArtifact.getFile().exists()) {
            packagedArtifact = projectArtifact.getFile();
        }

        File shadedOutput = shaded == null ? null : shaded.classes();
        if (shadedOutput != null && shadedOutput.isDirectory()
                && (!packagedArtifact.exists() || hasNewerOutput(shadedOutput, packagedArtifact))) {
            if (!failIfPackagingRequired) {
                return null;
            }
            // Returning null during artifact resolution would allow a stale installed copy instead.
            throw new IllegalStateException("Shaded workspace artifact " + requestedArtifact
                    + " must be repackaged: " + packagedArtifact
                    + " is missing or older than " + shadedOutput
                    + ". Run Maven package for " + project.getGroupId() + ":" + project.getArtifactId()
                    + " (for example, mvn package -pl :" + project.getArtifactId() + " -am)."
                    + " If the error persists, run mvn clean package -pl :" + project.getArtifactId() + " -am.");
        }

        File outputDirectory = determineOutputDirectory(project, requestedArtifact);
        if (requirePackaged) {
            if (!packagedArtifact.isFile()
                    || (outputDirectory != null && hasNewerOutput(outputDirectory, packagedArtifact))) {
                if (!failIfPackagingRequired) {
                    return null;
                }
                // Packaged-only mode must not fall back to an installed snapshot.
                throw new IllegalStateException("Workspace artifact " + requestedArtifact
                        + " requires a current packaged file because reactorRequirePackaged is enabled: "
                        + packagedArtifact + " is missing or older than the compiled output."
                        + " Run Maven package for " + project.getGroupId() + ":" + project.getArtifactId()
                        + " (for example, mvn package -pl :" + project.getArtifactId() + " -am)."
                        + " If the error persists, run mvn clean package -pl :" + project.getArtifactId() + " -am.");
            }
            return packagedArtifact;
        }

        boolean canUseClasses = isTestArtifact(requestedArtifact)
                || (!hasClassifier(requestedArtifact) && canResolveFromMainOutputDirectory(requestedArtifact));
        if (packagedArtifact.exists()) {
            if (canUseClasses && outputDirectory != null && hasNewerOutput(outputDirectory, packagedArtifact)) {
                return outputDirectory;
            }
            return packagedArtifact;
        }
        return canUseClasses ? outputDirectory : null;
    }

    private static File determineOutputDirectory(MavenProject project, Artifact artifact)
    {
        String directory = isTestArtifact(artifact)
                ? project.getBuild().getTestOutputDirectory()
                : project.getBuild().getOutputDirectory();
        File outputDirectory = Path.of(directory).toFile();
        return outputDirectory.isDirectory() ? outputDirectory : null;
    }

    private static boolean hasNewerOutput(File outputDirectory, File packagedArtifact)
    {
        try (Stream<Path> output = Files.walk(outputDirectory.toPath())) {
            var packagedTime = Files.getLastModifiedTime(packagedArtifact.toPath());
            // Include directories so removing a class or resource also invalidates the archive.
            return output.anyMatch(path -> {
                try {
                    return Files.getLastModifiedTime(path).compareTo(packagedTime) > 0;
                }
                catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private File determinePackagedArtifactFile(MavenProject project, Artifact artifact)
    {
        String classifier = artifact.getClassifier();
        String classifierSuffix = classifier == null || classifier.isEmpty() ? "" : "-" + classifier;
        return Path.of(
                project.getBuild().getDirectory(),
                project.getBuild().getFinalName() + classifierSuffix + "." + artifact.getExtension()).toFile();
    }

    private Artifact findMatchingArtifact(MavenProject project, Artifact requestedArtifact)
    {
        String requestedRepositoryConflictId = ArtifactIdUtils.toVersionlessId(requestedArtifact);

        Artifact mainArtifact = RepositoryUtils.toArtifact(project.getArtifact());
        if (requestedRepositoryConflictId.equals(ArtifactIdUtils.toVersionlessId(mainArtifact))) {
            return mainArtifact;
        }

        for (Artifact artifact : RepositoryUtils.toArtifacts(project.getAttachedArtifacts())) {
            if (isRequestedArtifact(requestedArtifact, artifact)) {
                return artifact;
            }
        }
        return null;
    }

    private static boolean isRequestedArtifact(Artifact requestedArtifact, Artifact artifact)
    {
        return Objects.equals(artifact.getArtifactId(), requestedArtifact.getArtifactId())
                && Objects.equals(artifact.getGroupId(), requestedArtifact.getGroupId())
                && Objects.equals(artifact.getVersion(), requestedArtifact.getVersion())
                && Objects.equals(artifact.getExtension(), requestedArtifact.getExtension())
                && Objects.equals(artifact.getClassifier(), requestedArtifact.getClassifier());
    }

    private static boolean isTestArtifact(Artifact artifact)
    {
        return "test-jar".equals(artifact.getProperty("type", ""))
                || ("jar".equals(artifact.getExtension()) && "tests".equals(artifact.getClassifier()));
    }

    private static boolean canResolveFromMainOutputDirectory(Artifact artifact)
    {
        return "jar".equals(artifact.getExtension()) && COMPILE_PHASE_TYPES.contains(artifact.getProperty("type", ""));
    }

    private static boolean hasClassifier(Artifact artifact)
    {
        String classifier = artifact.getClassifier();
        return classifier != null && !classifier.isEmpty();
    }

    private record ProjectIndex(
            List<MavenProject> projects,
            Map<String, MavenProject> projectsByGav,
            Map<String, List<MavenProject>> projectsByGa)
    {
        static final ProjectIndex EMPTY = new ProjectIndex(List.of(), Map.of(), Map.of());

        static ProjectIndex of(List<MavenProject> projects)
        {
            Map<String, MavenProject> projectsByGav = new HashMap<>();
            Map<String, List<MavenProject>> projectsByGa = new HashMap<>();
            for (MavenProject project : projects) {
                String gav = ArtifactUtils.key(project.getGroupId(), project.getArtifactId(), project.getVersion());
                String ga = ArtifactUtils.versionlessKey(project.getGroupId(), project.getArtifactId());
                projectsByGav.put(gav, project);
                projectsByGa.computeIfAbsent(ga, _ -> new ArrayList<>()).add(project);
            }
            return new ProjectIndex(projects, projectsByGav, projectsByGa);
        }
    }
}
