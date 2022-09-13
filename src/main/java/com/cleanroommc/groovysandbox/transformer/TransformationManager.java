package com.cleanroommc.groovysandbox.transformer;

import org.codehaus.groovy.ast.expr.ConstantExpression;
import org.codehaus.groovy.ast.expr.Expression;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;

public enum TransformationManager {

    INSTANCE;

    private final List<UnaryOperator<Expression>> transformations = new ArrayList<>();
    private final List<UnaryOperator<Expression>> unmodifiableTansformations = Collections.unmodifiableList(transformations);

    TransformationManager() {
        /*
        register(expression -> {
           if (expression instanceof ConstantExpression) {
               ConstantExpression constantExpression = (ConstantExpression) expression;
               if (constantExpression.getValue() instanceof String) {
                   String value = (String) constantExpression.getValue();

               }
           }
        });
         */
    }

    public void register(UnaryOperator<Expression> transformation) {
        this.transformations.add(transformation);
    }

    public List<UnaryOperator<Expression>> getTransformations() {
        return unmodifiableTansformations;
    }

    public Expression transform(Expression expression) {
        Expression transformedExpression = expression;
        for (int i = 0; i < this.transformations.size(); i++) {
            transformedExpression = this.transformations.get(i).apply(transformedExpression);
        }
        return transformedExpression;
    }

}
