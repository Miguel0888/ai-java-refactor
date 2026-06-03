package de.lazyjava.refactor.cli;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import javax.lang.model.SourceVersion;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RefactorCli {
    public static void main(String[] args) {
        try {
            new RefactorCli().run(args);
        } catch (Exception exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        }
    }

    private void run(String[] args) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            printHelp();
            return;
        }

        String command = args[0];
        Map<String, String> options = parseOptions(Arrays.copyOfRange(args, 1, args.length));
        Path projectRoot = Paths.get(option(options, "project", ".")).toAbsolutePath().normalize();
        ToolContext context = ToolContext.create(projectRoot);

        if ("outline".equals(command)) {
            System.out.print(new OutlinePrinter(context).print());
            return;
        }
        if ("rename-plan".equals(command)) {
            RenamePlan plan = new RenamePlanner(context).plan(renameRequest(context, options));
            System.out.print(plan.toJson());
            return;
        }
        if ("rename-apply".equals(command)) {
            RenamePlan plan = new RenamePlanner(context).plan(renameRequest(context, options));
            System.out.print(plan.toJson());
            apply(plan.status, plan.changeSet);
            return;
        }
        if ("extract-class-plan".equals(command)) {
            ExtractClassPlan plan = new ExtractClassPlanner(context).plan(extractClassRequest(options));
            System.out.print(plan.toJson());
            return;
        }
        if ("extract-class-apply".equals(command)) {
            ExtractClassPlan plan = new ExtractClassPlanner(context).plan(extractClassRequest(options));
            System.out.print(plan.toJson());
            apply(plan.status, plan.changeSet);
            return;
        }

        throw new IllegalArgumentException("Unknown command: " + command);
    }

    private RenameRequest renameRequest(ToolContext context, Map<String, String> options) {
        String newName = required(options, "new-name");
        String symbolId = required(options, "symbol");
        SymbolIdentity symbol = context.symbolLocator.locate(symbolId);
        return new RenameRequest(symbol, newName);
    }

    private ExtractClassRequest extractClassRequest(Map<String, String> options) {
        String sourceClass = required(options, "source-class");
        String targetClass = required(options, "target-class");
        String delegate = option(options, "delegate", lowerCamel(targetClass));
        String members = required(options, "members");
        return new ExtractClassRequest(sourceClass, targetClass, delegate, parseMembers(members));
    }

    private void apply(PlanStatus status, SourceChangeSet changeSet) {
        if (status != PlanStatus.SAFE) {
            throw new IllegalStateException("Plan is not SAFE. Changes were not applied.");
        }
        new SourceChangeApplier().apply(changeSet);
    }

    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        int index = 0;
        while (index < args.length) {
            String key = args[index];
            if (!key.startsWith("--")) {
                throw new IllegalArgumentException("Expected option, got: " + key);
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("Missing value for option: " + key);
            }
            options.put(key.substring(2), args[index + 1]);
            index += 2;
        }
        return options;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Missing required option: --" + name);
        }
        return value;
    }

    private static String option(Map<String, String> options, String name, String fallback) {
        String value = options.get(name);
        return value == null ? fallback : value;
    }

    private static List<String> parseMembers(String members) {
        List<String> values = new ArrayList<String>();
        String[] parts = members.split(",");
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index].trim();
            if (part.length() > 0) {
                values.add(part);
            }
        }
        return values;
    }

    private static String lowerCamel(String value) {
        if (value.length() == 0) {
            return value;
        }
        return Character.toLowerCase(value.charAt(0)) + value.substring(1);
    }

    private static void printHelp() {
        System.out.println("ai-java-refactor");
        System.out.println("Commands:");
        System.out.println("  outline --project <path>");
        System.out.println("  rename-plan --project <path> --symbol <owner#member()> --new-name <name>");
        System.out.println("  rename-apply --project <path> --symbol <owner#member()> --new-name <name>");
        System.out.println("  extract-class-plan --project <path> --source-class <fqn> --target-class <Name> --delegate <field> --members field:x,method:foo()");
        System.out.println("  extract-class-apply --project <path> --source-class <fqn> --target-class <Name> --delegate <field> --members field:x,method:foo()");
    }

    private static final class ToolContext {
        private final SourceWorkspace workspace;
        private final SourceFileLocator sourceFileLocator;
        private final ParsedSourceCache parsedSourceCache;
        private final AstSymbolResolver symbolResolver;
        private final SymbolLocator symbolLocator;
        private final SymbolUsageEngine usageEngine;

        private ToolContext(SourceWorkspace workspace, SourceFileLocator sourceFileLocator, ParsedSourceCache parsedSourceCache, AstSymbolResolver symbolResolver, SymbolLocator symbolLocator, SymbolUsageEngine usageEngine) {
            this.workspace = workspace;
            this.sourceFileLocator = sourceFileLocator;
            this.parsedSourceCache = parsedSourceCache;
            this.symbolResolver = symbolResolver;
            this.symbolLocator = symbolLocator;
            this.usageEngine = usageEngine;
        }

        private static ToolContext create(Path projectRoot) {
            SourceWorkspace workspace = SourceWorkspace.detect(projectRoot);
            SourceFileLocator sourceFileLocator = new SourceFileLocator(workspace);
            ParsedSourceCache cache = new ParsedSourceCache(new JavaSourceParser());
            AstSymbolResolver resolver = new AstSymbolResolver();
            SymbolLocator symbolLocator = new SymbolLocator(sourceFileLocator, cache, resolver);
            SymbolUsageEngine usageEngine = new SymbolUsageEngine(sourceFileLocator, cache, resolver);
            return new ToolContext(workspace, sourceFileLocator, cache, resolver, symbolLocator, usageEngine);
        }
    }

    private static final class SourceWorkspace {
        private final Path projectRoot;
        private final List<Path> sourceRoots;

        private SourceWorkspace(Path projectRoot, List<Path> sourceRoots) {
            this.projectRoot = projectRoot;
            this.sourceRoots = sourceRoots;
        }

        private static SourceWorkspace detect(Path projectRoot) {
            List<Path> sourceRoots = new ArrayList<Path>();
            Path mainJava = projectRoot.resolve("src/main/java");
            Path testJava = projectRoot.resolve("src/test/java");
            if (Files.isDirectory(mainJava)) {
                sourceRoots.add(mainJava.toAbsolutePath().normalize());
            }
            if (Files.isDirectory(testJava)) {
                sourceRoots.add(testJava.toAbsolutePath().normalize());
            }
            if (sourceRoots.isEmpty()) {
                sourceRoots.add(projectRoot.toAbsolutePath().normalize());
            }
            return new SourceWorkspace(projectRoot.toAbsolutePath().normalize(), sourceRoots);
        }
    }

    private static final class SourceFileLocator {
        private final SourceWorkspace workspace;

        private SourceFileLocator(SourceWorkspace workspace) {
            this.workspace = workspace;
        }

        private List<Path> findJavaFiles() {
            List<Path> result = new ArrayList<Path>();
            for (Path sourceRoot : workspace.sourceRoots) {
                collectJavaFiles(sourceRoot, result);
            }
            Collections.sort(result);
            return result;
        }

        private Path locateType(String qualifiedName) {
            String relative = qualifiedName.replace('.', '/') + ".java";
            for (Path sourceRoot : workspace.sourceRoots) {
                Path candidate = sourceRoot.resolve(relative).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
            return null;
        }

        private Path sourceRootOf(Path sourceFile) {
            Path normalized = sourceFile.toAbsolutePath().normalize();
            for (Path sourceRoot : workspace.sourceRoots) {
                if (normalized.startsWith(sourceRoot)) {
                    return sourceRoot;
                }
            }
            return workspace.sourceRoots.get(0);
        }

        private void collectJavaFiles(Path root, List<Path> result) {
            if (!Files.exists(root)) {
                return;
            }
            try {
                Files.walk(root)
                        .filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> result.add(path.toAbsolutePath().normalize()));
            } catch (IOException exception) {
                throw new IllegalStateException("Could not scan source root: " + root, exception);
            }
        }
    }

    private static final class JavaSourceParser {
        private final JavaParser parser;

        private JavaSourceParser() {
            ParserConfiguration configuration = new ParserConfiguration();
            configuration.setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_8);
            this.parser = new JavaParser(configuration);
        }

        private ParsedSourceFile parse(Path file) {
            try {
                String source = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                ParseResult<CompilationUnit> result = parser.parse(source);
                if (!result.isSuccessful() || !result.getResult().isPresent()) {
                    throw new ParseProblemException(result.getProblems());
                }
                return new ParsedSourceFile(file.toAbsolutePath().normalize(), new SourceText(source), result.getResult().get());
            } catch (IOException exception) {
                throw new IllegalStateException("Could not read source file: " + file, exception);
            }
        }
    }

    private static final class ParsedSourceCache {
        private final JavaSourceParser parser;
        private final Map<Path, ParsedSourceFile> files;

        private ParsedSourceCache(JavaSourceParser parser) {
            this.parser = parser;
            this.files = new HashMap<Path, ParsedSourceFile>();
        }

        private ParsedSourceFile parse(Path file) {
            Path normalized = file.toAbsolutePath().normalize();
            ParsedSourceFile parsed = files.get(normalized);
            if (parsed != null) {
                return parsed;
            }
            parsed = parser.parse(normalized);
            files.put(normalized, parsed);
            return parsed;
        }
    }

    private static final class ParsedSourceFile {
        private final Path path;
        private final SourceText sourceText;
        private final CompilationUnit compilationUnit;

        private ParsedSourceFile(Path path, SourceText sourceText, CompilationUnit compilationUnit) {
            this.path = path;
            this.sourceText = sourceText;
            this.compilationUnit = compilationUnit;
        }

        private String packageName() {
            if (compilationUnit.getPackageDeclaration().isPresent()) {
                return compilationUnit.getPackageDeclaration().get().getNameAsString();
            }
            return "";
        }
    }

    private static final class SourceText {
        private final String text;
        private final List<Integer> lineStarts;

        private SourceText(String text) {
            this.text = text;
            this.lineStarts = buildLineStarts(text);
        }

        private int offsetAt(Position position) {
            return offsetAt(position.line, position.column);
        }

        private int offsetAt(int line, int column) {
            int lineStart = lineStarts.get(Math.max(0, line - 1)).intValue();
            return Math.min(text.length(), lineStart + Math.max(0, column - 1));
        }

        private int startOffset(Node node) {
            if (!node.getRange().isPresent()) {
                return -1;
            }
            return offsetAt(node.getRange().get().begin);
        }

        private int endOffset(Node node) {
            if (!node.getRange().isPresent()) {
                return -1;
            }
            Range range = node.getRange().get();
            return Math.min(text.length(), offsetAt(range.end) + 1);
        }

        private int lineOfOffset(int offset) {
            int safeOffset = Math.max(0, Math.min(offset, text.length()));
            int result = 1;
            for (int index = 0; index < lineStarts.size(); index++) {
                if (lineStarts.get(index).intValue() <= safeOffset) {
                    result = index + 1;
                } else {
                    break;
                }
            }
            return result;
        }

        private int columnOfOffset(int offset) {
            int line = lineOfOffset(offset);
            return offset - lineStarts.get(line - 1).intValue() + 1;
        }

        private int lineStartOfOffset(int offset) {
            int line = lineOfOffset(offset);
            return lineStarts.get(line - 1).intValue();
        }

        private int extendDeletionEnd(int endOffset) {
            int current = consumeHorizontalWhitespace(endOffset);
            int afterLineBreak = consumeLineBreak(current);
            if (afterLineBreak == current) {
                return current;
            }
            current = afterLineBreak;

            while (true) {
                int afterIndent = consumeHorizontalWhitespace(current);
                int afterBlankLine = consumeLineBreak(afterIndent);
                if (afterBlankLine == afterIndent) {
                    return current;
                }
                current = afterBlankLine;
            }
        }

        private int consumeHorizontalWhitespace(int offset) {
            int current = offset;
            while (current < text.length()) {
                char character = text.charAt(current);
                if (character == ' ' || character == '\t') {
                    current++;
                } else {
                    break;
                }
            }
            return current;
        }

        private int consumeLineBreak(int offset) {
            if (offset >= text.length()) {
                return offset;
            }
            if (text.charAt(offset) == '\r') {
                if (offset + 1 < text.length() && text.charAt(offset + 1) == '\n') {
                    return offset + 2;
                }
                return offset + 1;
            }
            if (text.charAt(offset) == '\n') {
                return offset + 1;
            }
            return offset;
        }

        private String substring(int start, int end) {
            return text.substring(Math.max(0, start), Math.min(text.length(), end));
        }

        private String lineSeparator() {
            int newline = text.indexOf('\n');
            if (newline > 0 && text.charAt(newline - 1) == '\r') {
                return "\r\n";
            }
            return "\n";
        }

        private static List<Integer> buildLineStarts(String source) {
            List<Integer> starts = new ArrayList<Integer>();
            starts.add(Integer.valueOf(0));
            for (int index = 0; index < source.length(); index++) {
                if (source.charAt(index) == '\n') {
                    starts.add(Integer.valueOf(index + 1));
                }
            }
            return starts;
        }
    }

    private enum SymbolKind {
        TYPE,
        METHOD,
        FIELD,
        LOCAL,
        PARAMETER
    }

    private enum PlanStatus {
        SAFE,
        NEEDS_VERIFICATION,
        UNSAFE
    }

    private static final class SymbolIdentity {
        private final SymbolKind kind;
        private final String ownerType;
        private final String simpleName;
        private final String signature;
        private final Path sourceFile;
        private final int nameStart;
        private final int nameEnd;

        private SymbolIdentity(SymbolKind kind, String ownerType, String simpleName, String signature, Path sourceFile, int nameStart, int nameEnd) {
            this.kind = kind;
            this.ownerType = ownerType;
            this.simpleName = simpleName;
            this.signature = signature;
            this.sourceFile = sourceFile;
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
        }

        private String id() {
            if (kind == SymbolKind.TYPE) {
                return ownerType;
            }
            return ownerType + "#" + signature;
        }

        private boolean sameSymbol(SymbolIdentity other) {
            if (other == null) {
                return false;
            }
            if (kind != other.kind) {
                return false;
            }
            if (!ownerType.equals(other.ownerType)) {
                return false;
            }
            if (kind == SymbolKind.METHOD) {
                return methodName(signature).equals(methodName(other.signature)) && parameterCount(signature) == parameterCount(other.signature);
            }
            return signature.equals(other.signature);
        }
    }

    private static final class ResolutionResult {
        private final SymbolIdentity symbol;
        private final String failure;

        private ResolutionResult(SymbolIdentity symbol, String failure) {
            this.symbol = symbol;
            this.failure = failure;
        }

        private static ResolutionResult resolved(SymbolIdentity symbol) {
            return new ResolutionResult(symbol, "");
        }

        private static ResolutionResult unresolved(String failure) {
            return new ResolutionResult(null, failure);
        }

        private boolean isResolved() {
            return symbol != null;
        }
    }

    private static final class AstSymbolResolver {
        private ResolutionResult resolveDeclaration(Node node, ParsedSourceFile file) {
            if (node instanceof MethodDeclaration) {
                MethodDeclaration method = (MethodDeclaration) node;
                String owner = ownerName(method, file);
                return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.METHOD, owner, method.getNameAsString(), methodSignature(method), file.path, nameStart(method.getName(), file), nameEnd(method.getName(), file)));
            }
            if (node instanceof VariableDeclarator) {
                VariableDeclarator variable = (VariableDeclarator) node;
                if (variable.getParentNode().isPresent() && variable.getParentNode().get() instanceof FieldDeclaration) {
                    String owner = ownerName(variable, file);
                    return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.FIELD, owner, variable.getNameAsString(), variable.getNameAsString(), file.path, nameStart(variable.getName(), file), nameEnd(variable.getName(), file)));
                }
                MethodDeclaration method = enclosingMethod(variable);
                String signature = method == null ? "<initializer>" : methodSignature(method);
                return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.LOCAL, ownerName(variable, file), variable.getNameAsString(), signature + ":local:" + variable.getNameAsString(), file.path, nameStart(variable.getName(), file), nameEnd(variable.getName(), file)));
            }
            if (node instanceof ClassOrInterfaceDeclaration) {
                ClassOrInterfaceDeclaration type = (ClassOrInterfaceDeclaration) node;
                String owner = qualifiedName(type, file);
                return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.TYPE, owner, type.getNameAsString(), type.getNameAsString(), file.path, nameStart(type.getName(), file), nameEnd(type.getName(), file)));
            }
            return ResolutionResult.unresolved("Unsupported declaration node: " + node.getClass().getSimpleName());
        }

        private ResolutionResult resolveUsage(Node node, ParsedSourceFile file) {
            if (node instanceof MethodCallExpr) {
                return resolveMethodCall((MethodCallExpr) node, file);
            }
            if (node instanceof NameExpr) {
                return resolveName((NameExpr) node, file);
            }
            if (node instanceof FieldAccessExpr) {
                return resolveFieldAccess((FieldAccessExpr) node, file);
            }
            if (node instanceof ClassOrInterfaceType) {
                ClassOrInterfaceType type = (ClassOrInterfaceType) node;
                String owner = resolveTypeName(type.getNameAsString(), file);
                return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.TYPE, owner, type.getNameAsString(), type.getNameAsString(), file.path, nameStart(type.getName(), file), nameEnd(type.getName(), file)));
            }
            return resolveDeclaration(node, file);
        }

        private ResolutionResult resolveMethodCall(MethodCallExpr call, ParsedSourceFile file) {
            String owner = "";
            if (call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                if (scope.isThisExpr()) {
                    owner = ownerName(call, file);
                } else if (scope.isNameExpr()) {
                    String type = visibleVariableType(call, scope.asNameExpr().getNameAsString(), file);
                    if (type.length() == 0 && Character.isUpperCase(scope.asNameExpr().getNameAsString().charAt(0))) {
                        type = scope.asNameExpr().getNameAsString();
                    }
                    owner = resolveTypeName(type, file);
                } else {
                    return ResolutionResult.unresolved("Unsupported method scope: " + call);
                }
            } else {
                owner = ownerName(call, file);
            }
            if (owner.length() == 0) {
                return ResolutionResult.unresolved("Could not resolve method owner: " + call);
            }
            String signature = call.getNameAsString() + unknownArguments(call.getArguments().size());
            return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.METHOD, owner, call.getNameAsString(), signature, file.path, nameStart(call.getName(), file), nameEnd(call.getName(), file)));
        }

        private ResolutionResult resolveName(NameExpr name, ParsedSourceFile file) {
            MethodDeclaration method = enclosingMethod(name);
            if (method != null) {
                for (Parameter parameter : method.getParameters()) {
                    if (parameter.getNameAsString().equals(name.getNameAsString())) {
                        return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.PARAMETER, ownerName(name, file), name.getNameAsString(), methodSignature(method) + ":parameter:" + name.getNameAsString(), file.path, nameStart(name.getName(), file), nameEnd(name.getName(), file)));
                    }
                }
                if (hasVisibleLocal(method, name, name.getNameAsString(), file)) {
                    return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.LOCAL, ownerName(name, file), name.getNameAsString(), methodSignature(method) + ":local:" + name.getNameAsString(), file.path, nameStart(name.getName(), file), nameEnd(name.getName(), file)));
                }
            }
            ClassOrInterfaceDeclaration ownerType = enclosingClass(name);
            if (ownerType != null && findField(ownerType, name.getNameAsString()) != null) {
                return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.FIELD, qualifiedName(ownerType, file), name.getNameAsString(), name.getNameAsString(), file.path, nameStart(name.getName(), file), nameEnd(name.getName(), file)));
            }
            return ResolutionResult.unresolved("Could not resolve name: " + name.getNameAsString());
        }

        private ResolutionResult resolveFieldAccess(FieldAccessExpr access, ParsedSourceFile file) {
            String owner = "";
            if (access.getScope().isThisExpr()) {
                owner = ownerName(access, file);
            } else if (access.getScope().isNameExpr()) {
                String type = visibleVariableType(access, access.getScope().asNameExpr().getNameAsString(), file);
                owner = resolveTypeName(type, file);
            }
            if (owner.length() == 0) {
                return ResolutionResult.unresolved("Could not resolve field access: " + access);
            }
            return ResolutionResult.resolved(new SymbolIdentity(SymbolKind.FIELD, owner, access.getNameAsString(), access.getNameAsString(), file.path, nameStart(access.getName(), file), nameEnd(access.getName(), file)));
        }
    }

    private static final class SymbolLocator {
        private final SourceFileLocator sourceFileLocator;
        private final ParsedSourceCache cache;
        private final AstSymbolResolver resolver;

        private SymbolLocator(SourceFileLocator sourceFileLocator, ParsedSourceCache cache, AstSymbolResolver resolver) {
            this.sourceFileLocator = sourceFileLocator;
            this.cache = cache;
            this.resolver = resolver;
        }

        private SymbolIdentity locate(String symbolId) {
            int separator = symbolId.indexOf('#');
            if (separator < 0) {
                return locateType(symbolId);
            }
            String owner = symbolId.substring(0, separator);
            String member = symbolId.substring(separator + 1);
            ParsedSourceFile file = parseOwner(owner);
            ClassOrInterfaceDeclaration type = findType(file, simpleName(owner));
            if (type == null) {
                throw new IllegalArgumentException("Could not locate owner type: " + owner);
            }
            if (member.indexOf('(') >= 0) {
                MethodDeclaration method = findMethod(type, methodName(member), parameterCount(member));
                if (method == null) {
                    throw new IllegalArgumentException("Could not locate method: " + symbolId);
                }
                return resolver.resolveDeclaration(method, file).symbol;
            }
            VariableDeclarator field = findField(type, member);
            if (field == null) {
                throw new IllegalArgumentException("Could not locate field: " + symbolId);
            }
            return resolver.resolveDeclaration(field, file).symbol;
        }

        private SymbolIdentity locateType(String qualifiedName) {
            ParsedSourceFile file = parseOwner(qualifiedName);
            ClassOrInterfaceDeclaration type = findType(file, simpleName(qualifiedName));
            if (type == null) {
                throw new IllegalArgumentException("Could not locate type: " + qualifiedName);
            }
            return resolver.resolveDeclaration(type, file).symbol;
        }

        private ParsedSourceFile parseOwner(String owner) {
            Path file = sourceFileLocator.locateType(owner);
            if (file == null) {
                throw new IllegalArgumentException("Could not locate source file for type: " + owner);
            }
            return cache.parse(file);
        }
    }

    private static final class SymbolUsageEngine {
        private final SourceFileLocator sourceFileLocator;
        private final ParsedSourceCache cache;
        private final AstSymbolResolver resolver;

        private SymbolUsageEngine(SourceFileLocator sourceFileLocator, ParsedSourceCache cache, AstSymbolResolver resolver) {
            this.sourceFileLocator = sourceFileLocator;
            this.cache = cache;
            this.resolver = resolver;
        }

        private UsageGraph findUsages(SymbolIdentity target) {
            UsageGraph graph = new UsageGraph(target);
            List<Path> candidateFiles = findCandidateFiles(target.simpleName);
            for (Path file : candidateFiles) {
                ParsedSourceFile parsed = cache.parse(file);
                graph.parsedFiles.add(file);
                for (Node candidate : candidates(parsed, target)) {
                    ResolutionResult result = resolver.resolveUsage(candidate, parsed);
                    if (!result.isResolved()) {
                        graph.unresolved.add(new UnresolvedCandidate(parsed, candidate, result.failure));
                        continue;
                    }
                    if (target.sameSymbol(result.symbol)) {
                        graph.usages.add(new ResolvedUsage(parsed, candidate, result.symbol));
                    }
                }
            }
            return graph;
        }

        private List<Path> findCandidateFiles(String token) {
            List<Path> result = new ArrayList<Path>();
            for (Path file : sourceFileLocator.findJavaFiles()) {
                String source = read(file);
                if (containsJavaToken(source, token)) {
                    result.add(file);
                }
            }
            return result;
        }

        private boolean containsJavaToken(String source, String token) {
            int from = 0;
            while (from < source.length()) {
                int offset = source.indexOf(token, from);
                if (offset < 0) {
                    return false;
                }
                boolean before = offset == 0 || !Character.isJavaIdentifierPart(source.charAt(offset - 1));
                int afterIndex = offset + token.length();
                boolean after = afterIndex >= source.length() || !Character.isJavaIdentifierPart(source.charAt(afterIndex));
                if (before && after) {
                    return true;
                }
                from = offset + token.length();
            }
            return false;
        }

        private List<Node> candidates(ParsedSourceFile file, SymbolIdentity target) {
            if (target.kind == SymbolKind.METHOD) {
                return methodCandidates(file, target.simpleName);
            }
            if (target.kind == SymbolKind.FIELD) {
                return fieldCandidates(file, target.simpleName);
            }
            if (target.kind == SymbolKind.TYPE) {
                return typeCandidates(file, target.simpleName);
            }
            return new ArrayList<Node>();
        }

        private List<Node> methodCandidates(ParsedSourceFile file, String name) {
            List<Node> result = new ArrayList<Node>();
            for (MethodDeclaration method : file.compilationUnit.findAll(MethodDeclaration.class)) {
                if (method.getNameAsString().equals(name)) {
                    result.add(method);
                }
            }
            for (MethodCallExpr call : file.compilationUnit.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals(name)) {
                    result.add(call);
                }
            }
            return result;
        }

        private List<Node> fieldCandidates(ParsedSourceFile file, String name) {
            List<Node> result = new ArrayList<Node>();
            for (VariableDeclarator variable : file.compilationUnit.findAll(VariableDeclarator.class)) {
                if (variable.getNameAsString().equals(name) && variable.getParentNode().isPresent() && variable.getParentNode().get() instanceof FieldDeclaration) {
                    result.add(variable);
                }
            }
            for (NameExpr expression : file.compilationUnit.findAll(NameExpr.class)) {
                if (expression.getNameAsString().equals(name)) {
                    result.add(expression);
                }
            }
            for (FieldAccessExpr expression : file.compilationUnit.findAll(FieldAccessExpr.class)) {
                if (expression.getNameAsString().equals(name)) {
                    result.add(expression);
                }
            }
            return result;
        }

        private List<Node> typeCandidates(ParsedSourceFile file, String name) {
            List<Node> result = new ArrayList<Node>();
            for (ClassOrInterfaceDeclaration declaration : file.compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
                if (declaration.getNameAsString().equals(name)) {
                    result.add(declaration);
                }
            }
            for (ClassOrInterfaceType type : file.compilationUnit.findAll(ClassOrInterfaceType.class)) {
                if (type.getNameAsString().equals(name)) {
                    result.add(type);
                }
            }
            return result;
        }
    }

    private static final class UsageGraph {
        private final SymbolIdentity target;
        private final List<ResolvedUsage> usages = new ArrayList<ResolvedUsage>();
        private final List<UnresolvedCandidate> unresolved = new ArrayList<UnresolvedCandidate>();
        private final List<Path> parsedFiles = new ArrayList<Path>();

        private UsageGraph(SymbolIdentity target) {
            this.target = target;
        }
    }

    private static final class ResolvedUsage {
        private final ParsedSourceFile file;
        private final Node node;
        private final SymbolIdentity symbol;

        private ResolvedUsage(ParsedSourceFile file, Node node, SymbolIdentity symbol) {
            this.file = file;
            this.node = node;
            this.symbol = symbol;
        }
    }

    private static final class UnresolvedCandidate {
        private final ParsedSourceFile file;
        private final Node node;
        private final String reason;

        private UnresolvedCandidate(ParsedSourceFile file, Node node, String reason) {
            this.file = file;
            this.node = node;
            this.reason = reason;
        }
    }

    private static final class SourceEdit {
        private final Path file;
        private final int start;
        private final int end;
        private final String replacement;
        private final String reason;

        private SourceEdit(Path file, int start, int end, String replacement, String reason) {
            this.file = file;
            this.start = start;
            this.end = end;
            this.replacement = replacement;
            this.reason = reason;
        }
    }

    private static final class SourceChangeSet {
        private final List<SourceEdit> edits = new ArrayList<SourceEdit>();
        private final Map<Path, String> createdFiles = new LinkedHashMap<Path, String>();

        private void addEdit(SourceEdit edit) {
            edits.add(edit);
        }

        private void createFile(Path file, String source) {
            createdFiles.put(file, source);
        }

        private boolean hasOverlaps() {
            Map<Path, List<SourceEdit>> byFile = groupByFile();
            for (List<SourceEdit> fileEdits : byFile.values()) {
                Collections.sort(fileEdits, editComparator());
                SourceEdit previous = null;
                for (SourceEdit edit : fileEdits) {
                    if (previous != null && edit.start < previous.end) {
                        return true;
                    }
                    previous = edit;
                }
            }
            return false;
        }

        private Map<Path, List<SourceEdit>> groupByFile() {
            Map<Path, List<SourceEdit>> grouped = new LinkedHashMap<Path, List<SourceEdit>>();
            for (SourceEdit edit : edits) {
                List<SourceEdit> fileEdits = grouped.get(edit.file);
                if (fileEdits == null) {
                    fileEdits = new ArrayList<SourceEdit>();
                    grouped.put(edit.file, fileEdits);
                }
                fileEdits.add(edit);
            }
            return grouped;
        }
    }

    private static final class SourceChangeApplier {
        private void apply(SourceChangeSet changeSet) {
            if (changeSet.hasOverlaps()) {
                throw new IllegalStateException("Change set contains overlapping edits.");
            }
            for (Map.Entry<Path, List<SourceEdit>> entry : changeSet.groupByFile().entrySet()) {
                Path file = entry.getKey();
                List<SourceEdit> edits = entry.getValue();
                Collections.sort(edits, reverseEditComparator());
                String source = read(file);
                StringBuilder builder = new StringBuilder(source);
                for (SourceEdit edit : edits) {
                    builder.replace(edit.start, edit.end, edit.replacement);
                }
                write(file, builder.toString());
            }
            for (Map.Entry<Path, String> entry : changeSet.createdFiles.entrySet()) {
                if (Files.exists(entry.getKey())) {
                    throw new IllegalStateException("Target file already exists: " + entry.getKey());
                }
                try {
                    Files.createDirectories(entry.getKey().getParent());
                } catch (IOException exception) {
                    throw new IllegalStateException("Could not create output directory: " + entry.getKey().getParent(), exception);
                }
                write(entry.getKey(), entry.getValue());
            }
        }
    }

    private static final class RenameRequest {
        private final SymbolIdentity target;
        private final String newName;

        private RenameRequest(SymbolIdentity target, String newName) {
            this.target = target;
            this.newName = newName;
        }
    }

    private static final class RenamePlanner {
        private final ToolContext context;

        private RenamePlanner(ToolContext context) {
            this.context = context;
        }

        private RenamePlan plan(RenameRequest request) {
            List<String> diagnostics = new ArrayList<String>();
            SourceChangeSet changes = new SourceChangeSet();
            UsageGraph graph = context.usageEngine.findUsages(request.target);
            validateJavaIdentifier(request.newName, "new name", diagnostics);
            validateRenameConflict(request.target, request.newName, diagnostics);
            Set<String> editKeys = new HashSet<String>();
            addRenameEdit(changes, editKeys, request.target.sourceFile, request.target.nameStart, request.target.nameEnd, request.newName, "rename declaration");
            for (ResolvedUsage usage : graph.usages) {
                addRenameEdit(changes, editKeys, usage.file.path, usage.symbol.nameStart, usage.symbol.nameEnd, request.newName, "rename reference");
            }
            if (!graph.unresolved.isEmpty()) {
                diagnostics.add("Unresolved candidates found: " + graph.unresolved.size());
            }
            if (changes.hasOverlaps()) {
                diagnostics.add("Generated overlapping edits.");
            }
            PlanStatus status = diagnostics.isEmpty() ? PlanStatus.SAFE : PlanStatus.UNSAFE;
            return new RenamePlan(status, request.target, request.newName, graph, changes, diagnostics);
        }

        private void validateRenameConflict(SymbolIdentity target, String newName, List<String> diagnostics) {
            if (target.sourceFile == null) {
                diagnostics.add("Target symbol has no source file.");
                return;
            }
            ParsedSourceFile file = context.parsedSourceCache.parse(target.sourceFile);
            ClassOrInterfaceDeclaration owner = findType(file, simpleName(target.ownerType));
            if (owner == null) {
                diagnostics.add("Could not locate owner type: " + target.ownerType);
                return;
            }
            if (target.kind == SymbolKind.FIELD && findField(owner, newName) != null && !target.simpleName.equals(newName)) {
                diagnostics.add("Owner already has a field named " + newName);
            }
            if (target.kind == SymbolKind.METHOD && findMethod(owner, newName, parameterCount(target.signature)) != null && !target.simpleName.equals(newName)) {
                diagnostics.add("Owner already has a method named " + newName + " with the same arity.");
            }
        }

        private void addRenameEdit(SourceChangeSet changes, Set<String> keys, Path file, int start, int end, String replacement, String reason) {
            String key = file.toString() + ":" + start + ":" + end;
            if (keys.add(key)) {
                changes.addEdit(new SourceEdit(file, start, end, replacement, reason));
            }
        }
    }

    private static final class RenamePlan {
        private final PlanStatus status;
        private final SymbolIdentity target;
        private final String newName;
        private final UsageGraph graph;
        private final SourceChangeSet changeSet;
        private final List<String> diagnostics;

        private RenamePlan(PlanStatus status, SymbolIdentity target, String newName, UsageGraph graph, SourceChangeSet changeSet, List<String> diagnostics) {
            this.status = status;
            this.target = target;
            this.newName = newName;
            this.graph = graph;
            this.changeSet = changeSet;
            this.diagnostics = diagnostics;
        }

        private String toJson() {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            jsonField(builder, "operation", quote("rename"), true);
            jsonField(builder, "status", quote(status.name()), true);
            jsonField(builder, "targetSymbol", quote(target.id()), true);
            jsonField(builder, "newName", quote(newName), true);
            jsonField(builder, "parsedFiles", String.valueOf(graph.parsedFiles.size()), true);
            jsonField(builder, "resolvedUsages", String.valueOf(graph.usages.size()), true);
            jsonField(builder, "unresolvedCandidates", String.valueOf(graph.unresolved.size()), true);
            appendDiagnostics(builder, diagnostics);
            appendEdits(builder, changeSet, true);
            appendUnresolved(builder, graph.unresolved);
            builder.append("}\n");
            return builder.toString();
        }
    }

    private static final class ExtractClassRequest {
        private final String sourceClass;
        private final String targetClass;
        private final String delegateField;
        private final List<String> members;

        private ExtractClassRequest(String sourceClass, String targetClass, String delegateField, List<String> members) {
            this.sourceClass = sourceClass;
            this.targetClass = targetClass;
            this.delegateField = delegateField;
            this.members = members;
        }
    }

    private static final class SelectedMember {
        private final SymbolIdentity symbol;
        private final Node declaration;

        private SelectedMember(SymbolIdentity symbol, Node declaration) {
            this.symbol = symbol;
            this.declaration = declaration;
        }
    }

    private static final class ExtractClassPlanner {
        private final ToolContext context;

        private ExtractClassPlanner(ToolContext context) {
            this.context = context;
        }

        private ExtractClassPlan plan(ExtractClassRequest request) {
            List<String> diagnostics = new ArrayList<String>();
            SourceChangeSet changes = new SourceChangeSet();
            List<SelectedMember> selected = new ArrayList<SelectedMember>();
            validateJavaIdentifier(request.targetClass, "target class", diagnostics);
            validateJavaIdentifier(request.delegateField, "delegate field", diagnostics);

            Path sourcePath = context.sourceFileLocator.locateType(request.sourceClass);
            if (sourcePath == null) {
                diagnostics.add("Could not locate source class: " + request.sourceClass);
                return new ExtractClassPlan(PlanStatus.UNSAFE, request, null, changes, selected, diagnostics);
            }
            ParsedSourceFile sourceFile = context.parsedSourceCache.parse(sourcePath);
            ClassOrInterfaceDeclaration sourceType = findType(sourceFile, simpleName(request.sourceClass));
            if (sourceType == null) {
                diagnostics.add("Could not locate source type in file: " + request.sourceClass);
                return new ExtractClassPlan(PlanStatus.UNSAFE, request, null, changes, selected, diagnostics);
            }

            selected.addAll(resolveSelectedMembers(request, sourceFile, sourceType, diagnostics));
            validateSelectedMembers(selected, diagnostics);
            validateMovedDependencies(sourceFile, sourceType, selected, diagnostics);

            Map<String, SelectedMember> selectedBySymbol = new HashMap<String, SelectedMember>();
            Map<String, VariableDeclarator> selectedFields = new HashMap<String, VariableDeclarator>();
            for (SelectedMember member : selected) {
                selectedBySymbol.put(member.symbol.id(), member);
                if (member.symbol.kind == SymbolKind.FIELD && member.declaration instanceof FieldDeclaration) {
                    VariableDeclarator field = findFieldInDeclaration((FieldDeclaration) member.declaration, member.symbol.simpleName);
                    selectedFields.put(member.symbol.simpleName, field);
                }
            }

            Map<String, String> getterNames = new LinkedHashMap<String, String>();
            Map<String, String> setterNames = new LinkedHashMap<String, String>();
            planReferenceRewrites(request, sourceFile, sourceType, selected, selectedFields, getterNames, setterNames, changes, diagnostics);

            Path targetFile = sourcePath.getParent().resolve(request.targetClass + ".java");
            if (Files.exists(targetFile)) {
                diagnostics.add("Target file already exists: " + targetFile);
            }

            if (diagnostics.isEmpty()) {
                removeSelectedMembers(sourceFile, selected, changes);
                insertDelegateField(sourceFile, sourceType, request, changes);
                changes.createFile(targetFile, createExtractedClassSource(sourceFile, request, selected, getterNames, setterNames));
            }

            if (changes.hasOverlaps()) {
                diagnostics.add("Generated overlapping edits.");
            }
            PlanStatus status = diagnostics.isEmpty() ? PlanStatus.SAFE : PlanStatus.UNSAFE;
            return new ExtractClassPlan(status, request, targetFile, changes, selected, diagnostics);
        }

        private List<SelectedMember> resolveSelectedMembers(ExtractClassRequest request, ParsedSourceFile sourceFile, ClassOrInterfaceDeclaration sourceType, List<String> diagnostics) {
            List<SelectedMember> selected = new ArrayList<SelectedMember>();
            for (String member : request.members) {
                if (member.startsWith("field:")) {
                    String fieldName = member.substring("field:".length());
                    VariableDeclarator field = findField(sourceType, fieldName);
                    if (field == null || !field.getParentNode().isPresent()) {
                        diagnostics.add("Could not locate field: " + fieldName);
                        continue;
                    }
                    selected.add(new SelectedMember(context.symbolResolver.resolveDeclaration(field, sourceFile).symbol, field.getParentNode().get()));
                } else if (member.startsWith("method:")) {
                    String methodPart = member.substring("method:".length());
                    MethodDeclaration method = findMethod(sourceType, methodName(methodPart), parameterCount(methodPart));
                    if (method == null) {
                        diagnostics.add("Could not locate method: " + methodPart);
                        continue;
                    }
                    selected.add(new SelectedMember(context.symbolResolver.resolveDeclaration(method, sourceFile).symbol, method));
                } else {
                    diagnostics.add("Unsupported member selector: " + member);
                }
            }
            return selected;
        }

        private void validateSelectedMembers(List<SelectedMember> selected, List<String> diagnostics) {
            Set<String> ranges = new HashSet<String>();
            for (SelectedMember member : selected) {
                if (!ranges.add(member.declaration.toString())) {
                    continue;
                }
                if (member.symbol.kind == SymbolKind.FIELD && member.declaration instanceof FieldDeclaration) {
                    FieldDeclaration field = (FieldDeclaration) member.declaration;
                    if (!field.isPrivate()) {
                        diagnostics.add("MVP Extract Class only moves private fields: " + member.symbol.id());
                    }
                    if (field.getVariables().size() > 1) {
                        diagnostics.add("MVP Extract Class does not split multi-variable field declarations yet: " + member.symbol.id());
                    }
                }
                if (member.symbol.kind == SymbolKind.METHOD && member.declaration instanceof MethodDeclaration) {
                    MethodDeclaration method = (MethodDeclaration) member.declaration;
                    if (!method.isPrivate()) {
                        diagnostics.add("MVP Extract Class only moves private methods: " + member.symbol.id());
                    }
                    if (method.getAnnotationByName("Override").isPresent()) {
                        diagnostics.add("MVP Extract Class does not move @Override methods: " + member.symbol.id());
                    }
                }
            }
        }

        private void validateMovedDependencies(ParsedSourceFile sourceFile, ClassOrInterfaceDeclaration sourceType, List<SelectedMember> selected, List<String> diagnostics) {
            Set<String> selectedFields = new HashSet<String>();
            Set<String> selectedMethods = new HashSet<String>();
            Set<String> allFields = new HashSet<String>();
            Set<String> allMethods = new HashSet<String>();
            for (VariableDeclarator field : fieldsOf(sourceType)) {
                allFields.add(field.getNameAsString());
            }
            for (MethodDeclaration method : sourceType.getMethods()) {
                allMethods.add(method.getNameAsString());
            }
            for (SelectedMember member : selected) {
                if (member.symbol.kind == SymbolKind.FIELD) {
                    selectedFields.add(member.symbol.simpleName);
                }
                if (member.symbol.kind == SymbolKind.METHOD) {
                    selectedMethods.add(methodName(member.symbol.signature));
                }
            }
            for (SelectedMember member : selected) {
                if (member.symbol.kind != SymbolKind.METHOD || !(member.declaration instanceof MethodDeclaration)) {
                    continue;
                }
                MethodDeclaration method = (MethodDeclaration) member.declaration;
                if (!method.findAll(com.github.javaparser.ast.expr.ThisExpr.class).isEmpty()) {
                    diagnostics.add("Selected method uses this and is not supported yet: " + member.symbol.id());
                }
                if (!method.findAll(com.github.javaparser.ast.expr.SuperExpr.class).isEmpty()) {
                    diagnostics.add("Selected method uses super and is not supported yet: " + member.symbol.id());
                }
                for (NameExpr name : method.findAll(NameExpr.class)) {
                    String simple = name.getNameAsString();
                    if (allFields.contains(simple) && !selectedFields.contains(simple) && !hasVisibleLocal(enclosingMethod(name), name, simple, sourceFile)) {
                        diagnostics.add("Selected method reads remaining field: " + member.symbol.id() + " -> " + simple);
                    }
                }
                for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
                    if (call.getScope().isPresent() && !call.getScope().get().isThisExpr()) {
                        continue;
                    }
                    String simple = call.getNameAsString();
                    if (allMethods.contains(simple) && !selectedMethods.contains(simple)) {
                        diagnostics.add("Selected method calls remaining method: " + member.symbol.id() + " -> " + simple);
                    }
                    if (!allMethods.contains(simple)) {
                        diagnostics.add("Selected method calls inherited or external instance method: " + member.symbol.id() + " -> " + simple);
                    }
                }
            }
        }

        private void planReferenceRewrites(ExtractClassRequest request, ParsedSourceFile sourceFile, ClassOrInterfaceDeclaration sourceType, List<SelectedMember> selected, Map<String, VariableDeclarator> selectedFields, Map<String, String> getterNames, Map<String, String> setterNames, SourceChangeSet changes, List<String> diagnostics) {
            for (SelectedMember member : selected) {
                UsageGraph graph = context.usageEngine.findUsages(member.symbol);
                if (!graph.unresolved.isEmpty()) {
                    diagnostics.add("Unresolved candidates for moved member: " + member.symbol.id());
                }
                for (ResolvedUsage usage : graph.usages) {
                    if (isDeclarationUsage(member, usage) || isInsideSelectedMember(usage.node, selected)) {
                        continue;
                    }
                    ClassOrInterfaceDeclaration usageClass = enclosingClass(usage.node);
                    if (usageClass == null || !qualifiedName(usageClass, usage.file).equals(request.sourceClass)) {
                        diagnostics.add("External usage of moved member is not supported yet: " + member.symbol.id());
                        continue;
                    }
                    if (member.symbol.kind == SymbolKind.METHOD) {
                        rewriteMethodUsage(request, usage, changes, diagnostics);
                    } else if (member.symbol.kind == SymbolKind.FIELD) {
                        VariableDeclarator field = selectedFields.get(member.symbol.simpleName);
                        rewriteFieldUsage(request, sourceFile, field, usage, getterNames, setterNames, changes, diagnostics);
                    }
                }
            }
        }

        private void rewriteMethodUsage(ExtractClassRequest request, ResolvedUsage usage, SourceChangeSet changes, List<String> diagnostics) {
            if (!(usage.node instanceof MethodCallExpr)) {
                return;
            }
            MethodCallExpr call = (MethodCallExpr) usage.node;
            if (!call.getScope().isPresent()) {
                changes.addEdit(new SourceEdit(usage.file.path, usage.symbol.nameStart, usage.symbol.nameStart, request.delegateField + ".", "delegate moved method call"));
                return;
            }
            Expression scope = call.getScope().get();
            if (scope.isThisExpr()) {
                changes.addEdit(new SourceEdit(usage.file.path, usage.file.sourceText.startOffset(scope), usage.file.sourceText.endOffset(scope), request.delegateField, "delegate moved method call"));
                return;
            }
            diagnostics.add("Unsupported scoped moved method call: " + call);
        }

        private void rewriteFieldUsage(ExtractClassRequest request, ParsedSourceFile sourceFile, VariableDeclarator field, ResolvedUsage usage, Map<String, String> getterNames, Map<String, String> setterNames, SourceChangeSet changes, List<String> diagnostics) {
            if (field == null) {
                diagnostics.add("Could not plan accessor for field usage: " + usage.symbol.id());
                return;
            }
            Node node = usage.node;
            String fieldName = field.getNameAsString();
            if (isSimpleAssignTarget(node)) {
                AssignExpr assignment = node.findAncestor(AssignExpr.class).get();
                String setter = setterName(fieldName);
                setterNames.put(fieldName, setter);
                int valueStart = sourceFile.sourceText.startOffset(assignment.getValue());
                int valueEnd = sourceFile.sourceText.endOffset(assignment.getValue());
                String valueText = sourceFile.sourceText.substring(valueStart, valueEnd);
                changes.addEdit(new SourceEdit(sourceFile.path, sourceFile.sourceText.startOffset(assignment), sourceFile.sourceText.endOffset(assignment), request.delegateField + "." + setter + "(" + valueText + ")", "rewrite moved field write"));
                return;
            }
            if (isAssignTarget(node)) {
                diagnostics.add("Unsupported write to moved field: " + fieldName);
                return;
            }
            String getter = getterName(fieldName);
            getterNames.put(fieldName, getter);
            changes.addEdit(new SourceEdit(sourceFile.path, sourceFile.sourceText.startOffset(node), sourceFile.sourceText.endOffset(node), request.delegateField + "." + getter + "()", "rewrite moved field read"));
        }

        private void removeSelectedMembers(ParsedSourceFile sourceFile, List<SelectedMember> selected, SourceChangeSet changes) {
            Set<String> ranges = new HashSet<String>();
            for (SelectedMember member : selected) {
                int start = sourceFile.sourceText.lineStartOfOffset(sourceFile.sourceText.startOffset(member.declaration));
                int end = sourceFile.sourceText.extendDeletionEnd(sourceFile.sourceText.endOffset(member.declaration));
                String key = start + ":" + end;
                if (ranges.add(key)) {
                    changes.addEdit(new SourceEdit(sourceFile.path, start, end, "", "remove moved member"));
                }
            }
        }

        private void insertDelegateField(ParsedSourceFile sourceFile, ClassOrInterfaceDeclaration sourceType, ExtractClassRequest request, SourceChangeSet changes) {
            int offset = insertionOffsetAfterFields(sourceFile, sourceType);
            String lineSeparator = sourceFile.sourceText.lineSeparator();
            String source = "    private final " + request.targetClass + " " + request.delegateField + " = new " + request.targetClass + "();" + lineSeparator + lineSeparator;
            changes.addEdit(new SourceEdit(sourceFile.path, offset, offset, source, "insert delegate field"));
        }

        private String createExtractedClassSource(ParsedSourceFile sourceFile, ExtractClassRequest request, List<SelectedMember> selected, Map<String, String> getterNames, Map<String, String> setterNames) {
            String lineSeparator = sourceFile.sourceText.lineSeparator();
            StringBuilder builder = new StringBuilder();
            if (sourceFile.packageName().length() > 0) {
                builder.append("package ").append(sourceFile.packageName()).append(';').append(lineSeparator).append(lineSeparator);
            }
            for (ImportDeclaration importDeclaration : sourceFile.compilationUnit.getImports()) {
                builder.append(importDeclaration.toString()).append(lineSeparator);
            }
            if (!sourceFile.compilationUnit.getImports().isEmpty()) {
                builder.append(lineSeparator);
            }
            builder.append("final class ").append(request.targetClass).append(" {").append(lineSeparator);
            for (SelectedMember member : selected) {
                builder.append(lineSeparator);
                builder.append(indentMovedMember(sourceFile.sourceText.substring(sourceFile.sourceText.startOffset(member.declaration), sourceFile.sourceText.endOffset(member.declaration)), member.symbol.kind));
                builder.append(lineSeparator);
            }
            for (SelectedMember member : selected) {
                if (member.symbol.kind != SymbolKind.FIELD || !(member.declaration instanceof FieldDeclaration)) {
                    continue;
                }
                VariableDeclarator field = findFieldInDeclaration((FieldDeclaration) member.declaration, member.symbol.simpleName);
                if (field == null) {
                    continue;
                }
                String fieldName = field.getNameAsString();
                if (getterNames.containsKey(fieldName)) {
                    builder.append(lineSeparator)
                            .append("    ").append(field.getType().asString()).append(' ').append(getterNames.get(fieldName)).append("() {").append(lineSeparator)
                            .append("        return ").append(fieldName).append(';').append(lineSeparator)
                            .append("    }").append(lineSeparator);
                }
                if (setterNames.containsKey(fieldName)) {
                    builder.append(lineSeparator)
                            .append("    void ").append(setterNames.get(fieldName)).append('(').append(field.getType().asString()).append(' ').append(fieldName).append(") {").append(lineSeparator)
                            .append("        this.").append(fieldName).append(" = ").append(fieldName).append(';').append(lineSeparator)
                            .append("    }").append(lineSeparator);
                }
            }
            builder.append("}").append(lineSeparator);
            return builder.toString();
        }

        private String indentMovedMember(String source, SymbolKind kind) {
            String rewritten = source;
            if (kind == SymbolKind.METHOD) {
                rewritten = rewritten.replaceFirst("(?m)^(\\s*)private\\s+", "$1");
            }
            String[] lines = rewritten.split("\\r?\\n", -1);
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (line.trim().length() == 0) {
                    builder.append(line);
                } else {
                    builder.append("    ").append(removeFourSpaces(line));
                }
                if (index + 1 < lines.length) {
                    builder.append('\n');
                }
            }
            return builder.toString();
        }
    }

    private static final class ExtractClassPlan {
        private final PlanStatus status;
        private final ExtractClassRequest request;
        private final Path targetFile;
        private final SourceChangeSet changeSet;
        private final List<SelectedMember> selected;
        private final List<String> diagnostics;

        private ExtractClassPlan(PlanStatus status, ExtractClassRequest request, Path targetFile, SourceChangeSet changeSet, List<SelectedMember> selected, List<String> diagnostics) {
            this.status = status;
            this.request = request;
            this.targetFile = targetFile;
            this.changeSet = changeSet;
            this.selected = selected;
            this.diagnostics = diagnostics;
        }

        private String toJson() {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n");
            jsonField(builder, "operation", quote("extract-class"), true);
            jsonField(builder, "status", quote(status.name()), true);
            jsonField(builder, "sourceClass", quote(request.sourceClass), true);
            jsonField(builder, "targetClass", quote(request.targetClass), true);
            jsonField(builder, "targetFile", path(targetFile), true);
            appendMovedSymbols(builder, selected);
            appendDiagnostics(builder, diagnostics);
            appendEdits(builder, changeSet, false);
            appendCreatedFiles(builder, changeSet);
            builder.append("}\n");
            return builder.toString();
        }
    }

    private static final class OutlinePrinter {
        private final ToolContext context;

        private OutlinePrinter(ToolContext context) {
            this.context = context;
        }

        private String print() {
            StringBuilder builder = new StringBuilder();
            builder.append("{\n  \"files\": [\n");
            List<Path> files = context.sourceFileLocator.findJavaFiles();
            for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
                ParsedSourceFile file = context.parsedSourceCache.parse(files.get(fileIndex));
                builder.append("    {\"file\": ").append(path(file.path)).append(", \"types\": [");
                List<ClassOrInterfaceDeclaration> types = file.compilationUnit.findAll(ClassOrInterfaceDeclaration.class);
                for (int typeIndex = 0; typeIndex < types.size(); typeIndex++) {
                    ClassOrInterfaceDeclaration type = types.get(typeIndex);
                    builder.append("{\"name\": ").append(quote(type.getNameAsString()))
                            .append(", \"fields\": ").append(fieldsOf(type).size())
                            .append(", \"methods\": ").append(type.getMethods().size())
                            .append(", \"methodNames\": [");
                    for (int methodIndex = 0; methodIndex < type.getMethods().size(); methodIndex++) {
                        if (methodIndex > 0) {
                            builder.append(", ");
                        }
                        builder.append(quote(type.getMethods().get(methodIndex).getNameAsString()));
                    }
                    builder.append("]}");
                    if (typeIndex + 1 < types.size()) {
                        builder.append(", ");
                    }
                }
                builder.append("]}");
                if (fileIndex + 1 < files.size()) {
                    builder.append(',');
                }
                builder.append('\n');
            }
            builder.append("  ]\n}\n");
            return builder.toString();
        }
    }

    private static ClassOrInterfaceDeclaration enclosingClass(Node node) {
        if (node instanceof ClassOrInterfaceDeclaration) {
            return (ClassOrInterfaceDeclaration) node;
        }
        Optional<ClassOrInterfaceDeclaration> type = node.findAncestor(ClassOrInterfaceDeclaration.class);
        return type.isPresent() ? type.get() : null;
    }

    private static MethodDeclaration enclosingMethod(Node node) {
        if (node instanceof MethodDeclaration) {
            return (MethodDeclaration) node;
        }
        Optional<MethodDeclaration> method = node.findAncestor(MethodDeclaration.class);
        return method.isPresent() ? method.get() : null;
    }

    private static String ownerName(Node node, ParsedSourceFile file) {
        ClassOrInterfaceDeclaration type = enclosingClass(node);
        if (type == null) {
            return "";
        }
        return qualifiedName(type, file);
    }

    private static String qualifiedName(ClassOrInterfaceDeclaration type, ParsedSourceFile file) {
        String packageName = file.packageName();
        if (packageName.length() == 0) {
            return type.getNameAsString();
        }
        return packageName + "." + type.getNameAsString();
    }

    private static ClassOrInterfaceDeclaration findType(ParsedSourceFile file, String simpleName) {
        for (ClassOrInterfaceDeclaration type : file.compilationUnit.findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.getNameAsString().equals(simpleName)) {
                return type;
            }
        }
        return null;
    }

    private static VariableDeclarator findField(ClassOrInterfaceDeclaration type, String name) {
        for (FieldDeclaration field : type.getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getNameAsString().equals(name)) {
                    return variable;
                }
            }
        }
        return null;
    }

    private static VariableDeclarator findFieldInDeclaration(FieldDeclaration field, String name) {
        for (VariableDeclarator variable : field.getVariables()) {
            if (variable.getNameAsString().equals(name)) {
                return variable;
            }
        }
        return null;
    }

    private static List<VariableDeclarator> fieldsOf(ClassOrInterfaceDeclaration type) {
        List<VariableDeclarator> fields = new ArrayList<VariableDeclarator>();
        for (FieldDeclaration field : type.getFields()) {
            fields.addAll(field.getVariables());
        }
        return fields;
    }

    private static MethodDeclaration findMethod(ClassOrInterfaceDeclaration type, String name, int parameterCount) {
        for (MethodDeclaration method : type.getMethods()) {
            if (method.getNameAsString().equals(name) && method.getParameters().size() == parameterCount) {
                return method;
            }
        }
        return null;
    }

    private static String methodSignature(MethodDeclaration method) {
        StringBuilder builder = new StringBuilder();
        builder.append(method.getNameAsString()).append('(');
        for (int index = 0; index < method.getParameters().size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(method.getParameter(index).getType().asString());
        }
        builder.append(')');
        return builder.toString();
    }

    private static String unknownArguments(int count) {
        StringBuilder builder = new StringBuilder();
        builder.append('(');
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append('?');
        }
        builder.append(')');
        return builder.toString();
    }

    private static String methodName(String signature) {
        int index = signature.indexOf('(');
        if (index < 0) {
            return signature;
        }
        return signature.substring(0, index);
    }

    private static int parameterCount(String signature) {
        int start = signature.indexOf('(');
        int end = signature.lastIndexOf(')');
        if (start < 0 || end < start) {
            return 0;
        }
        String parameters = signature.substring(start + 1, end).trim();
        if (parameters.length() == 0) {
            return 0;
        }
        int count = 1;
        for (int index = 0; index < parameters.length(); index++) {
            if (parameters.charAt(index) == ',') {
                count++;
            }
        }
        return count;
    }

    private static String resolveTypeName(String typeName, ParsedSourceFile file) {
        if (typeName == null || typeName.trim().length() == 0) {
            return "";
        }
        String normalized = eraseType(typeName);
        if (isPrimitive(normalized)) {
            return normalized;
        }
        if (normalized.indexOf('.') >= 0) {
            return normalized;
        }
        for (ImportDeclaration importDeclaration : file.compilationUnit.getImports()) {
            if (!importDeclaration.isAsterisk()) {
                String imported = importDeclaration.getNameAsString();
                if (imported.endsWith("." + normalized)) {
                    return imported;
                }
            }
        }
        if (file.packageName().length() > 0) {
            return file.packageName() + "." + normalized;
        }
        return normalized;
    }

    private static String visibleVariableType(Node node, String name, ParsedSourceFile file) {
        MethodDeclaration method = enclosingMethod(node);
        if (method != null) {
            for (Parameter parameter : method.getParameters()) {
                if (parameter.getNameAsString().equals(name)) {
                    return parameter.getType().asString();
                }
            }
            int usageStart = file.sourceText.startOffset(node);
            VariableDeclarator best = null;
            int bestStart = -1;
            for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
                if (!variable.getNameAsString().equals(name)) {
                    continue;
                }
                int start = file.sourceText.startOffset(variable);
                if (start >= 0 && start < usageStart && start > bestStart) {
                    best = variable;
                    bestStart = start;
                }
            }
            if (best != null) {
                return best.getType().asString();
            }
        }
        ClassOrInterfaceDeclaration type = enclosingClass(node);
        if (type != null) {
            VariableDeclarator field = findField(type, name);
            if (field != null) {
                return field.getType().asString();
            }
        }
        return "";
    }

    private static boolean hasVisibleLocal(MethodDeclaration method, Node node, String name, ParsedSourceFile file) {
        if (method == null) {
            return false;
        }
        int usageStart = file.sourceText.startOffset(node);
        for (VariableDeclarator variable : method.findAll(VariableDeclarator.class)) {
            if (!variable.getNameAsString().equals(name)) {
                continue;
            }
            int start = file.sourceText.startOffset(variable);
            if (start >= 0 && start < usageStart) {
                return true;
            }
        }
        return false;
    }

    private static int nameStart(com.github.javaparser.ast.expr.SimpleName name, ParsedSourceFile file) {
        if (!name.getRange().isPresent()) {
            return -1;
        }
        return file.sourceText.offsetAt(name.getRange().get().begin);
    }

    private static int nameEnd(com.github.javaparser.ast.expr.SimpleName name, ParsedSourceFile file) {
        int start = nameStart(name, file);
        if (start < 0) {
            return -1;
        }
        return start + name.getIdentifier().length();
    }

    private static boolean isSimpleAssignTarget(Node node) {
        Optional<AssignExpr> assignment = node.findAncestor(AssignExpr.class);
        return assignment.isPresent() && assignment.get().getTarget() == node && assignment.get().getOperator() == AssignExpr.Operator.ASSIGN;
    }

    private static boolean isAssignTarget(Node node) {
        Optional<AssignExpr> assignment = node.findAncestor(AssignExpr.class);
        if (!assignment.isPresent()) {
            return false;
        }
        Node current = node;
        while (current.getParentNode().isPresent()) {
            current = current.getParentNode().get();
            if (current == assignment.get().getTarget()) {
                return true;
            }
            if (current == assignment.get()) {
                break;
            }
        }
        return assignment.get().getTarget() == node;
    }

    private static boolean isDeclarationUsage(SelectedMember member, ResolvedUsage usage) {
        return usage.file.path.equals(member.symbol.sourceFile) && usage.symbol.nameStart == member.symbol.nameStart && usage.symbol.nameEnd == member.symbol.nameEnd;
    }

    private static boolean isInsideSelectedMember(Node node, List<SelectedMember> selectedMembers) {
        for (SelectedMember member : selectedMembers) {
            Node current = node;
            while (true) {
                if (current == member.declaration) {
                    return true;
                }
                if (!current.getParentNode().isPresent()) {
                    break;
                }
                current = current.getParentNode().get();
            }
        }
        return false;
    }

    private static int insertionOffsetAfterFields(ParsedSourceFile file, ClassOrInterfaceDeclaration type) {
        int best = -1;
        for (BodyDeclaration<?> member : type.getMembers()) {
            if (member instanceof FieldDeclaration) {
                best = file.sourceText.endOffset(member);
            }
        }
        if (best >= 0) {
            return file.sourceText.extendDeletionEnd(best);
        }
        int start = file.sourceText.startOffset(type);
        int brace = file.sourceText.text.indexOf('{', start);
        return brace < 0 ? start : brace + 1;
    }

    private static String getterName(String fieldName) {
        return "get" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private static String setterName(String fieldName) {
        return "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
    }

    private static String removeFourSpaces(String line) {
        if (line.startsWith("    ")) {
            return line.substring(4);
        }
        return line;
    }

    private static String simpleName(String qualifiedName) {
        int index = qualifiedName.lastIndexOf('.');
        if (index < 0) {
            return qualifiedName;
        }
        return qualifiedName.substring(index + 1);
    }

    private static String eraseType(String typeName) {
        String trimmed = typeName.trim();
        int genericStart = trimmed.indexOf('<');
        if (genericStart >= 0) {
            trimmed = trimmed.substring(0, genericStart);
        }
        while (trimmed.endsWith("[]")) {
            trimmed = trimmed.substring(0, trimmed.length() - 2);
        }
        return trimmed;
    }

    private static boolean isPrimitive(String name) {
        return "boolean".equals(name) || "byte".equals(name) || "short".equals(name) || "int".equals(name) || "long".equals(name) || "float".equals(name) || "double".equals(name) || "char".equals(name) || "void".equals(name);
    }

    private static void validateJavaIdentifier(String value, String label, List<String> diagnostics) {
        if (!SourceVersion.isIdentifier(value) || SourceVersion.isKeyword(value)) {
            diagnostics.add("Invalid Java identifier for " + label + ": " + value);
        }
    }

    private static Comparator<SourceEdit> editComparator() {
        return new Comparator<SourceEdit>() {
            public int compare(SourceEdit left, SourceEdit right) {
                if (!left.file.equals(right.file)) {
                    return left.file.compareTo(right.file);
                }
                if (left.start != right.start) {
                    return left.start - right.start;
                }
                return left.end - right.end;
            }
        };
    }

    private static Comparator<SourceEdit> reverseEditComparator() {
        return new Comparator<SourceEdit>() {
            public int compare(SourceEdit left, SourceEdit right) {
                if (!left.file.equals(right.file)) {
                    return right.file.compareTo(left.file);
                }
                if (left.start != right.start) {
                    return right.start - left.start;
                }
                return right.end - left.end;
            }
        };
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read file: " + file, exception);
        }
    }

    private static void write(Path file, String source) {
        try {
            Files.write(file, source.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write file: " + file, exception);
        }
    }

    private static void jsonField(StringBuilder builder, String name, String value, boolean comma) {
        builder.append("  ").append(quote(name)).append(": ").append(value);
        if (comma) {
            builder.append(',');
        }
        builder.append('\n');
    }

    private static void appendDiagnostics(StringBuilder builder, List<String> diagnostics) {
        builder.append("  \"diagnostics\": [");
        for (int index = 0; index < diagnostics.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(quote(diagnostics.get(index)));
        }
        builder.append("],\n");
    }

    private static void appendMovedSymbols(StringBuilder builder, List<SelectedMember> selected) {
        builder.append("  \"movedSymbols\": [");
        for (int index = 0; index < selected.size(); index++) {
            if (index > 0) {
                builder.append(", ");
            }
            builder.append(quote(selected.get(index).symbol.id()));
        }
        builder.append("],\n");
    }

    private static void appendEdits(StringBuilder builder, SourceChangeSet changes, boolean commaAfter) {
        builder.append("  \"edits\": [\n");
        for (int index = 0; index < changes.edits.size(); index++) {
            SourceEdit edit = changes.edits.get(index);
            builder.append("    {\"file\": ").append(path(edit.file))
                    .append(", \"start\": ").append(edit.start)
                    .append(", \"end\": ").append(edit.end)
                    .append(", \"replacement\": ").append(quote(edit.replacement))
                    .append(", \"reason\": ").append(quote(edit.reason)).append("}");
            if (index + 1 < changes.edits.size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]");
        if (commaAfter) {
            builder.append(',');
        }
        builder.append('\n');
    }

    private static void appendCreatedFiles(StringBuilder builder, SourceChangeSet changes) {
        builder.append("  ,\"createdFiles\": [\n");
        int index = 0;
        for (Map.Entry<Path, String> entry : changes.createdFiles.entrySet()) {
            builder.append("    {\"file\": ").append(path(entry.getKey())).append(", \"characters\": ").append(entry.getValue().length()).append("}");
            if (index + 1 < changes.createdFiles.size()) {
                builder.append(',');
            }
            builder.append('\n');
            index++;
        }
        builder.append("  ]\n");
    }

    private static void appendUnresolved(StringBuilder builder, List<UnresolvedCandidate> unresolved) {
        builder.append("  \"unresolved\": [\n");
        for (int index = 0; index < unresolved.size(); index++) {
            UnresolvedCandidate candidate = unresolved.get(index);
            int start = candidate.file.sourceText.startOffset(candidate.node);
            builder.append("    {\"file\": ").append(path(candidate.file.path))
                    .append(", \"line\": ").append(candidate.file.sourceText.lineOfOffset(start))
                    .append(", \"column\": ").append(candidate.file.sourceText.columnOfOffset(start))
                    .append(", \"reason\": ").append(quote(candidate.reason)).append("}");
            if (index + 1 < unresolved.size()) {
                builder.append(',');
            }
            builder.append('\n');
        }
        builder.append("  ]\n");
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder builder = new StringBuilder();
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c == '"') {
                builder.append("\\\"");
            } else if (c == '\\') {
                builder.append("\\\\");
            } else if (c == '\n') {
                builder.append("\\n");
            } else if (c == '\r') {
                builder.append("\\r");
            } else if (c == '\t') {
                builder.append("\\t");
            } else {
                builder.append(c);
            }
        }
        builder.append('"');
        return builder.toString();
    }

    private static String path(Path path) {
        return path == null ? "null" : quote(path.toString());
    }
}
