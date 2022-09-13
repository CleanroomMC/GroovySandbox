package com.cleanroommc.groovysandbox.interception;

public class CallInterceptor {

    /**
     * Intercepts an instance method call on some object of the form "foo.bar(...)"
     */
    public Object onMethodCall(Invoker invoker, Object receiver, String method, Object... args) throws Throwable {
        return invoker.call(receiver, method, args);
    }

    /**
     * Intercepts a static method call on some class, like "Class.forName(...)".
     *
     * Note that Groovy doesn't clearly differentiate static method calls from instance method calls.
     * If calls are determined to be static at compile-time, you get this method called, but
     * method calls whose receivers are {@link Class} can invoke static methods, too
     * (that is, {@code x=Integer.class;x.valueOf(5)} results in {@code onMethodCall(invoker,Integer.class,"valueOf",5)}
     */
    public Object onStaticCall(Invoker invoker, Class<?> receiver, String method, Object... args) throws Throwable {
        return invoker.call(receiver, method, args);
    }

    /**
     * Intercepts an object instantiation, like "new Receiver(...)"
     */
    public Object onNewInstance(Invoker invoker, Class<?> receiver, Object... args) throws Throwable {
        return invoker.call(receiver, null, args);
    }

    /**
     * Intercepts an super method call, like "super.foo(...)"
     */
    public Object onSuperCall(Invoker invoker, Class<?> senderType, Object receiver, String method, Object... args) throws Throwable {
        return invoker.call(new Super(senderType, receiver), method, args);
    }

    /**
     * Intercepts a {@code super(…)} call from a constructor.
     */
    public void onSuperConstructor(Invoker invoker, Class<?> receiver, Object... args) throws Throwable {
        onNewInstance(invoker, receiver, args);
    }

    /**
     * Intercepts a property access, like "z=foo.bar"
     *
     * @param receiver
     *      'foo' in the above example, the object whose property is accessed.
     * @param property
     *      'bar' in the above example, the name of the property
     */
    public Object onGetProperty(Invoker invoker, Object receiver, String property) throws Throwable {
        return invoker.call(receiver, property);
    }

    /**
     * Intercepts a property assignment like "foo.bar=z"
     *
     * @param receiver
     *      'foo' in the above example, the object whose property is accessed.
     * @param property
     *      'bar' in the above example, the name of the property
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetProperty(Invoker invoker, Object receiver, String property, Object value) throws Throwable {
        return invoker.call(receiver, property, value);
    }

    /**
     * Intercepts an attribute access, like "z=foo.@bar"
     *
     * @param receiver
     *      'foo' in the above example, the object whose attribute is accessed.
     * @param attribute
     *      'bar' in the above example, the name of the attribute
     */
    public Object onGetAttribute(Invoker invoker, Object receiver, String attribute) throws Throwable {
        return invoker.call(receiver, attribute);
    }

    /**
     * Intercepts an attribute assignment like "foo.@bar=z"
     *
     * @param receiver
     *      'foo' in the above example, the object whose attribute is accessed.
     * @param attribute
     *      'bar' in the above example, the name of the attribute
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetAttribute(Invoker invoker, Object receiver, String attribute, Object value) throws Throwable {
        return invoker.call(receiver, attribute, value);
    }

    /**
     * Intercepts an array access, like "z=foo[bar]"
     *
     * @param receiver
     *      'foo' in the above example, the array-like object.
     * @param index
     *      'bar' in the above example, the object that acts as an index.
     */
    public Object onGetArray(Invoker invoker, Object receiver, Object index) throws Throwable {
        return invoker.call(receiver, null, index);
    }

    /**
     * Intercepts an attribute assignment like "foo[bar]=z"
     *
     * @param receiver
     *      'foo' in the above example, the array-like object.
     * @param index
     *      'bar' in the above example, the object that acts as an index.
     * @param value
     *      The value to be assigned.
     * @return
     *      The result of the assignment expression. Normally, you should return the same object as {@code value}.
     */
    public Object onSetArray(Invoker invoker, Object receiver, Object index, Object value) throws Throwable {
        return invoker.call(receiver, null, index, value);
    }

}
