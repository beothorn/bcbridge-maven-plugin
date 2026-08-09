package br.com.isageek.bcbridge.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;

/** Prints a greeting to confirm that the plugin is active. */
@Mojo(name = "hello", defaultPhase = LifecyclePhase.PACKAGE, threadSafe = true)
public final class HelloMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("Hello World");
    }
}
