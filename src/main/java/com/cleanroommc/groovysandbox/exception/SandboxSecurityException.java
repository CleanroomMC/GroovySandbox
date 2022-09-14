package com.cleanroommc.groovysandbox.exception;

public class SandboxSecurityException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public SandboxSecurityException(String msg) {
        super(msg);
    }

    public static SandboxSecurityException format(String msg, String source, int lineNumber) {
        return new SandboxSecurityException(String.format("%s in script '%s' in line '%d'!", msg, source, lineNumber));
    }

    public static SandboxSecurityException format(String msg, int lineNumber) {
        return new SandboxSecurityException(String.format("%s at line [%d] not permitted!", msg, lineNumber));
    }

    public static SandboxSecurityException format(String msg) {
        return new SandboxSecurityException(String.format("%s is not permitted!", msg));
    }

}
