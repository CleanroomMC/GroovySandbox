package com.cleanroommc.groovysandbox;

import com.cleanroommc.groovysandbox.interception.InterceptionManager;
import com.cleanroommc.groovysandbox.primer.ClassTransformerPrimer;
import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilerConfiguration;

public class GroovySandbox {

    public static void main(String[] args) {
        InterceptionManager.INSTANCE.initDefaultBans();
        CompilerConfiguration config = new CompilerConfiguration();
        config.addCompilationCustomizers(ClassTransformerPrimer.PRIMER);
        GroovyShell shell = new GroovyShell(config);
        shell.evaluate(""); // Insert test snippet here
    }

}
