package com.cleanroommc.groovysandbox.transformer;

import com.cleanroommc.groovysandbox.exception.SandboxSecurityException;
import com.cleanroommc.groovysandbox.interception.bubblewrap.Bubblewrap;
import com.cleanroommc.groovysandbox.interception.bubblewrap.BubblewrappedMethodClosure;
import com.cleanroommc.groovysandbox.interception.bubblewrap.Bubblewraps;
import com.cleanroommc.groovysandbox.util.ClosureSupport;
import com.cleanroommc.groovysandbox.util.Operators;
import groovy.lang.Script;
import org.codehaus.groovy.ast.*;
import org.codehaus.groovy.ast.expr.*;
import org.codehaus.groovy.ast.stmt.*;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.codehaus.groovy.syntax.Token;
import org.codehaus.groovy.syntax.Types;

import java.util.*;

public class GroovyClassTransformer extends ClassCodeExpressionTransformer implements VariableVisitor {

    /**
     * Do not care about {@code super} or {@code this} calls for classes extending these types.
     */
    private static final Set<String> TRIVIAL_CONSTRUCTORS = new HashSet<>(Arrays.asList(
            Object.class.getName(),
            Script.class.getName()));
    private static final ClassNode OBJECT_CLASS_NODE = new ClassNode(Object.class);

    private static final ClassNode BUBBLEWRAP = new ClassNode(Bubblewrap.class);
    private static final Token ASSIGNMENT_TOKEN = new Token(Types.ASSIGN, "=", -1, -1);
    private static final Token LEFT_SQUARE_BRACKET_TOKEN = new Token(Types.LEFT_SQUARE_BRACKET, "[", -1, -1);

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
        newExpression = innerTransform(newExpression);
        if (newExpression != expression) {
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

    private Expression rerouteCall(Bubblewraps bubblewrap, Expression... arguments) {
        return rerouteCall(bubblewrap.name(), arguments);
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
            // lhs.foo(arg1, arg2) => wrapCall(lhs, "foo", arg1, arg2)
            // lhs + rhs => lhs.plus(rhs)
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
                return rerouteCall(Bubblewraps.wrapSuperCall,
                        this.classExpression,
                        objExpression,
                        methodExpression,
                        argumentExpression,
                        this.sourceUnitConstantExpression,
                        new ConstantExpression(expression.getLineNumber()));
            }
            return rerouteCall(Bubblewraps.wrapCall,
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
            return rerouteCall(Bubblewraps.wrapStaticCall,
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
                return rerouteCall(Bubblewraps.wrapConstructorCall,
                        new ClassExpression(expression.getType()),
                        transformArguments(callExpression.getArguments()),
                        this.sourceUnitConstantExpression,
                        new ConstantExpression(expression.getLineNumber()));
            }
        }
        if (expression instanceof AttributeExpression) {
            AttributeExpression attributeExpression = (AttributeExpression) expression;
            return rerouteCall(Bubblewraps.wrapGetAttribute,
                    transform(attributeExpression.getObjectExpression()),
                    attributeExpression.isSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    attributeExpression.isSpreadSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    transform(attributeExpression.getProperty()),
                    this.sourceUnitConstantExpression,
                    new ConstantExpression(expression.getLineNumber()));
        }
        if (expression instanceof PropertyExpression) {
            PropertyExpression propertyExpression = (PropertyExpression) expression;
            return rerouteCall(Bubblewraps.wrapGetProperty,
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
        if (expression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) expression;
            int binaryExpressionType = binaryExpression.getOperation().getType();
            // This covers everything from a + b to a = b
            if (Types.ofType(binaryExpressionType, Types.ASSIGNMENT_OPERATOR)) {
                // Simple assignment like = as well as compound assignments like +=, -=, etc
                // How we dispatch this depends on the lhs expressions:
                // According to AsmClassGenerator, PropertyExpression, AttributeExpression, FieldExpression, VariableExpression are all valid lhs expressions
                Expression lhsExpression = binaryExpression.getLeftExpression();
                if (lhsExpression instanceof VariableExpression) {
                    VariableExpression variableExpression = (VariableExpression) lhsExpression;
                    if (variableExpression.isSuperExpression() || variableExpression.isThisExpression() || this.variableTracker.isIn(variableExpression)) {
                        // We don't care what sandboxed code does to itself until it starts interacting with outside world
                        return super.transform(expression);
                    } else {
                        // If the variable is not an in-scope local variable, it gets treated as a property access with implicit this
                        // See AsmClassGenerator.visitVariableExpression and processClassVariable
                        PropertyExpression propertyExpression = new PropertyExpression(VariableExpression.THIS_EXPRESSION, variableExpression.getName());
                        propertyExpression.setImplicitThis(true);
                        propertyExpression.setSourcePosition(variableExpression);
                        lhsExpression = propertyExpression;
                    }
                }
                if (lhsExpression instanceof PropertyExpression) {
                    PropertyExpression propertyExpression = (PropertyExpression) lhsExpression;
                    Bubblewraps bubblewrap;
                    if (lhsExpression instanceof AttributeExpression) {
                        bubblewrap = Bubblewraps.wrapSetAttribute;
                    } else {
                        Expression receiver = propertyExpression.getObjectExpression();
                        if (receiver instanceof VariableExpression && ((VariableExpression) receiver).isThisExpression()) {
                            FieldNode field = this.currentClass != null ? this.currentClass.getField(propertyExpression.getPropertyAsString()) : null;
                            if (field != null) {
                                // Could also verify that it is final, but not necessary
                                // cf. BinaryExpression.transformExpression; super.transform(exp) transforms the LHS to checkedGetProperty
                                return new BinaryExpression(lhsExpression, binaryExpression.getOperation(), transform(binaryExpression.getRightExpression()));
                            }
                        }
                        bubblewrap = Bubblewraps.wrapSetProperty;
                    }
                    return rerouteCall(bubblewrap,
                            transformPropertyExpression(propertyExpression),
                            transform(propertyExpression.getProperty()),
                            propertyExpression.isSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                            propertyExpression.isSpreadSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                            new ConstantExpression(binaryExpressionType, true),
                            this.sourceUnitConstantExpression,
                            new ConstantExpression(expression.getLineNumber()));
                } else if (lhsExpression instanceof FieldExpression) {
                    // While javadoc of FieldExpression isn't very clear
                    // AsmClassGenerator maps this to GETSTATIC/SETSTATIC/GETFIELD/SETFIELD access
                    // Not sure how we can intercept this, so skipping this for now
                    // Additionally, it looks like FieldExpression might only be used internally during
                    // Class generation for AttributeExpression/PropertyExpression in AsmClassGenerator
                    // E.g: the receiver for the expression is always referenced indirectly via the stack
                    // So we are limited in what we can do at this level.
                    return super.transform(expression);
                } else if (lhsExpression instanceof BinaryExpression) {
                    BinaryExpression lhsBinaryExpression = (BinaryExpression) lhsExpression;
                    if (lhsBinaryExpression.getOperation().getType() == Types.LEFT_SQUARE_BRACKET) { // Expression of the form x[y] = z
                        return rerouteCall(Bubblewraps.wrapSetArray,
                                transform(lhsBinaryExpression.getLeftExpression()),
                                transform(lhsBinaryExpression.getRightExpression()),
                                new ConstantExpression(binaryExpressionType, true),
                                transform(binaryExpression.getRightExpression()));
                    }
                }
                throw new AssertionError("Unexpected LHS of an assignment: " + lhsExpression.getClass());
            }
            if (binaryExpressionType == Types.LEFT_SQUARE_BRACKET) { // Array reference
                return rerouteCall(Bubblewraps.wrapGetArray, transform(binaryExpression.getLeftExpression()), transform(binaryExpression.getRightExpression()));
            } else if (binaryExpressionType == Types.KEYWORD_INSTANCEOF || Operators.isLogicalOperator(binaryExpressionType)) { // instanceof operator or logical operator
                return super.transform(expression);
            } else if (binaryExpressionType == Types.KEYWORD_IN) {
                // Membership operator: issue JENKINS-28154
                // This requires inverted operand order: a in [...] -> [...].isCase(a)
                return rerouteCall(Bubblewraps.wrapCall,
                        transform(binaryExpression.getRightExpression()),
                        ConstantExpression.PRIM_FALSE,
                        ConstantExpression.PRIM_FALSE,
                        new ConstantExpression("isCase"),
                        transform(binaryExpression.getLeftExpression()),
                        this.sourceUnitConstantExpression,
                        new ConstantExpression(expression.getLineNumber()));
            } else if (Operators.isRegexpComparisonOperator(binaryExpressionType)) {
                return rerouteCall(Bubblewraps.wrapStaticCall,
                        new ClassExpression(new ClassNode(ScriptBytecodeAdapter.class)), // TODO cache?
                        new ConstantExpression(Operators.binaryOperatorMethods(binaryExpressionType)),
                        transform(binaryExpression.getLeftExpression()),
                        transform(binaryExpression.getRightExpression()),
                        this.sourceUnitConstantExpression,
                        new ConstantExpression(expression.getLineNumber()));
            } else if (Operators.isComparisionOperator(binaryExpressionType)) {
                return rerouteCall(Bubblewraps.wrapComparison,
                        transform(binaryExpression.getLeftExpression()),
                        new ConstantExpression(binaryExpressionType),
                        transform(binaryExpression.getRightExpression()));
            } else {
                // Normally binary operators like a + b. TODO: check what other weird binary operators land here
                return rerouteCall(Bubblewraps.wrapBinaryOperation,
                        transform(binaryExpression.getLeftExpression()),
                        new ConstantExpression(binaryExpressionType),
                        transform(binaryExpression.getRightExpression()));
            }
        }
        if (expression instanceof PrefixExpression) {
            PrefixExpression prefixExpression = (PrefixExpression) expression;
            return operationExpression(expression, prefixExpression.getExpression(), prefixExpression.getOperation(), OperationSide.PREFIX);
        }
        if (expression instanceof PostfixExpression) {
            PostfixExpression postfixExpression = (PostfixExpression) expression;
            return operationExpression(expression, postfixExpression.getExpression(), postfixExpression.getOperation(), OperationSide.POSTFIX);
        }
        if (expression instanceof CastExpression) {
            CastExpression castExpression = (CastExpression) expression;
            return rerouteCall(Bubblewraps.wrapCast,
                    new ClassExpression(castExpression.getType()),
                    transform(castExpression.getExpression()),
                    castExpression.isIgnoringAutoboxing() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    castExpression.isCoerce() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    castExpression.isStrict() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE);
        }
        return super.transform(expression);
    }

    /**
     * Transforms the arguments of a call.
     *
     * Groovy primarily uses {@link ArgumentListExpression} for this, but the signature doesn't guarantee that. So this method takes care of that.
     */
    private Expression transformArguments(Expression expression) {
        boolean containsSpread = false;
        List<Expression> expressions;
        if (expression instanceof TupleExpression) {
            expressions = new ArrayList<>();
            TupleExpression tupleExpression = (TupleExpression) expression;
            for (int i = 0; i < tupleExpression.getExpressions().size(); i++) {
                Expression tupleExpressionElement = tupleExpression.getExpression(i);
                containsSpread |= tupleExpressionElement instanceof SpreadExpression;
                expressions.add(transform(tupleExpressionElement));
            }
        } else {
            expressions = Collections.singletonList(transform(expression));
            containsSpread = expression instanceof SpreadExpression;
        }
        // Only call toArray when there is a need to expand the spread operator, otherwise use ArrayExpression
        // wrapCall expects an array
        Expression retExpression;
        if (containsSpread) {
            retExpression = new MethodCallExpression(new ListExpression(expressions), "toArray", ArgumentListExpression.EMPTY_ARGUMENTS);
        } else {
            retExpression = new ArrayExpression(OBJECT_CLASS_NODE, expressions);
        }
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

    // Handles the cases mentioned in BinaryExpressionHelper.execMethodAndStoreForSubscriptOperator
    private Expression operationExpression(Expression wholeExpression, Expression atomicExpression, Token task, OperationSide side) {
        String operation = "++".equals(task.getText()) ? "next" : "previous";
        // a[b]++
        if (atomicExpression instanceof BinaryExpression) {
            BinaryExpression binaryExpression = (BinaryExpression) atomicExpression;
            if (binaryExpression.getOperation().getType() == Types.LEFT_SQUARE_BRACKET) {
                return rerouteCall(side.arrayCall,
                        transform(binaryExpression.getLeftExpression()),
                        transform(binaryExpression.getRightExpression()),
                        new ConstantExpression(operation));
            }
        }
        // a++
        if (atomicExpression instanceof VariableExpression) {
            VariableExpression variableExpression = (VariableExpression) atomicExpression;
            if (this.variableTracker.isIn(variableExpression)) {
                if (side == OperationSide.POSTFIX) {
                    // A trick to rewrite a++ without introducing a new local variable
                    // a++ -> [a, a = a.next()][0]
                    ListExpression lhs = new ListExpression();
                    lhs.addExpression(atomicExpression);
                    MethodCallExpression operationCallExpression = new MethodCallExpression(atomicExpression, operation, ArgumentListExpression.EMPTY_ARGUMENTS);
                    operationCallExpression.setSourcePosition(atomicExpression);
                    lhs.addExpression(operationCallExpression);
                    BinaryExpression replacement = new BinaryExpression(lhs, LEFT_SQUARE_BRACKET_TOKEN, new ConstantExpression(0, true));
                    replacement.setSourcePosition(wholeExpression);
                    return transform(replacement);
                } else { // ++a -> a = a.next()
                    MethodCallExpression operationCallExpression = new MethodCallExpression(atomicExpression, operation, ArgumentListExpression.EMPTY_ARGUMENTS);
                    BinaryExpression replacement = new BinaryExpression(atomicExpression, ASSIGNMENT_TOKEN, operationCallExpression);
                    replacement.setSourcePosition(wholeExpression);
                    return transform(replacement);
                }
            } else {
                // If the variable is not in-scope local variable, it gets treated as a property access with implicit this.
                // See AsmClassGenerator.visitVariableExpression and processClassVariable.
                PropertyExpression propertyExpression = new PropertyExpression(VariableExpression.THIS_EXPRESSION, variableExpression);
                propertyExpression.setImplicitThis(true);
                propertyExpression.setSourcePosition(atomicExpression);
                atomicExpression = propertyExpression;
                // Fall through to the "a.b++" case below
            }
        }
        // a.b++
        if (atomicExpression instanceof PropertyExpression) {
            PropertyExpression propertyExpression = (PropertyExpression) atomicExpression;
            return rerouteCall(side.propertyCall,
                    transformPropertyExpression(propertyExpression),
                    transformArguments(propertyExpression.getProperty()),
                    propertyExpression.isSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    propertyExpression.isSpreadSafe() ? ConstantExpression.PRIM_TRUE : ConstantExpression.PRIM_FALSE,
                    new ConstantExpression(operation));
        }
        // a.b++ where a.b is a FieldExpression
        // TODO: It is unclear if this is actually reachable, I think that syntax like `a.b` will always be a PropertyExpression in this context
        // We handle it explicitly as a precaution, since the catch-all below does not store the result
        // Which would definitely be wrong for FieldExpression
        if (atomicExpression instanceof FieldExpression) {
            // See note in innerTransform regarding FieldExpression; this type of expression cannot be intercepted.
            return atomicExpression;
        }
        // method()++, 1++, any other cases where "atomic" is not valid as the LHS of an assignment expression
        // So no store is performed, see BinaryExpressionHelper
        if (side == OperationSide.POSTFIX) {
            // We need to intercept the call to x.next() while making sure that x is not evaluated more than once.
            //  x++ -> (temp -> { temp.next(); temp } (x))
            VariableScope closureScope = new VariableScope();
            Parameter[] parameters = new Parameter[] { new Parameter(ClassHelper.OBJECT_TYPE, "temp") };
            BlockStatement blockCode = new BlockStatement();
            blockCode.setVariableScope(closureScope);
            blockCode.addStatement(new ExpressionStatement(new MethodCallExpression(new VariableExpression("temp"), operation, ArgumentListExpression.EMPTY_ARGUMENTS)));
            blockCode.addStatement(new ExpressionStatement(new VariableExpression("temp")));
            ClosureExpression closureExpression = new ClosureExpression(parameters, blockCode);
            closureExpression.setSourcePosition(wholeExpression);
            MethodCallExpression retExpression = new MethodCallExpression(closureExpression, "doCall", new ArgumentListExpression(atomicExpression));
            retExpression.setSourcePosition(closureExpression);
            return transform(retExpression);
        } else { // ++x -> x.next()
            MethodCallExpression retExpression = new MethodCallExpression(atomicExpression, operation, ArgumentListExpression.EMPTY_ARGUMENTS);
            retExpression.setSourcePosition(wholeExpression);
            return transform(retExpression);
        }
    }

    private enum OperationSide {

        PREFIX(Bubblewraps.wrapPrefixArray, Bubblewraps.wrapPrefixProperty),
        POSTFIX(Bubblewraps.wrapPostfixArray, Bubblewraps.wrapPostfixProperty);

        private final Bubblewraps arrayCall, propertyCall;

        private OperationSide(Bubblewraps arrayCall, Bubblewraps propertyCall) {
            this.arrayCall = arrayCall;
            this.propertyCall = propertyCall;
        }

    }

}
