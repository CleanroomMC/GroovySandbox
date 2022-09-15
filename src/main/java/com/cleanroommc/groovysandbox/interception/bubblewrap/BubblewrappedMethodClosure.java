package com.cleanroommc.groovysandbox.interception.bubblewrap;

import groovy.lang.MetaClassImpl;
import org.codehaus.groovy.runtime.InvokerInvocationException;
import org.codehaus.groovy.runtime.MethodClosure;

public class BubblewrappedMethodClosure extends MethodClosure {

    private static final Object[] SINGULAR_ELEMENT_ARRAY = new Object[1];
    private static final String DUMMY_METHOD_NAME = "BubblewrappedClosure";

    public BubblewrappedMethodClosure(Object owner, String method) {
        super(owner, method);
    }

    @Override
    protected Object doCall(Object arguments) {
        if (arguments == null) {
            return doCall(Bubblewrap.EMPTY_ARRAY);
        }
        if (arguments instanceof Object[]) {
            return (Object[]) arguments;
        }
        SINGULAR_ELEMENT_ARRAY[0] = arguments;
        return SINGULAR_ELEMENT_ARRAY;
    }

    /**
     * Special logic needed to handle invocation due to not being an instance of MethodClosure itself. See
     * {@link MetaClassImpl#invokeMethod(Class, Object, String, Object[], boolean, boolean)} and its special handling
     * of {@code objectClass == MethodClosure.class}.
     */
    protected Object doCall(Object[] arguments) {
        try {
            return Bubblewrap.wrapCall(getOwner(), false, false, getMethod(), arguments, DUMMY_METHOD_NAME, -1);
        } catch (Throwable e) {
            throw new InvokerInvocationException(e);
        }
    }

    protected Object doCall() {
        return doCall(Bubblewrap.EMPTY_ARRAY);
    }

}
