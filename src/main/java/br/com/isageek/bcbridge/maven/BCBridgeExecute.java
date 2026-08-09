package br.com.isageek.bcbridge.maven;

import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

/** Loads and prints the bridges configured in the consuming project's POM. */
@Mojo(name = "hello", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class BCBridgeExecute extends AbstractMojo {

    /** Bridges that will eventually be applied to the packaged application. */
    @Parameter
    private List<Bridge> bridges;

    @Override
    public void execute() {
        getLog().info("Hello World");

        if (bridges == null || bridges.isEmpty()) {
            getLog().info("No bridges configured");
            return;
        }

        getLog().info("Configured bridges:");
        for (int index = 0; index < bridges.size(); index++) {
            Bridge bridge = bridges.get(index);
            getLog().info(String.format(
                    "Bridge %d: sourceApplication=%s, source=%s, dest=%s, type=%s",
                    index + 1,
                    bridge.getSourceApplication(),
                    bridge.getSource(),
                    bridge.getDest(),
                    bridge.getType()));
        }
    }
}
