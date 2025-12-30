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
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
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
 * Операция встраивания (inline).
 * Заменяет использования переменной/метода на их значение/тело.
 */
public class InlineOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "inline";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required");
        }
        if (!params.has("symbol") && !params.has("line")) {
            throw new IllegalArgumentException("Either 'symbol' or 'line' is required");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "file";
        boolean deleteDeclaration = !params.has("keepDeclaration") ||
                !params.get("keepDeclaration").asBoolean();

        // Находим символ
        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("symbol") ? params.get("symbol").asText() : "at position",
                    path.toString());
        }

        // Получаем значение/тело символа
        String inlineValue = extractInlineValue(symbol, path, context);
        if (inlineValue == null || inlineValue.isEmpty()) {
            throw new RefactoringException("Cannot inline: no value or body found for " + symbol.name());
        }

        // Находим все использования
        List<Location> usages = findUsages(symbol, path, scope, context);
        if (usages.isEmpty()) {
            return RefactoringResult.noChanges("inline", "No usages found for " + symbol.name());
        }

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Inline " + symbol.kind() + " '" + symbol.name() + "'";
        context.beginTransaction(instruction);

        try {
            List<RefactoringResult.FileChange> changes = new ArrayList<>();

            // Группируем по файлам
            Map<Path, List<Location>> byFile = usages.stream()
                    .collect(Collectors.groupingBy(Location::path));

            for (Map.Entry<Path, List<Location>> entry : byFile.entrySet()) {
                Path filePath = entry.getKey();
                List<Location> fileUsages = entry.getValue();

                context.backupFile(filePath);

                String content = Files.readString(filePath);
                List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

                List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

                // Заменяем использования снизу вверх
                List<Location> sorted = fileUsages.stream()
                        .sorted(Comparator.comparingInt(Location::startLine).reversed()
                                .thenComparingInt(Location::startColumn).reversed())
                        .toList();

                for (Location usage : sorted) {
                    int lineIndex = usage.startLine() - 1;
                    if (lineIndex >= 0 && lineIndex < lines.size()) {
                        String line = lines.get(lineIndex);
                        String before = line;

                        // Заменяем символ на значение
                        String newLine = replaceSymbolInLine(line, symbol.name(),
                                inlineValue, usage.startColumn() - 1);
                        lines.set(lineIndex, newLine);

                        details.add(new RefactoringResult.ChangeDetail(
                                usage.startLine(), usage.startColumn(),
                                before.trim(), newLine.trim()));
                    }
                }

                String newContent = String.join("\n", lines);
                FileUtils.safeWrite(filePath, newContent, StandardCharsets.UTF_8);
                context.getTreeManager().invalidateCache(filePath);

                // Вычисляем метаданные и регистрируем токен
                int lineCount = lines.size();
                long crc32c = LineAccessToken.computeRangeCrc(newContent);

                // Обновляем снапшот сессии для синхронизации с batch tools
                SessionContext.currentOrDefault().externalChanges()
                    .updateSnapshot(filePath, newContent, crc32c, StandardCharsets.UTF_8, lineCount);

                LineAccessToken token = LineAccessTracker.registerAccess(filePath, 1, lineCount, newContent, lineCount);

                changes.add(new RefactoringResult.FileChange(
                        filePath, fileUsages.size(), details, token.encode(), null, crc32c, lineCount));
            }

            // Удаляем объявление если нужно
            if (deleteDeclaration) {
                RefactoringResult.FileChange deleteChange = deleteDeclaration(symbol, context);
                if (deleteChange != null) {
                    changes.add(deleteChange);
                }
            }

            String txId = context.commitTransaction();

            int totalChanges = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("inline")
                    .summary(String.format("Inlined '%s' in %d location(s)",
                            symbol.name(), usages.size()))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalChanges)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Inline failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "file";

        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("symbol") ? params.get("symbol").asText() : "at position",
                    path.toString());
        }

        String inlineValue = extractInlineValue(symbol, path, context);
        if (inlineValue == null || inlineValue.isEmpty()) {
            throw new RefactoringException("Cannot inline: no value or body found");
        }

        List<Location> usages = findUsages(symbol, path, scope, context);
        if (usages.isEmpty()) {
            return RefactoringResult.noChanges("inline", "No usages found");
        }

        // Генерируем preview
        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        Map<Path, List<Location>> byFile = usages.stream()
                .collect(Collectors.groupingBy(Location::path));

        for (Map.Entry<Path, List<Location>> entry : byFile.entrySet()) {
            Path filePath = entry.getKey();
            List<Location> fileUsages = entry.getValue();

            try {
                String content = Files.readString(filePath);
                String[] lines = content.split("\n", -1);

                StringBuilder diff = new StringBuilder();
                diff.append("--- a/").append(filePath.getFileName()).append("\n");
                diff.append("+++ b/").append(filePath.getFileName()).append("\n");

                List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

                for (Location usage : fileUsages) {
                    int lineIndex = usage.startLine() - 1;
                    if (lineIndex >= 0 && lineIndex < lines.length) {
                        String line = lines[lineIndex];
                        String newLine = replaceSymbolInLine(line, symbol.name(),
                                inlineValue, usage.startColumn() - 1);

                        diff.append("@@ -").append(usage.startLine()).append(" @@\n");
                        diff.append("-").append(line).append("\n");
                        diff.append("+").append(newLine).append("\n");

                        details.add(new RefactoringResult.ChangeDetail(
                                usage.startLine(), usage.startColumn(),
                                line.trim(), newLine.trim()));
                    }
                }

                changes.add(new RefactoringResult.FileChange(
                        filePath, fileUsages.size(), details, null, diff.toString()));

            } catch (IOException e) {
                throw new RefactoringException("Failed to preview: " + filePath, e);
            }
        }

        return RefactoringResult.preview("inline", changes);
    }

    private SymbolInfo findSymbol(JsonNode params, Path path, RefactoringContext context)
            throws RefactoringException {
        try {
            if (params.has("symbol")) {
                String symbolName = params.get("symbol").asText();
                List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
                return symbols.stream()
                        .filter(s -> s.name().equals(symbolName))
                        .filter(s -> canInline(s.kind()))
                        .findFirst()
                        .orElse(null);

            } else if (params.has("line")) {
                int line = params.get("line").asInt();
                int column = params.has("column") ? params.get("column").asInt() : 1;
                return context.getSymbolResolver().hover(path, line, column)
                        .filter(s -> canInline(s.kind()))
                        .orElse(null);
            }
            return null;
        } catch (IOException e) {
            throw new RefactoringException("Failed to find symbol: " + e.getMessage(), e);
        }
    }

    private boolean canInline(SymbolKind kind) {
        return kind == SymbolKind.VARIABLE ||
                kind == SymbolKind.FIELD ||
                kind == SymbolKind.METHOD ||
                kind == SymbolKind.FUNCTION ||
                kind == SymbolKind.CONSTANT;
    }

    /**
     * Извлекает значение или тело символа для инлайнинга.
     * Использует AST для точного извлечения.
     */
    private String extractInlineValue(SymbolInfo symbol, Path path, RefactoringContext context)
            throws RefactoringException {
        try {
            String langId = LanguageDetector.detect(path).orElse("java");

            // Пробуем AST-based извлечение
            TreeSitterManager.ParseResult parseResult = context.getParseResult(path);
            TSNode root = parseResult.tree().getRootNode();
            String content = parseResult.content();

            int startLine = symbol.location().startLine() - 1; // 0-based для tree-sitter
            int endLine = symbol.location().endLine() - 1;

            // Для переменных/констант извлекаем значение
            if (symbol.kind() == SymbolKind.VARIABLE ||
                    symbol.kind() == SymbolKind.FIELD ||
                    symbol.kind() == SymbolKind.CONSTANT) {

                String value = SymbolExtractorUtils.extractVariableValue(
                        root, content, startLine, symbol.name(), langId);

                if (value != null) {
                    return value;
                }

                // Fallback на regex
                return extractVariableValueRegex(content, symbol.name(), startLine);
            }

            // Для методов извлекаем тело
            if (symbol.kind() == SymbolKind.METHOD ||
                    symbol.kind() == SymbolKind.FUNCTION) {

                String body = SymbolExtractorUtils.extractMethodBodyForInline(
                        root, content, startLine, endLine, langId);

                if (body != null) {
                    return body;
                }

                // Fallback на regex
                return extractMethodBodyRegex(content, startLine, endLine);
            }

            return null;

        } catch (Exception e) {
            throw new RefactoringException("Failed to extract value: " + e.getMessage(), e);
        }
    }

    /**
     * Fallback regex-based извлечение значения переменной.
     */
    private String extractVariableValueRegex(String content, String symbolName, int startLine) {
        String[] lines = content.split("\n", -1);
        if (startLine < 0 || startLine >= lines.length) {
            return null;
        }

        String line = lines[startLine];
        Pattern valuePattern = Pattern.compile(
                symbolName + "\\s*=\\s*(.+?)\\s*[;,]?\\s*$");
        Matcher matcher = valuePattern.matcher(line);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Fallback regex-based извлечение тела метода.
     */
    private String extractMethodBodyRegex(String content, int startLine, int endLine) {
        String[] lines = content.split("\n", -1);

        StringBuilder body = new StringBuilder();
        boolean inBody = false;
        int braceCount = 0;

        for (int i = startLine; i <= endLine && i < lines.length; i++) {
            String l = lines[i];
            for (char c : l.toCharArray()) {
                if (c == '{') {
                    inBody = true;
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                }
            }

            if (inBody && braceCount > 0) {
                String trimmed = l.trim();
                if (trimmed.startsWith("return ") && trimmed.endsWith(";")) {
                    body.append(trimmed.substring(7, trimmed.length() - 1));
                } else if (!trimmed.equals("{") && !trimmed.equals("}")) {
                    body.append(trimmed);
                }
            }
        }

        return body.toString().trim();
    }

    /**
     * Находит все использования символа.
     */
    private List<Location> findUsages(SymbolInfo symbol, Path path, String scope,
                                       RefactoringContext context) throws RefactoringException {
        try {
            List<Location> refs = context.getSymbolResolver().findReferences(
                    path, symbol.location().startLine(), symbol.location().startColumn(),
                    scope, false);

            // Убираем определение
            Location defLoc = symbol.location();
            return refs.stream()
                    .filter(r -> !(r.path().equals(defLoc.path()) &&
                            r.startLine() == defLoc.startLine()))
                    .toList();

        } catch (IOException e) {
            throw new RefactoringException("Failed to find usages: " + e.getMessage(), e);
        }
    }

    /**
     * Заменяет символ в строке на значение.
     */
    private String replaceSymbolInLine(String line, String symbol, String value, int column) {
        // Ищем символ как слово
        Pattern pattern = Pattern.compile("\\b" + Pattern.quote(symbol) + "\\b");
        Matcher matcher = pattern.matcher(line);

        if (matcher.find(column)) {
            return line.substring(0, matcher.start()) + value + line.substring(matcher.end());
        }

        return line.replaceFirst("\\b" + Pattern.quote(symbol) + "\\b", value);
    }

    /**
     * Удаляет объявление символа.
     */
    private RefactoringResult.FileChange deleteDeclaration(SymbolInfo symbol,
                                                            RefactoringContext context) {
        try {
            Path path = symbol.location().path();
            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            int startLine = symbol.location().startLine() - 1;
            int endLine = symbol.location().endLine() - 1;

            StringBuilder deleted = new StringBuilder();
            for (int i = endLine; i >= startLine && i < lines.size(); i--) {
                deleted.insert(0, lines.remove(i) + "\n");
            }

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

            return new RefactoringResult.FileChange(
                    path, 1,
                    List.of(new RefactoringResult.ChangeDetail(
                            symbol.location().startLine(), 0,
                            deleted.toString().trim(), "[DELETED]")),
                    token.encode(), null, crc32c, lineCount
            );

        } catch (IOException e) {
            return null;
        }
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
