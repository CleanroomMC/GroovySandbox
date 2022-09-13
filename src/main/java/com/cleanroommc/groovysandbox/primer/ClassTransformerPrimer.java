package com.cleanroommc.groovysandbox.primer;

import com.cleanroommc.groovysandbox.exception.SandboxSecurityException;
import com.cleanroommc.groovysandbox.transformer.GroovyClassTransformer;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilationFailedException;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

public class ClassTransformerPrimer extends CompilationCustomizer {

    public static final ClassTransformerPrimer PRIMER = new ClassTransformerPrimer();

    private final ThreadLocal<GroovyClassTransformer> transformer = ThreadLocal.withInitial(GroovyClassTransformer::new);

    private ClassTransformerPrimer() {
        super(CompilePhase.CANONICALIZATION);
    }

    @Override
    public void call(SourceUnit sourceUnit, GeneratorContext context, ClassNode classNode) throws CompilationFailedException {
        GroovyClassTransformer transformer = this.transformer.get();
        transformer.setSourceUnit(sourceUnit);
        transformer.setClassNode(classNode);
        transformer.visitClass(classNode);
        SandboxSecurityException sse = transformer.getAndClearException();
        if (sse != null) {
            // TODO: Decide if this RuntimeException should double-wrap a [CompilationFailedException (SandboxSecurityException)]
            // Since calling CompilationFailedException will simply swallow it
            throw new RuntimeException(sse);
        }
    }

}
