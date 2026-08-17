package br.com.isageek.bcbridge.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BytecodeBridgeRewriterTest {

    private static final String FIXTURE_CLASS = "br.com.isageek.bcbridge.maven.fixture.RedirectFixture";
    private static final String FIXTURE_RESOURCE = FIXTURE_CLASS.replace('.', '/') + ".class";

    @TempDir
    Path temporaryDirectory;

    @Test
    void supportsLegacyClassAndMethodSource() throws Exception {
        assertRewrites(FIXTURE_CLASS + "#original");
    }

    @Test
    void rewritesMethodsSelectedByMatcherExpression() throws Exception {
        assertRewrites("nameEndsWith(RedirectFixture)#nameStartsWith(orig)");
    }

    @Test
    void invokesEnterWithoutArgumentsBeforeOriginal() throws Exception {
        assertAdvice("OnMethodEnter", "adviceNoArguments", false, false,
                "advice();original(value);");
    }

    @Test
    void invokesExitWithArgumentsAfterOriginal() throws Exception {
        assertAdvice("OnMethodExit", "adviceArguments", true, false,
                "original(value);advice(value);");
    }

    @Test
    void invokesEnterWithThisOnly() throws Exception {
        assertAdvice("OnMethodEnter", "adviceThis", false, true,
                "advice(RedirectFixture);original(value);");
    }

    @Test
    void invokesExitWithThisAndArguments() throws Exception {
        assertAdvice("OnMethodExit", "adviceThisAndArguments", true, true,
                "original(value);advice(RedirectFixture,value);");
    }

    @Test
    void redirectsWithThisAndArguments() throws Exception {
        TestArtifact artifact = artifact();
        Bridge bridge = bridge("redirectedWithThis", "redirect", true, true);
        new BytecodeBridgeRewriter(artifact.classes(), artifact.jar(), ignored -> { }).rewrite(List.of(bridge));
        assertEquals("RedirectFixture: value", invoke(artifact.classes().toUri().toURL(), "value"));
    }

    @Test
    void rejectsNonVoidAdviceDestination() throws Exception {
        TestArtifact artifact = artifact();
        Bridge bridge = bridge("invalidAdviceReturn", "OnMethodEnter", true, false);
        BridgeConfigurationException error = assertThrows(BridgeConfigurationException.class,
                () -> new BytecodeBridgeRewriter(artifact.classes(), artifact.jar(), ignored -> { })
                        .rewrite(List.of(bridge)));
        assertEquals("Destination return type for OnMethodEnter must be void: "
                + FIXTURE_CLASS + "#invalidAdviceReturn", error.getMessage());
    }

    private void assertAdvice(String type, String destination, boolean captureArguments,
            boolean thisAsParameter, String expectedEvents) throws Exception {
        TestArtifact artifact = artifact();
        Bridge bridge = bridge(destination, type, captureArguments, thisAsParameter);
        new BytecodeBridgeRewriter(artifact.classes(), artifact.jar(), ignored -> { }).rewrite(List.of(bridge));
        assertEquals("original: value", invoke(artifact.classes().toUri().toURL(), "value"));
        assertEquals(expectedEvents, events(artifact.classes().toUri().toURL()));
    }

    private static Bridge bridge(String destination, String type, boolean captureArguments,
            boolean thisAsParameter) {
        Bridge bridge = new Bridge();
        bridge.setSource(FIXTURE_CLASS + "#original");
        bridge.setDest(FIXTURE_CLASS + "#" + destination);
        bridge.setType(type);
        bridge.setCaptureArguments(captureArguments);
        bridge.setThisAsParameter(thisAsParameter);
        return bridge;
    }

    private TestArtifact artifact() throws Exception {
        Path classes = temporaryDirectory.resolve("classes-" + System.nanoTime());
        Path classFile = classes.resolve(FIXTURE_RESOURCE);
        Files.createDirectories(classFile.getParent());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            Files.copy(input, classFile);
        }
        Path jar = temporaryDirectory.resolve("fixture-" + System.nanoTime() + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(FIXTURE_RESOURCE));
            output.write(Files.readAllBytes(classFile));
            output.closeEntry();
        }
        return new TestArtifact(classes, jar);
    }

    private static String events(URL location) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader())) {
            Class<?> fixture = Class.forName(FIXTURE_CLASS, true, loader);
            Method original = fixture.getMethod("original", String.class);
            original.invoke(fixture.getConstructor().newInstance(), "value");
            return (String) fixture.getField("events").get(null);
        }
    }

    private record TestArtifact(Path classes, Path jar) { }

    private void assertRewrites(String sourceExpression) throws Exception {
        Path classes = temporaryDirectory.resolve("classes");
        Path classFile = classes.resolve(FIXTURE_RESOURCE);
        Files.createDirectories(classFile.getParent());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(FIXTURE_RESOURCE)) {
            Files.copy(input, classFile);
        }

        Path jar = temporaryDirectory.resolve("fixture.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(FIXTURE_RESOURCE));
            output.write(Files.readAllBytes(classFile));
            output.closeEntry();
        }

        Bridge bridge = new Bridge();
        bridge.setSource(sourceExpression);
        bridge.setDest(FIXTURE_CLASS + "#redirected");

        new BytecodeBridgeRewriter(classes, jar, ignored -> { }).rewrite(List.of(bridge));

        assertEquals("redirected: directory", invoke(classes.toUri().toURL(), "directory"));
        assertEquals("redirected: jar", invoke(jar.toUri().toURL(), "jar"));
    }

    private static Object invoke(URL location, String argument) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{location}, ClassLoader.getPlatformClassLoader())) {
            Class<?> fixture = Class.forName(FIXTURE_CLASS, true, loader);
            Method original = fixture.getMethod("original", String.class);
            return original.invoke(fixture.getConstructor().newInstance(), argument);
        }
    }
}
