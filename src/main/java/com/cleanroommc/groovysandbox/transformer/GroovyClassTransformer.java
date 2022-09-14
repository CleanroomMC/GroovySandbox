package com.cleanroommc.groovysandbox.transformer;

import com.cleanroommc.groovysandbox.exception.SandboxSecurityException;
import com.cleanroommc.groovysandbox.interception.bubblewrap.Bubblewrap;
import com.cleanroommc.groovysandbox.interception.bubblewrap.BubblewrappedMethodClosure;
import com.cleanroommc.groovysandbox.util.ClosureSupport;
import groovy.lang.Script;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.SourceUnit;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class GroovyClassTransformer extends ClassCodeExpressionTransformer implements VariableVisitor {

    /**
     * Do not care about {@code super} or {@code this} calls for classes extending these types.
     */
    private static final Set<String> TRIVIAL_CONSTRUCTORS = new HashSet<>(Arrays.asList(
            Object.class.getName(),
            Script.class.getName()));

    private static final ClassNode BUBBLEWRAP = new ClassNode(Bubblewrap.class);

    private SourceUnit currentSourceUnit;
    private ClassNode currentClass;
    /**
     * As we visit expressions, track variable scopes. This is used to distinguish local variables from property access.
     */
    private VariableTracker variableTracker;
    private boolean withinClosure = false;
    private SandboxSecurityException exception;

    // Cached Expression instances
    private ClassExpression classExpression;
    private ConstantExpression sourceUnitConstantExpression;

    public void setSourceUnit(SourceUnit sourceUnit) {
        this.currentSourceUnit = sourceUnit;
        this.sourceUnitConstantExpression = new ConstantExpression(sourceUnit.getName());
    }

    public void setClassNode(ClassNode classNode) {
        this.currentClass = classNode;
        this.classExpression = new ClassExpression(classNode);
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
    public VariableTracker getVariableTracker() {
        return variableTracker;
    }

    @Override
    public void setVariableTracker(VariableTracker variableTracker) {
        this.variableTracker = variableTracker;
    }

    @Override
    public void visitMethod(MethodNode node) {
        if (this.currentClass == null) {
            this.currentClass = node.getDeclaringClass();
        }
        this.variableTracker = null;
        try (VariableTracker tracker = new VariableTracker(this)) {
            for (Parameter p : node.getParameters()) {
                tracker.declare(p.getName());
            }
            super.visitMethod(node);
        }
    }

    @Override
    public void visitField(FieldNode node) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitField(node);
        }
    }

    @Override
    public void visitBlockStatement(BlockStatement block) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitBlockStatement(block);
        }
    }

    @Override
    public void visitDoWhileLoop(DoWhileStatement loop) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            loop.getLoopBlock().visit(this);
        }
        try (VariableTracker tracker = new VariableTracker(this)) {
            loop.setBooleanExpression((BooleanExpression) transform(loop.getBooleanExpression()));
        }
    }

    @Override
    public void visitForLoop(ForStatement forLoop) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            /*
                Groovy appears to always treat the left-hand side of forLoop as a declaration.
                i.e., the following code is error

                def h() {
                    def x =0;
                    def i = 0;
                    for (i in 0..9 ) {
                        x+= i;
                    }
                    println x;
                }

                script1414457812466.groovy: 18: The current scope already contains a variable of the name i
                 @ line 18, column 5.
                       for (i in 0..9 ) {
                       ^

                1 error

                Also see Jenkins Sandbox issue 17.
             */
            tracker.declare(forLoop.getVariable());
            super.visitForLoop(forLoop);
        }
    }

    @Override
    public void visitIfElse(IfStatement ifElse) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            ifElse.setBooleanExpression((BooleanExpression) transform(ifElse.getBooleanExpression()));
        }
        try (VariableTracker tracker = new VariableTracker(this)) {
            ifElse.getIfBlock().visit(this);
        }
        try (VariableTracker tracker = new VariableTracker(this)) {
            ifElse.getElseBlock().visit(this);
        }
    }

    @Override
    public void visitSwitch(SwitchStatement statement) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitSwitch(statement);
        }
    }

    @Override
    public void visitSynchronizedStatement(SynchronizedStatement sync) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitSynchronizedStatement(sync);
        }
    }

    @Override
    public void visitTryCatchFinally(TryCatchStatement statement) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitTryCatchFinally(statement);
        }
    }

    @Override
    public void visitCatchStatement(CatchStatement statement) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            tracker.declare(statement.getVariable());
            super.visitCatchStatement(statement);
        }
    }

    @Override
    public void visitWhileLoop(WhileStatement loop) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitWhileLoop(loop);
        }
    }

    @Override
    public void visitClosureExpression(ClosureExpression expression) {
        try (VariableTracker tracker = new VariableTracker(this)) {
            super.visitClosureExpression(expression);
        }
    }

    @Override
    public Expression transform(Expression expression) {
        // expression = super.transform(expression);
        Expression newExpression = TransformationManager.INSTANCE.transform(expression);
        newExpression = transform(newExpression);
        if (newExpression != null) {
            newExpression.setSourcePosition(expression);
        }
        return newExpression;
    }

    /**
     * Reroute call to {@link Bubblewrap}
     * @param name bubblewrap method name
     * @param arguments arguments to be passed to the rerouted method call
     * @return original or modified expression
     */
    private Expression rerouteCall(String name, Expression... arguments) {
        return new StaticMethodCallExpression(BUBBLEWRAP, name, arguments.length == 0 ? ArgumentListExpression.EMPTY_ARGUMENTS : new ArgumentListExpression(arguments));
    }

    private Expression transformPropertyExpression(PropertyExpression exp) {
        if (exp.isImplicitThis() && this.withinClosure && !this.variableTracker.isIn(exp.getObjectExpression())) {
            return ClosureSupport.getClosureSelfCall();
        } else {
            return transform(exp.getObjectExpression());
        }
    }

    /**
     * Do inner transformations to bubblewrap calls and peek inside Closures
     */
    private Expression innerTransform(Expression expression) {
        if (expression instanceof ClosureExpression) {
            // ClosureExpression.transformExpression doesn't visit the code inside
            ClosureExpression closureExpression = (ClosureExpression) expression;
            Parameter[] parameters = closureExpression.getParameters();
            if (parameters != null) {
                if (parameters.length > 0) {
                    for (Parameter parameter : parameters) {
                        if (parameter.hasInitialExpression()) {
                            parameter.setInitialExpression(transform(parameter.getInitialExpression()));
                        }
                    }
                }
            }
            boolean old = withinClosure;
            withinClosure = true;
            try {
                closureExpression.getCode().visit(this);
            } finally {
                withinClosure = old;
            }
        }
        if (expression instanceof MethodCallExpression) {
            // lhs.foo(arg1,arg2) => checkedCall(lhs, "foo", arg1, arg2)
            // lhs+rhs => lhs.plus(rhs)
            // Integer.plus(Integer) => DefaultGroovyMethods.plus
            // lhs || rhs => lhs.or(rhs)
            MethodCallExpression callExpression = (MethodCallExpression) expression;
            Expression objExpression;
            if (this.withinClosure && callExpression.isImplicitThis() && !variableTracker.isIn(callExpression)) {
                objExpression = ClosureSupport.getClosureSelfCall();
            } else {
                objExpression = transform(callExpression.getObjectExpression());
            }
            Expression methodExpression = transform(callExpression.getMethod());
            Expression argumentExpression = transformArguments(callExpression.getArguments());
            if (callExpression.getObjectExpression() instanceof VariableExpression && ((VariableExpression) callExpression.getObjectExpression()).isSuperExpression()) {
                if (this.currentClass == null) {
                    throw new IllegalStateException("Owning class not defined.");
                }
                return rerouteCall(Bubblewrap.WRAP_SUPER_CALL,
                        this.classExpression,
                        objExpression,
                        methodExpression,
                        argumentExpression,
                        this.sourceUnitConstantExpression,
                        new ConstantExpression(expression.getLineNumber()));
            }
            return rerouteCall(Bubblewrap.WRAP_CALL,
                    objExpression,
                    callExpression.isSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    callExpression.isSpreadSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    methodExpression,
                    argumentExpression,
                    this.sourceUnitConstantExpression,
                    new ConstantExpression(expression.getLineNumber()));
        }
        if (expression instanceof StaticMethodCallExpression) {
            // Groovy doesn't use StaticMethodCallExpression as much as it could in compilation.
            // E.g: Math.max(...) results in a regular MethodCallExpression.
            // However, static import handling uses this, and so are some ASTTransformations like toString, equals, hashCode.
            StaticMethodCallExpression callExpression = (StaticMethodCallExpression) expression;
            return rerouteCall(Bubblewrap.WRAP_STATIC_CALL,
                    new ClassExpression(callExpression.getOwnerType()),
                    new ConstantExpression(callExpression.getMethod()),
                    transformArguments(callExpression.getArguments()),
                    this.sourceUnitConstantExpression,
                    new ConstantExpression(expression.getLineNumber()));
        }
        if (expression instanceof MethodPointerExpression) {
            MethodPointerExpression pointerExpression = (MethodPointerExpression) expression;
            return new ConstructorCallExpression(
                    new ClassNode(BubblewrappedMethodClosure.class),
                    new ArgumentListExpression(transform(pointerExpression.getExpression()), transform(pointerExpression.getMethodName())));
        }
        if (expression instanceof ConstructorCallExpression) {
            ConstructorCallExpression callExpression = (ConstructorCallExpression) expression;
            if (!callExpression.isSpecialCall()) {
                return rerouteCall(Bubblewrap.WRAP_CONSTRUCTOR_CALL,
                        new ClassExpression(expression.getType()),
                        transformArguments(callExpression.getArguments()),
                        this.sourceUnitConstantExpression,
                        new ConstantExpression(expression.getLineNumber()));
            }
        }
        if (expression instanceof AttributeExpression) {
            AttributeExpression attributeExpression = (AttributeExpression) expression;
            return rerouteCall(Bubblewrap.WRAP_GET_ATTRIBUTE_CALL,
                    transform(attributeExpression.getObjectExpression()),
                    attributeExpression.isSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    attributeExpression.isSpreadSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    transform(attributeExpression.getProperty()),
                    this.sourceUnitConstantExpression,
                    new ConstantExpression(expression.getLineNumber()));
        }
        if (expression instanceof PropertyExpression) {
            PropertyExpression propertyExpression = (PropertyExpression) expression;
            return rerouteCall(Bubblewrap.WRAP_GET_PROPERTY_CALL,
                    transformPropertyExpression(propertyExpression),
                    propertyExpression.isSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    propertyExpression.isSpreadSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    transform(propertyExpression.getProperty()),
                    this.sourceUnitConstantExpression,
                    new ConstantExpression(expression.getLineNumber()));
        }
        if (expression instanceof DeclarationExpression) {
            handleDeclarations((DeclarationExpression) expression);
        }
        return super.transform(expression);
    }

    /**
     * Transforms the arguments of a call.
     *
     * Groovy primarily uses {@link ArgumentListExpression} for this, but the signature doesn't guarantee that. So this method takes care of that.
     */
    private Expression transformArguments(Expression expression) {
        ListExpression listExpression = new ListExpression();
        if (expression instanceof TupleExpression) {
            TupleExpression tupleExpression = (TupleExpression) expression;
            for (int i = 0; i < tupleExpression.getExpressions().size(); i++) {
                listExpression.addExpression(transform(tupleExpression.getExpression(i)));
            }
        } else {
            listExpression.addExpression(transform(expression));
        }
        // wrapCall expects an array
        MethodCallExpression retExpression = new MethodCallExpression(listExpression, "toArray", ArgumentListExpression.EMPTY_ARGUMENTS);
        retExpression.setSourcePosition(expression);
        return retExpression;
    }

    /**
     * @see org.codehaus.groovy.classgen.asm.BinaryExpressionHelper#evaluateEqual(org.codehaus.groovy.ast.expr.BinaryExpression, boolean)
     */
    private void handleDeclarations(DeclarationExpression expression) {
        Expression leftExpression = expression.getLeftExpression();
        if (leftExpression instanceof VariableExpression) {
            this.variableTracker.declare((VariableExpression) leftExpression);
        } else if (leftExpression instanceof TupleExpression) {
            TupleExpression tupleExpression = (TupleExpression) leftExpression;
            for (Expression tuple : tupleExpression.getExpressions()) {
                this.variableTracker.declare((VariableExpression) tuple);
            }
        }
    }

}
