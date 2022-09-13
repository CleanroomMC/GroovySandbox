package com.cleanroommc.groovysandbox.interception.bubblewrap;

public class ThisConstructorWrapper {

    final Object[] args;

    ThisConstructorWrapper(Object[] args) {
        this.args = args;
    }

    Object arg(int idx) {
        return this.args[idx];
    }

}
