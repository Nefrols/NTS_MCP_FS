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
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
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

            // Анализируем код
            ExtractionAnalysis analysis = analyzeExtraction(extractedCode, lines, startLine, context);

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
                                null, null))
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

            ExtractionAnalysis analysis = analyzeExtraction(extractedCode, lines, startLine, context);

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
     * Анализирует извлекаемый код для определения параметров и типа возврата.
     */
    private ExtractionAnalysis analyzeExtraction(List<String> code, List<String> allLines,
                                                  int startLine, RefactoringContext context) {
        Set<String> usedVariables = new HashSet<>();
        Set<String> declaredVariables = new HashSet<>();
        boolean hasReturn = false;
        String inferredReturnType = "void";

        // Простой анализ через regex
        Pattern varUsage = Pattern.compile("\\b([a-z][a-zA-Z0-9_]*)\\b");
        Pattern varDecl = Pattern.compile("\\b(var|int|long|String|boolean|double|float|Object|List|Map|Set)\\s+([a-z][a-zA-Z0-9_]*)\\b");
        Pattern returnPattern = Pattern.compile("\\breturn\\s+");

        for (String line : code) {
            // Находим объявления
            Matcher declMatcher = varDecl.matcher(line);
            while (declMatcher.find()) {
                declaredVariables.add(declMatcher.group(2));
            }

            // Находим использования
            Matcher usageMatcher = varUsage.matcher(line);
            while (usageMatcher.find()) {
                usedVariables.add(usageMatcher.group(1));
            }

            // Проверяем return
            if (returnPattern.matcher(line).find()) {
                hasReturn = true;
            }
        }

        // Определяем параметры (переменные используемые, но не объявленные внутри)
        Set<String> parameters = new HashSet<>(usedVariables);
        parameters.removeAll(declaredVariables);
        parameters.removeAll(Set.of("this", "true", "false", "null", "new", "return"));

        // Проверяем переменные объявленные до извлекаемого кода
        Set<String> availableVars = findDeclaredVariablesBefore(allLines, startLine);
        parameters.retainAll(availableVars);

        List<ParameterInfo> parameterInfos = parameters.stream()
                .map(name -> new ParameterInfo(name, "Object")) // Упрощённый тип
                .toList();

        return new ExtractionAnalysis(parameterInfos, hasReturn, inferredReturnType);
    }

    /**
     * Находит переменные объявленные до указанной строки.
     */
    private Set<String> findDeclaredVariablesBefore(List<String> lines, int beforeLine) {
        Set<String> vars = new HashSet<>();
        Pattern varDecl = Pattern.compile("\\b(var|int|long|String|boolean|double|float|Object|List|Map|Set)\\s+([a-z][a-zA-Z0-9_]*)\\b");

        for (int i = 0; i < beforeLine - 1 && i < lines.size(); i++) {
            Matcher matcher = varDecl.matcher(lines.get(i));
            while (matcher.find()) {
                vars.add(matcher.group(2));
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
