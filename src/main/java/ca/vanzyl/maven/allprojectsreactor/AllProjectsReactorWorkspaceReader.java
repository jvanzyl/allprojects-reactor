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
import org.apache.maven.SessionScoped;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.apache.maven.repository.internal.MavenWorkspaceReader;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.WorkspaceRepository;
import org.eclipse.aether.util.artifact.ArtifactIdUtils;

import javax.inject.Inject;
import javax.inject.Named;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Makes all discovered reactor projects available for workspace resolution, even when {@code -pl} restricts execution.
 */
@Named
@SessionScoped
public final class AllProjectsReactorWorkspaceReader
        implements MavenWorkspaceReader
{
    private static final String RESOLVE_CLASSES_PROPERTY = "allprojects-reactor.resolveClasses";

    private static final Collection<String> COMPILE_PHASE_TYPES = new HashSet<>(Arrays.asList(
            "jar", "ejb-client", "war", "rar", "ejb3", "par", "sar", "wsr", "har", "app-client"));

    private final Map<String, MavenProject> projectsByGav;
    private final Map<String, List<MavenProject>> projectsByGa;
    private final WorkspaceRepository repository;
    private final boolean resolveClasses;

    @Inject
    public AllProjectsReactorWorkspaceReader(MavenSession session)
    {
        List<MavenProject> projects = session.getAllProjects();
        if (projects == null || projects.isEmpty()) {
            projects = session.getProjects();
        }
        this.resolveClasses = Boolean.parseBoolean(
                session.getUserProperties().getProperty(
                        RESOLVE_CLASSES_PROPERTY,
                        session.getSystemProperties().getProperty(RESOLVE_CLASSES_PROPERTY, "true")));

        this.projectsByGav = new HashMap<>();
        this.projectsByGa = new HashMap<>();

        Set<String> keys = new LinkedHashSet<>();
        for (MavenProject project : projects) {
            String gav = ArtifactUtils.key(project.getGroupId(), project.getArtifactId(), project.getVersion());
            String ga = ArtifactUtils.versionlessKey(project.getGroupId(), project.getArtifactId());
            projectsByGav.put(gav, project);
            projectsByGa.computeIfAbsent(ga, _ -> new ArrayList<>()).add(project);
            keys.add(gav);
        }

        this.repository = new WorkspaceRepository("all-projects-reactor", keys);
    }

    @Override
    public WorkspaceRepository getRepository()
    {
        return repository;
    }

    @Override
    public File findArtifact(Artifact artifact)
    {
        MavenProject project = projectsByGav.get(ArtifactUtils.key(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
        if (project == null) {
            return null;
        }

        File file = find(project, artifact);
        if (file == null && project != project.getExecutionProject()) {
            file = find(project.getExecutionProject(), artifact);
        }
        return file;
    }

    @Override
    public List<String> findVersions(Artifact artifact)
    {
        List<MavenProject> projects = projectsByGa.get(ArtifactUtils.versionlessKey(
                artifact.getGroupId(), artifact.getArtifactId()));
        if (projects == null) {
            return Collections.emptyList();
        }

        List<String> versions = new ArrayList<>();
        for (MavenProject project : projects) {
            if (find(project, artifact) != null) {
                versions.add(project.getVersion());
            }
        }
        return Collections.unmodifiableList(versions);
    }

    @Override
    public Model findModel(Artifact artifact)
    {
        MavenProject project = projectsByGav.get(ArtifactUtils.key(
                artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion()));
        return project == null ? null : project.getModel();
    }

    private File find(MavenProject project, Artifact requestedArtifact)
    {
        if ("pom".equals(requestedArtifact.getExtension())) {
            return project.getFile();
        }

        Artifact projectArtifact = findMatchingArtifact(project, requestedArtifact);
        if (projectArtifact != null && projectArtifact.getFile() != null && projectArtifact.getFile().exists()) {
            return projectArtifact.getFile();
        }

        File packagedArtifact = determinePackagedArtifactFile(project, requestedArtifact);
        if (packagedArtifact.exists()) {
            return packagedArtifact;
        }

        if (!resolveClasses) {
            return null;
        }

        if (isTestArtifact(requestedArtifact)) {
            File testOutputDirectory = Path.of(project.getBuild().getTestOutputDirectory()).toFile();
            return testOutputDirectory.exists() ? testOutputDirectory : null;
        }

        if (!hasClassifier(requestedArtifact) && canResolveFromMainOutputDirectory(requestedArtifact)) {
            File outputDirectory = Path.of(project.getBuild().getOutputDirectory()).toFile();
            return outputDirectory.exists() ? outputDirectory : null;
        }

        return null;
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
}
