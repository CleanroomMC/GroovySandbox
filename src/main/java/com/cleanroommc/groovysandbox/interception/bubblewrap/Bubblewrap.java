package com.cleanroommc.groovysandbox.interception.bubblewrap;

import com.cleanroommc.groovysandbox.util.ClosureSupport;
import com.cleanroommc.groovysandbox.exception.SandboxSecurityException;
import com.cleanroommc.groovysandbox.interception.CallInterceptor;
import com.cleanroommc.groovysandbox.interception.InterceptChain;
import com.cleanroommc.groovysandbox.util.Operators;
import groovy.lang.*;
import org.codehaus.groovy.classgen.asm.BinaryExpressionHelper;
import org.codehaus.groovy.reflection.CachedField;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.reflection.ParameterTypes;
import org.codehaus.groovy.runtime.*;
import org.codehaus.groovy.runtime.callsite.*;
import org.codehaus.groovy.syntax.Types;

import java.io.File;
import java.lang.reflect.*;
import java.util.*;

import static org.codehaus.groovy.runtime.MetaClassHelper.convertToTypeArray;

public class Bubblewrap {

    private static final DummyCallSite CALL_SITE = new DummyCallSite();
    private static final Object[] SINGULAR_ELEMENT_ARRAY = new Object[1];
    private static final ThreadLocal<String> SOURCE = new ThreadLocal<>();
    private static final ThreadLocal<Integer> LINE_NUMBER = new ThreadLocal<>();

    public static Object wrapStaticCall(Class receiver, String method, String source, int line, Object... args) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapStaticCall(receiver, method, args);
    }

    public static Object wrapStaticCall(Class receiver, String method, Object... args) throws Throwable {
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onStaticCall(this, (Class) receiver, method, args);
                } else {
                    setCallSite(method);
                    return CALL_SITE.callStatic((Class) receiver, args);
                }
            }
        }.call(receiver, method, fixNullArgs(args));
    }

    public static Object wrapConstructorCall(Class type, String source, int line, Object... args) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapConstructorCall(type, args);
    }

    public static Object wrapConstructorCall(Class type, Object... args) throws Throwable {
        CallSiteSelector.findConstructor(type, args); // TODO: cache this in a lookup?
        return new InterceptChain() {
            @Override
            public Object call(Object type, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onNewInstance(this, (Class) type, args);
                } else {
                    setCallSite("<init>");
                    return CALL_SITE.callConstructor(type, args);
                }
            }
        }.call(type, "<init>", fixNullArgs(args));
    }

    public static Object wrapCall(Object receiver, boolean safe, boolean spread, String method, String source, int line, Object... args) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapCall(receiver, safe, spread, method, args);
    }

    public static Object wrapCall(Object receiver, boolean safe, boolean spread, String method, Object... args) throws Throwable {
        if (safe && receiver == null) {
            return null;
        }
        args = fixNullArgs(args);
        if (spread) {
            List<Object> ret = new ArrayList<>();
            Iterator iter = InvokerHelper.asIterator(receiver);
            while (iter.hasNext()) {
                Object it = iter.next();
                if (it != null) {
                    ret.add(wrapCall(it, true, false, method, args));
                }
            }
            return ret;
        }
        if (receiver instanceof Class) {
            MetaClass metaClass = InvokerHelper.getMetaClass((Class) receiver);
            if (metaClass instanceof MetaClassImpl) {
                MetaClassImpl metaClassImpl = (MetaClassImpl) metaClass;
                MetaMethod metaMethod = metaClassImpl.retrieveStaticMethod(method, args);
                if (metaMethod != null && metaMethod.isStatic()) {
                    if (metaMethod.getDeclaringClass().getTheClass() == Class.class) {
                        return wrapStaticCall(Class.class, method, args);
                    } else {
                        return wrapStaticCall((Class) receiver, method, args);
                    }
                }
            }
        }
        if (receiver instanceof Closure) {
            if ("invokeMethod".equals(method) && isInvokingMethodOnClosure(receiver, method, args)) {
                // If someone is calling closure.invokeMethod("foo", args), map that back to closure.foo("args")
                method = args[0].toString();
                args = (Object[]) args[1];
            }
            MetaMethod metaMethod = InvokerHelper.getMetaClass(receiver).pickMethod(method, MetaClassHelper.convertToTypeArray(args));
            if (metaMethod == null) {
                // If we are trying to call a method that's actually defined in Closure, then we'll get non-null 'm'
                // In that case, treat it like normal method call.
                // If we are here, that means we are trying to delegate the call to 'owner', 'delegate', etc.
                // It will check access accordingly. Groovy's corresponding code is in MetaClassImpl.invokeMethod(...)

                // TODO: Change List => Array, tighter loops
                List<Object> targets = ClosureSupport.targetsOf((Closure) receiver);
                Class[] argTypes = convertToTypeArray(args);

                // First phase: look for exact method match
                for (Object candidate : targets) {
                    if (InvokerHelper.getMetaClass(candidate).pickMethod(method, argTypes) != null) {
                        return wrapCall(candidate, false, false, method, args);
                    }
                }
                // Second phase: try calling invokeMethod on them
                for (Object candidate : targets) {
                    try {
                        return wrapCall(candidate, false, false, "invokeMethod", method, args);
                    } catch (MissingMethodException ignored) { } // Try the next one
                }
                // We tried to be smart about Closure.invokeMethod, but we are just not finding any.
                // So we'll have to treat this like any other method.
            }
        }
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onMethodCall(this, receiver, method, args);
                } else {
                    setCallSite(method);
                    return CALL_SITE.call(receiver, args);
                }
            }
        }.call(receiver, method, args);
    }

    public static Object wrapSuperCall(Class senderType, Object receiver, String method, Object[] args, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapSuperCall(senderType, receiver, method, args);
    }

    public static Object wrapSuperCall(Class senderType, Object receiver, String method, Object[] args) throws Throwable {
        // Super $super = new Super(senderType, receiver);
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onSuperCall(this, senderType, receiver, method, args);
                } else {
                    try {
                        MetaClass metaClass = InvokerHelper.getMetaClass(receiver.getClass());
                        return metaClass.invokeMethod(senderType.getSuperclass(), receiver, method, args, true, true);
                    } catch (GroovyRuntimeException exception) {
                        throw ScriptBytecodeAdapter.unwrap(exception);
                    }
                }
            }
        }.call(/*$super*/null, method, fixNullArgs(args));
    }

    public static SuperConstructorWrapper wrapSuperConstructor(Class<?> thisClass, Class<?> superClass, Object[] superCallArgs, Object[] constructorArgs,
                                                               Class<?>[] paramTypes) throws Throwable {
        // Make sure that the call to this synthetic constructor is not illegal.
        CallSiteSelector.findConstructor(superClass, superCallArgs);
        explicitConstructorCallSanity(thisClass, SuperConstructorWrapper.class, constructorArgs, paramTypes);
        new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    callInterceptor.onSuperConstructor(this, superClass, args);
                }
                return null;
            }
        }.call(superClass, null, fixNullArgs(superCallArgs));
        return new SuperConstructorWrapper(superCallArgs);
    }

    public static Object wrapThisConstructor(final Class<?> clazz, Object[] thisCallArgs, Object[] constructorArgs, Class<?>[] constructorParamTypes, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapThisConstructor(clazz, thisCallArgs, constructorArgs, constructorParamTypes);
    }

    public static ThisConstructorWrapper wrapThisConstructor(final Class<?> clazz, Object[] thisCallArgs, Object[] constructorArgs, Class<?>[] paramTypes) throws Throwable {
        // Make sure that the call to this synthetic constructor is not illegal.
        CallSiteSelector.findConstructor(clazz, thisCallArgs);
        explicitConstructorCallSanity(clazz, ThisConstructorWrapper.class, constructorArgs, paramTypes);
        new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    callInterceptor.onNewInstance(this, clazz, args);
                }
                return null;
            }
        }.call(clazz, null, fixNullArgs(thisCallArgs));
        return new ThisConstructorWrapper(thisCallArgs);
    }

    public static Object wrapGetProperty(final Object receiver, boolean safe, boolean spread, Object property, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapGetProperty(receiver, safe, spread, property);
    }

    public static Object wrapGetProperty(final Object receiver, boolean safe, boolean spread, Object property) throws Throwable {
        if (safe && receiver == null) {
            return null;
        }
        if (spread || (receiver instanceof Collection && !ClosureSupport.BUILTIN_PROPERTIES.contains(property))) {
            List<Object> ret = new ArrayList<>();
            Iterator itr = InvokerHelper.asIterator(receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it != null) {
                    ret.add(wrapGetProperty(it, true, false, property));
                }
            }
            return ret;
        }
        // 1st try: do the same call site stuff
        // return fakeCallSite(property.toString()).callGetProperty(receiver);
        if (isInvokingMethodOnClosure(receiver, "getProperty", property) && !ClosureSupport.BUILTIN_PROPERTIES.contains(property)) {
            // If we are trying to invoke Closure.getProperty(), we want to find out where the call is going to, and check that target
            MissingPropertyException x = null;
            for (Object candidate : ClosureSupport.targetsOf((Closure) receiver)) {
                try {
                    return wrapGetProperty(candidate, false, false, property);
                } catch (MissingPropertyException e) {
                    x = e; // Try the next one
                }
            }
            if (x != null) {
                throw x;
            }
            throw new MissingPropertyException(property.toString(), receiver.getClass());
        }
        if (receiver instanceof Map) { // MetaClassImpl.getProperty looks for Map subtype and handles it as Map.get call, so dispatch that call accordingly.
            return wrapCall(receiver, false, false, "get", property);
        }
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String property, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onGetProperty(this, receiver, property);
                } else {
                    return ScriptBytecodeAdapter.getProperty(null, receiver, property);
                }
            }
        }.call(receiver, property.toString());
    }

    public static Object wrapSetProperty(Object receiver, Object property, boolean safe, boolean spread, int operator, Object value, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapSetProperty(receiver, property, safe, spread, operator, value);
    }

    public static Object wrapSetProperty(Object receiver, Object property, boolean safe, boolean spread, int operator, Object value) throws Throwable {
        if (operator != Types.ASSIGN) { // A compound assignment operator is decomposed into get + operator + set. E.g, a.x += y  => a.x = a.x + y
            Object v = wrapGetProperty(receiver, safe, spread, property);
            return wrapSetProperty(receiver, property, safe, spread, Types.ASSIGN, wrapBinaryOperation(v, Operators.compoundAssignmentToBinaryOperator(operator), value));
        }
        if (safe && receiver == null) {
            return value;
        }
        if (spread) {
            Iterator itr = InvokerHelper.asIterator(receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it != null) {
                    wrapSetProperty(it, property, true, false, operator, value);
                }
            }
            return value;
        }
        if (isInvokingMethodOnClosure(receiver, "setProperty", property, value) && !ClosureSupport.BUILTIN_PROPERTIES.contains(property)) {
            // If we are trying to invoke Closure.setProperty(), we want to find out where the call is going to, and check that target
            GroovyRuntimeException x = null;
            for (Object candidate : ClosureSupport.targetsOf((Closure) receiver)) {
                try {
                    return wrapSetProperty(candidate, property, false, false, operator, value);
                } catch (GroovyRuntimeException e) { // Catching GroovyRuntimeException feels questionable, but this is how Groovy does it in Closure.setPropertyTryThese()
                    x = e; // Try the next one
                }
            }
            if (x != null) {
                throw x;
            }
            throw new MissingPropertyException(property.toString(), receiver.getClass());
        }
        if (receiver instanceof Map) { // MetaClassImpl.getProperty looks for Map subtype and handles it as Map.put call, so dispatch that call accordingly.
            wrapCall(receiver, false, false, "put", property, value);
            return value;
        }
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String property, Object... value) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onSetProperty(this, receiver, property, value);
                } else {
                    ScriptBytecodeAdapter.setProperty(value, null, receiver, property);
                    return value;
                }
            }
        }.call(receiver, property.toString(), value);
    }

    public static Object wrapGetAttribute(Object receiver, boolean safe, boolean spread, Object property, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapGetAttribute(receiver, safe, spread, property);
    }

    public static Object wrapGetAttribute(Object receiver, boolean safe, boolean spread, Object property) throws Throwable {
        if (safe && receiver == null) {
            return null;
        }
        if (spread) {
            List<Object> r = new ArrayList<>();
            Iterator itr = InvokerHelper.asIterator(receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it != null) {
                    r.add(wrapGetAttribute(it, true, false, property));
                }
            }
            return r;
        }
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String property, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onGetAttribute(this, receiver, property);
                } else {
                    return ScriptBytecodeAdapter.getField(null, receiver, property); // According to AsmClassGenerator this is how the compiler maps it
                }
            }
        }.call(receiver, property.toString());
    }

    public static Object wrapSetAttribute(Object receiver, Object property, boolean safe, boolean spread, int operator, Object value, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapSetAttribute(receiver, property, safe, spread, operator, value);
    }

    /**
     * Intercepts the attribute assignment of the form "receiver.@property = value"
     *
     * @param operator One of the assignment operators of {@link Types}
     */
    public static Object wrapSetAttribute(Object receiver, Object property, boolean safe, boolean spread, int operator, Object value) throws Throwable {
        if (operator != Types.ASSIGN) {  // A compound assignment operator is decomposed into get + operator + set. E.g, a.@x += y  => a.@x = a.@x + y
            Object v = wrapGetAttribute(receiver, safe, spread, property);
            return wrapSetAttribute(receiver, property, safe, spread, Types.ASSIGN, wrapBinaryOperation(v, Operators.compoundAssignmentToBinaryOperator(operator), value));
        }
        if (safe && receiver == null) {
            return value;
        }
        if (spread) {
            Iterator itr = InvokerHelper.asIterator(receiver);
            while (itr.hasNext()) {
                Object it = itr.next();
                if (it != null) {
                    wrapSetAttribute(it, property, true, false, operator, value);
                }
            }
            return value;
        }
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String property, Object... value) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onSetAttribute(this, receiver, property, value);
                } else {
                    ScriptBytecodeAdapter.setField(value, null, receiver, property); // According to AsmClassGenerator this is how the compiler maps it
                    return value;
                }
            }
        }.call(receiver, property.toString(), value);
    }

    public static Object wrapGetArray(Object receiver, Object index, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapGetArray(receiver, index);
    }

    public static Object wrapGetArray(Object receiver, Object index) throws Throwable {
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onGetArray(this, receiver, index);
                } else {
                    CALL_SITE.name = "getAt"; // BinaryExpressionHelper.eval maps this to "getAt" call
                    return CALL_SITE.call(receiver, index);
                }
            }
        }.call(receiver, null, index);
    }

    public static Object wrapSetArray(Object receiver, Object index, int operator, Object value, String source, int line) throws Throwable {
        SOURCE.set(source);
        LINE_NUMBER.set(line);
        return wrapSetArray(receiver, index, operator, value);
    }

    /**
     * Intercepts the array assignment of the form "receiver[index] = value"
     *
     * @param operator One of the assignment operators of {@link Types}
     */
    public static Object wrapSetArray(Object receiver, Object index, int operator, Object value) throws Throwable {
        if (operator != Types.ASSIGN) {  // A compound assignment operator is decomposed into get + operator + set. E.g, a.[x] += y  => a.[x] = a.[x] + y
            Object v = wrapGetArray(receiver, index);
            return wrapSetArray(receiver, index, Types.ASSIGN, wrapBinaryOperation(v, Operators.compoundAssignmentToBinaryOperator(operator), value));
        }
        return new InterceptChain() {
            @Override
            public Object call(Object receiver, String method, Object... args) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    return callInterceptor.onSetArray(this, receiver, args[0], args[1]);
                } else {
                    CALL_SITE.name = "putAt"; // BinaryExpressionHelper.assignToArray maps this to "putAt" call
                    return CALL_SITE.call(receiver, index, value);
                }
            }
        }.call(receiver, null, index, value);
    }

    /**
     * ++a[i] / --a[i]
     */
    public static Object wrapPrefixArray(Object r, Object i, String operator) throws Throwable {
        Object o = wrapGetArray(r, i);
        Object n = wrapCall(o, false, false, operator);
        wrapSetArray(r, i, Types.ASSIGN, n);
        return n;
    }

    /**
     * a[i]++ / a[i]--
     *
     * @param operator "next" for ++, "previous" for --. These names are defined by Groovy.
     */
    public static Object wrapPostfixArray(Object r, Object i, String operator) throws Throwable {
        Object o = wrapGetArray(r, i);
        Object n = wrapCall(o, false, false, operator);
        wrapSetArray(r, i, Types.ASSIGN, n);
        return o;
    }

    /**
     * ++a.x / --a.x
     */
    public static Object wrapPrefixProperty(Object receiver, Object property, boolean safe, boolean spread, String operator) throws Throwable {
        Object o = wrapGetProperty(receiver, safe, spread, property);
        Object n = wrapCall(o, false, false, operator);
        wrapSetProperty(receiver, property, safe, spread, Types.ASSIGN, n);
        return n;
    }

    /**
     * a.x++ / a.x--
     */
    public static Object wrapPostfixProperty(Object receiver, Object property, boolean safe, boolean spread, String operator) throws Throwable {
        Object o = wrapGetProperty(receiver, safe, spread, property);
        Object n = wrapCall(o, false, false, operator);
        wrapSetProperty(receiver, property, safe, spread, Types.ASSIGN, n);
        return o;
    }

    /**
     * Intercepts the binary expression of the form {@code lhs operator rhs} like {@code lhs + rhs}, {@code lhs >> rhs}, etc.
     * <p>
     * In Groovy, binary operators are method calls.
     *
     * @param operator One of the binary operators of {@link Types}
     * @see BinaryExpressionHelper#evaluateBinaryExpressionWithAssignment
     */
    public static Object wrapBinaryOperation(Object lhs, int operator, Object rhs) throws Throwable {
        return wrapCall(lhs, false, false, Operators.binaryOperatorMethods(operator), rhs);
    }

    /**
     * A compare method that invokes a.equals(b) or a.compareTo(b) == 0
     */
    public static Object wrapComparison(Object lhs, final int operator, Object rhs) throws Throwable {
        if (lhs == null) { // Bypass the checker if lhs is null, as it will not result in any calls that will require protection
            return InvokerHelper.invokeStaticMethod(ScriptBytecodeAdapter.class, Operators.binaryOperatorMethods(operator), new Object[] { null, rhs });
        }
        return new InterceptChain() {
            @Override
            public Object call(Object lhs, String method, Object... rhs) throws Throwable {
                CallInterceptor callInterceptor = next();
                if (callInterceptor != null) {
                    // Based on what ScriptBytecodeAdapter does
                    return callInterceptor.onMethodCall(this, lhs, lhs instanceof Comparable ? "compareTo" : "equals", rhs);
                } else {
                    return InvokerHelper.invokeStaticMethod(ScriptBytecodeAdapter.class, Operators.binaryOperatorMethods(operator), new Object[] { lhs, rhs });
                }
            }
        }.call(lhs, null, rhs);
    }

    /**
     * Runs {@link ScriptBytecodeAdapter#asType} but only after giving interceptors the chance to reject any possible interface methods as applied to the receiver.
     * E.g: might run {@code receiver.method1(null, false)} and {@code receiver.method2(0, null)} if methods with matching signatures were defined in the interfaces.
     */
    public static Object wrapCast(Class<?> clazz, Object exp, boolean ignoreAutoboxing, boolean coerce, boolean strict) throws Throwable {
        return preWrappedCast(clazz, exp, ignoreAutoboxing, coerce, strict);
    }

    // TODO: investigate
    public static Object preWrappedCast(Class<?> clazz, Object exp, boolean ignoreAutoboxing, boolean coerce, boolean strict) throws Throwable {
        // Note: Be careful calling methods on exp here since the user has control over that object. (See DefaultGroovyMethods.asType(Collection, Class))
        if (exp != null && !(Collection.class.isAssignableFrom(clazz) && clazz.getPackage().getName().equals("java.util"))) {
            // Don't actually cast at all if this is already assignable.
            if (clazz.isAssignableFrom(exp.getClass())) {
                return exp;
            } else if (clazz.isInterface()) {
                for (Method method : clazz.getMethods()) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    Object[] args = new Object[paramTypes.length];
                    for (int i = 0; i < args.length; i++) {
                        args[i] = getDefaultValue(paramTypes[i]);
                    }
                    // We intercept all methods defined on the interface to ensure they are permitted, and deliberately ignore the return value
                    new InterceptChain() {
                        @Override
                        public Object call(Object receiver, String method, Object... args) throws Throwable {
                            CallInterceptor callInterceptor = next();
                            if (callInterceptor != null) {
                                if (receiver instanceof Class) {
                                    return callInterceptor.onStaticCall(this, (Class) receiver, method, args);
                                } else {
                                    return callInterceptor.onMethodCall(this, receiver, method, args);
                                }
                            } else {
                                return null;
                            }
                        }
                    }.call(exp, method.getName(), args);
                }
            } else if (!clazz.isArray() && clazz != Object.class && !Modifier.isAbstract(clazz.getModifiers()) &&
                    (exp instanceof Collection || exp instanceof Map || exp.getClass().isArray())) {
                // cf. mightBePositionalArgumentConstructor
                Object[] args;
                if (exp instanceof Collection) {
                    if (exp.getClass().getPackage().getName().equals("java.util")) {
                        args = ((Collection) exp).toArray();
                    } else {
                        throw new UnsupportedOperationException(
                                "Casting non-standard Collections to a type via constructor is not supported. " +
                                        "Consider converting " + exp.getClass() + " to a Collection defined in the java.util package and then casting to " + clazz + ".");
                    }
                } else if (exp instanceof Map) {
                    args = new Object[] { exp };
                } else { // Arrays
                    // TODO tricky to determine which constructor will actually be called; array might be expanded, or might not
                    throw new UnsupportedOperationException("Casting arrays to types via constructor is not yet supported");
                }
                // We intercept the constructor that will be used for the cast, and again, deliberately ignore the return value
                new InterceptChain() {
                    @Override
                    public Object call(Object receiver, String method, Object... args) throws Throwable {
                        CallInterceptor callInterceptor = next();
                        if (callInterceptor != null) {
                            return callInterceptor.onNewInstance(this, (Class) receiver, args);
                        } else {
                            return null;
                        }
                    }
                }.call(clazz, null, args);
            } else if (clazz == File.class && exp instanceof CharSequence) {
                // See DefaultTypeTransformation.asCollection
                new InterceptChain() {
                    @Override
                    public Object call(Object receiver, String method, Object... args) throws Throwable {
                        CallInterceptor callInterceptor = next();
                        if (callInterceptor != null) {
                            return callInterceptor.onNewInstance(this, (Class) receiver, args);
                        } else {
                            return null;
                        }
                    }
                }.call(clazz, null, exp.toString());
            } else if (exp instanceof Class && ((Class) exp).isEnum() && (clazz.isArray() || Collection.class.isAssignableFrom(clazz))) {
                // See DefaultTypeTransformation.asCollection
                // We intercept the method that will be used for the cast, and again, deliberately ignore the return value:
                new InterceptChain() {
                    @Override
                    public Object call(Object receiver, String method, Object... args) throws Throwable {
                        CallInterceptor callInterceptor = next();
                        if (callInterceptor != null) {
                            return callInterceptor.onStaticCall(this, (Class) receiver, method, args);
                        } else {
                            return null;
                        }
                    }
                }.call(ResourceGroovyMethods.class, "readLines", exp);
            } else if (exp instanceof Class && ((Class) exp).isEnum() && (clazz.isArray() || Collection.class.isAssignableFrom(clazz))) {
                // See DefaultTypeTransformation.asCollection
                for (Field field : ((Class) exp).getFields()) {
                    if (field.isEnumConstant()) {
                        // We intercept all Enum constants to ensure they are permitted, and deliberately ignore the return value
                        new InterceptChain() {
                            @Override
                            public Object call(Object receiver, String field, Object... args) throws Throwable {
                                CallInterceptor callInterceptor = next();
                                if (callInterceptor != null) {
                                    return callInterceptor.onGetProperty(this, receiver, field);
                                } else {
                                    return null;
                                }
                            }
                        }.call(exp, field.getName());
                    }
                }
            }
        }
        // TODO what does ignoreAutoboxing do?
        return strict ? clazz.cast(exp) : coerce ? ScriptBytecodeAdapter.asType(exp, clazz) : ScriptBytecodeAdapter.castToType(exp, clazz);
    }

    // https://stackoverflow.com/a/38243203/12916
    @SuppressWarnings("unwrap")
    private static <T> T getDefaultValue(Class<T> clazz) {
        return (T) Array.get(Array.newInstance(clazz, 1), 0);
    }

    private static DummyCallSite setCallSite(String name) {
        CALL_SITE.name = name;
        return CALL_SITE;
    }

    private static Object[] fixNullArgs(Object[] args) {
        return args == null ? SINGULAR_ELEMENT_ARRAY : args;
    }

    /**
     * Are we trying to invoke a method defined on Closure or its super type?
     * (If so, we'll need to chase down which method we are actually invoking.)
     *
     * <p>
     * Used for invokeMethod/getProperty/setProperty.
     *
     * <p>
     * If the receiver overrides this method, return false since we don't know how such methods behave.
     */
    private static boolean isInvokingMethodOnClosure(Object receiver, String method, Object... args) {
        MetaMethod m = InvokerHelper.getMetaClass(receiver).pickMethod(method, MetaClassHelper.convertToTypeArray(args));
        return m != null && m.getDeclaringClass().isAssignableFrom(Closure.class);
    }

    /**
     * Makes sure that explicit constructor calls inside the synthetic constructors will go to the intended constructor at runtime (Part of Jenkins [SECURITY-1754]).
     */
    private static void explicitConstructorCallSanity(Class<?> thisClass, Class<?> wrapperClass, Object[] argsExcludingWrapper, Class<?>[] paramsIncludingWrapper) {
        // Construct argument types for the explicit constructor call.
        Class<?>[] argTypes = new Class<?>[argsExcludingWrapper.length + 1];
        argTypes[0] = wrapperClass;
        System.arraycopy(MetaClassHelper.convertToTypeArray(argsExcludingWrapper), 0, argTypes, 1, argsExcludingWrapper.length);
        // Find the constructor that the sandbox is expecting will be called.
        Constructor<?> expectedConstructor;
        try {
            expectedConstructor = thisClass.getDeclaredConstructor(paramsIncludingWrapper);
        } catch (NoSuchMethodException exception) {
            // The original constructor that made it necessary to create a synthetic constructor should always exist.
            throw new AssertionError("Unable to find original constructor", exception);
        }
        ParameterTypes expectedParamTypes = new ParameterTypes(paramsIncludingWrapper);
        for (Constructor<?> ctor : thisClass.getDeclaredConstructors()) {
            // Make sure that no other constructor matches the arguments better than the constructor we are expecting to call
            // Otherwise that would be the constructor that would actually be invoked.
            ParameterTypes paramTypes = new ParameterTypes(ctor.getParameterTypes());
            if (!ctor.equals(expectedConstructor) && paramTypes.isValidMethod(argTypes) && CallSiteSelector.isMoreSpecific(paramTypes, expectedParamTypes, argTypes)) {
                throw new SandboxSecurityException("Rejecting unexpected invocation of constructor: " + ctor + ". Expected to invoke synthetic constructor: " + expectedConstructor);
            }
        }
    }

    private Bubblewrap() { }

    private static class DummyCallSite extends AbstractCallSite {

        private static final CallSiteArray callSiteArray = new CallSiteArray(Bubblewrap.class, new String[] { "" });

        private String name;

        public DummyCallSite() {
            super(callSiteArray, 0, "");
            callSiteArray.array[0] = this;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public Object callGroovyObjectGetProperty(final Object receiver) throws Throwable {
            if (receiver == null) {
                try {
                    return InvokerHelper.getProperty(NullObject.getNullObject(), this.name);
                } catch (GroovyRuntimeException gre) {
                    throw ScriptBytecodeAdapter.unwrap(gre);
                }
            } else {
                return acceptGroovyObjectGetProperty(receiver).getProperty(receiver);
            }
        }

        @Override
        public CallSite acceptGetProperty(final Object receiver) {
            if (receiver == null) {
                return new NullCallSite(this);
            } else if (receiver instanceof GroovyObject) {
                return createGroovyObjectGetPropertySite(receiver);
            } else if (receiver instanceof Class) {
                CallSite site = new InternalClassMetaClassGetPropertySite(this, (Class) receiver);
                array.array[index] = site;
                return site;
            }
            return internal$createPojoMetaClassGetPropertySite(receiver);
        }

        private CallSite internal$createPojoMetaClassGetPropertySite(final Object receiver) {
            final MetaClass metaClass = InvokerHelper.getMetaClass(receiver);
            CallSite site;
            if (metaClass.getClass() != MetaClassImpl.class || GroovyCategorySupport.hasCategoryInCurrentThread()) {
                site = new PojoMetaClassGetPropertySite(this);
            } else {
                final MetaProperty effective = ((MetaClassImpl) metaClass).getEffectiveGetMetaProperty(receiver.getClass(), receiver, this.name, false);
                if (effective != null) {
                    if (effective instanceof CachedField) {
                        site = new InternalGetEffectivePojoFieldSite(this, (MetaClassImpl) metaClass, (CachedField) effective);
                    } else {
                        site = new GetEffectivePojoPropertySite(this, (MetaClassImpl) metaClass, effective);
                    }
                } else {
                    site = new PojoMetaClassGetPropertySite(this);
                }
            }
            array.array[index] = site;
            return site;
        }

    }

    private static class InternalClassMetaClassGetPropertySite extends AbstractCallSite {

        private final MetaClass metaClass;
        private final Class aClass;
        private final ClassInfo classInfo;
        private final int version;

        public InternalClassMetaClassGetPropertySite(CallSite parent, Class aClass) {
            super(parent);
            this.aClass = aClass;
            this.classInfo = ClassInfo.getClassInfo(aClass);
            this.version = classInfo.getVersion();
            this.metaClass = classInfo.getMetaClass();
        }

        @Override
        public final CallSite acceptGetProperty(Object receiver) {
            if (receiver != aClass || version != classInfo.getVersion()) { // metaClass is invalid
                return createGetPropertySite(receiver);
            } else {
                return this;
            }
        }

        @Override
        public final Object getProperty(Object receiver) throws Throwable{
            try {
                return metaClass.getProperty(aClass, name);
            } catch (GroovyRuntimeException gre) {
                throw ScriptBytecodeAdapter.unwrap(gre);
            }
        }
    }

    private static class InternalGetEffectivePojoFieldSite extends AbstractCallSite {

        private final MetaClassImpl metaClass;
        private final CachedField effective;
        private final int metaClassVersion;

        public InternalGetEffectivePojoFieldSite(final CallSite site, final MetaClassImpl metaClass, final CachedField effective) {
            super(site);
            this.metaClass = metaClass;
            this.effective = effective;
            this.metaClassVersion = metaClass.getVersion();
        }

        @Override
        public final CallSite acceptGetProperty(final Object receiver) {
            if (GroovyCategorySupport.hasCategoryInCurrentThread() || metaClass.getTheClass() != receiver.getClass() || metaClass.getVersion() != metaClassVersion) {
                return createGetPropertySite(receiver);
            }
            return this;
        }

        @Override
        public final Object getProperty(final Object receiver) {
            return effective.getProperty(receiver);
        }
    }


}
