package br.com.isageek.bcbridge.maven;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodCall;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

final class BytecodeBridgeRewriter {

    private final Path classesDirectory;
    private final Path packagedArtifact;
    private final Consumer<String> logger;

    BytecodeBridgeRewriter(Path classesDirectory, Path packagedArtifact, Consumer<String> logger) {
        this.classesDirectory = classesDirectory;
        this.packagedArtifact = packagedArtifact;
        this.logger = logger;
    }

    void rewrite(List<Bridge> bridges) throws Exception {
        if (!Files.isDirectory(classesDirectory)) {
            throw new BridgeConfigurationException("Compiled classes directory does not exist: " + classesDirectory);
        }
        if (!Files.isRegularFile(packagedArtifact)) {
            throw new BridgeConfigurationException("Packaged artifact does not exist: " + packagedArtifact);
        }

        Map<String, List<Bridge>> bridgesBySourceClass = new LinkedHashMap<>();
        for (Bridge bridge : bridges) {
            MethodReference source = MethodReference.parse(bridge.getSource(), "source");
            MethodReference.parse(bridge.getDest(), "dest");
            bridgesBySourceClass.computeIfAbsent(source.className(), ignored -> new ArrayList<>()).add(bridge);
        }

        URL[] classPath = {classesDirectory.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classPath, ClassLoader.getPlatformClassLoader());
             ClassFileLocator locator = new ClassFileLocator.ForFolder(classesDirectory.toFile())) {
            Map<String, byte[]> rewrittenClasses = new LinkedHashMap<>();
            for (Map.Entry<String, List<Bridge>> entry : bridgesBySourceClass.entrySet()) {
                rewriteSourceClass(loader, locator, entry.getKey(), entry.getValue(), rewrittenClasses);
            }
            writeClasses(rewrittenClasses);
            writeJar(rewrittenClasses);
        }
    }

    private void rewriteSourceClass(
            ClassLoader loader,
            ClassFileLocator locator,
            String sourceClassName,
            List<Bridge> bridges,
            Map<String, byte[]> rewrittenClasses) throws Exception {
        Class<?> sourceClass = loadClass(loader, sourceClassName, "source");
        DynamicType.Builder<?> builder = new ByteBuddy().redefine(sourceClass, locator);

        for (Bridge bridge : bridges) {
            MethodReference source = MethodReference.parse(bridge.getSource(), "source");
            MethodReference destination = MethodReference.parse(bridge.getDest(), "dest");
            Class<?> destinationClass = loadClass(loader, destination.className(), "destination");
            List<Method> sourceMethods = declaredMethodsNamed(sourceClass, source.methodName());
            if (sourceMethods.isEmpty()) {
                throw new BridgeConfigurationException("Source method not found: " + bridge.getSource());
            }

            for (Method sourceMethod : sourceMethods) {
                Method destinationMethod = matchingDestination(sourceMethod, destinationClass, destination, bridge);
                MethodCall methodCall = destinationCall(destinationClass, destinationMethod, bridge);
                builder = builder.method(named(source.methodName())
                                .and(isMethod())
                                .and(isDeclaredBy(sourceClass))
                                .and(takesArguments(sourceMethod.getParameterTypes())))
                        .intercept(methodCall.withAllArguments());
                logger.accept("Redirecting " + bridge.getSource() + " -> " + bridge.getDest());
            }
        }

        try (DynamicType.Unloaded<?> unloaded = builder.make()) {
            rewrittenClasses.put(sourceClassName, unloaded.getBytes());
        }
    }

    private static MethodCall destinationCall(Class<?> destinationClass, Method destinationMethod, Bridge bridge)
            throws BridgeConfigurationException {
        if (Modifier.isStatic(destinationMethod.getModifiers())) {
            return MethodCall.invoke(destinationMethod);
        }

        try {
            Constructor<?> constructor = destinationClass.getDeclaredConstructor();
            return MethodCall.invoke(destinationMethod).onMethodCall(MethodCall.construct(constructor));
        } catch (NoSuchMethodException e) {
            throw new BridgeConfigurationException("Non-static destination " + bridge.getDest()
                    + " requires a no-argument constructor", e);
        }
    }

    private static Method matchingDestination(
            Method sourceMethod,
            Class<?> destinationClass,
            MethodReference destination,
            Bridge bridge) throws BridgeConfigurationException {
        try {
            Method method = destinationClass.getDeclaredMethod(destination.methodName(), sourceMethod.getParameterTypes());
            if (!sourceMethod.getReturnType().isAssignableFrom(method.getReturnType())) {
                throw new BridgeConfigurationException("Destination return type for " + bridge.getDest()
                        + " is not compatible with " + bridge.getSource());
            }
            return method;
        } catch (NoSuchMethodException e) {
            throw new BridgeConfigurationException("Destination method with matching parameters not found: "
                    + bridge.getDest(), e);
        }
    }

    private static List<Method> declaredMethodsNamed(Class<?> type, String name) {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                methods.add(method);
            }
        }
        return methods;
    }

    private static Class<?> loadClass(ClassLoader loader, String className, String role)
            throws BridgeConfigurationException {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new BridgeConfigurationException("Could not load " + role + " class " + className, e);
        }
    }

    private void writeClasses(Map<String, byte[]> rewrittenClasses) throws IOException {
        for (Map.Entry<String, byte[]> entry : rewrittenClasses.entrySet()) {
            Path classFile = classesDirectory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.write(classFile, entry.getValue());
        }
    }

    private void writeJar(Map<String, byte[]> rewrittenClasses) throws IOException {
        URI jarUri = URI.create("jar:" + packagedArtifact.toUri());
        try (FileSystem jar = FileSystems.newFileSystem(jarUri, new HashMap<>())) {
            for (Map.Entry<String, byte[]> entry : rewrittenClasses.entrySet()) {
                Path classEntry = jar.getPath("/" + entry.getKey().replace('.', '/') + ".class");
                if (!Files.exists(classEntry)) {
                    throw new IOException("Class is missing from packaged artifact: " + entry.getKey());
                }
                Files.write(classEntry, entry.getValue());
            }
        }
    }
}
