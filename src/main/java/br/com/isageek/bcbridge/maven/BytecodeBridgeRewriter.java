package br.com.isageek.bcbridge.maven;

import com.github.beothorn.agent.parser.ClassAndMethodMatcher;
import com.github.beothorn.agent.parser.CompilationException;
import com.github.beothorn.agent.parser.ElementMatcherFromExpression;
import java.io.File;
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
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;

import static net.bytebuddy.matcher.ElementMatchers.isDeclaredBy;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
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

        List<ParsedBridge> parsedBridges = new ArrayList<>();
        for (Bridge bridge : bridges) {
            parsedBridges.add(parse(bridge));
        }

        URL[] classPath = {classesDirectory.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classPath, ClassLoader.getPlatformClassLoader());
             ClassFileLocator locator = new ClassFileLocator.ForFolder(classesDirectory.toFile())) {
            Map<String, byte[]> rewrittenClasses = new LinkedHashMap<>();
            Map<ParsedBridge, Integer> matchCounts = new LinkedHashMap<>();
            parsedBridges.forEach(bridge -> matchCounts.put(bridge, 0));
            TypePool typePool = TypePool.Default.of(locator);
            for (String className : applicationClassNames()) {
                TypeDescription sourceType = typePool.describe(className).resolve();
                List<ParsedBridge> matchingBridges = parsedBridges.stream()
                        .filter(bridge -> bridge.source().getClassMatcher().matches(sourceType))
                        .toList();
                if (!matchingBridges.isEmpty()) {
                    Class<?> sourceClass = loadClass(loader, className, "source");
                    rewriteSourceClass(loader, locator, sourceClass, matchingBridges, matchCounts, rewrittenClasses);
                }
            }
            for (Map.Entry<ParsedBridge, Integer> entry : matchCounts.entrySet()) {
                if (entry.getValue() == 0) {
                    throw new BridgeConfigurationException("Source expression matched no methods: "
                            + entry.getKey().configuration().getSource());
                }
            }
            writeClasses(rewrittenClasses);
            writeJar(rewrittenClasses);
        }
    }

    private static ParsedBridge parse(Bridge bridge) throws BridgeConfigurationException {
        if (bridge.getSource() == null || bridge.getSource().isBlank()) {
            throw new BridgeConfigurationException("Bridge source must not be empty");
        }
        try {
            return new ParsedBridge(
                    bridge,
                    ElementMatcherFromExpression.forExpression(bridge.getSource()),
                    MethodReference.parse(bridge.getDest(), "dest"));
        } catch (CompilationException | RuntimeException e) {
            throw new BridgeConfigurationException(
                    "Invalid bridge source expression '" + bridge.getSource() + "': " + e.getMessage(), e);
        }
    }

    private List<String> applicationClassNames() throws IOException {
        try (var paths = Files.walk(classesDirectory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .map(classesDirectory::relativize)
                    .map(Path::toString)
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .map(name -> name.replace(File.separatorChar, '.'))
                    .filter(name -> !name.equals("module-info") && !name.endsWith("package-info"))
                    .sorted()
                    .toList();
        }
    }

    private void rewriteSourceClass(
            ClassLoader loader,
            ClassFileLocator locator,
            Class<?> sourceClass,
            List<ParsedBridge> bridges,
            Map<ParsedBridge, Integer> matchCounts,
            Map<String, byte[]> rewrittenClasses) throws Exception {
        DynamicType.Builder<?> builder = new ByteBuddy().redefine(sourceClass, locator);

        for (ParsedBridge parsedBridge : bridges) {
            Bridge bridge = parsedBridge.configuration();
            MethodReference destination = parsedBridge.destination();
            Class<?> destinationClass = loadClass(loader, destination.className(), "destination");
            ElementMatcher<? super MethodDescription> methodMatcher = methodMatcherFor(
                    parsedBridge.source(), TypeDescription.ForLoadedType.of(sourceClass));
            List<Method> sourceMethods = declaredMethodsMatching(sourceClass, methodMatcher);
            if (sourceMethods.isEmpty()) {
                continue;
            }
            matchCounts.compute(parsedBridge, (ignored, count) -> count + sourceMethods.size());

            for (Method sourceMethod : sourceMethods) {
                Method destinationMethod = matchingDestination(sourceMethod, destinationClass, destination, bridge);
                ElementMatcher.Junction<MethodDescription> exactMatcher = isMethod()
                                .and(isDeclaredBy(sourceClass))
                                .and(methodMatcher)
                                .and(takesArguments(sourceMethod.getParameterTypes()));
                if ("redirect".equals(bridge.getType())) {
                    MethodCall call = configuredCall(destinationClass, destinationMethod, bridge);
                    builder = builder.method(exactMatcher).intercept(call);
                } else {
                    builder = builder.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                            .method(exactMatcher, adviceVisitor(destinationClass, destinationMethod, bridge))
                            .writerFlags(ClassWriter.COMPUTE_MAXS));
                }
                logger.accept(bridge.getType() + " " + bridge.getSource() + " -> " + bridge.getDest());
            }
        }

        try (DynamicType.Unloaded<?> unloaded = builder.make()) {
            rewrittenClasses.put(sourceClass.getName(), unloaded.getBytes());
        }
    }

    private static ElementMatcher<? super MethodDescription> methodMatcherFor(
            ElementMatcherFromExpression source,
            TypeDescription sourceType) {
        for (ClassAndMethodMatcher matcher : source.getClassAndMethodMatchers()) {
            if (matcher.classMatcher.matches(sourceType)) {
                return matcher.methodMatcher;
            }
        }
        return isMethod();
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

    private static MethodCall configuredCall(Class<?> destinationClass, Method destinationMethod, Bridge bridge)
            throws BridgeConfigurationException {
        MethodCall call = destinationCall(destinationClass, destinationMethod, bridge);
        if (bridge.isThisAsParameter()) {
            call = call.withThis();
        }
        return bridge.isCaptureArguments() ? call.withAllArguments() : call;
    }

    private static Method matchingDestination(
            Method sourceMethod,
            Class<?> destinationClass,
            MethodReference destination,
            Bridge bridge) throws BridgeConfigurationException {
        if (bridge.isThisAsParameter() && Modifier.isStatic(sourceMethod.getModifiers())) {
            throw new BridgeConfigurationException("thisAsParameter cannot be used with static source method "
                    + bridge.getSource());
        }
        Class<?>[] sourceParameters = bridge.isCaptureArguments() ? sourceMethod.getParameterTypes() : new Class<?>[0];
        Class<?>[] destinationParameters = new Class<?>[sourceParameters.length + (bridge.isThisAsParameter() ? 1 : 0)];
        int offset = 0;
        if (bridge.isThisAsParameter()) {
            destinationParameters[0] = Object.class;
            offset = 1;
        }
        System.arraycopy(sourceParameters, 0, destinationParameters, offset, sourceParameters.length);
        try {
            Method method = destinationClass.getDeclaredMethod(destination.methodName(), destinationParameters);
            if ("redirect".equals(bridge.getType()) && sourceMethod.getReturnType() != method.getReturnType()) {
                throw new BridgeConfigurationException("Destination return type for " + bridge.getDest()
                        + " does not match " + bridge.getSource());
            }
            if (!"redirect".equals(bridge.getType()) && method.getReturnType() != void.class) {
                throw new BridgeConfigurationException("Destination return type for " + bridge.getType()
                        + " must be void: " + bridge.getDest());
            }
            if (!Modifier.isStatic(method.getModifiers())) {
                try {
                    destinationClass.getDeclaredConstructor();
                } catch (NoSuchMethodException e) {
                    throw new BridgeConfigurationException("Non-static destination " + bridge.getDest()
                            + " requires a no-argument constructor", e);
                }
            }
            return method;
        } catch (NoSuchMethodException e) {
            throw new BridgeConfigurationException("Destination method with matching parameters not found: "
                    + bridge.getDest(), e);
        }
    }

    private static AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper adviceVisitor(
            Class<?> destinationClass, Method destinationMethod, Bridge bridge) {
        return (instrumentedType, instrumentedMethod, methodVisitor, context, typePool, writerFlags, readerFlags) ->
                new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        if ("OnMethodEnter".equals(bridge.getType())) {
                            emitDestinationCall(this, instrumentedMethod, destinationClass, destinationMethod, bridge);
                        }
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if ("OnMethodExit".equals(bridge.getType())
                                && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                            emitDestinationCall(this, instrumentedMethod, destinationClass, destinationMethod, bridge);
                        }
                        super.visitInsn(opcode);
                    }
                };
    }

    private static void emitDestinationCall(MethodVisitor visitor, MethodDescription sourceMethod,
            Class<?> destinationClass, Method destinationMethod, Bridge bridge) {
        String owner = Type.getInternalName(destinationClass);
        boolean isStatic = Modifier.isStatic(destinationMethod.getModifiers());
        if (!isStatic) {
            visitor.visitTypeInsn(Opcodes.NEW, owner);
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        }
        if (bridge.isThisAsParameter()) {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
        }
        if (bridge.isCaptureArguments()) {
            int local = sourceMethod.isStatic() ? 0 : 1;
            for (Type parameter : Type.getArgumentTypes(sourceMethod.getDescriptor())) {
                visitor.visitVarInsn(parameter.getOpcode(Opcodes.ILOAD), local);
                local += parameter.getSize();
            }
        }
        int opcode = isStatic ? Opcodes.INVOKESTATIC
                : destinationClass.isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        visitor.visitMethodInsn(opcode, owner, destinationMethod.getName(), Type.getMethodDescriptor(destinationMethod),
                destinationClass.isInterface());
    }

    private static List<Method> declaredMethodsMatching(
            Class<?> type,
            ElementMatcher<? super MethodDescription> matcher) {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getDeclaredMethods()) {
            if (matcher.matches(new MethodDescription.ForLoadedMethod(method))) {
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

    private record ParsedBridge(
            Bridge configuration,
            ElementMatcherFromExpression source,
            MethodReference destination) {
    }
}
