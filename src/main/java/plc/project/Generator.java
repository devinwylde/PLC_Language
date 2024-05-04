package plc.project;

import java.beans.Expression;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.math.BigInteger;

public final class Generator implements Ast.Visitor<Void> {

    private final PrintWriter writer;
    private int indent = 0;

    public Generator(PrintWriter writer) {
        this.writer = writer;
    }

    private void print(Object... objects) {
        for (Object object : objects) {
            if (object instanceof Ast) {
                visit((Ast) object);
            } else {
                writer.write(object.toString());
            }
        }
    }

    private void newline(int indent) {
        writer.println();
        for (int i = 0; i < indent; i++) {
            writer.write("    ");
        }
    }

    private String getTypeStr(String s) {
        return switch (s) {
            case "Character" -> "char";
            case "Boolean" -> "boolean";
            case "String" -> "String";
            case "Integer" -> "int";
            case "Decimal" -> "double";
            default -> "void";
        };
    }

    @Override
    public Void visit(Ast.Source ast) {
        writer.write("public class Main {");
        if (!ast.getGlobals().isEmpty()) {
            newline(indent);
            indent++;
            for (Ast.Global g : ast.getGlobals()) {
                newline(indent);
                visit(g);
            }
            indent--;
        }
        newline(indent);
        indent++;
        newline(indent);
        writer.write("public static void main(String[] args) {");
        indent++;
        newline(indent);
        writer.write("System.exit(new Main().main());");
        indent--;
        newline(indent);
        writer.write("}");
        indent--;
        for (Ast.Function f : ast.getFunctions()) {
            newline(indent);
            indent++;
            newline(indent);
            visit(f);
            indent--;
        }
        newline(indent);
        newline(indent);
        writer.write("}");

        return null;
    }

    @Override
    public Void visit(Ast.Global ast) {
        if (!ast.getMutable()) writer.write("final ");
        writer.write(getTypeStr(ast.getTypeName()));
        if (ast.getValue().isPresent() && ast.getValue().get() instanceof Ast.Expression.PlcList) writer.write("[]");
        writer.write(" " + ast.getName());
        if (ast.getValue().isPresent()) {
            writer.write(" = ");
            visit(ast.getValue().get());
        }
        writer.write(";");

        return null;
    }

    @Override
    public Void visit(Ast.Function ast) {
        writer.write(ast.getFunction().getReturnType().getJvmName());
        writer.write(" " + ast.getName() + "(");
        for (int i = 0; i < ast.getParameters().size(); i++) {
            if (i > 0) writer.write(", ");
            writer.write(getTypeStr(ast.getParameterTypeNames().get(i)));
            writer.write(" " + ast.getParameters().get(i));
        }
        writer.write(") {");
        indent++;
        for (Ast.Statement s : ast.getStatements()) {
            newline(indent);
            visit(s);
        }
        indent--;
        newline(indent);
        writer.write("}");

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Expression ast) {
        visit(ast.getExpression());
        writer.write(";");

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Declaration ast) {
        writer.write(ast.getVariable().getType().getJvmName());
        writer.write(" " + ast.getName());
        if (ast.getValue().isPresent()) {
            writer.write(" = ");
            visit(ast.getValue().get());
        }
        writer.write(";");

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Assignment ast) {
        visit(ast.getReceiver());
        writer.write(" = ");
        visit(ast.getValue());
        writer.write(";");

        return null;
    }

    @Override
    public Void visit(Ast.Statement.If ast) {
        writer.write("if (");
        visit(ast.getCondition());
        writer.write(") {");
        indent++;
        for (int i = 0; i < ast.getThenStatements().size(); i++) {
            newline(indent);
            visit(ast.getThenStatements().get(i));
        }
        indent--;
        newline(indent);
        writer.write("}");
        if (!ast.getElseStatements().isEmpty()) {
            writer.write(" else {");
            indent++;
            for (int i = 0; i < ast.getElseStatements().size(); i++) {
                newline(indent);
                visit(ast.getElseStatements().get(i));
            }
            indent--;
            newline(indent);
            writer.write("}");
        }

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Switch ast) {
        writer.write("switch (");
        visit(ast.getCondition());
        writer.write(") {");
        indent++;
        for (int i = 0; i < ast.getCases().size(); i++) {
            newline(indent);
            visit(ast.getCases().get(i));
        }
        indent--;
        newline(indent);
        writer.write("}");

        return null;
    }

    @Override
    public Void visit(Ast.Statement.Case ast) {
        if (ast.getValue().isPresent()) {
            writer.write("case ");
            visit(ast.getValue().get());
            writer.write(":");
        } else writer.write("default:");
        indent++;
        for (int i = 0; i < ast.getStatements().size(); i++) {
            newline(indent);
            visit(ast.getStatements().get(i));
        }
        if (ast.getValue().isPresent()) {
            newline(indent);
            writer.write("break;");
        }
        indent--;

        return null;
    }

    @Override
    public Void visit(Ast.Statement.While ast) {
        writer.write("while (");
        visit(ast.getCondition());
        if (ast.getStatements().isEmpty()) writer.write(") ;");
        else {
            writer.write(") {");
            indent++;
            for (int i = 0; i < ast.getStatements().size(); i++) {
                newline(indent);
                visit(ast.getStatements().get(i));
            }
            indent--;
            newline(indent);
            writer.write("}");
        }
        return null;
    }

    @Override
    public Void visit(Ast.Statement.Return ast) {
        writer.write("return ");
        visit(ast.getValue());
        writer.write(";");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Literal ast) {
        switch (ast.getLiteral()) {
            case BigDecimal d -> writer.write(d.toString());
            case BigInteger i -> writer.write(i.toString());
            case String s -> writer.write("\"" + s + "\"");
            case Boolean b -> writer.write(b ? "true" : "false");
            case Character c -> writer.write("'" + c + "'");
            default -> writer.write("null");
        }
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Group ast) {
        writer.write("(");
        visit(ast.getExpression());
        writer.write(")");
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Binary ast) {
        if (ast.getOperator().equals("^")) {
            writer.write("Math.pow(");
            visit(ast.getLeft());
            writer.write(", ");
            visit(ast.getRight());
            writer.write(")");
        } else {
            visit(ast.getLeft());
            writer.write(" " + ast.getOperator() + " ");
            visit(ast.getRight());
        }
        return null;
    }

    @Override
    public Void visit(Ast.Expression.Access ast) {
        if (ast.getOffset().isPresent()) {
            writer.write(ast.getName() + "[");
            visit(ast.getOffset().get());
            writer.write("]");
        } else writer.write(ast.getName());

        return null;
    }

    @Override
    public Void visit(Ast.Expression.Function ast) {
        writer.write(ast.getFunction().getJvmName() + "(");
        for (int i = 0; i < ast.getArguments().size(); i++) {
            if (i > 0) writer.write(", ");
            visit(ast.getArguments().get(i));
        }
        writer.write(")");

        return null;
    }

    @Override
    public Void visit(Ast.Expression.PlcList ast) {
        writer.write("{");
        for (int i = 0; i < ast.getValues().size(); i++) {
            if (i > 0) writer.write(", ");
            visit(ast.getValues().get(i));
        }
        writer.write("}");

        return null;
    }

}
