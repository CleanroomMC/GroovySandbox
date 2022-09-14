package com.cleanroommc.groovysandbox.transformer;

import org.codehaus.groovy.ast.Variable;
import org.codehaus.groovy.ast.expr.Expression;
import org.codehaus.groovy.ast.expr.VariableExpression;

import java.util.HashSet;
import java.util.Set;

/**
 * Keep track of in-scope variables on the stack.
 * <p>
 * In groovy, various statements implicitly create new scopes (as in Java), so we track them in a chain.
 * <p>
 * This only tracks variables on stack (as opposed to field access and closure accessing variables in the calling context).
 *
 * @author Kohsuke Kawaguchi
 */
public class VariableTracker implements AutoCloseable {

    final VariableVisitor owner;
    final VariableTracker parent;
    final Set<String> names = new HashSet<>();

    public VariableTracker(VariableVisitor owner) {
        this.owner = owner;
        this.parent = owner.getVariableTracker();
        owner.setVariableTracker(this);
    }

    public void declare(String name) {
        this.names.add(name);
    }

    public void declare(Variable variable) {
        this.names.add(variable.getName());
    }

    public boolean isIn(String name) {
        for (VariableTracker tracker = this; tracker != null; tracker = tracker.parent) {
            if (tracker.names.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public boolean isIn(VariableExpression expression) {
        return isIn(expression.getName());
    }

    public boolean isIn(Expression expression) {
        return expression instanceof VariableExpression && isIn(((VariableExpression) expression).getName());
    }

    @Override
    public void close() {
        this.owner.setVariableTracker(this.parent);
    }

}
