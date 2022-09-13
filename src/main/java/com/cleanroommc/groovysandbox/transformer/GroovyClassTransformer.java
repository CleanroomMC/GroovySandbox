package com.cleanroommc.groovysandbox.transformer;

import com.cleanroommc.groovysandbox.exception.SandboxSecurityException;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.control.SourceUnit;

public class GroovyClassTransformer extends ClassCodeExpressionTransformer {

    private SourceUnit currentSourceUnit;
    private ClassNode currentClass;
    private SandboxSecurityException exception;

    public void setSourceUnit(SourceUnit sourceUnit) {
        this.currentSourceUnit = sourceUnit;
    }

    public void setClassNode(ClassNode classNode) {
        this.currentClass = classNode;
    }

    public SandboxSecurityException getAndClearException() {
        SandboxSecurityException exception = this.exception;
        this.exception = null;
        return exception;
    }

    @Override
    protected SourceUnit getSourceUnit() {
        return currentSourceUnit;
    }

    @Override
    public void visitMethod(MethodNode node) {
        if (this.currentClass == null) {
            this.currentClass = node.getDeclaringClass();
        }
        super.visitMethod(node);
    }

    @Override
    public Expression transform(Expression expression) {
        // expression = super.transform(expression);
        Expression newExpression = TransformationManager.INSTANCE.transform(expression);

        if (newExpression != null) {
            newExpression.setSourcePosition(expression);
        }
        return newExpression;
    }

    /*
        try {
            if (expression instanceof ClosureExpression) {
                ClosureExpression closureExpression = (ClosureExpression) expression;
                if (closureExpression.isParameterSpecified()) {
                    checkVariable(closureExpression.getParameters());
                }
                checkVariableScope(closureExpression.getVariableScope());
            }
        } catch (SandboxSecurityException exception) {
            this.exception = exception;
        }
         */

    /*
    private void checkVariable(Variable... variables) throws SandboxSecurityException {
        for (Variable variable : variables) {
            if (InterceptionManager.INSTANCE.interceptClass(variable.getType().getName())) {
                if (variable instanceof ASTNode) {
                    throw SandboxSecurityException.format(String.format("Parameter [%s] of Type [%s]", variable.getName(), variable.getType().getName()),
                            ((ASTNode) variable).getLineNumber());
                } else {
                    throw SandboxSecurityException.format(String.format("Parameter [%s] of Type [%s]", variable.getName(), variable.getType().getName()));
                }
            }
        }
    }

    private void checkVariableScope(VariableScope variableScope) throws SandboxSecurityException {
        List<Variable> variables = new ArrayList<>();
        for (Iterator<Variable> iter = variableScope.getDeclaredVariablesIterator(); iter.hasNext();) {
            variables.add(iter.next());
        }
        for (Iterator<Variable> iter = variableScope.getReferencedClassVariablesIterator(); iter.hasNext();) {
            variables.add(iter.next());
        }
        for (Iterator<Variable> iter = variableScope.getReferencedLocalVariablesIterator(); iter.hasNext();) {
            variables.add(iter.next());
        }
        if (!variables.isEmpty()) {
            checkVariable(variables.toArray(new Variable[0]));
        }
    }
     */

}
