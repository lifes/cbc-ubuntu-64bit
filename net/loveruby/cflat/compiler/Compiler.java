package net.loveruby.cflat.compiler;
import net.loveruby.cflat.parser.*;
import net.loveruby.cflat.ast.*;
import net.loveruby.cflat.type.*;
import net.loveruby.cflat.utils.*;
import net.loveruby.cflat.exception.*;
import java.util.*;
import java.io.*;

public class Compiler {
    static final public String ProgramID = "cbc";
    static final public String Version = "1.0.0";

    static public void main(String[] args) {
        new Compiler(ProgramID).commandMain(args);
    }

    protected ErrorHandler errorHandler;

    public Compiler(String programName) {
        this.errorHandler = new ErrorHandler(programName);
    }

    private TypeTable defaultTypeTable() {
        return TypeTable.ilp32();
    }

    public void commandMain(String[] origArgs) {
        Options opts = new Options(defaultTypeTable(), new LibraryLoader());
        List srcs = null;
        try {
            srcs = opts.parse(Arrays.asList(origArgs));
        }
        catch (OptionParseError err) {
            errorHandler.error(err.getMessage());
            errorHandler.error("Try cbc --help for option usage");
            System.exit(1);
        }
        if (opts.isMode("--check-syntax")) {
            Iterator inputs = srcs.iterator();
            boolean failed = false;
            while (inputs.hasNext()) {
                SourceFile src = (SourceFile)inputs.next();
                if (isValidSyntax(src, opts)) {
                    System.out.println(src.name() + ": Syntax OK");
                }
                else {
                    System.out.println(src.name() + ": Syntax Error");
                    failed = true;
                }
            }
            System.exit(failed ? 1 : 0);
        }
        else {
            try {
                Iterator inputs = srcs.iterator();
                while (inputs.hasNext()) {
                    SourceFile src = (SourceFile)inputs.next();
                    compileFile(src, opts);
                }
                if (! opts.isLinkRequired()) System.exit(0);
                generateExecutable(opts);
                System.exit(0);
            }
            catch (CompileException ex) {
                errorHandler.error(ex.getMessage());
                System.exit(1);
            }
        }
    }

    private void errorExit(String msg) {
        errorHandler.error(msg);
        System.exit(1);
    }

    protected boolean isValidSyntax(SourceFile src, Options opts) {
        try {
            parseFile(src, opts);
            return true;
        }
        catch (SyntaxException ex) {
            return false;
        }
        catch (FileException ex) {
            errorHandler.error(ex.getMessage());
            return false;
        }
    }

    protected void compileFile(SourceFile src, Options opts)
                                        throws CompileException {
        if (src.isCflatSource()) {
            AST ast = parseFile(src, opts);
            if (opts.isMode("--dump-tokens")) {
                dumpTokens(ast.sourceTokens(), System.out);
                return;
            }
            if (opts.isMode("--dump-ast")) {
                ast.dump();
                return;
            }
            if (opts.isMode("--dump-stmt")) {
                findStmt(ast).dump();
                return;
            }
            semanticAnalysis(ast, opts);
            if (opts.isMode("--dump-reference")) return;
            if (opts.isMode("--dump-semantic")) {
                ast.dump();
                return;
            }
            String asm = generateAssembly(ast, opts);
            if (opts.isMode("--dump-asm")) {
                System.out.println(asm);
                return;
            }
            writeFile(src.asmFileName(opts), asm);
            src.setCurrentName(src.asmFileName(opts));
            if (opts.isMode("-S")) {
                return;
            }
        }
        if (! opts.isAssembleRequired()) return;
        if (src.isAssemblySource()) {
            assemble(src.asmFileName(opts), src.objFileName(opts), opts);
            src.setCurrentName(src.objFileName(opts));
        }
    }

    protected void dumpTokens(Iterator tokens, PrintStream s) {
        while (tokens.hasNext()) {
            CflatToken t = (CflatToken)tokens.next();
            printPair(t.kindName(), t.dumpedImage(), s);
        }
    }

    static final protected int numLeftColumns = 24;

    protected void printPair(String key, String value, PrintStream s) {
        s.print(key);
        for (int n = numLeftColumns - key.length(); n > 0; n--) {
            s.print(" ");
        }
        s.println(value);
    }

    protected Node findStmt(AST ast) {
        Iterator funcs = ast.functions();
        while (funcs.hasNext()) {
            DefinedFunction f = (DefinedFunction)funcs.next();
            if (f.name().equals("main")) {
                Iterator stmts = f.body().stmts();
                while (stmts.hasNext()) {
                    return (Node)stmts.next();
                }
                errorExit("main() has no stmt");
            }
        }
        errorExit("source file does not contains main()");
        return null;   // never reach
    }

    protected AST parseFile(SourceFile src, Options opts)
                            throws SyntaxException, FileException {
        return Parser.parseFile(new File(src.currentName()),
                                opts.loader(),
                                errorHandler,
                                opts.doesDebugParser());
    }

    protected void semanticAnalysis(AST ast, Options opts)
                                        throws SemanticException {
        JumpResolver.resolve(ast, errorHandler);
        LocalReferenceResolver.resolve(ast, errorHandler);
        TypeResolver.resolve(ast, opts.typeTable(), errorHandler);
        opts.typeTable().semanticCheck(errorHandler);
        DereferenceChecker.check(ast, errorHandler);
        if (opts.isMode("--dump-reference")) {
            ast.dump();
            return;
        }
        new TypeChecker(opts.typeTable(), errorHandler).check(ast);
    }

    protected String generateAssembly(AST ast, Options opts) {
        return CodeGenerator.generate(ast, opts.typeTable(), errorHandler);
    }

    protected void assemble(String srcPath,
                            String destPath,
                            Options opts) throws IPCException {
        List cmd = new ArrayList();
        cmd.add("as");
        cmd.addAll(opts.asOptions());
        cmd.add("-o");
        cmd.add(destPath);
        cmd.add(srcPath);
        invoke(cmd, opts.isVerboseMode());
    }

    protected void generateExecutable(Options opts) throws IPCException {
        List cmd = new ArrayList();
        cmd.add("ld");
        // FIXME: -dynamic-linker required only on dynamic linking
        cmd.add("-dynamic-linker");
        cmd.add("/lib/ld-linux.so.2");
        if (! opts.noStartFiles()) cmd.add("/usr/lib/crt1.o");
        if (! opts.noStartFiles()) cmd.add("/usr/lib/crti.o");
        cmd.addAll(opts.ldArgs());
        if (! opts.noDefaultLibs()) cmd.add("-lc");
        if (! opts.noStartFiles()) cmd.add("/usr/lib/crtn.o");
        cmd.add("-o");
        cmd.add(opts.exeFileName());
        invoke(cmd, opts.isVerboseMode());
    }

    protected void invoke(List cmdArgs, boolean debug) throws IPCException {
        if (debug) {
            dumpCommand(cmdArgs.iterator());
        }
        try {
            String[] cmd = stringListToArray(cmdArgs);
            Process proc = Runtime.getRuntime().exec(cmd);
            proc.waitFor();
            passThrough(proc.getInputStream());
            passThrough(proc.getErrorStream());
            if (proc.exitValue() != 0) {
                errorHandler.error(cmd[0] + " failed."
                                   + " (status " + proc.exitValue() + ")");
                throw new IPCException("compile error");
            }
        }
        catch (InterruptedException ex) {
            errorHandler.error("gcc interrupted: " + ex.getMessage());
            throw new IPCException("compile error");
        }
        catch (IOException ex) {
            errorHandler.error(ex.getMessage());
            throw new IPCException("compile error");
        }
    }

    protected String[] stringListToArray(List list) {
        String[] a = new String[list.size()];
        int idx = 0;
        Iterator it = list.iterator();
        while (it.hasNext()) {
            Object o = it.next();
            a[idx++] = o.toString();
        }
        return a;
    }

    protected void dumpCommand(Iterator args) {
        String sep = "";
        while (args.hasNext()) {
            String arg = args.next().toString();
            System.out.print(sep); sep = " ";
            System.out.print(arg);
        }
        System.out.println("");
    }

    protected void passThrough(InputStream s) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(s));
        String line;
        while ((line = r.readLine()) != null) {
            System.err.println(line);
        }
    }

    protected void writeFile(String path, String str)
                                    throws FileException {
        try {
            BufferedWriter f = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(path)));
            try {
                f.write(str);
            }
            finally {
                f.close();
            }
        }
        catch (FileNotFoundException ex) {
            errorHandler.error("file not found: " + path);
            throw new FileException("file error");
        }
        catch (IOException ex) {
            errorHandler.error("IO error" + ex.getMessage());
            throw new FileException("file error");
        }
    }
}
