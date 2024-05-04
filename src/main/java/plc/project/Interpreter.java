package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static plc.project.Environment.NIL;

public class Interpreter implements Ast.Visitor<Environment.PlcObject> {

    private Scope scope = new Scope(null);

    public Interpreter(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", 1, args -> {
            System.out.println(args.get(0).getValue());
            return NIL;
        });
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Environment.PlcObject visit(Ast.Source ast) {
        for (Ast.Global g : ast.getGlobals()) visit(g);
        for (Ast.Function f : ast.getFunctions()) visit(f);
        return scope.lookupFunction("main", 0).invoke(new ArrayList<>());
    }

    @Override
    public Environment.PlcObject visit(Ast.Global ast) {
        if (ast.getValue().isEmpty()) scope.defineVariable(ast.getName(), true, NIL);
        else scope.defineVariable(ast.getName(), ast.getMutable(), visit(ast.getValue().get()));
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Function ast) {
        Scope cur = scope;
        Function<List<Environment.PlcObject>, Environment.PlcObject> function = args -> {
            Scope prev = scope;
            scope = cur;
            for (int i = 0; i < args.size(); i++) scope.defineVariable(ast.getParameters().get(i), true, args.get(i));

            for (Ast.Statement s : ast.getStatements()) {
                try {
                    visit(s);
                } catch (Return ret) {
                    scope = prev;
                    return ret.value;
                }
            }

            scope = prev;
            return NIL;
        };

        scope.defineFunction(ast.getName(), ast.getParameters().size(), function);

        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Expression ast) {
        visit(ast.getExpression());
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Declaration ast) {
        if (ast.getValue().isEmpty()) scope.defineVariable(ast.getName(), true, NIL);
        else scope.defineVariable(ast.getName(), true, visit(ast.getValue().get()));
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access receiver)) throw new RuntimeException("Cannot assign to receiver of incorrect type.");
        if (receiver.getOffset().isPresent()) {
            Environment.PlcObject var = scope.lookupVariable(receiver.getName()).getValue();
            if (!(var.getValue() instanceof List<?>)) throw new RuntimeException("Cannot index non-list object.");
            List<Object> list = (List<Object>) var.getValue();
            int offset = requireType(BigInteger.class, visit(receiver.getOffset().get())).intValue();
            list.set(offset, visit(ast.getValue()).getValue());
        } else scope.lookupVariable(receiver.getName()).setValue(visit(ast.getValue()));
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.If ast) {
        boolean cond = requireType(Boolean.class, visit(ast.getCondition()));
        scope = new Scope(scope);
        if (cond) for (Ast.Statement statement : ast.getThenStatements()) visit(statement);
        else for (Ast.Statement statement : ast.getElseStatements()) visit(statement);
        scope = scope.getParent();
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Switch ast) {
        scope = new Scope(scope);
        Object var = visit(ast.getCondition()).getValue();
        for (Ast.Statement.Case c : ast.getCases()) {
            if (c.getValue().isEmpty() || visit(c.getValue().get()).getValue().equals(var)) {
                visit(c);
                break;
            }
        }
        scope = scope.getParent();

        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Case ast) {
        for (Ast.Statement s : ast.getStatements()) visit(s);
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.While ast) {
        while (requireType(Boolean.class, visit(ast.getCondition()))) {
            scope = new Scope(scope);
            for (Ast.Statement s : ast.getStatements()) visit(s);
            scope = scope.getParent();
        }
        return NIL;
    }

    @Override
    public Environment.PlcObject visit(Ast.Statement.Return ast) {
        throw new Return(visit(ast.getValue()));
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Literal ast) {
//        return Environment.create(ast.getLiteral());
        if (ast.getLiteral() == null) return NIL;
        return new Environment.PlcObject(scope, ast.getLiteral());
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Group ast) {
        return visit(ast.getExpression());
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Binary ast) {
        switch (ast.getOperator()) {
            case "&&":
                boolean leftAnd = requireType(Boolean.class, visit(ast.getLeft()));
                boolean rightAnd = requireType(Boolean.class, visit(ast.getRight()));
                return new Environment.PlcObject(scope, leftAnd && rightAnd);
            case "||":
                if (requireType(Boolean.class, visit(ast.getLeft()))) return new Environment.PlcObject(scope, true);
                return new Environment.PlcObject(scope, requireType(Boolean.class, visit(ast.getRight())));
            case "<":
                Comparable leftLt = requireType(Comparable.class, visit(ast.getLeft()));
                Comparable rightLt = requireType(Comparable.class, visit(ast.getRight()));
                return new Environment.PlcObject(scope, leftLt.compareTo(rightLt) < 0);
            case ">":
                Comparable leftGt = requireType(Comparable.class, visit(ast.getLeft()));
                Comparable rightGt = requireType(Comparable.class, visit(ast.getRight()));
                return new Environment.PlcObject(scope, leftGt.compareTo(rightGt) > 0);
            case "==":
                Object leftEq = visit(ast.getLeft()).getValue();
                Object rightEq = visit(ast.getRight()).getValue();
                return new Environment.PlcObject(scope, leftEq.equals(rightEq));
            case "!=":
                Object leftNe = visit(ast.getLeft()).getValue();
                Object rightNe = visit(ast.getRight()).getValue();
                return new Environment.PlcObject(scope, !leftNe.equals(rightNe));
            case "+":
                Environment.PlcObject leftO = visit(ast.getLeft());
                Environment.PlcObject rightO = visit(ast.getRight());
                if (checkType(String.class, leftO) || checkType(String.class, rightO)) {
                    return new Environment.PlcObject(scope, leftO.getValue().toString() + rightO.getValue());
                } else if (checkType(BigDecimal.class, leftO)) {
                    BigDecimal leftAd = requireType(BigDecimal.class, visit(ast.getLeft()));
                    BigDecimal rightAd = requireType(BigDecimal.class, visit(ast.getRight()));
                    return new Environment.PlcObject(scope, leftAd.add(rightAd));
                }
                BigInteger leftAi = requireType(BigInteger.class, visit(ast.getLeft()));
                BigInteger rightAi = requireType(BigInteger.class, visit(ast.getRight()));
                return new Environment.PlcObject(scope, leftAi.add(rightAi));
            case "-":
                if (checkType(BigDecimal.class, visit(ast.getLeft()))) {
                    BigDecimal leftSd = requireType(BigDecimal.class, visit(ast.getLeft()));
                    BigDecimal rightSd = requireType(BigDecimal.class, visit(ast.getRight()));
                    return new Environment.PlcObject(scope, leftSd.subtract(rightSd));
                }
                BigInteger leftSi = requireType(BigInteger.class, visit(ast.getLeft()));
                BigInteger rightSi = requireType(BigInteger.class, visit(ast.getRight()));
                return new Environment.PlcObject(scope, leftSi.subtract(rightSi));
            case "*":
                if (checkType(BigDecimal.class, visit(ast.getLeft()))) {
                    BigDecimal leftMd = requireType(BigDecimal.class, visit(ast.getLeft()));
                    BigDecimal rightMd = requireType(BigDecimal.class, visit(ast.getRight()));
                    return new Environment.PlcObject(scope, leftMd.multiply(rightMd));
                }
                BigInteger leftMi = requireType(BigInteger.class, visit(ast.getLeft()));
                BigInteger rightMi = requireType(BigInteger.class, visit(ast.getRight()));
                return new Environment.PlcObject(scope, leftMi.multiply(rightMi));
            case "/":
                if (checkType(BigDecimal.class, visit(ast.getLeft()))) {
                    BigDecimal leftDd = requireType(BigDecimal.class, visit(ast.getLeft()));
                    BigDecimal rightDd = requireType(BigDecimal.class, visit(ast.getRight()));
                    if (rightDd.equals(BigDecimal.ZERO)) throw new RuntimeException("Cannot divide by zero.");
                    return new Environment.PlcObject(scope, leftDd.divide(rightDd, RoundingMode.HALF_EVEN));
                }
                BigInteger leftDi = requireType(BigInteger.class, visit(ast.getLeft()));
                BigInteger rightDi = requireType(BigInteger.class, visit(ast.getRight()));
                if (rightDi.equals(BigInteger.ZERO)) throw new RuntimeException("Cannot divide by zero.");
                return new Environment.PlcObject(scope, leftDi.divide(rightDi));
            case "^":
                BigInteger leftExp = requireType(BigInteger.class, visit(ast.getLeft()));
                BigInteger rightExp = requireType(BigInteger.class, visit(ast.getRight()));
                while (!rightExp.equals(BigInteger.ZERO)) {
                    leftExp = leftExp.multiply(leftExp);
                    rightExp = rightExp.subtract(BigInteger.ONE);
                }
                return new Environment.PlcObject(scope, leftExp);
            default:
                throw new RuntimeException("Unsupported operation: " + ast.getOperator());
        }
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Access ast) {
        if (ast.getOffset().isPresent()) {
            if (!(scope.lookupVariable(ast.getName()).getValue().getValue() instanceof List<?> vals)) throw new RuntimeException("Cannot index non-list object.");
            int offset = requireType(BigInteger.class, visit(ast.getOffset().get())).intValue();
            return new Environment.PlcObject(scope, vals.get(offset));
        }
        return scope.lookupVariable(ast.getName()).getValue();
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.Function ast) {
        Environment.Function func = scope.lookupFunction(ast.getName(), ast.getArguments().size());
        List<Environment.PlcObject> args = new ArrayList<>();
        for (Ast.Expression exp : ast.getArguments()) args.add(visit(exp));
        return func.invoke(args);
    }

    @Override
    public Environment.PlcObject visit(Ast.Expression.PlcList ast) {
        List<Object> vals = new ArrayList<>();
        for (Ast.Expression exp : ast.getValues()) vals.add(visit(exp).getValue());
        return new Environment.PlcObject(scope, vals);
    }

    private static <T> boolean checkType(Class<T> type, Environment.PlcObject object) {
        return type.isInstance(object.getValue());
    }

    /**
     * Helper function to ensure an object is of the appropriate type.
     */
    private static <T> T requireType(Class<T> type, Environment.PlcObject object) {
        if (type.isInstance(object.getValue())) {
            return type.cast(object.getValue());
        } else {
            throw new RuntimeException("Expected type " + type.getName() + ", received " + object.getValue().getClass().getName() + ".");
        }
    }

    /**
     * Exception class for returning values.
     */
    private static class Return extends RuntimeException {

        private final Environment.PlcObject value;

        private Return(Environment.PlcObject value) {
            this.value = value;
        }

    }

}
