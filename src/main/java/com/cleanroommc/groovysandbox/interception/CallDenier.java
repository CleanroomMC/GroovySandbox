package com.cleanroommc.groovysandbox.interception;

import com.cleanroommc.groovysandbox.exception.SandboxSecurityException;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CallDenier extends CallInterceptor {

    public static final CallDenier INSTANCE = new CallDenier();

    private CallDenier() { }

    @Override
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed method call: " + getClassName(receiver) + "." + method + getArgumentClassNames(args));
    }

    @Override
    public Object onStaticCall(Invoker invoker, Class receiver, String method, Object... args) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed static method call: " + getClassName(receiver) + "." + method + getArgumentClassNames(args));
    }

    @Override
    public Object onNewInstance(Invoker invoker, Class receiver, Object... args) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed constructor call: " + getClassName(receiver) + getArgumentClassNames(args));
    }

    @Override
    public Object onSuperCall(Invoker invoker, Class senderType, Object receiver, String method, Object... args) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed super method call: " + getClassName(receiver) + "." + method + getArgumentClassNames(args));
    }

    @Override
    public void onSuperConstructor(Invoker invoker, Class receiver, Object... args) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed super constructor call: " + getClassName(receiver) + getArgumentClassNames(args));
    }

    @Override
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed property get: " + getClassName(receiver) + "." + property);
    }

    @Override
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed property set: " + getClassName(receiver) + "." + property + " = " + getClassName(value));
    }

    @Override
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed attribute get: " + getClassName(receiver) + "." + attribute);
    }

    @Override
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed attribute set: " + getClassName(receiver) + "." + attribute + " = " + getClassName(value));
    }

    @Override
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed array get: " + getClassName(receiver) + "[" + getArrayIndex(index) + "]");
    }

    @Override
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        throw new SandboxSecurityException("Rejecting unsandboxed array set: " + getClassName(receiver) + "[" + getArrayIndex(index) + "] = " + getClassName(value));
    }

    private static String getClassName(Object value) {
        if (value == null) {
            return null;
        } else if (value instanceof Class) {
            return ((Class) value).getName();
        } else {
            return value.getClass().getName();
        }
    }

    private static String getArgumentClassNames(Object[] args) {
        return Stream.of(args).map(CallDenier::getClassName).collect(Collectors.joining(", ", "(", ")"));
    }

    private static String getArrayIndex(Object value) {
        if (value == null) {
            return "null";
        } else if (value instanceof Integer) {
            return value.toString();
        } else {
            return value.getClass().getName();
        }
    }

}
