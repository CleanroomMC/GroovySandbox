package com.cleanroommc.groovysandbox.interception.bubblewrap;

import com.cleanroommc.groovysandbox.closure.ClosureSupport;
import com.cleanroommc.groovysandbox.interception.CallInterceptor;
import com.cleanroommc.groovysandbox.interception.InterceptChain;
import groovy.lang.*;
import org.codehaus.groovy.reflection.CachedField;
import org.codehaus.groovy.reflection.ClassInfo;
import org.codehaus.groovy.runtime.*;
import org.codehaus.groovy.runtime.callsite.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
                // If someone is calling closure.invokeMethod("foo",args), map that back to closure.foo("args")
                method = args[0].toString();
                args = (Object[]) args[1];
            }
            MetaMethod metaMethod = InvokerHelper.getMetaClass(receiver).pickMethod(method, MetaClassHelper.convertToTypeArray(args));
            if (metaMethod == null) {
                // if we are trying to call a method that's actually defined in Closure, then we'll get non-null 'm'
                // in that case, treat it like normal method call
                // if we are here, that means we are trying to delegate the call to 'owner', 'delegate', etc.
                // is going to, and check access accordingly. Groovy's corresponding code is in MetaClassImpl.invokeMethod(...)

                // TODO: Change List => Array, tighter loops
                List<Object> targets = ClosureSupport.targetsOf((Closure) receiver);
                Class[] argTypes = convertToTypeArray(args);

                // in the first phase, we look for exact method match
                for (Object candidate : targets) {
                    if (InvokerHelper.getMetaClass(candidate).pickMethod(method, argTypes) != null)
                        return wrapCall(candidate, false, false, method, args);
                }
                // in the second phase, we try to call invokeMethod on them
                for (Object candidate : targets) {
                    try {
                        return wrapCall(candidate, false, false, "invokeMethod", method, args);
                    } catch (MissingMethodException ignored) {
                        // try the next one
                    }
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
        }.call(receiver, method, fixNullArgs(args));
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
