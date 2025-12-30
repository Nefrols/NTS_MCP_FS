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
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.LineAccessToken;
import ru.nts.tools.mcp.core.LineAccessTracker;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.SessionContext;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Операция удаления символа.
 * Удаляет символ и обрабатывает все ссылки на него.
 */
public class DeleteOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "delete";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required for delete operation");
        }
        if (!params.has("symbol") && !params.has("line")) {
            throw new IllegalArgumentException("Either 'symbol' or 'line' is required for delete operation");
        }

        if (params.has("handleReferences")) {
            String handle = params.get("handleReferences").asText();
            if (!Set.of("comment", "remove", "error").contains(handle)) {
                throw new IllegalArgumentException(
                        "Invalid handleReferences value: '" + handle + "'. Valid: comment, remove, error");
            }
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String handleReferences = params.has("handleReferences")
                ? params.get("handleReferences").asText() : "error";
        String scope = params.has("scope") ? params.get("scope").asText() : "file";

        // Находим символ
        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            String symbolName = params.has("symbol") ? params.get("symbol").asText() : "at position";
            throw RefactoringException.symbolNotFound(symbolName, path.toString());
        }

        // Находим все ссылки
        List<Location> references = findReferences(symbol, path, scope, context);

        // Обрабатываем ссылки
        if (!references.isEmpty() && handleReferences.equals("error")) {
            List<String> refLocations = references.stream()
                    .map(r -> r.path().getFileName() + ":" + r.startLine())
                    .distinct()
                    .limit(10)
                    .toList();

            throw new RefactoringException(
                    "Cannot delete '" + symbol.name() + "': " + references.size() +
                            " reference(s) found. Use handleReferences='comment' or 'remove' to proceed.",
                    refLocations.stream()
                            .map(l -> "Referenced at: " + l)
                            .collect(Collectors.toList())
            );
        }

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Delete " + symbol.kind() + " '" + symbol.name() + "'";
        context.beginTransaction(instruction);

        try {
            List<RefactoringResult.FileChange> changes = new ArrayList<>();

            // Обрабатываем ссылки в зависимости от режима
            if (!references.isEmpty() && !handleReferences.equals("error")) {
                changes.addAll(handleReferencesAction(references, symbol, handleReferences, context));
            }

            // Удаляем сам символ
            RefactoringResult.FileChange deleteChange = deleteSymbol(symbol, context);
            changes.add(deleteChange);

            String txId = context.commitTransaction();

            int totalChanges = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("delete")
                    .summary(String.format("Deleted %s '%s'%s",
                            symbol.kind(), symbol.name(),
                            references.isEmpty() ? "" : " and handled " + references.size() + " reference(s)"))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalChanges)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Delete failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String handleReferences = params.has("handleReferences")
                ? params.get("handleReferences").asText() : "error";
        String scope = params.has("scope") ? params.get("scope").asText() : "file";

        // Находим символ
        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            String symbolName = params.has("symbol") ? params.get("symbol").asText() : "at position";
            throw RefactoringException.symbolNotFound(symbolName, path.toString());
        }

        // Находим все ссылки
        List<Location> references = findReferences(symbol, path, scope, context);

        // Генерируем preview
        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        // Preview удаления символа
        changes.add(generateDeletePreview(symbol, context));

        // Preview обработки ссылок
        if (!references.isEmpty()) {
            if (handleReferences.equals("error")) {
                List<String> refLocations = references.stream()
                        .map(r -> "Referenced at: " + r.path().getFileName() + ":" + r.startLine())
                        .distinct()
                        .limit(10)
                        .toList();

                return RefactoringResult.builder()
                        .status(RefactoringResult.Status.PREVIEW)
                        .action("delete")
                        .summary("Would delete '" + symbol.name() + "' but " +
                                references.size() + " reference(s) exist")
                        .changes(changes)
                        .addSuggestion("Use handleReferences='comment' to comment out references")
                        .addSuggestion("Use handleReferences='remove' to delete reference lines")
                        .suggestions(refLocations)
                        .build();
            }

            changes.addAll(generateReferencePreview(references, symbol, handleReferences, context));
        }

        return RefactoringResult.preview("delete", changes);
    }

    /**
     * Находит символ по имени или позиции.
     */
    private SymbolInfo findSymbol(JsonNode params, Path path, RefactoringContext context)
            throws RefactoringException {

        try {
            if (params.has("symbol")) {
                String symbolName = params.get("symbol").asText();
                String kindFilter = params.has("kind") ? params.get("kind").asText() : null;

                List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
                return symbols.stream()
                        .filter(s -> s.name().equals(symbolName))
                        .filter(s -> kindFilter == null || matchesKind(s, kindFilter))
                        .findFirst()
                        .orElse(null);

            } else if (params.has("line")) {
                int line = params.get("line").asInt();
                int column = params.has("column") ? params.get("column").asInt() : 1;

                return context.getSymbolResolver().hover(path, line, column).orElse(null);
            }

            return null;

        } catch (IOException e) {
            throw new RefactoringException("Failed to analyze file: " + e.getMessage(), e);
        }
    }

    /**
     * Находит все ссылки на символ.
     */
    private List<Location> findReferences(SymbolInfo symbol, Path path, String scope,
                                           RefactoringContext context) throws RefactoringException {
        try {
            List<Location> refs = context.getSymbolResolver().findReferences(
                    path, symbol.location().startLine(), symbol.location().startColumn(),
                    scope, false);

            // Убираем само определение
            Location defLoc = symbol.location();
            return refs.stream()
                    .filter(r -> !(r.path().equals(defLoc.path()) &&
                            r.startLine() == defLoc.startLine() &&
                            r.startColumn() == defLoc.startColumn()))
                    .toList();

        } catch (IOException e) {
            throw new RefactoringException("Failed to find references: " + e.getMessage(), e);
        }
    }

    /**
     * Обрабатывает ссылки в зависимости от режима.
     */
    private List<RefactoringResult.FileChange> handleReferencesAction(
            List<Location> references, SymbolInfo symbol,
            String handleMode, RefactoringContext context) throws IOException, RefactoringException {

        // Группируем по файлам
        Map<Path, List<Location>> byFile = references.stream()
                .collect(Collectors.groupingBy(Location::path));

        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        for (Map.Entry<Path, List<Location>> entry : byFile.entrySet()) {
            Path filePath = entry.getKey();
            List<Location> fileRefs = entry.getValue();

            context.backupFile(filePath);

            String content = Files.readString(filePath);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

            // Сортируем снизу вверх
            List<Location> sorted = fileRefs.stream()
                    .sorted(Comparator.comparingInt(Location::startLine).reversed())
                    .toList();

            Set<Integer> processedLines = new HashSet<>();

            for (Location ref : sorted) {
                int lineIndex = ref.startLine() - 1;
                if (lineIndex < 0 || lineIndex >= lines.size()) continue;
                if (processedLines.contains(lineIndex)) continue;

                String line = lines.get(lineIndex);
                String before = line;

                if (handleMode.equals("comment")) {
                    // Комментируем строку
                    String commented = "// TODO: Deleted reference to '" + symbol.name() +
                            "' - " + line.trim();
                    lines.set(lineIndex, commented);
                    details.add(new RefactoringResult.ChangeDetail(
                            ref.startLine(), 0, before.trim(), commented.trim()));
                } else if (handleMode.equals("remove")) {
                    // Удаляем строку
                    lines.remove(lineIndex);
                    details.add(new RefactoringResult.ChangeDetail(
                            ref.startLine(), 0, before.trim(), "[DELETED]"));
                }

                processedLines.add(lineIndex);
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
                    filePath, fileRefs.size(), details, token.encode(), null, crc32c, lineCount));
        }

        return changes;
    }

    /**
     * Удаляет символ из файла.
     */
    private RefactoringResult.FileChange deleteSymbol(SymbolInfo symbol, RefactoringContext context)
            throws IOException, RefactoringException {

        Path path = symbol.location().path();
        context.backupFile(path);

        String content = Files.readString(path);
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

        int startLine = symbol.location().startLine() - 1;
        int endLine = symbol.location().endLine() - 1;

        // Сохраняем удаляемый код для отчёта
        StringBuilder deletedCode = new StringBuilder();
        for (int i = startLine; i <= endLine && i < lines.size(); i++) {
            deletedCode.append(lines.get(i)).append("\n");
        }

        // Удаляем строки
        for (int i = endLine; i >= startLine && i < lines.size(); i--) {
            lines.remove(i);
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
                        deletedCode.toString().trim(), "[DELETED]")),
                token.encode(), null, crc32c, lineCount
        );
    }

    /**
     * Генерирует preview удаления символа.
     */
    private RefactoringResult.FileChange generateDeletePreview(SymbolInfo symbol,
                                                                RefactoringContext context) throws RefactoringException {
        try {
            Path path = symbol.location().path();
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);

            int startLine = symbol.location().startLine() - 1;
            int endLine = symbol.location().endLine() - 1;

            StringBuilder diff = new StringBuilder();
            diff.append("--- a/").append(path.getFileName()).append("\n");
            diff.append("+++ b/").append(path.getFileName()).append("\n");
            diff.append("@@ -").append(startLine + 1).append(",")
                    .append(endLine - startLine + 1).append(" @@\n");

            for (int i = startLine; i <= endLine && i < lines.length; i++) {
                diff.append("-").append(lines[i]).append("\n");
            }

            StringBuilder deletedCode = new StringBuilder();
            for (int i = startLine; i <= endLine && i < lines.length; i++) {
                deletedCode.append(lines[i]).append("\n");
            }

            return new RefactoringResult.FileChange(
                    path, 1,
                    List.of(new RefactoringResult.ChangeDetail(
                            symbol.location().startLine(), 0,
                            deletedCode.toString().trim(), "[WOULD DELETE]")),
                    null, diff.toString()
            );

        } catch (IOException e) {
            throw new RefactoringException("Failed to generate preview: " + e.getMessage(), e);
        }
    }

    /**
     * Генерирует preview обработки ссылок.
     */
    private List<RefactoringResult.FileChange> generateReferencePreview(
            List<Location> references, SymbolInfo symbol,
            String handleMode, RefactoringContext context) throws RefactoringException {

        Map<Path, List<Location>> byFile = references.stream()
                .collect(Collectors.groupingBy(Location::path));

        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        for (Map.Entry<Path, List<Location>> entry : byFile.entrySet()) {
            Path filePath = entry.getKey();
            List<Location> fileRefs = entry.getValue();

            try {
                String content = Files.readString(filePath);
                String[] lines = content.split("\n", -1);

                StringBuilder diff = new StringBuilder();
                diff.append("--- a/").append(filePath.getFileName()).append("\n");
                diff.append("+++ b/").append(filePath.getFileName()).append("\n");

                List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

                for (Location ref : fileRefs) {
                    int lineIndex = ref.startLine() - 1;
                    if (lineIndex < 0 || lineIndex >= lines.length) continue;

                    String line = lines[lineIndex];
                    diff.append("@@ -").append(ref.startLine()).append(" @@\n");
                    diff.append("-").append(line).append("\n");

                    if (handleMode.equals("comment")) {
                        String commented = "// TODO: Deleted reference - " + line.trim();
                        diff.append("+").append(commented).append("\n");
                        details.add(new RefactoringResult.ChangeDetail(
                                ref.startLine(), 0, line.trim(), commented));
                    } else {
                        details.add(new RefactoringResult.ChangeDetail(
                                ref.startLine(), 0, line.trim(), "[WOULD DELETE]"));
                    }
                }

                changes.add(new RefactoringResult.FileChange(
                        filePath, fileRefs.size(), details, null, diff.toString()));

            } catch (IOException e) {
                throw new RefactoringException("Failed to preview: " + filePath, e);
            }
        }

        return changes;
    }

    private boolean matchesKind(SymbolInfo symbol, String kind) {
        return switch (kind.toLowerCase()) {
            case "class" -> symbol.kind() == SymbolInfo.SymbolKind.CLASS;
            case "interface" -> symbol.kind() == SymbolInfo.SymbolKind.INTERFACE;
            case "method" -> symbol.kind() == SymbolInfo.SymbolKind.METHOD;
            case "function" -> symbol.kind() == SymbolInfo.SymbolKind.FUNCTION;
            case "field" -> symbol.kind() == SymbolInfo.SymbolKind.FIELD;
            case "variable" -> symbol.kind() == SymbolInfo.SymbolKind.VARIABLE;
            default -> true;
        };
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
