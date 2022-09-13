package com.cleanroommc.groovysandbox.interception.bubblewrap;

public class SuperConstructorWrapper {

    final Object[] args;

    SuperConstructorWrapper(Object[] args) {
        this.args = args;
    }

    Object arg(int idx) {
        return this.args[idx];
    }

}
