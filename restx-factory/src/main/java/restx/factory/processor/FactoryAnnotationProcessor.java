package restx.factory.processor;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.*;
import com.google.common.io.CharStreams;
import com.samskivert.mustache.Template;
import restx.common.processor.RestxAbstractProcessor;
import restx.factory.*;

import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.inject.Inject;
import javax.inject.Named;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static restx.common.Mustaches.compile;

/**
 * User: xavierhanin
 * Date: 1/18/13
 * Time: 10:02 PM
 */
@SupportedAnnotationTypes({
        "restx.factory.Component",
        "restx.factory.Module",
        "restx.factory.Provides",
        "restx.factory.Alternative",
        "restx.factory.Machine"
})
@SupportedOptions({ "debug" })
public class FactoryAnnotationProcessor extends RestxAbstractProcessor {
    final Template componentMachineTpl;
    final Template alternativeMachineTpl;
    final Template conditionalMachineTpl;
    final Template moduleMachineTpl;
    private final FactoryAnnotationProcessor.ServicesDeclaration machinesDeclaration;

    public FactoryAnnotationProcessor() {
        componentMachineTpl = compile(FactoryAnnotationProcessor.class, "ComponentMachine.mustache");
        alternativeMachineTpl = compile(FactoryAnnotationProcessor.class, "AlternativeMachine.mustache");
        conditionalMachineTpl = compile(FactoryAnnotationProcessor.class, "ConditionalMachine.mustache");
        moduleMachineTpl = compile(FactoryAnnotationProcessor.class, "ModuleMachine.mustache");
        machinesDeclaration = new ServicesDeclaration("restx.factory.FactoryMachine");
    }


    @Override
    protected boolean processImpl(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) throws IOException {
        machinesDeclaration.processing();
        if (roundEnv.processingOver()) {
            machinesDeclaration.generate();
        } else {
            processComponents(roundEnv);
            processAlternatives(roundEnv);
            processModules(roundEnv);
            processMachines(roundEnv);
        }

        return true;
    }

    private void processModules(RoundEnvironment roundEnv) throws IOException {
        for (Element annotation : roundEnv.getElementsAnnotatedWith(Module.class)) {
            try {
                if (!(annotation instanceof TypeElement)) {
                    error("annotating element " + annotation + " of type " + annotation.getKind().name()
                                    + " with @Module is not supported", annotation);
                    continue;
                }
                TypeElement typeElem = (TypeElement) annotation;
                Module mod = typeElem.getAnnotation(Module.class);

                ModuleClass module = new ModuleClass(typeElem.getQualifiedName().toString(), typeElem, mod.priority());
                for (Element element : typeElem.getEnclosedElements()) {
                    if (element instanceof ExecutableElement
                            && element.getKind() == ElementKind.METHOD
                            && element.getAnnotation(Provides.class) != null) {
                        ExecutableElement exec = (ExecutableElement) element;

                        ProviderMethod m = new ProviderMethod(
                                exec.getReturnType().toString(),
                                exec.getSimpleName().toString(),
                                getInjectionName(exec.getAnnotation(Named.class)),
                                exec);

                        buildInjectableParams(exec, m.parameters);

                        buildCheckedExceptions(exec, m.exceptions);

                        module.providerMethods.add(m);
                    }
                }

                generateMachineFile(module);
            } catch (IOException e) {
                fatalError("error when processing " + annotation, e, annotation);
            }
        }
    }

    private void processMachines(RoundEnvironment roundEnv) throws IOException {
        for (Element annotation : roundEnv.getElementsAnnotatedWith(Machine.class)) {
            try {
                if (!(annotation instanceof TypeElement)) {
                    error("annotating element " + annotation + " of type " + annotation.getKind().name()
                                    + " with @Machine is not supported", annotation);
                    continue;
                }
                TypeElement typeElem = (TypeElement) annotation;
                machinesDeclaration.declareService(typeElem.getQualifiedName().toString());
            } catch (Exception e) {
                fatalError("error when processing " + annotation, e, annotation);
            }
        }
    }

    private void processComponents(RoundEnvironment roundEnv) throws IOException {
        for (Element elem : roundEnv.getElementsAnnotatedWith(Component.class)) {
            try {
                if (!(elem instanceof TypeElement)) {
                    error("annotating element " + elem + " of type " + elem.getKind().name()
                                    + " with @Component is not supported", elem);
                    continue;
                }
                TypeElement component = (TypeElement) elem;

                ExecutableElement exec = findInjectableConstructor(component);

                ComponentClass componentClass = new ComponentClass(
                        component.getQualifiedName().toString(),
                        getPackage(component).getQualifiedName().toString(),
                        component.getSimpleName().toString(),
                        getInjectionName(component.getAnnotation(Named.class)),
                        component.getAnnotation(Component.class).priority(),
                        component);

                buildInjectableParams(exec, componentClass.parameters);

                When when = component.getAnnotation(When.class);
                if (when == null) {
                    generateMachineFile(componentClass);
                } else {
                    generateMachineFile(componentClass, when);
                }
            } catch (Exception e) {
                fatalError("error when processing " + elem, e, elem);
            }
        }
    }

    private void processAlternatives(RoundEnvironment roundEnv) throws IOException {
        for (Element elem : roundEnv.getElementsAnnotatedWith(Alternative.class)) {
            try {
                if (!(elem instanceof TypeElement)) {
                    error("annotating element " + elem + " of type " + elem.getKind().name()
                                    + " with @Alternative is not supported", elem);
                    continue;
                }
                TypeElement component = (TypeElement) elem;

                ExecutableElement exec = findInjectableConstructor(component);

                Alternative alternative = component.getAnnotation(Alternative.class);
                TypeElement alternativeTo = null;
                if (alternative != null) {
                    try {
                        alternative.to();
                    } catch (MirroredTypeException mte) {
                        alternativeTo = asTypeElement(mte.getTypeMirror());
                    }
                }

                ComponentClass componentClass = new ComponentClass(
                        component.getQualifiedName().toString(),
                        getPackage(component).getQualifiedName().toString(),
                        component.getSimpleName().toString(),
                        getInjectionName(component.getAnnotation(Named.class)),
                        alternative.priority(),
                        component);

                ComponentClass alternativeToComponentClass = new ComponentClass(
                        alternativeTo.getQualifiedName().toString(),
                        getPackage(alternativeTo).getQualifiedName().toString(),
                        alternativeTo.getSimpleName().toString(),
                        getInjectionName(alternativeTo.getAnnotation(Named.class)),
                        alternative.priority(),
                        alternativeTo);

                When when = component.getAnnotation(When.class);
                if (when == null) {
                    error("an Alternative MUST be annotated with @When to tell when it must be activated", elem);
                    continue;
                }

                buildInjectableParams(exec, componentClass.parameters);

                generateMachineFile(componentClass, alternativeToComponentClass, when);
            } catch (Exception e) {
                fatalError("error when processing " + elem, e, elem);
            }
        }
    }

    private ExecutableElement findInjectableConstructor(TypeElement component) {
        ExecutableElement exec = null;
        for (Element element : component.getEnclosedElements()) {
            if (element instanceof ExecutableElement && element.getKind() == ElementKind.CONSTRUCTOR) {
                if (exec == null
                        || element.getAnnotation(Inject.class) != null) {
                    exec = (ExecutableElement) element;
                    if (exec.getAnnotation(Inject.class) != null) {
                        // if a constructor is marked with @Inject we use it whatever other constructors are available
                        return exec;
                    }
                }
            }
        }
        return exec;
    }

    private void buildCheckedExceptions(ExecutableElement executableElement, List<String> exceptions) {
    	for (TypeMirror e : executableElement.getThrownTypes()) {
    		// Assuming Exceptions never have type arguments. Qualified names include type arguments.
    		String exception = ((TypeElement) ((DeclaredType) e).asElement()).getQualifiedName().toString();
    		exceptions.add(exception);
    	}
    }
    
    private void buildInjectableParams(ExecutableElement executableElement, List<InjectableParameter> parameters) {
        for (VariableElement p : executableElement.getParameters()) {
            parameters.add(new InjectableParameter(
                    p.asType(),
                    p.getSimpleName().toString(),
                    getInjectionName(p.getAnnotation(Named.class))
            ));
        }
    }

    private Optional<String> getInjectionName(Named named) {
        return named != null ? Optional.of(named.value()) : Optional.<String>absent();
    }

    private void generateMachineFile(ModuleClass moduleClass) throws IOException {
        List<ImmutableMap<String, Object>> engines = Lists.newArrayList();

        for (ProviderMethod method : moduleClass.providerMethods) {
            engines.add(ImmutableMap.<String, Object>builder()
                    .put("type", method.type)
                    .put("name", method.name)
                    .put("injectionName", method.injectionName.isPresent() ?
                            method.injectionName.get() : method.name)
                    .put("queriesDeclarations", Joiner.on("\n").join(buildQueriesDeclarationsCode(method.parameters)))
                    .put("queries", Joiner.on(",\n").join(buildQueriesNames(method.parameters)))
                    .put("parameters", Joiner.on(",\n").join(buildParamFromSatisfiedBomCode(method.parameters)))
                    .put("exceptions", method.exceptions.isEmpty() ? false : Joiner.on("|").join(method.exceptions))
                    .build());
        }

        ImmutableMap<String, Object> ctx = ImmutableMap.<String, Object>builder()
                .put("package", moduleClass.pack)
                .put("machine", moduleClass.name + "FactoryMachine")
                .put("moduleFqcn", moduleClass.fqcn)
                .put("moduleType", moduleClass.name)
                .put("priority", moduleClass.priority)
                .put("engines", engines)
                .build();

        generateJavaClass(moduleClass.fqcn + "FactoryMachine", moduleMachineTpl, ctx,
                Collections.singleton(moduleClass.originatingElement));
    }


    private void generateMachineFile(ComponentClass componentClass, ComponentClass alternativeTo, When when) throws IOException {
        ImmutableMap<String, String> ctx = ImmutableMap.<String, String>builder()
                .put("package", componentClass.pack)
                .put("machine", componentClass.name + "FactoryMachine")
                .put("componentFqcn", componentClass.fqcn)
                .put("componentType", componentClass.name)
                .put("priority", String.valueOf(componentClass.priority))
                .put("whenName", when.name())
                .put("whenValue", when.value())
                .put("componentInjectionName", componentClass.injectionName.or(componentClass.name))
                .put("alternativeToComponentFqcn", alternativeTo.fqcn)
                .put("alternativeToComponentType", alternativeTo.name)
                .put("alternativeToComponentName", alternativeTo.injectionName.or(alternativeTo.name))
                .put("queriesDeclarations", Joiner.on("\n").join(buildQueriesDeclarationsCode(componentClass.parameters)))
                .put("queries", Joiner.on(",\n").join(buildQueriesNames(componentClass.parameters)))
                .put("parameters", Joiner.on(",\n").join(buildParamFromSatisfiedBomCode(componentClass.parameters)))
                .build();

        generateJavaClass(componentClass.pack + "." + componentClass.name + "FactoryMachine", alternativeMachineTpl, ctx,
                Collections.singleton(componentClass.originatingElement));
    }

    private void generateMachineFile(ComponentClass componentClass, When when) throws IOException {
        ImmutableMap<String, String> ctx = ImmutableMap.<String, String>builder()
                .put("package", componentClass.pack)
                .put("machine", componentClass.name + "FactoryMachine")
                .put("componentFqcn", componentClass.fqcn)
                .put("componentType", componentClass.name)
                .put("priority", String.valueOf(componentClass.priority))
                .put("whenName", when.name())
                .put("whenValue", when.value())
                .put("componentInjectionName", componentClass.injectionName.isPresent() ?
                        componentClass.injectionName.get() : componentClass.name)
                .put("queriesDeclarations", Joiner.on("\n").join(buildQueriesDeclarationsCode(componentClass.parameters)))
                .put("queries", Joiner.on(",\n").join(buildQueriesNames(componentClass.parameters)))
                .put("parameters", Joiner.on(",\n").join(buildParamFromSatisfiedBomCode(componentClass.parameters)))
                .build();

        generateJavaClass(componentClass.pack + "." + componentClass.name + "FactoryMachine", conditionalMachineTpl, ctx,
                Collections.singleton(componentClass.originatingElement));
    }

    private void generateMachineFile(ComponentClass componentClass) throws IOException {
        ImmutableMap<String, String> ctx = ImmutableMap.<String, String>builder()
                .put("package", componentClass.pack)
                .put("machine", componentClass.name + "FactoryMachine")
                .put("componentFqcn", componentClass.fqcn)
                .put("componentType", componentClass.name)
                .put("priority", String.valueOf(componentClass.priority))
                .put("componentInjectionName", componentClass.injectionName.isPresent() ?
                        componentClass.injectionName.get() : componentClass.name)
                .put("queriesDeclarations", Joiner.on("\n").join(buildQueriesDeclarationsCode(componentClass.parameters)))
                .put("queries", Joiner.on(",\n").join(buildQueriesNames(componentClass.parameters)))
                .put("parameters", Joiner.on(",\n").join(buildParamFromSatisfiedBomCode(componentClass.parameters)))
                .build();

        generateJavaClass(componentClass.pack + "." + componentClass.name + "FactoryMachine", componentMachineTpl, ctx,
                Collections.singleton(componentClass.originatingElement));

    }

    private List<String> buildQueriesDeclarationsCode(List<InjectableParameter> parameters) {
        List<String> parametersCode = Lists.newArrayList();
        for (InjectableParameter parameter : parameters) {
            parametersCode.add(parameter.getQueryDeclarationCode());
        }
        return parametersCode;
    }

    private List<String> buildQueriesNames(List<InjectableParameter> parameters) {
        List<String> parametersCode = Lists.newArrayList();
        for (InjectableParameter parameter : parameters) {
            parametersCode.add(parameter.name);
        }
        return parametersCode;
    }

    private List<String> buildParamFromSatisfiedBomCode(List<InjectableParameter> parameters) {
        List<String> parametersCode = Lists.newArrayList();
        for (InjectableParameter parameter : parameters) {
            parametersCode.add(parameter.getFromSatisfiedBomCode());
        }
        return parametersCode;
    }

    private static class ComponentClass {
        final String fqcn;

        final List<InjectableParameter> parameters = Lists.newArrayList();
        final Element originatingElement;
        final String pack;
        final String name;
        final int priority;
        final Optional<String> injectionName;

        ComponentClass(String fqcn,
                       String pack, String name,
                       Optional<String> injectionName, int priority, Element originatingElement) {
            this.fqcn = fqcn;
            this.injectionName = injectionName;
            this.priority = priority;

            this.pack = pack;
            this.name = name;
            this.originatingElement = originatingElement;
        }
    }

    private static class InjectableParameter {
        private static final Class[] iterableClasses = new Class[]{
                Iterable.class, Collection.class, List.class, Set.class,
                ImmutableList.class, ImmutableSet.class};

        final TypeMirror baseType;
        final String name;
        final Optional<String> injectionName;

        private InjectableParameter(TypeMirror baseType, String name, Optional<String> injectionName) {
            this.baseType = baseType;
            this.name = name;
            this.injectionName = injectionName;
        }

        public String getQueryDeclarationCode() {
            TypeMirror targetType = targetType();
            String optionalOrNotQueryQualifier = isGuavaOptionalType() || isJava8OptionalType() || isMultiType() ? "optional()" : "mandatory()";

            if (injectionName.isPresent()) {
                return String.format("private final Factory.Query<%s> %s = Factory.Query.byName(Name.of(%s, \"%s\")).%s;",
                        targetType, name, targetType + ".class", injectionName.get(), optionalOrNotQueryQualifier);
            } else {
                return String.format("private final Factory.Query<%s> %s = Factory.Query.byClass(%s).%s;",
                        targetType, name, targetType + ".class", optionalOrNotQueryQualifier);
            }
        }

        public String getFromSatisfiedBomCode() {
            if (isGuavaOptionalType()) {
                return String.format("satisfiedBOM.getOneAsComponent(%s)", name);
            } else if (isJava8OptionalType()) {
                return String.format("java.util.Optional.ofNullable(satisfiedBOM.getOneAsComponent(%s).orNull())", name);
            } else if (isMultiType()) {
                String code = String.format("satisfiedBOM.getAsComponents(%s)", name);
                if (baseType.toString().startsWith(Collection.class.getCanonicalName())
                        || baseType.toString().startsWith(List.class.getCanonicalName())) {
                    code = String.format("com.google.common.collect.Lists.newArrayList(%s)", code);
                } else if (baseType.toString().startsWith(Set.class.getCanonicalName())) {
                    code = String.format("com.google.common.collect.Sets.newLinkedHashSet(%s)", code);
                } else if (baseType.toString().startsWith(ImmutableList.class.getCanonicalName())) {
                    code = String.format("com.google.common.collect.ImmutableList.copyOf(%s)", code);
                } else if (baseType.toString().startsWith(ImmutableSet.class.getCanonicalName())) {
                    code = String.format("com.google.common.collect.ImmutableSet.copyOf(%s)", code);
                }
                return code;
            } else {
                return String.format("satisfiedBOM.getOne(%s).get().getComponent()", name);
            }
        }

        private TypeMirror targetType() {
            if (isGuavaOptionalType() || isJava8OptionalType() || isMultiType()) {
                DeclaredType declaredBaseType = (DeclaredType) baseType;
                if(declaredBaseType.getTypeArguments().isEmpty()){
                    throw new RuntimeException("Optional | Collection type for parameter " + name + " needs" +
                            " parameterized type (generics) to be processed correctly !");
                }
                return declaredBaseType.getTypeArguments().get(0);
            } else {
                return baseType;
            }
        }

        private boolean isGuavaOptionalType() {
            return baseType.toString().startsWith(Optional.class.getCanonicalName());
        }
        private boolean isJava8OptionalType() {
            return baseType.toString().startsWith("java.util.Optional");
        }

        private boolean isMultiType() {
            for (Class it : iterableClasses) {
                if (baseType.toString().startsWith(it.getCanonicalName())) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class ModuleClass {
        final String fqcn;

        final List<ProviderMethod> providerMethods = Lists.newArrayList();
        final Element originatingElement;
        final String pack;
        final String name;
        final int priority;

        ModuleClass(String fqcn, Element originatingElement, int priority) {
            this.fqcn = fqcn;
            this.pack = fqcn.substring(0, fqcn.lastIndexOf('.'));
            this.name = fqcn.substring(fqcn.lastIndexOf('.') + 1);
            this.originatingElement = originatingElement;
            this.priority = priority;
        }
    }

    private static class ProviderMethod {
        final Element originatingElement;
        final String type;
        final String name;
        final Optional<String> injectionName;
        final List<InjectableParameter> parameters = Lists.newArrayList();
        final List<String> exceptions = Lists.newArrayList();

        ProviderMethod(String type, String name, Optional<String> injectionName, Element originatingElement) {
            this.type = type;
            this.name = name;
            this.injectionName = injectionName;
            this.originatingElement = originatingElement;
        }
    }


    private class ServicesDeclaration {
        private final Set<String> declaredServices = Sets.newHashSet();
        private final String targetFile;
        private FileObject fileObject;

        private ServicesDeclaration(String targetFile) {
            this.targetFile = "META-INF/services/" + targetFile;
        }

        void declareService(String service) {
            declaredServices.add(service);
        }

        void generate() throws IOException {
            if (declaredServices.isEmpty()) {
                return;
            }

            writeServicesFile(targetFile);

            declaredServices.clear();

            fileObject = null;
        }


        public void processing() throws IOException {
            readExistingServicesIfExists(targetFile);
        }

        private void writeServicesFile(String targetFile) throws IOException {
            if (fileObject != null
                    && fileObject.getClass().getSimpleName().equals("EclipseFileObject")
                    ) {
                // eclipse does not allow to do a createResource for a fileobject already obtained via getResource
                // but the file object can be used for writing, so it's ok to reuse it in this case

                // see source code at:
                // https://github.com/eclipse/eclipse.jdt.core/blob/master/org.eclipse.jdt.compiler.apt/src/org/eclipse/jdt/internal/compiler/apt/dispatch/BatchProcessingEnvImpl.java
                // https://github.com/eclipse/eclipse.jdt.core/blob/master/org.eclipse.jdt.compiler.tool/src/org/eclipse/jdt/internal/compiler/tool/EclipseFileObject.java
            } else {
                try {
                    fileObject = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", targetFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            try (Writer writer = fileObject.openWriter()) {
                for (String declaredService : Ordering.natural().sortedCopy(declaredServices)) {
                    writer.write(declaredService + "\n");
                }
            }
        }

        private void readExistingServicesIfExists(String targetFile) throws IOException {
            try {
                if (fileObject == null) {
                    fileObject = processingEnv.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", targetFile);
                }
                try (Reader r = fileObject.openReader(true)) {
                    declaredServices.addAll(CharStreams.readLines(r));
                }
            } catch (FileNotFoundException ex) {
                /*
                   This is a very strange behaviour of javac during incremantal compilation (at least experienced
                   with Intellij make process): a FileNotFoundException is raised while the file actually exist.

                   "Fortunately" the exception message is the path of the file, so we can try to load it using
                   plain java.io
                 */
                try {
                    File file = new File(ex.getMessage());
                    if (file.exists()) {
                        try (Reader r = new FileReader(file)) {
                            declaredServices.addAll(CharStreams.readLines(r));
                        } catch (IOException e) {
                            // ignore
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            } catch (IOException | IllegalArgumentException ex) {
                // ignore
            }
        }
    }
}
