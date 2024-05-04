package plc.project;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See the specification for information about what the different visit
 * methods should do.
 */
public final class Analyzer implements Ast.Visitor<Void> {

    public Scope scope;
    private Ast.Function function;

    public Analyzer(Scope parent) {
        scope = new Scope(parent);
        scope.defineFunction("print", "System.out.println", Arrays.asList(Environment.Type.ANY), Environment.Type.NIL, args -> Environment.NIL);
    }

    public Scope getScope() {
        return scope;
    }

    @Override
    public Void visit(Ast.Source ast) {
        boolean mainFound = false;
        for (Ast.Global g : ast.getGlobals()) visit(g);
        for (Ast.Function f : ast.getFunctions()) {
            if (f.getName().equals("main") && f.getParameters().isEmpty()) {
                mainFound = true;
                if (f.getReturnTypeName().isEmpty() || !f.getReturnTypeName().get().equals("Integer"))
                    throw new RuntimeException("Main function returns non-integer type.");
            }
            visit(f);
        }
        if (!mainFound) throw new RuntimeException("Missing main/0 function.");
        return null;
    }

    @Override
    public Void visit(Ast.Global ast) {
        if (ast.getValue().isPresent()) {
            visit(ast.getValue().get());
            requireAssignable(getType(ast.getTypeName()), ast.getValue().get().getType());
        }
        Environment.Variable v = scope.defineVariable(ast.getName(), ast.getName(), getType(ast.getTypeName()), ast.getMutable(), Environment.NIL);
        ast.setVariable(v);
        return null;
    }

    @Override
    public Void visit(Ast.Function ast) {
        List<Environment.Type> parameterTypes = new ArrayList<>();
        for (String typeName : ast.getParameterTypeNames()) parameterTypes.add(getType(typeName));
        Environment.Function f = scope.defineFunction(ast.getName(), ast.getName(), parameterTypes, ast.getReturnTypeName().isPresent() ? getType(ast.getReturnTypeName().get()) : Environment.Type.NIL, args -> Environment.NIL);
        ast.setFunction(f);
        function = ast;
        scope = new Scope(scope);
        for (Ast.Statement s : ast.getStatements()) visit(s);
        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        if (!(ast.getExpression() instanceof Ast.Expression.Function)) throw new RuntimeException("Invalid base expression.");
        visit(ast.getExpression());
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        if (ast.getTypeName().isEmpty() && ast.getValue().isEmpty()) throw new RuntimeException("Missing type.");
        if (ast.getValue().isPresent()) visit(ast.getValue().get());
        if (ast.getTypeName().isPresent() && ast.getValue().isPresent()) requireAssignable(getType(ast.getTypeName().get()), ast.getValue().get().getType());
        Environment.Variable v = scope.defineVariable(ast.getName(), ast.getName(), ast.getTypeName().isPresent() ? getType(ast.getTypeName().get()) : ast.getValue().get().getType(), true, Environment.NIL);
        ast.setVariable(v);
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        if (!(ast.getReceiver() instanceof Ast.Expression.Access)) throw new RuntimeException("Cannot assign to non-access value.");
        visit(ast.getReceiver());
        visit(ast.getValue());
        requireAssignable(ast.getReceiver().getType(), ast.getValue().getType());
        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        visit(ast.getCondition());
        if (ast.getCondition().getType() != Environment.Type.BOOLEAN) throw new RuntimeException("If condition is not a boolean.");
        if (ast.getThenStatements().isEmpty()) throw new RuntimeException("Then statements are missing.");
        scope = new Scope(scope);
        for (Ast.Statement s : ast.getThenStatements()) visit(s);
        scope = new Scope(scope.getParent());
        for (Ast.Statement s : ast.getElseStatements()) visit(s);
        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Switch ast) {
        visit(ast.getCondition());
        for (int i = 0; i < ast.getCases().size(); i++) {
            Ast.Statement.Case c = ast.getCases().get(i);
            if (i == ast.getCases().size() - 1) {
                if (c.getValue().isPresent()) throw new RuntimeException("Missing default case.");
            } else {
                if (c.getValue().isEmpty()) throw new RuntimeException("Missing value for case.");
                visit(c.getValue().get());
                if (c.getValue().get().getType() != ast.getCondition().getType()) throw new RuntimeException("Condition type does not match case.");
            }
            visit(c);
        }
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Case ast) {
        scope = new Scope(scope);
        for (Ast.Statement s : ast.getStatements()) visit(s);
        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        visit(ast.getCondition());
        if (ast.getCondition().getType() != Environment.Type.BOOLEAN) throw new RuntimeException("If condition is not a boolean.");
        scope = new Scope(scope);
        for (Ast.Statement s : ast.getStatements()) visit(s);
        scope = scope.getParent();
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        visit(ast.getValue());
        if (ast.getValue().getType() != function.getFunction().getReturnType()) throw new RuntimeException("Return types do not match.");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        Object obj = ast.getLiteral();
        switch (obj) {
            case BigDecimal d -> {
                if (d.doubleValue() == Double.POSITIVE_INFINITY || d.doubleValue() == Double.NEGATIVE_INFINITY)
                    throw new RuntimeException("Double value outside of range.");
                ast.setType(Environment.Type.DECIMAL);
            }
            case BigInteger i -> {
                if (i.bitLength() > 32) throw new RuntimeException("Integer value outside of range.");
                ast.setType(Environment.Type.INTEGER);
            }
            case String s -> ast.setType(Environment.Type.STRING);
            case Boolean b -> ast.setType(Environment.Type.BOOLEAN);
            case Character c -> ast.setType(Environment.Type.CHARACTER);
            case null -> ast.setType(Environment.Type.NIL);
            default -> ast.setType(Environment.Type.ANY);
        }
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        if (!(ast.getExpression() instanceof Ast.Expression.Binary)) throw new RuntimeException("Non-binary expression in parentheses.");
        visit(ast.getExpression());
        ast.setType(ast.getExpression().getType());
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        visit(ast.getLeft());
        visit(ast.getRight());
        Environment.Type lt = ast.getLeft().getType();
        Environment.Type rt = ast.getRight().getType();

        switch (ast.getOperator()) {
            case "&&":
            case "||":
                if (lt != Environment.Type.BOOLEAN || rt != Environment.Type.BOOLEAN) throw new RuntimeException("Non-boolean type not permitted.");
                ast.setType(Environment.Type.BOOLEAN);
                break;
            case "<":
            case ">":
            case "==":
            case "!=":
                if (lt != rt)throw new RuntimeException("Cannot compare mismatched types.");
                if (!isComparable(lt) || !isComparable(rt)) throw new RuntimeException("Cannot compare items of incomparable type.");
                ast.setType(Environment.Type.BOOLEAN);
                break;
            case "+":
                if (lt == Environment.Type.STRING || rt == Environment.Type.STRING) ast.setType(Environment.Type.STRING);
                else if (lt != Environment.Type.INTEGER && lt != Environment.Type.DECIMAL) throw new RuntimeException("Cannot add invalid types.");
                else if (lt != rt) throw new RuntimeException("Cannot add mismatched types.");
                else ast.setType(lt);
                break;
            case "-":
            case "/":
            case "*":
                if (lt != Environment.Type.INTEGER && lt != Environment.Type.DECIMAL) throw new RuntimeException("Cannot add invalid types.");
                if (lt != rt) throw new RuntimeException("Cannot add mismatched types.");
                ast.setType(lt);
                break;
            case "^":
                if (lt != Environment.Type.INTEGER || rt != Environment.Type.INTEGER) throw new RuntimeException("Cannot exponentiate with non-integer types.");
                ast.setType(Environment.Type.INTEGER);
                break;
            default:
                throw new RuntimeException("Invalid operator.");
        }
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        if (ast.getOffset().isPresent()) {
            visit(ast.getOffset().get());
            if (ast.getOffset().get().getType() != Environment.Type.INTEGER) throw new RuntimeException("Cannot use non-integer type as an offset.");
        }
        ast.setVariable(scope.lookupVariable(ast.getName()));
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        Environment.Function func = scope.lookupFunction(ast.getName(), ast.getArguments().size());
        for (int i = 0; i < ast.getArguments().size(); i++) {
            Ast.Expression arg = ast.getArguments().get(i);
            visit(arg);
            requireAssignable(func.getParameterTypes().get(i), arg.getType());
        }
        ast.setFunction(func);
        return null;
    }

    @Override
    public Void visit(Ast.Expression.PlcList ast) {
        for (Ast.Expression val : ast.getValues()) {
            visit(val);
            try {
                if (val.getType() != ast.getType()) throw new RuntimeException("Mismatched types in list.");
            } catch (Exception e) {
                ast.setType(val.getType());
            }
        }
        return null;
    }

    public Environment.Type getType(String s) {
        return switch (s) {
            case "Integer" -> Environment.Type.INTEGER;
            case "Decimal" -> Environment.Type.DECIMAL;
            case "Character" -> Environment.Type.CHARACTER;
            case "Any" -> Environment.Type.ANY;
            case "Boolean" -> Environment.Type.BOOLEAN;
            case "Comparable" -> Environment.Type.COMPARABLE;
            case "String" -> Environment.Type.STRING;
            case "Nil" -> Environment.Type.NIL;
            default -> throw new RuntimeException("Unknown type " + s + ".");
        };
    }

    public static boolean isComparable(Environment.Type t) {
        return t == Environment.Type.INTEGER || t == Environment.Type.DECIMAL || t == Environment.Type.CHARACTER || t == Environment.Type.STRING;
    }

    public static void requireAssignable(Environment.Type target, Environment.Type type) {
        if (target == Environment.Type.ANY) return;
        if (target == Environment.Type.COMPARABLE && isComparable(type)) return;
        if (target != type) throw new RuntimeException("Cannot assign value to variable of mismatched type.");
    }

}
