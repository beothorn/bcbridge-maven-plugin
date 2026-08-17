package br.com.isageek.bcbridge.maven;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
