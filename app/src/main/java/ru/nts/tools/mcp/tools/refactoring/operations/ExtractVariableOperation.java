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
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.TaskContext;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils;
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

/**
 * Операция извлечения выражения в переменную (extract variable).
 * Концептуально обратная операция к inline.
 *
 * Поддерживаемые языки: Java, Kotlin, JS/TS, Python, Go, Rust, C/C++.
 */
public class ExtractVariableOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "extract_variable";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required");
        }
        if (!params.has("variableName")) {
            throw new IllegalArgumentException("Parameter 'variableName' is required for extract_variable");
        }
        if (!params.has("startLine")) {
            throw new IllegalArgumentException("Parameter 'startLine' is required for extract_variable");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String variableName = params.get("variableName").asText();
        boolean replaceAll = !params.has("replaceAll") || params.get("replaceAll").asBoolean(true);

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        try {
            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            // Определяем диапазон выражения
            int startLine = params.get("startLine").asInt();
            int endLine = params.has("endLine") ? params.get("endLine").asInt() : startLine;
            int startColumn = params.has("startColumn") ? params.get("startColumn").asInt() : -1;
            int endColumn = params.has("endColumn") ? params.get("endColumn").asInt() : -1;

            // Извлекаем выражение
            String expression = extractExpression(lines, startLine, endLine, startColumn, endColumn);
            if (expression == null || expression.isBlank()) {
                throw new RefactoringException("Cannot extract variable: empty expression at lines " +
                        startLine + "-" + endLine);
            }

            // Определяем тип переменной
            String variableType = params.has("variableType")
                    ? params.get("variableType").asText() : "auto";
            if ("auto".equals(variableType)) {
                variableType = inferExpressionType(expression, path, startLine, langId, context);
            }

            // Находим содержащий scope (метод/функцию)
            int scopeStartLine = findContainingScopeStart(path, startLine, context);
            int scopeEndLine = findContainingScopeEnd(path, startLine, context);

            // Находим все вхождения выражения в scope (если replaceAll)
            List<ExpressionOccurrence> occurrences = new ArrayList<>();
            occurrences.add(new ExpressionOccurrence(startLine, endLine, startColumn, endColumn));

            if (replaceAll) {
                List<ExpressionOccurrence> additionalOccurrences = findExpressionOccurrences(
                        lines, expression, scopeStartLine, scopeEndLine, startLine, endLine);
                occurrences.addAll(additionalOccurrences);
                occurrences.sort(Comparator.comparingInt(ExpressionOccurrence::startLine)
                        .thenComparingInt(ExpressionOccurrence::startColumn));
            }

            // Определяем точку вставки объявления (перед первым использованием)
            int insertLine = occurrences.get(0).startLine();

            // Определяем отступ
            String baseIndent = detectIndent(lines.get(insertLine - 1));

            // Генерируем объявление переменной
            String declaration = generateDeclaration(
                    variableName, variableType, expression, langId, baseIndent);

            // Начинаем транзакцию
            String instruction = params.has("instruction")
                    ? params.get("instruction").asText()
                    : "Extract variable '" + variableName + "'";
            context.beginTransaction(instruction);

            try {
                context.backupFile(path);

                List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

                // Заменяем вхождения снизу вверх
                for (int i = occurrences.size() - 1; i >= 0; i--) {
                    ExpressionOccurrence occ = occurrences.get(i);
                    String before = getOccurrenceText(lines, occ);
                    replaceOccurrence(lines, occ, variableName);
                    String after = getOccurrenceText(lines,
                            new ExpressionOccurrence(occ.startLine(), occ.startLine(), -1, -1));
                    details.add(new RefactoringResult.ChangeDetail(
                            occ.startLine(), Math.max(occ.startColumn(), 0),
                            before.trim(), after.trim()));
                }

                // Вставляем объявление переменной перед первым использованием
                lines.add(insertLine - 1, declaration);
                details.add(0, new RefactoringResult.ChangeDetail(
                        insertLine, 0, "", declaration.trim()));

                String newContent = String.join("\n", lines);
                FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
                context.getTreeManager().invalidateCache(path);

                // Вычисляем метаданные и регистрируем токен
                int lineCount = lines.size();
                long crc32c = LineAccessToken.computeRangeCrc(newContent);

                TaskContext.currentOrDefault().externalChanges()
                        .updateSnapshot(path, newContent, crc32c, StandardCharsets.UTF_8, lineCount);

                LineAccessToken token = LineAccessTracker.registerAccess(
                        path, 1, lineCount, newContent, lineCount, crc32c);

                String txId = context.commitTransaction();

                return RefactoringResult.builder()
                        .status(RefactoringResult.Status.SUCCESS)
                        .action("extract_variable")
                        .summary(String.format("Extracted variable '%s' (%s), replaced %d occurrence(s)",
                                variableName, variableType, occurrences.size()))
                        .addChange(new RefactoringResult.FileChange(
                                path, occurrences.size() + 1,
                                details,
                                token.encode(), null, crc32c, lineCount))
                        .affectedFiles(1)
                        .totalChanges(occurrences.size() + 1)
                        .transactionId(txId)
                        .build();

            } catch (Exception e) {
                context.rollbackTransaction();
                throw e;
            }

        } catch (IOException e) {
            throw new RefactoringException("Failed to extract variable: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String variableName = params.get("variableName").asText();
        boolean replaceAll = !params.has("replaceAll") || params.get("replaceAll").asBoolean(true);

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        try {
            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            int startLine = params.get("startLine").asInt();
            int endLine = params.has("endLine") ? params.get("endLine").asInt() : startLine;
            int startColumn = params.has("startColumn") ? params.get("startColumn").asInt() : -1;
            int endColumn = params.has("endColumn") ? params.get("endColumn").asInt() : -1;

            String expression = extractExpression(lines, startLine, endLine, startColumn, endColumn);
            if (expression == null || expression.isBlank()) {
                throw new RefactoringException("Cannot extract variable: empty expression");
            }

            String variableType = params.has("variableType")
                    ? params.get("variableType").asText() : "auto";
            if ("auto".equals(variableType)) {
                variableType = inferExpressionType(expression, path, startLine, langId, context);
            }

            int scopeStartLine = findContainingScopeStart(path, startLine, context);
            int scopeEndLine = findContainingScopeEnd(path, startLine, context);

            List<ExpressionOccurrence> occurrences = new ArrayList<>();
            occurrences.add(new ExpressionOccurrence(startLine, endLine, startColumn, endColumn));

            if (replaceAll) {
                List<ExpressionOccurrence> additional = findExpressionOccurrences(
                        lines, expression, scopeStartLine, scopeEndLine, startLine, endLine);
                occurrences.addAll(additional);
                occurrences.sort(Comparator.comparingInt(ExpressionOccurrence::startLine));
            }

            String baseIndent = detectIndent(lines.get(occurrences.get(0).startLine() - 1));
            String declaration = generateDeclaration(variableName, variableType, expression, langId, baseIndent);

            StringBuilder diff = new StringBuilder();
            diff.append("=== Extract Variable Preview ===\n");
            diff.append("Variable: ").append(variableType).append(" ").append(variableName).append("\n");
            diff.append("Expression: ").append(expression).append("\n");
            diff.append("Occurrences: ").append(occurrences.size()).append("\n\n");

            diff.append("+").append(declaration).append("\n");
            for (ExpressionOccurrence occ : occurrences) {
                String lineText = lines.get(occ.startLine() - 1);
                diff.append("@@ line ").append(occ.startLine()).append(" @@\n");
                diff.append("-").append(lineText).append("\n");
                diff.append("+").append(lineText.replace(expression, variableName)).append("\n");
            }

            return RefactoringResult.preview("extract_variable", List.of(
                    new RefactoringResult.FileChange(
                            path, occurrences.size(),
                            List.of(new RefactoringResult.ChangeDetail(
                                    startLine, 0,
                                    expression,
                                    variableType + " " + variableName + " = " + expression)),
                            null, diff.toString())
            ));

        } catch (IOException e) {
            throw new RefactoringException("Failed to preview: " + e.getMessage(), e);
        }
    }

    /**
     * Извлекает выражение из указанного диапазона строк и колонок.
     */
    private String extractExpression(List<String> lines, int startLine, int endLine,
                                      int startColumn, int endColumn) {
        if (startLine < 1 || startLine > lines.size()) return null;

        if (startLine == endLine) {
            String line = lines.get(startLine - 1);
            if (startColumn > 0 && endColumn > 0) {
                // Точные колонки
                int sc = Math.min(startColumn - 1, line.length());
                int ec = Math.min(endColumn, line.length());
                return line.substring(sc, ec).trim();
            }
            // Вся строка (без отступа и финального ;)
            String trimmed = line.trim();
            if (trimmed.endsWith(";")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
            return trimmed;
        }

        // Многострочное выражение
        StringBuilder sb = new StringBuilder();
        for (int i = startLine - 1; i < endLine && i < lines.size(); i++) {
            if (i > startLine - 1) sb.append("\n");
            String line = lines.get(i);
            if (i == startLine - 1 && startColumn > 0) {
                sb.append(line.substring(Math.min(startColumn - 1, line.length())));
            } else if (i == endLine - 1 && endColumn > 0) {
                sb.append(line, 0, Math.min(endColumn, line.length()));
            } else {
                sb.append(line);
            }
        }
        String result = sb.toString().trim();
        if (result.endsWith(";")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    /**
     * Выводит тип выражения через AST-анализ и эвристики.
     */
    private String inferExpressionType(String expression, Path path, int line,
                                        String langId, RefactoringContext context) {
        // Литералы
        if (expression.matches("\\d+L")) return isJavaLike(langId) ? "long" : inferDefaultIntType(langId);
        if (expression.matches("\\d+\\.\\d+[fF]")) return isJavaLike(langId) ? "float" : inferDefaultFloatType(langId);
        if (expression.matches("\\d+\\.\\d+[dD]?")) return isJavaLike(langId) ? "double" : inferDefaultFloatType(langId);
        if (expression.matches("\\d+")) return isJavaLike(langId) ? "int" : inferDefaultIntType(langId);
        if (expression.equals("true") || expression.equals("false")) {
            return isJavaLike(langId) ? "boolean" : inferDefaultBoolType(langId);
        }
        if (expression.startsWith("\"") || expression.startsWith("'")) {
            return inferStringType(langId);
        }

        // new Type(...)
        Matcher newMatcher = Pattern.compile("new\\s+([A-Z][a-zA-Z0-9_<>]*)").matcher(expression);
        if (newMatcher.find()) {
            return newMatcher.group(1);
        }

        // Вызов метода — пытаемся определить через AST
        try {
            TreeSitterManager.ParseResult parseResult = context.getParseResult(path);
            TSNode root = parseResult.tree().getRootNode();
            String content = parseResult.content();

            // Ищем известные переменные в scope
            Map<String, String> outerVars = SymbolExtractorUtils.extractOuterScopeVariables(
                    root, content, line - 1, langId);
            if (outerVars.containsKey(expression)) {
                return outerVars.get(expression);
            }
        } catch (Exception ignored) {
            // Fallback на var/auto
        }

        // Используем var/auto/val в зависимости от языка
        return inferVarKeyword(langId);
    }

    /**
     * Находит все вхождения выражения в scope (кроме исходного).
     */
    private List<ExpressionOccurrence> findExpressionOccurrences(
            List<String> lines, String expression, int scopeStart, int scopeEnd,
            int skipStartLine, int skipEndLine) {

        List<ExpressionOccurrence> occurrences = new ArrayList<>();
        String escapedExpr = Pattern.quote(expression);

        for (int i = Math.max(scopeStart - 1, 0); i < Math.min(scopeEnd, lines.size()); i++) {
            int lineNum = i + 1;
            // Пропускаем исходное вхождение
            if (lineNum >= skipStartLine && lineNum <= skipEndLine) continue;

            String line = lines.get(i);
            Matcher matcher = Pattern.compile(escapedExpr).matcher(line);
            while (matcher.find()) {
                occurrences.add(new ExpressionOccurrence(
                        lineNum, lineNum,
                        matcher.start() + 1, matcher.end()));
            }
        }

        return occurrences;
    }

    /**
     * Находит начальную строку содержащего scope (метод/функция).
     */
    private int findContainingScopeStart(Path path, int line, RefactoringContext context) {
        try {
            List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
            return symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.FUNCTION)
                    .filter(s -> s.location().startLine() <= line && s.location().endLine() >= line)
                    .mapToInt(s -> s.location().startLine())
                    .findFirst()
                    .orElse(1);
        } catch (Exception e) {
            return 1;
        }
    }

    /**
     * Находит конечную строку содержащего scope (метод/функция).
     */
    private int findContainingScopeEnd(Path path, int line, RefactoringContext context) {
        try {
            String content = Files.readString(path);
            int totalLines = content.split("\n", -1).length;

            List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
            return symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.FUNCTION)
                    .filter(s -> s.location().startLine() <= line && s.location().endLine() >= line)
                    .mapToInt(s -> s.location().endLine())
                    .findFirst()
                    .orElse(totalLines);
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Генерирует объявление переменной в зависимости от языка.
     */
    private String generateDeclaration(String name, String type, String expression,
                                        String langId, String indent) {
        return switch (langId) {
            case "java" -> {
                if ("var".equals(type)) {
                    yield indent + "var " + name + " = " + expression + ";";
                }
                yield indent + type + " " + name + " = " + expression + ";";
            }
            case "kotlin" -> indent + "val " + name + " = " + expression;
            case "python" -> indent + name + " = " + expression;
            case "javascript", "jsx" -> indent + "const " + name + " = " + expression + ";";
            case "typescript", "tsx" -> {
                if (!"var".equals(type) && !"any".equals(type)) {
                    yield indent + "const " + name + ": " + type + " = " + expression + ";";
                }
                yield indent + "const " + name + " = " + expression + ";";
            }
            case "go" -> indent + name + " := " + expression;
            case "rust" -> indent + "let " + name + " = " + expression + ";";
            case "c", "cpp" -> {
                if ("auto".equals(type) && langId.equals("cpp")) {
                    yield indent + "auto " + name + " = " + expression + ";";
                }
                yield indent + type + " " + name + " = " + expression + ";";
            }
            case "csharp" -> indent + "var " + name + " = " + expression + ";";
            default -> indent + "var " + name + " = " + expression + ";";
        };
    }

    /**
     * Заменяет вхождение выражения на имя переменной.
     */
    private void replaceOccurrence(List<String> lines, ExpressionOccurrence occ, String variableName) {
        if (occ.startLine() == occ.endLine()) {
            int lineIdx = occ.startLine() - 1;
            String line = lines.get(lineIdx);

            if (occ.startColumn() > 0 && occ.endColumn() > 0) {
                // Точная замена по колонкам
                int sc = occ.startColumn() - 1;
                int ec = occ.endColumn();
                lines.set(lineIdx, line.substring(0, sc) + variableName +
                        (ec < line.length() ? line.substring(ec) : ""));
            } else {
                // Замена всего выражения в строке (удаляя тип если есть объявление)
                String trimmed = line.trim();
                if (trimmed.endsWith(";")) {
                    trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
                }
                String indent = detectIndent(line);
                lines.set(lineIdx, indent + line.trim().replace(trimmed, variableName + ";"));
            }
        } else {
            // Многострочная замена: заменяем первую строку, удаляем остальные
            int lineIdx = occ.startLine() - 1;
            String firstLine = lines.get(lineIdx);
            String indent = detectIndent(firstLine);

            // Удаляем строки снизу вверх
            for (int i = occ.endLine() - 1; i > lineIdx; i--) {
                lines.remove(i);
            }

            // Заменяем первую строку
            if (occ.startColumn() > 0) {
                int sc = occ.startColumn() - 1;
                String before = firstLine.substring(0, sc);
                lines.set(lineIdx, before + variableName + ";");
            } else {
                lines.set(lineIdx, indent + variableName + ";");
            }
        }
    }

    /**
     * Получает текст вхождения.
     */
    private String getOccurrenceText(List<String> lines, ExpressionOccurrence occ) {
        if (occ.startLine() == occ.endLine()) {
            int lineIdx = occ.startLine() - 1;
            if (lineIdx >= 0 && lineIdx < lines.size()) {
                return lines.get(lineIdx);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = occ.startLine() - 1; i < occ.endLine() && i < lines.size(); i++) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(lines.get(i));
        }
        return sb.toString();
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

    private boolean isJavaLike(String langId) {
        return langId.equals("java") || langId.equals("kotlin") || langId.equals("csharp");
    }

    private String inferDefaultIntType(String langId) {
        return switch (langId) {
            case "python" -> "int";
            case "go" -> "int";
            case "rust" -> "i32";
            case "c", "cpp" -> "int";
            case "typescript", "tsx", "javascript", "jsx" -> "number";
            default -> "int";
        };
    }

    private String inferDefaultFloatType(String langId) {
        return switch (langId) {
            case "python" -> "float";
            case "go" -> "float64";
            case "rust" -> "f64";
            case "c", "cpp" -> "double";
            case "typescript", "tsx", "javascript", "jsx" -> "number";
            default -> "double";
        };
    }

    private String inferDefaultBoolType(String langId) {
        return switch (langId) {
            case "python" -> "bool";
            case "go" -> "bool";
            case "rust" -> "bool";
            case "c" -> "int";
            case "cpp" -> "bool";
            case "typescript", "tsx", "javascript", "jsx" -> "boolean";
            default -> "boolean";
        };
    }

    private String inferStringType(String langId) {
        return switch (langId) {
            case "java", "kotlin", "csharp" -> "String";
            case "python" -> "str";
            case "go" -> "string";
            case "rust" -> "&str";
            case "c", "cpp" -> "const char*";
            case "typescript", "tsx", "javascript", "jsx" -> "string";
            default -> "String";
        };
    }

    /**
     * Возвращает ключевое слово для автоматического вывода типа.
     */
    private String inferVarKeyword(String langId) {
        return switch (langId) {
            case "java" -> "var";
            case "kotlin" -> "val";
            case "python" -> "Any";
            case "go" -> ""; // Go use := which infers type
            case "rust" -> ""; // Rust infers from let
            case "cpp" -> "auto";
            case "c" -> "int"; // C has no auto type inference for variables
            case "csharp" -> "var";
            case "typescript", "tsx", "javascript", "jsx" -> "any";
            default -> "var";
        };
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private record ExpressionOccurrence(int startLine, int endLine, int startColumn, int endColumn) {}
}
