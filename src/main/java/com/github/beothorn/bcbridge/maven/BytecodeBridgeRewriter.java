package com.github.beothorn.bcbridge.maven;

import com.github.beothorn.bcbridge.maven.parser.ClassAndMethodMatcher;
import com.github.beothorn.bcbridge.maven.parser.CompilationException;
import com.github.beothorn.bcbridge.maven.parser.ElementMatcherFromExpression;
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
import java.util.Arrays;
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

/**
 * Rewrites compiled application classes according to a collection of {@link Bridge} configurations.
 *
 * <p>A Java {@code .class} file contains JVM instructions rather than Java source. This class uses Byte Buddy for
 * class and method selection and for complete method replacement, and uses ASM visitors when instructions must be
 * inserted before or after an existing method body. The resulting bytes are written to both the compiler output
 * directory and the packaged JAR.</p>
 *
 * <p>Rewriting is made repeatable by keeping the original and last-rewritten class bytes under
 * {@code target/bcbridge-cache}. Without that cache, running the Maven goal repeatedly would insert another copy of
 * every enter or exit hook into bytecode that already contains the previous copy.</p>
 */
final class BytecodeBridgeRewriter {

    /** Maven compiler output used both for class discovery and as Byte Buddy's class-file source. */
    private final Path classesDirectory;
    /** JAR produced earlier in the package phase and updated with the same generated class bytes. */
    private final Path packagedArtifact;
    /** Build-log sink kept independent of Maven APIs so this class remains straightforward to test. */
    private final Consumer<String> logger;

    /**
     * Creates a rewriter for one Maven build output.
     *
     * @param classesDirectory directory containing compiled application {@code .class} files
     * @param packagedArtifact JAR containing those same application classes
     * @param logger receives one description for each bridge that is applied
     */
    BytecodeBridgeRewriter(Path classesDirectory, Path packagedArtifact, Consumer<String> logger) {
        this.classesDirectory = classesDirectory;
        this.packagedArtifact = packagedArtifact;
        this.logger = logger;
    }

    /**
     * Validates and applies all bridges to the compiled classes and packaged JAR.
     *
     * @param bridges bridge configurations to apply
     * @throws Exception if configuration, class loading, bytecode generation, or file writing fails
     */
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

        // Always begin from unmodified bytecode so advice calls do not accumulate across Maven invocations.
        Map<String, byte[]> originalClasses = restoreOriginalClasses();
        writeJar(originalClasses);

        // Use an isolated loader: application classes must be inspected, but should not leak into Maven's loader.
        URL[] classPath = {classesDirectory.toUri().toURL()};
        try (URLClassLoader loader = new URLClassLoader(classPath, ClassLoader.getPlatformClassLoader());
             ClassFileLocator locator = new ClassFileLocator.ForFolder(classesDirectory.toFile())) {
            Map<String, byte[]> rewrittenClasses = new LinkedHashMap<>();
            Map<ParsedBridge, Integer> matchCounts = new LinkedHashMap<>();
            parsedBridges.forEach(bridge -> matchCounts.put(bridge, 0));
            // A TypePool reads class metadata without requiring every discovered class to be initialized.
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
            rememberRewrittenClasses(rewrittenClasses);
        }
    }

    /**
     * Restores classes that still equal the last generated output, while accepting newly compiled classes as the
     * new originals. Comparing bytes is important because Maven may recompile only a subset of the application.
     *
     * @return the original bytes, keyed by binary class name
     */
    private Map<String, byte[]> restoreOriginalClasses() throws IOException {
        Path cache = classesDirectory.getParent().resolve("bcbridge-cache");
        Path originals = cache.resolve("original");
        Path rewritten = cache.resolve("rewritten");
        Map<String, byte[]> result = new LinkedHashMap<>();
        for (String className : applicationClassNames()) {
            Path relative = Path.of(className.replace('.', '/') + ".class");
            Path classFile = classesDirectory.resolve(relative);
            Path originalFile = originals.resolve(relative);
            Path rewrittenFile = rewritten.resolve(relative);
            byte[] current = Files.readAllBytes(classFile);
            if (Files.isRegularFile(originalFile) && Files.isRegularFile(rewrittenFile)
                    && Arrays.equals(current, Files.readAllBytes(rewrittenFile))) {
                current = Files.readAllBytes(originalFile);
                Files.write(classFile, current);
            } else {
                Files.createDirectories(originalFile.getParent());
                Files.write(originalFile, current);
            }
            result.put(className, current);
        }
        return result;
    }

    /** Saves the generated bytes used by {@link #restoreOriginalClasses()} to recognize a repeated rewrite. */
    private void rememberRewrittenClasses(Map<String, byte[]> rewrittenClasses) throws IOException {
        Path rewritten = classesDirectory.getParent().resolve("bcbridge-cache/rewritten");
        for (Map.Entry<String, byte[]> entry : rewrittenClasses.entrySet()) {
            Path classFile = rewritten.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.createDirectories(classFile.getParent());
            Files.write(classFile, entry.getValue());
        }
    }

    /** Parses matcher syntax and the exact destination reference after validating scalar bridge options. */
    private static ParsedBridge parse(Bridge bridge) throws BridgeConfigurationException {
        if (bridge.getSource() == null || bridge.getSource().isBlank()) {
            throw new BridgeConfigurationException("Bridge source must not be empty");
        }
        if (bridge.getCaptureArguments() != null
                && !"args".equals(bridge.getCaptureArguments())
                && !"array".equals(bridge.getCaptureArguments())) {
            throw new BridgeConfigurationException("captureArguments must be 'args', 'array', or omitted: "
                    + bridge.getCaptureArguments());
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

    /**
     * Converts paths below the compiler output directory into Java binary class names.
     * Metadata-only {@code module-info} and {@code package-info} classes cannot contain bridgeable methods.
     */
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

    /**
     * Builds all transformations for one source class and stores its generated class-file bytes in memory.
     * Delaying disk writes avoids loading a mixture of original and rewritten versions during this pass.
     */
    private void rewriteSourceClass(
            ClassLoader loader,
            ClassFileLocator locator,
            Class<?> sourceClass,
            List<ParsedBridge> bridges,
            Map<ParsedBridge, Integer> matchCounts,
            Map<String, byte[]> rewrittenClasses) throws Exception {
        // "redefine" retains the class identity while allowing its method implementations to be changed.
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
                // Include the concrete parameter types so overloaded source methods are transformed independently.
                ElementMatcher.Junction<MethodDescription> exactMatcher = isMethod()
                                .and(isDeclaredBy(sourceClass))
                                .and(methodMatcher)
                                .and(takesArguments(sourceMethod.getParameterTypes()));
                if ("redirect".equals(bridge.getType())) {
                    MethodCall call = configuredCall(destinationClass, destinationMethod, bridge);
                    builder = builder.method(exactMatcher).intercept(call);
                } else {
                    // COMPUTE_MAXS asks ASM to recalculate operand-stack and local-variable capacity after insertion.
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

    /** Returns the method portion associated with the class-expression branch that matched this source type. */
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

    /**
     * Creates the base Byte Buddy call for a static destination or for a new instance of a non-static destination.
     */
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

    /** Adds the configured receiver and source-argument bindings to a redirect call. */
    private static MethodCall configuredCall(Class<?> destinationClass, Method destinationMethod, Bridge bridge)
            throws BridgeConfigurationException {
        MethodCall call = destinationCall(destinationClass, destinationMethod, bridge);
        if (bridge.isThisAsParameter()) {
            call = call.withThis();
        }
        if ("args".equals(bridge.getCaptureArguments())) {
            return call.withAllArguments();
        }
        if ("array".equals(bridge.getCaptureArguments())) {
            return call.withArgumentArray();
        }
        return call;
    }

    /**
     * Resolves and validates the destination overload required by one concrete source method.
     * Reflection is used here only at build time; generated application bytecode invokes the method directly.
     */
    private static Method matchingDestination(
            Method sourceMethod,
            Class<?> destinationClass,
            MethodReference destination,
            Bridge bridge) throws BridgeConfigurationException {
        if (bridge.isThisAsParameter() && Modifier.isStatic(sourceMethod.getModifiers())) {
            throw new BridgeConfigurationException("thisAsParameter cannot be used with static source method "
                    + bridge.getSource());
        }
        Class<?>[] sourceParameters;
        if ("args".equals(bridge.getCaptureArguments())) {
            sourceParameters = sourceMethod.getParameterTypes();
        } else if ("array".equals(bridge.getCaptureArguments())) {
            sourceParameters = new Class<?>[]{Object[].class};
        } else {
            sourceParameters = new Class<?>[0];
        }
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

    /**
     * Creates an ASM visitor that inserts an enter call when method code begins, or an exit call immediately before
     * each normal JVM return instruction. Exceptional exits are intentionally not represented by a return opcode.
     */
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
                        // IRETURN through RETURN cover primitive, reference, and void normal returns.
                        if ("OnMethodExit".equals(bridge.getType())
                                && opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                            emitDestinationCall(this, instrumentedMethod, destinationClass, destinationMethod, bridge);
                        }
                        super.visitInsn(opcode);
                    }
                };
    }

    /**
     * Emits JVM instructions that place the destination receiver and arguments on the operand stack, then invoke
     * the destination. JVM call instructions consume their receiver and arguments from that stack.
     */
    private static void emitDestinationCall(MethodVisitor visitor, MethodDescription sourceMethod,
            Class<?> destinationClass, Method destinationMethod, Bridge bridge) {
        String owner = Type.getInternalName(destinationClass);
        boolean isStatic = Modifier.isStatic(destinationMethod.getModifiers());
        if (!isStatic) {
            // NEW leaves an uninitialized reference; DUP retains one for the later virtual call while <init> uses one.
            visitor.visitTypeInsn(Opcodes.NEW, owner);
            visitor.visitInsn(Opcodes.DUP);
            visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", "()V", false);
        }
        if (bridge.isThisAsParameter()) {
            visitor.visitVarInsn(Opcodes.ALOAD, 0);
        }
        if ("args".equals(bridge.getCaptureArguments())) {
            // Instance methods reserve local slot 0 for "this"; static method parameters start at slot 0.
            int local = sourceMethod.isStatic() ? 0 : 1;
            for (Type parameter : Type.getArgumentTypes(sourceMethod.getDescriptor())) {
                visitor.visitVarInsn(parameter.getOpcode(Opcodes.ILOAD), local);
                // long and double occupy two local-variable slots; all other JVM values occupy one.
                local += parameter.getSize();
            }
        } else if ("array".equals(bridge.getCaptureArguments())) {
            emitArgumentArray(visitor, sourceMethod);
        }
        int opcode = isStatic ? Opcodes.INVOKESTATIC
                : destinationClass.isInterface() ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL;
        visitor.visitMethodInsn(opcode, owner, destinationMethod.getName(), Type.getMethodDescriptor(destinationMethod),
                destinationClass.isInterface());
    }

    /**
     * Emits construction and population of an {@code Object[]} containing every source argument.
     * The array reference remains on the operand stack as the single destination argument.
     */
    private static void emitArgumentArray(MethodVisitor visitor, MethodDescription sourceMethod) {
        Type[] parameters = Type.getArgumentTypes(sourceMethod.getDescriptor());
        pushInteger(visitor, parameters.length);
        visitor.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
        int local = sourceMethod.isStatic() ? 0 : 1;
        for (int index = 0; index < parameters.length; index++) {
            Type parameter = parameters[index];
            // AASTORE consumes array, index, and value, so duplicate the array reference for the next element/call.
            visitor.visitInsn(Opcodes.DUP);
            pushInteger(visitor, index);
            visitor.visitVarInsn(parameter.getOpcode(Opcodes.ILOAD), local);
            box(visitor, parameter);
            visitor.visitInsn(Opcodes.AASTORE);
            local += parameter.getSize();
        }
    }

    /**
     * Emits the most compact JVM instruction that pushes a non-negative integer constant onto the operand stack.
     *
     * <p>The JVM defines dedicated one-byte opcodes {@code ICONST_0} through {@code ICONST_5}; therefore the first
     * comparison is with {@code 5}. Larger constants require an instruction followed by an operand:
     * {@code BIPUSH} stores a signed byte, {@code SIPUSH} stores a signed short, and {@code LDC} loads still larger
     * values from the class-file constant pool.</p>
     *
     * @param visitor visitor receiving the generated instruction
     * @param value non-negative integer to push
     */
    private static void pushInteger(MethodVisitor visitor, int value) {
        if (value <= 5) {
            visitor.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE) {
            visitor.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value <= Short.MAX_VALUE) {
            visitor.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            visitor.visitLdcInsn(value);
        }
    }

    /**
     * Boxes a primitive value currently on top of the operand stack by emitting its wrapper's {@code valueOf}
     * invocation. Reference and array values need no conversion and leave the method unchanged.
     *
     * <p>JVM arrays created with {@code ANEWARRAY java/lang/Object} can store only references. Primitive source
     * arguments are raw numeric values on the operand stack, so they must become wrapper references before
     * {@code AASTORE} can place them into the captured {@code Object[]}.</p>
     *
     * @param visitor visitor receiving the generated boxing call
     * @param type JVM type of the value currently on top of the operand stack
     */
    private static void box(MethodVisitor visitor, Type type) {
        String wrapper;
        String descriptor;
        switch (type.getSort()) {
            case Type.BOOLEAN -> { wrapper = "java/lang/Boolean"; descriptor = "(Z)Ljava/lang/Boolean;"; }
            case Type.BYTE -> { wrapper = "java/lang/Byte"; descriptor = "(B)Ljava/lang/Byte;"; }
            case Type.CHAR -> { wrapper = "java/lang/Character"; descriptor = "(C)Ljava/lang/Character;"; }
            case Type.SHORT -> { wrapper = "java/lang/Short"; descriptor = "(S)Ljava/lang/Short;"; }
            case Type.INT -> { wrapper = "java/lang/Integer"; descriptor = "(I)Ljava/lang/Integer;"; }
            case Type.FLOAT -> { wrapper = "java/lang/Float"; descriptor = "(F)Ljava/lang/Float;"; }
            case Type.LONG -> { wrapper = "java/lang/Long"; descriptor = "(J)Ljava/lang/Long;"; }
            case Type.DOUBLE -> { wrapper = "java/lang/Double"; descriptor = "(D)Ljava/lang/Double;"; }
            default -> { return; }
        }
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, wrapper, "valueOf", descriptor, false);
    }

    /** Returns declared reflection methods whose Byte Buddy descriptions satisfy the source method matcher. */
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

    /** Loads a class without running its static initializer and reports its configuration role on failure. */
    private static Class<?> loadClass(ClassLoader loader, String className, String role)
            throws BridgeConfigurationException {
        try {
            return Class.forName(className, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new BridgeConfigurationException("Could not load " + role + " class " + className, e);
        }
    }

    /** Writes generated class-file bytes back to the compiler output directory. */
    private void writeClasses(Map<String, byte[]> rewrittenClasses) throws IOException {
        for (Map.Entry<String, byte[]> entry : rewrittenClasses.entrySet()) {
            Path classFile = classesDirectory.resolve(entry.getKey().replace('.', '/') + ".class");
            Files.write(classFile, entry.getValue());
        }
    }

    /** Updates existing class entries in the packaged JAR through the ZIP file-system provider. */
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

    /** A bridge configuration together with its parsed source matcher and destination reference. */
    private record ParsedBridge(
            Bridge configuration,
            ElementMatcherFromExpression source,
            MethodReference destination) {
    }
}
