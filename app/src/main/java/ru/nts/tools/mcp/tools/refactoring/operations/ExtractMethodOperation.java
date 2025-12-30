/*
 * Copyright 2025 Aristo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ru.nts.tools.mcp.tools.refactoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.VariableAnalysisResult;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.VariableInfoAST;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Операция извлечения метода.
 * Извлекает выделенный код в новый метод и заменяет его вызовом.
 */
public class ExtractMethodOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "extract_method";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required");
        }
        if (!params.has("methodName")) {
            throw new IllegalArgumentException("Parameter 'methodName' is required");
        }
        if (!params.has("startLine") && !params.has("codePattern")) {
            throw new IllegalArgumentException("Either 'startLine' or 'codePattern' is required");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String methodName = params.get("methodName").asText();

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        try {
            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            // Определяем диапазон извлечения
            int startLine, endLine;
            if (params.has("startLine")) {
                startLine = params.get("startLine").asInt();
                endLine = params.has("endLine") ? params.get("endLine").asInt() : startLine;
            } else {
                String pattern = params.get("codePattern").asText();
                int[] range = findCodeByPattern(lines, pattern, params);
                startLine = range[0];
                endLine = range[1];
            }

            // Извлекаем код
            List<String> extractedCode = new ArrayList<>();
            for (int i = startLine - 1; i < endLine && i < lines.size(); i++) {
                extractedCode.add(lines.get(i));
            }

            // Анализируем код с использованием AST
            ExtractionAnalysis analysis = analyzeExtractionWithAST(extractedCode, lines, startLine, path, langId, context);

            // Находим класс-контейнер и содержащий метод
            SymbolInfo containingClass = findContainingClass(path, startLine, context);
            if (containingClass == null && needsClass(langId)) {
                throw new RefactoringException("Cannot extract method: no containing class found");
            }

            // Проверяем, находится ли код в статическом контексте
            boolean isStaticContext = isInStaticContext(path, startLine, context);

            // Генерируем новый метод
            String accessModifier = params.has("accessModifier")
                    ? params.get("accessModifier").asText() : "private";
            String returnType = params.has("returnType")
                    ? params.get("returnType").asText() : analysis.inferredReturnType;

            String newMethod = generateMethod(
                    methodName, accessModifier, returnType,
                    analysis.parameters, extractedCode, langId, isStaticContext);

            // Генерируем вызов (без this. в статическом контексте)
            String methodCall = generateMethodCall(
                    methodName, analysis.parameters, analysis.hasReturn, langId, isStaticContext);

            // Начинаем транзакцию
            String instruction = params.has("instruction")
                    ? params.get("instruction").asText()
                    : "Extract method '" + methodName + "'";
            context.beginTransaction(instruction);

            try {
                context.backupFile(path);

                // Заменяем код на вызов
                String baseIndent = detectIndent(extractedCode.get(0));
                for (int i = endLine - 1; i >= startLine - 1 && i < lines.size(); i--) {
                    lines.remove(i);
                }
                lines.add(startLine - 1, baseIndent + methodCall);

                // Добавляем новый метод
                int insertLine = findMethodInsertPosition(containingClass, lines);
                List<String> methodLines = Arrays.asList(newMethod.split("\n"));
                lines.addAll(insertLine, methodLines);

                String newContent = String.join("\n", lines);
                FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
                context.getTreeManager().invalidateCache(path);

                // Вычисляем метаданные и регистрируем токен
                int lineCount = lines.size();
                long crc32c = LineAccessToken.computeRangeCrc(newContent);

                // Обновляем снапшот сессии для синхронизации с batch tools
                SessionContext.currentOrDefault().externalChanges()
                    .updateSnapshot(path, newContent, crc32c, StandardCharsets.UTF_8, lineCount);

                LineAccessToken token = LineAccessTracker.registerAccess(path, 1, lineCount, newContent, lineCount);

                String txId = context.commitTransaction();

                return RefactoringResult.builder()
                        .status(RefactoringResult.Status.SUCCESS)
                        .action("extract_method")
                        .summary(String.format("Extracted method '%s' with %d parameter(s)",
                                methodName, analysis.parameters.size()))
                        .addChange(new RefactoringResult.FileChange(
                                path, 2,
                                List.of(
                                        new RefactoringResult.ChangeDetail(
                                                startLine, 0,
                                                String.join("\n", extractedCode).trim(),
                                                methodCall),
                                        new RefactoringResult.ChangeDetail(
                                                insertLine + 1, 0, "", newMethod.trim())
                                ),
                                token.encode(), null, crc32c, lineCount))
                        .affectedFiles(1)
                        .totalChanges(2)
                        .transactionId(txId)
                        .build();

            } catch (Exception e) {
                context.rollbackTransaction();
                throw e;
            }

        } catch (IOException e) {
            throw new RefactoringException("Failed to extract method: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String methodName = params.get("methodName").asText();

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        try {
            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            int startLine, endLine;
            if (params.has("startLine")) {
                startLine = params.get("startLine").asInt();
                endLine = params.has("endLine") ? params.get("endLine").asInt() : startLine;
            } else {
                String pattern = params.get("codePattern").asText();
                int[] range = findCodeByPattern(lines, pattern, params);
                startLine = range[0];
                endLine = range[1];
            }

            List<String> extractedCode = new ArrayList<>();
            for (int i = startLine - 1; i < endLine && i < lines.size(); i++) {
                extractedCode.add(lines.get(i));
            }

            ExtractionAnalysis analysis = analyzeExtractionWithAST(extractedCode, lines, startLine, path, langId, context);

            // Проверяем статический контекст
            boolean isStaticContext = isInStaticContext(path, startLine, context);

            String accessModifier = params.has("accessModifier")
                    ? params.get("accessModifier").asText() : "private";
            String returnType = params.has("returnType")
                    ? params.get("returnType").asText() : analysis.inferredReturnType;

            String newMethod = generateMethod(
                    methodName, accessModifier, returnType,
                    analysis.parameters, extractedCode, langId, isStaticContext);

            String methodCall = generateMethodCall(
                    methodName, analysis.parameters, analysis.hasReturn, langId, isStaticContext);

            StringBuilder diff = new StringBuilder();
            diff.append("=== Extracted Code ===\n");
            for (String line : extractedCode) {
                diff.append("-").append(line).append("\n");
            }
            diff.append("\n=== Method Call ===\n");
            diff.append("+").append(methodCall).append("\n");
            diff.append("\n=== New Method ===\n");
            for (String line : newMethod.split("\n")) {
                diff.append("+").append(line).append("\n");
            }

            return RefactoringResult.preview("extract_method", List.of(
                    new RefactoringResult.FileChange(
                            path, 1,
                            List.of(new RefactoringResult.ChangeDetail(
                                    startLine, 0,
                                    String.join("\n", extractedCode).trim(),
                                    methodCall + "\n...\n" + newMethod.trim())),
                            null, diff.toString())
            ));

        } catch (IOException e) {
            throw new RefactoringException("Failed to preview: " + e.getMessage(), e);
        }
    }

    /**
     * Находит код по паттерну.
     */
    private int[] findCodeByPattern(List<String> lines, String pattern, JsonNode params)
            throws RefactoringException {
        Pattern regex = Pattern.compile(pattern);
        int includeLines = params.has("includeLines") ? params.get("includeLines").asInt() : 1;

        for (int i = 0; i < lines.size(); i++) {
            if (regex.matcher(lines.get(i)).find()) {
                return new int[]{i + 1, Math.min(i + includeLines, lines.size())};
            }
        }

        throw new RefactoringException("Pattern not found: " + pattern);
    }

    /**
     * Анализирует извлекаемый код с полным контекстом AST.
     * Использует tree-sitter для точного определения переменных.
     */
    private ExtractionAnalysis analyzeExtractionWithAST(List<String> code, List<String> allLines,
                                                         int startLine, Path path, String langId,
                                                         RefactoringContext context) {
        return analyzeExtractionWithContext(code, allLines, startLine, path, null, langId, context);
    }

    /**
     * Основной метод анализа с опциональной поддержкой AST.
     */
    private ExtractionAnalysis analyzeExtractionWithContext(List<String> code, List<String> allLines,
                                                             int startLine, Path path, String content,
                                                             String langId, RefactoringContext context) {
        Set<String> usedVariables = new HashSet<>();
        Set<String> declaredVariables = new HashSet<>();
        Map<String, String> variableTypes = new HashMap<>();
        boolean hasReturn = false;
        String inferredReturnType = "void";

        // Пробуем использовать AST для анализа
        TSNode root = null;
        String fileContent = content;

        if (path != null && context != null) {
            try {
                TreeSitterManager.ParseResult parseResult = context.getParseResult(path);
                root = parseResult.tree().getRootNode();
                fileContent = parseResult.content();
            } catch (Exception e) {
                // Fallback на regex если AST недоступен
            }
        }

        int endLine = startLine + code.size() - 1;

        if (root != null && fileContent != null) {
            // Используем AST для анализа переменных
            VariableAnalysisResult analysisResult = SymbolExtractorUtils.analyzeVariablesInRange(
                    root, fileContent, startLine - 1, endLine - 1, langId);

            // Извлекаем объявленные переменные
            for (VariableInfoAST varInfo : analysisResult.declaredVariables()) {
                declaredVariables.add(varInfo.name());
                variableTypes.put(varInfo.name(), varInfo.type());
            }

            // Извлекаем использованные переменные
            usedVariables.addAll(analysisResult.usedVariables());

            // Извлекаем переменные из внешнего scope через AST
            Map<String, String> outerVariables = SymbolExtractorUtils.extractOuterScopeVariables(
                    root, fileContent, startLine - 1, langId);

            // Определяем параметры (используемые, но не объявленные внутри)
            Set<String> parameters = new HashSet<>(usedVariables);
            parameters.removeAll(declaredVariables);
            parameters.retainAll(outerVariables.keySet());

            // Проверяем наличие return
            hasReturn = checkForReturn(code);
            if (hasReturn) {
                inferredReturnType = inferReturnTypeFromCode(code, variableTypes, outerVariables);
            }

            // Создаём список параметров с типами из AST
            List<ParameterInfo> parameterInfos = parameters.stream()
                    .sorted()
                    .map(name -> {
                        String type = outerVariables.getOrDefault(name, variableTypes.getOrDefault(name, "Object"));
                        return new ParameterInfo(name, type);
                    })
                    .toList();

            return new ExtractionAnalysis(parameterInfos, hasReturn, inferredReturnType);
        }

        // Fallback на regex анализ
        return analyzeExtractionWithRegex(code, allLines, startLine);
    }

    /**
     * Fallback regex-based анализ переменных.
     */
    private ExtractionAnalysis analyzeExtractionWithRegex(List<String> code, List<String> allLines, int startLine) {
        Set<String> usedVariables = new HashSet<>();
        Set<String> declaredVariables = new HashSet<>();
        Map<String, String> variableTypes = new HashMap<>();
        boolean hasReturn = false;
        String inferredReturnType = "void";

        String typePattern = "(var|int|long|short|byte|char|String|boolean|double|float|" +
                "Object|List|Map|Set|Array|ArrayList|HashMap|HashSet|TreeMap|TreeSet|" +
                "Integer|Long|Double|Float|Boolean|Character|Short|Byte|" +
                "[A-Z][a-zA-Z0-9_<>\\[\\],\\s]*)";

        Pattern varDecl = Pattern.compile(typePattern + "\\s+([a-z_][a-zA-Z0-9_]*)\\s*[=;,)]");
        Pattern enhancedFor = Pattern.compile("for\\s*\\(\\s*" + typePattern + "\\s+([a-z_][a-zA-Z0-9_]*)\\s*:");
        Pattern varUsage = Pattern.compile("\\b([a-z_][a-zA-Z0-9_]*)\\b");
        Pattern returnPattern = Pattern.compile("\\breturn\\s+(.+?)\\s*;");
        Pattern methodCall = Pattern.compile("\\b([a-z_][a-zA-Z0-9_]*)\\s*\\(");

        Set<String> javaKeywords = Set.of(
                "this", "super", "true", "false", "null", "new", "return", "if", "else",
                "for", "while", "do", "switch", "case", "default", "break", "continue",
                "try", "catch", "finally", "throw", "throws", "class", "interface", "enum",
                "extends", "implements", "import", "package", "public", "private", "protected",
                "static", "final", "abstract", "synchronized", "volatile", "transient",
                "native", "strictfp", "instanceof", "void", "int", "long", "short", "byte",
                "char", "boolean", "double", "float", "var"
        );

        Set<String> methodCalls = new HashSet<>();

        for (String line : code) {
            Matcher methodMatcher = methodCall.matcher(line);
            while (methodMatcher.find()) {
                methodCalls.add(methodMatcher.group(1));
            }

            Matcher declMatcher = varDecl.matcher(line);
            while (declMatcher.find()) {
                String type = declMatcher.group(1).trim();
                String name = declMatcher.group(2);
                declaredVariables.add(name);
                variableTypes.put(name, normalizeType(type));
            }

            Matcher forMatcher = enhancedFor.matcher(line);
            while (forMatcher.find()) {
                String type = forMatcher.group(1).trim();
                String name = forMatcher.group(2);
                declaredVariables.add(name);
                variableTypes.put(name, normalizeType(type));
            }

            Matcher usageMatcher = varUsage.matcher(line);
            while (usageMatcher.find()) {
                String name = usageMatcher.group(1);
                if (!javaKeywords.contains(name)) {
                    usedVariables.add(name);
                }
            }

            Matcher returnMatcher = returnPattern.matcher(line);
            if (returnMatcher.find()) {
                hasReturn = true;
                String returnExpr = returnMatcher.group(1).trim();
                inferredReturnType = inferReturnType(returnExpr, variableTypes);
            }
        }

        usedVariables.removeAll(methodCalls);

        Set<String> parameters = new HashSet<>(usedVariables);
        parameters.removeAll(declaredVariables);
        parameters.removeAll(javaKeywords);

        Map<String, String> outerVariables = findOuterScopeVariablesRegex(allLines, startLine, code.size());
        parameters.retainAll(outerVariables.keySet());

        List<ParameterInfo> parameterInfos = parameters.stream()
                .sorted()
                .map(name -> {
                    String type = outerVariables.getOrDefault(name, "Object");
                    return new ParameterInfo(name, type);
                })
                .toList();

        return new ExtractionAnalysis(parameterInfos, hasReturn, inferredReturnType);
    }

    /**
     * Проверяет наличие return в коде.
     */
    private boolean checkForReturn(List<String> code) {
        Pattern returnPattern = Pattern.compile("\\breturn\\s+.+?\\s*;");
        for (String line : code) {
            if (returnPattern.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Выводит тип возврата из кода.
     */
    private String inferReturnTypeFromCode(List<String> code, Map<String, String> variableTypes,
                                            Map<String, String> outerVariables) {
        Pattern returnPattern = Pattern.compile("\\breturn\\s+(.+?)\\s*;");
        for (String line : code) {
            Matcher matcher = returnPattern.matcher(line);
            if (matcher.find()) {
                String expr = matcher.group(1).trim();
                // Сначала проверяем в locальных переменных, затем во внешних
                if (variableTypes.containsKey(expr)) {
                    return variableTypes.get(expr);
                }
                if (outerVariables.containsKey(expr)) {
                    return outerVariables.get(expr);
                }
                return inferReturnType(expr, variableTypes);
            }
        }
        return "void";
    }

    /**
     * Нормализует тип (убирает лишние пробелы, упрощает generics для вывода).
     */
    private String normalizeType(String type) {
        if (type == null) return "Object";
        type = type.trim();

        // Упрощаем var до Object (в реальном коде нужен анализ правой части)
        if (type.equals("var")) return "Object";

        // Убираем множественные пробелы
        type = type.replaceAll("\\s+", " ");

        return type;
    }

    /**
     * Пытается определить тип возвращаемого значения по выражению.
     */
    private String inferReturnType(String expr, Map<String, String> knownTypes) {
        if (expr == null || expr.isEmpty()) return "void";

        // Литералы
        if (expr.matches("\\d+L?")) return "long";
        if (expr.matches("\\d+\\.\\d+[fF]?")) return expr.endsWith("f") || expr.endsWith("F") ? "float" : "double";
        if (expr.matches("\\d+")) return "int";
        if (expr.equals("true") || expr.equals("false")) return "boolean";
        if (expr.startsWith("\"")) return "String";
        if (expr.startsWith("'")) return "char";

        // Известная переменная
        if (knownTypes.containsKey(expr)) {
            return knownTypes.get(expr);
        }

        // new Type(...)
        Matcher newMatcher = Pattern.compile("new\\s+([A-Z][a-zA-Z0-9_<>]*)").matcher(expr);
        if (newMatcher.find()) {
            return newMatcher.group(1);
        }

        return "Object";
    }

    /**
     * Находит переменные доступные во внешнем scope (fallback regex версия).
     *
     * @param lines все строки файла
     * @param extractStart начало извлекаемого блока (1-based)
     * @param extractLength длина извлекаемого блока
     * @return карта: имя переменной -> тип
     */
    private Map<String, String> findOuterScopeVariablesRegex(List<String> lines, int extractStart, int extractLength) {
        Map<String, String> vars = new LinkedHashMap<>();

        String typePattern = "(final\\s+)?(var|int|long|short|byte|char|String|boolean|double|float|" +
                "Object|List|Map|Set|[A-Z][a-zA-Z0-9_<>\\[\\],\\s]*)";

        // Паттерн для объявления локальных переменных
        Pattern varDecl = Pattern.compile(typePattern + "\\s+([a-z_][a-zA-Z0-9_]*)\\s*[=;,)]");

        // Паттерн для параметров метода: (Type name или Type name, или Type name)
        Pattern methodParam = Pattern.compile(typePattern + "\\s+([a-z_][a-zA-Z0-9_]*)\\s*[,)]");

        // Паттерн для enhanced for
        Pattern enhancedFor = Pattern.compile("for\\s*\\(\\s*(final\\s+)?" + typePattern + "\\s+([a-z_][a-zA-Z0-9_]*)\\s*:");

        // Анализируем строки ДО извлекаемого блока
        for (int i = 0; i < extractStart - 1 && i < lines.size(); i++) {
            String line = lines.get(i);

            // Локальные переменные
            Matcher declMatcher = varDecl.matcher(line);
            while (declMatcher.find()) {
                String type = declMatcher.group(2).trim();
                String name = declMatcher.group(3);
                vars.put(name, normalizeType(type));
            }

            // Параметры метода (если строка содержит сигнатуру метода)
            if (line.contains("(") && (line.contains("void") || line.contains("public") ||
                    line.contains("private") || line.contains("protected"))) {
                Matcher paramMatcher = methodParam.matcher(line);
                while (paramMatcher.find()) {
                    String type = paramMatcher.group(2).trim();
                    String name = paramMatcher.group(3);
                    vars.put(name, normalizeType(type));
                }
            }

            // Enhanced for переменные
            Matcher forMatcher = enhancedFor.matcher(line);
            while (forMatcher.find()) {
                String type = forMatcher.group(2).trim();
                String name = forMatcher.group(3);
                vars.put(name, normalizeType(type));
            }
        }

        return vars;
    }

    /**
     * Находит класс, содержащий указанную строку.
     */
    private SymbolInfo findContainingClass(Path path, int line, RefactoringContext context) {
        try {
            List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
            return symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.CLASS)
                    .filter(s -> s.location().startLine() <= line && s.location().endLine() >= line)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Проверяет, находится ли указанная строка в статическом контексте.
     * Использует AST для анализа модификаторов содержащего метода.
     */
    private boolean isInStaticContext(Path path, int line, RefactoringContext context) {
        try {
            // Используем getParseResult для поддержки виртуального контента в batch
            TreeSitterManager.ParseResult parseResult = context.getParseResult(path);
            TSTree tree = parseResult.tree();
            String content = parseResult.content();

            // Находим метод, содержащий указанную строку
            TSNode root = tree.getRootNode();
            TSNode containingMethod = findContainingMethodNode(root, line - 1, content); // 0-based

            if (containingMethod != null) {
                // Ищем модификаторы метода
                TSNode modifiers = findChildByType(containingMethod, "modifiers");
                if (modifiers != null) {
                    String modifiersText = getNodeText(modifiers, content);
                    return modifiersText.contains("static");
                }

                // Для методов без явных модификаторов проверяем сигнатуру
                String methodText = getNodeText(containingMethod, content);
                // Простая проверка: есть ли "static" перед именем метода
                int braceIdx = methodText.indexOf('{');
                if (braceIdx > 0) {
                    String signature = methodText.substring(0, braceIdx);
                    return signature.contains(" static ");
                }
            }
        } catch (Exception e) {
            // В случае ошибки считаем, что контекст не статический
        }
        return false;
    }

    /**
     * Находит узел метода, содержащий указанную строку.
     */
    private TSNode findContainingMethodNode(TSNode node, int row, String content) {
        String nodeType = node.getType();

        // Проверяем, является ли это методом
        if (nodeType.equals("method_declaration") || nodeType.equals("constructor_declaration") ||
                nodeType.equals("function_declaration") || nodeType.equals("function_definition")) {
            int startRow = node.getStartPoint().getRow();
            int endRow = node.getEndPoint().getRow();
            if (startRow <= row && endRow >= row) {
                return node;
            }
        }

        // Рекурсивно ищем в дочерних узлах
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                TSNode found = findContainingMethodNode(child, row, content);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Находит дочерний узел по типу.
     */
    private TSNode findChildByType(TSNode parent, String type) {
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = parent.getChild(i);
            if (child != null && !child.isNull() && child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Извлекает текст узла (корректная обработка UTF-8).
     */
    private String getNodeText(TSNode node, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start >= 0 && end <= contentBytes.length && start < end) {
            return new String(contentBytes, start, end - start, StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * Генерирует новый метод.
     * Если isStaticContext=true, добавляет модификатор static.
     */
    private String generateMethod(String name, String accessModifier, String returnType,
                                   List<ParameterInfo> parameters, List<String> body,
                                   String langId, boolean isStaticContext) {
        StringBuilder sb = new StringBuilder();
        String baseIndent = detectIndent(body.get(0));
        String methodIndent = baseIndent.isEmpty() ? "    " : baseIndent;

        if (langId.equals("java")) {
            sb.append("\n");
            sb.append(methodIndent).append(accessModifier);
            if (isStaticContext) {
                sb.append(" static");
            }
            sb.append(" ").append(returnType)
                    .append(" ").append(name).append("(");
            sb.append(parameters.stream()
                    .map(p -> p.type + " " + p.name)
                    .collect(Collectors.joining(", ")));
            sb.append(") {\n");
            for (String line : body) {
                sb.append("    ").append(line).append("\n");
            }
            sb.append(methodIndent).append("}\n");

        } else if (langId.equals("python")) {
            sb.append("\n");
            if (isStaticContext) {
                sb.append(methodIndent).append("@staticmethod\n");
                sb.append(methodIndent).append("def ").append(name).append("(");
            } else {
                sb.append(methodIndent).append("def ").append(name).append("(self");
                if (!parameters.isEmpty()) {
                    sb.append(", ");
                }
            }
            sb.append(parameters.stream()
                    .map(p -> p.name)
                    .collect(Collectors.joining(", ")));
            sb.append("):\n");
            for (String line : body) {
                sb.append("    ").append(line).append("\n");
            }

        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            sb.append("\n");
            if (isStaticContext) {
                sb.append(methodIndent).append("static ");
            } else {
                sb.append(methodIndent);
            }
            sb.append(name).append("(");
            sb.append(parameters.stream()
                    .map(p -> p.name)
                    .collect(Collectors.joining(", ")));
            sb.append(") {\n");
            for (String line : body) {
                sb.append("    ").append(line).append("\n");
            }
            sb.append(methodIndent).append("}\n");
        }

        return sb.toString();
    }

    /**
     * Генерирует вызов метода.
     * В статическом контексте не использует this./self.
     */
    private String generateMethodCall(String name, List<ParameterInfo> parameters,
                                       boolean hasReturn, String langId, boolean isStaticContext) {
        StringBuilder sb = new StringBuilder();

        if (hasReturn) {
            sb.append("return ");
        }

        // В статическом контексте не используем this./self.
        if (!isStaticContext) {
            if (langId.equals("python")) {
                sb.append("self.");
            } else if (langId.equals("java") || langId.equals("kotlin")) {
                sb.append("this.");
            }
        }

        sb.append(name).append("(");
        sb.append(parameters.stream()
                .map(p -> p.name)
                .collect(Collectors.joining(", ")));
        sb.append(")");

        if (!langId.equals("python")) {
            sb.append(";");
        }

        return sb.toString();
    }

    /**
     * Находит позицию для вставки нового метода.
     */
    private int findMethodInsertPosition(SymbolInfo containingClass, List<String> lines) {
        if (containingClass != null) {
            return containingClass.location().endLine() - 1;
        }
        return lines.size();
    }

    private boolean needsClass(String langId) {
        return langId.equals("java") || langId.equals("kotlin");
    }

    private String detectIndent(String line) {
        StringBuilder indent = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private record ParameterInfo(String name, String type) {}

    private record ExtractionAnalysis(
            List<ParameterInfo> parameters,
            boolean hasReturn,
            String inferredReturnType
    ) {}
}
