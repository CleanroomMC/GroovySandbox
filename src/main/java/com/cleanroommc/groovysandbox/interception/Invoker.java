package com.cleanroommc.groovysandbox.interception;

/**
 * Represents the next interceptor in the chain.
 *
 * As {@link CallInterceptor}, you intercept by doing one of the following:
 *
 * <ul>
 *     <li>Pass on to the next interceptor by calling one of the call() method,
 *         possibly modifying the arguments and return values, intercepting an exception, etc.
 *     <li>Throws an exception to block the call.
 *     <li>Return some value without calling the next interceptor.
 * </ul>
 *
 * The signature of the call method is as follows:
 *
 * <dl>
 *     <dt>receiver</dt>
 *     <dd>
 *         The object whose method/property is accessed.
 *         For constructor invocations and static calls, this is {@link Class}.
 *         If the receiver is null, all the interceptors will be skipped.
 *     </dd>
 *     <dt>method</dt>
 *     <dd>
 *         The name of the method/property/attribute. Otherwise pass in null.
 *     </dd>
 *     <dt>args</dt>
 *     <dd>
 *         Arguments of the method call, index of the array access, and/or values to be set.
 *         Multiple override of the call method is provided to avoid the implicit object
 *         array creation, but otherwise they behave the same way.
 *     </dd>
 * </dl>
 */
@FunctionalInterface
public interface Invoker {

    Object call(Object receiver, String method, Object... args) throws Throwable;

}
