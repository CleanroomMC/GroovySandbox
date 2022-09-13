package com.cleanroommc.groovysandbox.interception;

import java.util.List;

public abstract class InterceptChain implements Invoker {

    private final List<CallInterceptor> callInterceptors;
    private final int size;

    private int index = -1;

    public InterceptChain() {
        this.callInterceptors = InterceptionManager.INSTANCE.getCallInterceptors();
        this.size = this.callInterceptors.size();
    }

    public CallInterceptor next() {
        if (this.index < this.size) {
            return this.callInterceptors.get(this.index++);
        }
        return null;
    }

}
