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

import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.artifact.Artifact;

import java.io.File;
import java.nio.file.Path;

/// Inspects Maven Shade plugin configuration to locate shaded artifacts and their compiled output directories.
/// This lets the workspace reader detect when repackaging is required instead of substituting unshaded classes.
final class ShadedArtifacts
{
    private ShadedArtifacts() {}

    static Output find(MavenProject project, Artifact artifact)
    {
        if (!"jar".equals(artifact.getExtension())) {
            return null;
        }

        for (Plugin plugin : project.getBuildPlugins()) {
            if (!"org.apache.maven.plugins".equals(plugin.getGroupId())
                    || !"maven-shade-plugin".equals(plugin.getArtifactId())) {
                continue;
            }
            for (PluginExecution execution : plugin.getExecutions()) {
                if (!execution.getGoals().contains("shade") || "none".equals(execution.getPhase())) {
                    continue;
                }
                Xpp3Dom configuration = Xpp3Dom.mergeXpp3Dom(
                        copy(execution.getConfiguration()), copy(plugin.getConfiguration()));
                if (Boolean.parseBoolean(value(configuration, "skip", "false"))
                        || value(configuration, "outputFile", null) != null) {
                    continue;
                }

                boolean attached = Boolean.parseBoolean(value(configuration, "shadedArtifactAttached", "false"));
                String finalName = value(configuration, "finalName", project.getBuild().getFinalName());
                if (!attached && !finalName.equals(project.getBuild().getFinalName())) {
                    // A separately named output does not replace the project's main artifact.
                    continue;
                }
                String classifier = attached ? value(configuration, "shadedClassifierName", "shaded") : "";
                if (classifier.equals(artifact.getClassifier())) {
                    return output(project, configuration, attached, false, classifier);
                }
                String testClassifier = attached ? classifier + "-tests" : "tests";
                if (Boolean.parseBoolean(value(configuration, "shadeTestJar", "false"))
                        && testClassifier.equals(artifact.getClassifier())) {
                    return output(project, configuration, attached, true, classifier);
                }
            }
        }
        return null;
    }

    private static Output output(MavenProject project, Xpp3Dom configuration, boolean attached, boolean tests, String classifier)
    {
        String suffix = tests ? "-tests" : "";
        Path archive;
        if (attached) {
            String finalName = value(configuration, "finalName", null);
            String name;
            if (finalName != null && !finalName.equals(project.getBuild().getFinalName())) {
                name = finalName + suffix + ".jar";
            }
            else {
                name = value(configuration, "shadedArtifactId", project.getArtifactId())
                        + "-" + project.getVersion() + "-" + classifier + suffix + ".jar";
            }
            Path directory = Path.of(value(configuration, "outputDirectory", project.getBuild().getDirectory()));
            archive = directory.resolve(name);
        }
        else {
            archive = Path.of(project.getBuild().getDirectory(), project.getBuild().getFinalName() + suffix + ".jar");
        }
        if (!archive.isAbsolute()) {
            archive = project.getBasedir().toPath().resolve(archive);
        }
        File outputDirectory = Path.of(tests ? project.getBuild().getTestOutputDirectory() : project.getBuild().getOutputDirectory()).toFile();
        return new Output(archive.toFile(), outputDirectory);
    }

    record Output(File artifact, File classes) {}

    private static Xpp3Dom copy(Object configuration)
    {
        return configuration == null ? null : new Xpp3Dom((Xpp3Dom) configuration);
    }

    private static String value(Xpp3Dom configuration, String name, String defaultValue)
    {
        Xpp3Dom child = configuration == null ? null : configuration.getChild(name);
        String value = child == null ? null : child.getValue();
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }
}
