package br.com.isageek.bcbridge.maven;

import java.io.File;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Rewrites configured source methods to invoke their destinations directly. */
@Mojo(name = "rewrite", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class BCBridgeExecute extends AbstractMojo {

    /** Bridges that will eventually be applied to the packaged application. */
    @Parameter
    private List<Bridge> bridges;

    @Parameter(defaultValue = "${project.build.outputDirectory}", readonly = true, required = true)
    private File outputDirectory;

    @Parameter(defaultValue = "${project.build.directory}/${project.build.finalName}.${project.packaging}",
            readonly = true, required = true)
    private File packagedArtifact;

    void setBridges(List<Bridge> bridges) {
        this.bridges = bridges;
    }

    void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    void setPackagedArtifact(File packagedArtifact) {
        this.packagedArtifact = packagedArtifact;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (bridges == null || bridges.isEmpty()) {
            getLog().info("No bridges configured");
            return;
        }

        for (Bridge bridge : bridges) {
            if (!List.of("redirect", "OnMethodEnter", "OnMethodExit").contains(bridge.getType())) {
                throw new MojoFailureException("Unsupported bridge type '" + bridge.getType()
                        + "'. Supported types are 'redirect', 'OnMethodEnter', and 'OnMethodExit'.");
            }
        }

        try {
            new BytecodeBridgeRewriter(outputDirectory.toPath(), packagedArtifact.toPath(), getLog()::info)
                    .rewrite(bridges);
        } catch (BridgeConfigurationException e) {
            throw new MojoFailureException(e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException("Could not rewrite bytecode", e);
        }
    }
}
