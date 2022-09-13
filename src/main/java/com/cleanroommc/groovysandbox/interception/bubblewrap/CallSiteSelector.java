package com.cleanroommc.groovysandbox.interception.bubblewrap;

import org.codehaus.groovy.reflection.ParameterTypes;
import org.codehaus.groovy.runtime.MetaClassHelper;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CallSiteSelector {

    /**
     * Find the {@link Constructor} that Groovy will invoke at runtime for the given type and arguments.
     *
     * @throws SecurityException if no valid constructor is found, or if the constructor is a synthetic constructor
     *                           added by SandboxTransformer and the constructor wrapper argument is invalid.
     */
    public static Constructor<?> findConstructor(Class<?> type, Object[] args) {
        Constructor<?> c = constructor(type, args);
        if (c == null) {
            throw new SecurityException("Unable to find constructor: " + formatConstructor(type, args));
        }
        // Check to make sure that users are not directly calling synthetic constructors without going through
        // `Checker.checkedSuperConstructor` or `Checker.checkedThisConstructor`. Part of SECURITY-1754.
        if (isIllegalCallToSyntheticConstructor(c, args)) {
            String alternateConstructors = Stream.of(c.getDeclaringClass().getDeclaredConstructors())
                    .filter(tempC -> !isSyntheticConstructor(tempC))
                    .map(Object::toString)
                    .sorted()
                    .collect(Collectors.joining(", "));
            throw new SecurityException("Rejecting illegal call to synthetic constructor: " + c + ". Perhaps you meant to use one of these constructors instead: " + alternateConstructors);
        }
        return c;
    }

    static Constructor<?> constructor(Class<?> receiver, Object[] args) {
        Constructor<?>[] constructors = receiver.getDeclaredConstructors();
        Constructor<?> bestMatch = null;
        ParameterTypes bestMatchParamTypes = null;
        Class<?>[] argTypes = MetaClassHelper.convertToTypeArray(args);
        for (Constructor<?> c : constructors) {
            ParameterTypes cParamTypes = new ParameterTypes(c.getParameterTypes());
            if (cParamTypes.isValidMethod(argTypes)) {
                if (bestMatch == null || isMoreSpecific(cParamTypes, bestMatchParamTypes, argTypes)) {
                    bestMatch = c;
                    bestMatchParamTypes = cParamTypes;
                }
            }
        }
        if (bestMatch != null) {
            return bestMatch;
        }

        // Only check for the magic Map constructor if we haven't already found a real constructor.
        // Also note that this logic is derived from how Groovy itself decides to use the magic Map constructor, at
        // MetaClassImpl#invokeConstructor(Class, Object[]).
        if (args.length == 1 && args[0] instanceof Map) {
            for (Constructor<?> c : constructors) {
                if (c.getParameterTypes().length == 0 && !c.isVarArgs()) {
                    return c;
                }
            }
        }

        return null;
    }

    public static boolean isMoreSpecific(ParameterTypes paramsForCandidate, ParameterTypes paramsForBaseline, Class<?>[] argTypes) {
        long candidateDistance = MetaClassHelper.calculateParameterDistance(argTypes, paramsForCandidate);
        long currentBestDistance = MetaClassHelper.calculateParameterDistance(argTypes, paramsForBaseline);
        return candidateDistance < currentBestDistance;
    }

    // private static final Class<?>[] SYNTHETIC_CONSTRUCTOR_PARAMETER_TYPES = new Class<?>[] { SuperConstructorWrapper.class, ThisConstructorWrapper.class, };

    private static boolean isSyntheticConstructor(Constructor<?> c) {
        for (Class<?> parameterType : c.getParameterTypes()) {
            if (parameterType == SuperConstructorWrapper.class || parameterType == ThisConstructorWrapper.class) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIllegalCallToSyntheticConstructor(Constructor<?> c, Object[] args) {
        Class<?>[] parameterTypes = c.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            Object arg = args[i];
            if (parameterType == SuperConstructorWrapper.class && (arg == null || arg.getClass() != SuperConstructorWrapper.class)) {
                return true;
            }
            if (parameterType == ThisConstructorWrapper.class && (arg == null || arg.getClass() != ThisConstructorWrapper.class)) {
                return true;
            }
        }
        return false;
    }

    public static String formatConstructor(Class<?> c, Object... args) {
        return "new " + getName(c) + printArgumentTypes(args);
    }

    private static String printArgumentTypes(Object[] args) {
        StringBuilder b = new StringBuilder();
        for (Object arg : args) {
            b.append(' ');
            b.append(getName(arg));
        }
        return b.toString();
    }

    public static String getName(Object o) {
        return o == null ? "null" : getName(o.getClass());
    }

    private static String getName(Class<?> c) {
        Class<?> e = c.getComponentType();
        if (e == null) {
            return c.getName();
        } else {
            return getName(e) + "[]";
        }
    }

    private CallSiteSelector() { }

}
