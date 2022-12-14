package com.cleanroommc.groovysandbox.util;

import groovy.lang.Closure;
import org.codehaus.groovy.ast.expr.*;

import java.util.*;

/**
 * Helps with sandbox intercepting Closures, which has unique dispatching rules we need to understand.
 *
 * @author Kohsuke Kawaguchi
 */
// TODO: Change List => Arrays
public class ClosureSupport {

    private static final MethodCallExpression CLOSURE$THIS;

    static {
        CLOSURE$THIS = new MethodCallExpression(new VariableExpression("this"), "composeSelf", new ArgumentListExpression(new ConstantExpression(0, true)));
        CLOSURE$THIS.setImplicitThis(true);
    }

    public static MethodCallExpression getClosureSelfCall() {
        return CLOSURE$THIS;
    }

    /**
     * {@link Closure} forwards methods/properties to other objects, depending on the resolution strategy.
     * <p>
     * This method returns the list of non-null objects that should be considered, in that order.
     */
    public static List<Object> targetsOf(Closure receiver) {
        Object owner = receiver.getOwner();
        Object delegate = receiver.getDelegate();
        // Groovy's method dispatch logic for Closure is defined in MetaClassImpl.invokeMethod
        switch (receiver.getResolveStrategy()) {
            case Closure.OWNER_FIRST:
                return of(owner, delegate);
            case Closure.DELEGATE_FIRST:
                return of(delegate, owner);
            case Closure.OWNER_ONLY:
                return of(owner);
            case Closure.DELEGATE_ONLY:
                return of(delegate);
            default:
                // Fields/Methods defined on Closure are checked by SandboxInterceptor, so if we are here it means we will not find the target of the dispatch.
                return Collections.emptyList();
        }
    }

    private static List<Object> of(Object o1, Object o2) {
        // Various cases where the list of two become the list of one (or empty)
        if (o1 == null) {
            return of(o2);
        }
        if (o2 == null) {
            return of(o1);
        }
        if (o1 == o2) {
            return of(o1);
        }
        return Arrays.asList(o1, o2);
    }

    private static List<Object> of(Object maybeNull) {
        return maybeNull == null ? Collections.emptyList() : Collections.singletonList(maybeNull);
    }

    /**
     * Built-in properties on {@link Closure} that do not follow the delegation rules.
     */
    public static final Set<String> BUILTIN_PROPERTIES = new HashSet<>(Arrays.asList(
            "delegate",
            "owner",
            "maximumNumberOfParameters",
            "parameterTypes",
            "metaClass",
            "class",
            "directive",
            "resolveStrategy",
            "thisObject"
    ));

    private ClosureSupport() { }

}
