package com.cleanroommc.groovysandbox.interception;

public class Super {

    final Class senderType;
    final Object receiver;

    public Super(Class senderType, Object receiver) {
        this.senderType = senderType;
        this.receiver = receiver;
    }

}
