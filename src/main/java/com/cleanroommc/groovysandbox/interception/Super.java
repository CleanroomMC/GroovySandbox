package com.cleanroommc.groovysandbox.interception;

public class Super {

    public final Class senderType;
    public final Object receiver;

    public Super(Class senderType, Object receiver) {
        this.senderType = senderType;
        this.receiver = receiver;
    }

}
