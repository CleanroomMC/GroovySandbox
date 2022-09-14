package com.cleanroommc.groovysandbox.transformer;

public interface VariableVisitor {

    VariableTracker getVariableTracker();

    void setVariableTracker(VariableTracker variableTracker);

}
