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
import ru.nts.tools.mcp.core.TaskContext;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Операция переименования символа.
 * Находит все использования символа и обновляет их атомарно.
 */
public class RenameOperation implements RefactoringOperation {

    private static final int MAX_FILES_TO_SCAN = 1000;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String getName() {
        return "rename";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required for rename operation");
        }
        if (!params.has("newName")) {
            throw new IllegalArgumentException("Parameter 'newName' is required for rename operation");
        }
        if (!params.has("symbol") && !params.has("line")) {
            throw new IllegalArgumentException("Either 'symbol' or 'line'/'column' is required");
        }

        String newName = params.get("newName").asText();
        if (!isValidIdentifier(newName)) {
            throw new IllegalArgumentException("Invalid identifier: '" + newName + "'");
        }

        // Валидация signature если указана
        if (params.has("signature")) {
            String signature = params.get("signature").asText();
            if (!signature.isEmpty() && !signature.matches("\\(.*\\)")) {
                throw new IllegalArgumentException(
                        "Invalid signature format: '" + signature + "'. Expected format: (Type1, Type2) or ()");
            }
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        // Проверяем, включен ли гибридный режим
        boolean hybridMode = params.has("hybridMode") && params.get("hybridMode").asBoolean(false);
        if (hybridMode) {
            return executeHybrid(params, context, false);
        }

        Path path = resolvePath(params.get("path").asText());
        String newName = params.get("newName").asText();
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        // Находим символ
        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            return handleSymbolNotFound(params, path, context);
        }

        String oldName = symbol.name();

        // Находим все ссылки
        List<RenameLocation> allLocations = findAllReferences(symbol, path, scope, context);
        if (allLocations.isEmpty()) {
            return RefactoringResult.noChanges("rename",
                    "No references found for symbol '" + oldName + "'");
        }

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Rename '" + oldName + "' to '" + newName + "'";
        context.beginTransaction(instruction);

        try {
            // Применяем изменения
            List<RefactoringResult.FileChange> changes = applyRename(
                    allLocations, oldName, newName, context);

            String txId = context.commitTransaction();

            int totalOccurrences = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("rename")
                    .summary(String.format("Renamed '%s' to '%s' in %d file(s) (%d occurrence(s))",
                            oldName, newName, changes.size(), totalOccurrences))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalOccurrences)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Rename failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        // Проверяем, включен ли гибридный режим
        boolean hybridMode = params.has("hybridMode") && params.get("hybridMode").asBoolean(false);
        if (hybridMode) {
            return executeHybrid(params, context, true);
        }

        Path path = resolvePath(params.get("path").asText());
        String newName = params.get("newName").asText();
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            return handleSymbolNotFound(params, path, context);
        }

        String oldName = symbol.name();

        List<RenameLocation> allLocations = findAllReferences(symbol, path, scope, context);
        if (allLocations.isEmpty()) {
            return RefactoringResult.noChanges("rename",
                    "No references found for symbol '" + oldName + "'");
        }

        // Генерируем preview
        List<RefactoringResult.FileChange> changes = generatePreview(
                allLocations, oldName, newName, context);

        return RefactoringResult.preview("rename", changes);
    }

    /**
     * Находит символ по имени или позиции.
     * При поиске по line без column, если указан kind, ищем символ нужного типа на строке.
     * Поддерживает параметр signature для различения перегрузок методов.
     * Формат signature: "(Type1, Type2)" или "()" для методов без параметров.
     */
    private SymbolInfo findSymbol(JsonNode params, Path path, RefactoringContext context)
            throws RefactoringException {

        try {
            // Получаем фильтр по сигнатуре если указан
            String signatureFilter = params.has("signature") ? params.get("signature").asText() : null;

            if (params.has("symbol")) {
                // Поиск по имени
                String symbolName = params.get("symbol").asText();
                String kindFilter = params.has("kind") ? params.get("kind").asText() : null;

                List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
                List<SymbolInfo> matches = symbols.stream()
                        .filter(s -> s.name().equals(symbolName))
                        .filter(s -> kindFilter == null || matchesKind(s, kindFilter))
                        .filter(s -> signatureFilter == null || matchesSignature(s, signatureFilter, path, context))
                        .toList();

                if (matches.isEmpty()) {
                    return null;
                }
                if (matches.size() > 1) {
                    // Если есть несколько совпадений и не указана сигнатура, выдаём подсказку
                    List<String> suggestions = matches.stream()
                            .map(s -> formatSymbolWithSignature(s, path, context))
                            .toList();
                    throw RefactoringException.ambiguousSymbol(symbolName, suggestions);
                }
                return matches.get(0);

            } else if (params.has("line")) {
                // Поиск по позиции
                int line = params.get("line").asInt();
                String kindFilter = params.has("kind") ? params.get("kind").asText() : null;

                // REPORT4 Fix 1.2: При указанном kind приоритетно ищем по типу на строке
                // Это предотвращает путаницу между типами возврата и методами на той же строке
                if (kindFilter != null) {
                    SymbolInfo byKind = findSymbolAtLineByKind(path, line, kindFilter, context);
                    if (byKind != null) {
                        return byKind;
                    }
                    // Если не нашли по kind, не fallback на hover - это предотвращает
                    // случайное переименование типа вместо метода
                    return null;
                }

                // Без kind используем hover с column (по умолчанию 1)
                int column = params.has("column") ? params.get("column").asInt() : 1;
                Optional<SymbolInfo> found = context.getSymbolResolver().hover(path, line, column);
                return found.orElse(null);
            }

            return null;

        } catch (IOException e) {
            throw new RefactoringException("Failed to analyze file: " + e.getMessage(), e);
        }
    }

    /**
     * Находит символ заданного типа на указанной строке.
     * Используется когда column не указан, но указан kind.
     *
     * @param path путь к файлу
     * @param line номер строки (1-based)
     * @param kindFilter тип символа (method, class, field и т.д.)
     * @param context контекст рефакторинга
     * @return найденный символ или null
     */
    private SymbolInfo findSymbolAtLineByKind(Path path, int line, String kindFilter,
                                               RefactoringContext context) throws IOException {
        List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);

        // Ищем символы, определённые на указанной строке с нужным типом
        List<SymbolInfo> matches = symbols.stream()
                .filter(s -> s.location().startLine() == line)
                .filter(s -> matchesKind(s, kindFilter))
                .toList();

        if (matches.size() == 1) {
            return matches.get(0);
        }

        if (matches.size() > 1) {
            // Несколько символов одного типа на строке - возвращаем первый
            // (в реальности это редкий случай)
            return matches.get(0);
        }

        // Если точного совпадения по startLine нет, ищем символы, включающие эту строку
        List<SymbolInfo> containing = symbols.stream()
                .filter(s -> s.location().startLine() <= line && s.location().endLine() >= line)
                .filter(s -> matchesKind(s, kindFilter))
                .toList();

        if (!containing.isEmpty()) {
            // Возвращаем наиболее специфичный (с наименьшим диапазоном)
            return containing.stream()
                    .min(Comparator.comparingInt(s -> s.location().endLine() - s.location().startLine()))
                    .orElse(null);
        }

        return null;
    }

    /**
     * Находит все ссылки на символ.
     */
    private List<RenameLocation> findAllReferences(SymbolInfo symbol, Path originPath,
                                                    String scope, RefactoringContext context)
            throws RefactoringException {

        List<RenameLocation> locations = new ArrayList<>();
        String symbolName = symbol.name();

        try {
            switch (scope) {
                case "file" -> {
                    // Только текущий файл
                    List<Location> refs = context.getSymbolResolver()
                            .findReferences(originPath, symbol.location().startLine(),
                                    symbol.location().startColumn(), "file", true);
                    for (Location ref : refs) {
                        locations.add(new RenameLocation(ref.path(), ref.startLine(),
                                ref.startColumn(), ref.endColumn()));
                    }
                }

                case "directory" -> {
                    // Текущая директория
                    locations.addAll(findReferencesInDirectory(
                            originPath.getParent(), symbolName, originPath, context));
                }

                case "project" -> {
                    // Весь проект
                    locations.addAll(findReferencesInProject(
                            symbolName, originPath, context));
                }
            }

            // Убираем дубликаты и сортируем
            return locations.stream()
                    .distinct()
                    .sorted(Comparator
                            .comparing((RenameLocation l) -> l.path.toString())
                            .thenComparing(RenameLocation::line)
                            .thenComparing(RenameLocation::startColumn))
                    .toList();

        } catch (IOException e) {
            throw new RefactoringException("Failed to find references: " + e.getMessage(), e);
        }
    }

    /**
     * Ищет ссылки в директории.
     */
    private List<RenameLocation> findReferencesInDirectory(Path directory, String symbolName,
                                                            Path originPath, RefactoringContext context)
            throws IOException {

        List<RenameLocation> locations = new ArrayList<>();
        String langId = LanguageDetector.detect(originPath).orElse(null);
        if (langId == null) return locations;

        String fileExtension = LanguageDetector.getFileExtension(langId).orElse(null);
        if (fileExtension == null) return locations;

        try (var stream = Files.list(directory)) {
            List<Path> files = stream
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().endsWith("." + fileExtension))
                    .toList();

            for (Path file : files) {
                locations.addAll(findReferencesInFile(file, symbolName, context));
            }
        }

        return locations;
    }

    /**
     * Ищет ссылки в проекте.
     */
    private List<RenameLocation> findReferencesInProject(String symbolName, Path originPath,
                                                          RefactoringContext context)
            throws RefactoringException {

        Path projectRoot = context.getProjectRoot();
        String langId = LanguageDetector.detect(originPath).orElse(null);
        if (langId == null) {
            throw RefactoringException.unsupportedLanguage("unknown");
        }

        String fileExtension = LanguageDetector.getFileExtension(langId).orElse(null);
        if (fileExtension == null) {
            throw RefactoringException.unsupportedLanguage(langId);
        }

        // Находим файлы-кандидаты
        List<Path> candidateFiles = new ArrayList<>();

        try {
            Files.walkFileTree(projectRoot, EnumSet.noneOf(FileVisitOption.class), 15,
                    new SimpleFileVisitor<>() {
                        int count = 0;

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (count >= MAX_FILES_TO_SCAN) {
                                return FileVisitResult.TERMINATE;
                            }
                            String fileName = file.getFileName().toString();
                            if (fileName.endsWith("." + fileExtension)) {
                                try {
                                    String content = Files.readString(file);
                                    if (content.contains(symbolName)) {
                                        candidateFiles.add(file);
                                        count++;
                                    }
                                } catch (IOException ignored) {}
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName().toString();
                            if (name.startsWith(".") || name.equals("node_modules") ||
                                    name.equals("build") || name.equals("target") ||
                                    name.equals("dist") || name.equals("out")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            throw new RefactoringException("Failed to scan project: " + e.getMessage(), e);
        }

        // Параллельно анализируем кандидатов
        List<Future<List<RenameLocation>>> futures = candidateFiles.stream()
                .map(file -> executor.submit(() -> findReferencesInFile(file, symbolName, context)))
                .toList();

        List<RenameLocation> allLocations = new ArrayList<>();
        for (Future<List<RenameLocation>> future : futures) {
            try {
                allLocations.addAll(future.get(10, TimeUnit.SECONDS));
            } catch (Exception ignored) {}
        }

        return allLocations;
    }

    /**
     * Ищет ссылки в одном файле.
     */
    private List<RenameLocation> findReferencesInFile(Path file, String symbolName,
                                                       RefactoringContext context) {
        List<RenameLocation> locations = new ArrayList<>();

        try {
            // Используем getParseResult для поддержки виртуального контента в batch
            TreeSitterManager.ParseResult parseResult = context.getParseResult(file);

            List<Location> refs = context.getSymbolExtractor().findReferences(
                    parseResult.tree(), file, parseResult.content(),
                    parseResult.langId(), symbolName);

            for (Location ref : refs) {
                locations.add(new RenameLocation(ref.path(), ref.startLine(),
                        ref.startColumn(), ref.endColumn()));
            }

        } catch (Exception ignored) {}

        return locations;
    }

    /**
     * Применяет переименование ко всем найденным локациям.
     * Использует точные AST-координаты с проверкой целостности.
     */
    private List<RefactoringResult.FileChange> applyRename(
            List<RenameLocation> locations, String oldName, String newName,
            RefactoringContext context) throws IOException, RefactoringException {

        // Группируем по файлам
        Map<Path, List<RenameLocation>> byFile = locations.stream()
                .collect(Collectors.groupingBy(RenameLocation::path));

        List<RefactoringResult.FileChange> changes = new ArrayList<>();
        List<String> integrityWarnings = new ArrayList<>();

        for (Map.Entry<Path, List<RenameLocation>> entry : byFile.entrySet()) {
            Path filePath = entry.getKey();
            List<RenameLocation> fileLocations = entry.getValue();

            // Backup перед изменением
            context.backupFile(filePath);

            // Читаем файл
            String content = Files.readString(filePath);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            List<RefactoringResult.ChangeDetail> details = new ArrayList<>();
            int skippedCount = 0;

            // Применяем изменения справа налево и снизу вверх для сохранения индексов
            List<RenameLocation> sorted = fileLocations.stream()
                    .sorted(Comparator
                            .comparingInt(RenameLocation::line).reversed()
                            .thenComparingInt(RenameLocation::startColumn).reversed())
                    .toList();

            for (RenameLocation loc : sorted) {
                int lineIndex = loc.line - 1;
                if (lineIndex >= 0 && lineIndex < lines.size()) {
                    String line = lines.get(lineIndex);
                    String before = line;

                    int start = loc.startColumn - 1;
                    int end = start + oldName.length();

                    if (start >= 0 && end <= line.length()) {
                        String actualText = line.substring(start, end);

                        if (!actualText.equals(oldName)) {
                            integrityWarnings.add(String.format(
                                    "%s:%d:%d - Expected '%s' but found '%s', skipping",
                                    filePath.getFileName(), loc.line, loc.startColumn,
                                    oldName, actualText.length() > 30 ? actualText.substring(0, 30) + "..." : actualText));
                            skippedCount++;
                            continue;
                        }

                        // Применяем замену
                        String newLine = line.substring(0, start) + newName + line.substring(end);
                        lines.set(lineIndex, newLine);

                        details.add(new RefactoringResult.ChangeDetail(
                                loc.line, loc.startColumn, before.trim(), newLine.trim()));
                    } else {
                        integrityWarnings.add(String.format(
                                "%s:%d:%d - Coordinates out of bounds (line length: %d)",
                                filePath.getFileName(), loc.line, loc.startColumn, line.length()));
                        skippedCount++;
                    }
                }
            }

            // Если все замены пропущены, не записываем файл
            if (details.isEmpty()) {
                continue;
            }

            // Записываем файл
            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(filePath, newContent, java.nio.charset.StandardCharsets.UTF_8);

            // Вычисляем метаданные после записи
            int lineCount = lines.size();
            long crc32c = LineAccessToken.computeRangeCrc(newContent);

            // Обновляем снапшот сессии для синхронизации с batch tools
            TaskContext.currentOrDefault().externalChanges()
                .updateSnapshot(filePath, newContent, crc32c, java.nio.charset.StandardCharsets.UTF_8, lineCount);

            // Регистрируем токен доступа на весь файл
            LineAccessToken token = LineAccessTracker.registerAccess(filePath, 1, lineCount, newContent, lineCount, crc32c);

            // Инвалидируем кэш tree-sitter
            context.getTreeManager().invalidateCache(filePath);

            changes.add(new RefactoringResult.FileChange(
                    filePath, details.size(), details, token.encode(),
                    skippedCount > 0 ? String.format("Skipped %d locations due to integrity check", skippedCount) : null,
                    crc32c, lineCount));
        }

        // Если были предупреждения целостности, добавляем их в лог
        if (!integrityWarnings.isEmpty()) {
            System.err.println("[RENAME] Integrity warnings:\n" + String.join("\n", integrityWarnings));
        }

        return changes;
    }

    /**
     * Генерирует preview без применения изменений.
     */
    private List<RefactoringResult.FileChange> generatePreview(
            List<RenameLocation> locations, String oldName, String newName,
            RefactoringContext context) throws RefactoringException {

        Map<Path, List<RenameLocation>> byFile = locations.stream()
                .collect(Collectors.groupingBy(RenameLocation::path));

        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        for (Map.Entry<Path, List<RenameLocation>> entry : byFile.entrySet()) {
            Path filePath = entry.getKey();
            List<RenameLocation> fileLocations = entry.getValue();

            try {
                String content = Files.readString(filePath);
                String[] lines = content.split("\n", -1);

                StringBuilder diff = new StringBuilder();
                diff.append("--- a/").append(filePath.getFileName()).append("\n");
                diff.append("+++ b/").append(filePath.getFileName()).append("\n");

                List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

                for (RenameLocation loc : fileLocations) {
                    int lineIndex = loc.line - 1;
                    if (lineIndex >= 0 && lineIndex < lines.length) {
                        String line = lines[lineIndex];
                        int start = loc.startColumn - 1;
                        int end = start + oldName.length();
                        if (start >= 0 && end <= line.length()) {
                            String newLine = line.substring(0, start) + newName +
                                    line.substring(end);

                            diff.append("@@ -").append(loc.line).append(" +").append(loc.line).append(" @@\n");
                            diff.append("-").append(line).append("\n");
                            diff.append("+").append(newLine).append("\n");

                            details.add(new RefactoringResult.ChangeDetail(
                                    loc.line, loc.startColumn, line.trim(), newLine.trim()));
                        }
                    }
                }

                changes.add(new RefactoringResult.FileChange(
                        filePath, fileLocations.size(), details, null, diff.toString()));

            } catch (IOException e) {
                throw new RefactoringException("Failed to preview file: " + filePath, e);
            }
        }

        return changes;
    }

    /**
     * Обрабатывает случай, когда символ не найден.
     */
    private RefactoringResult handleSymbolNotFound(JsonNode params, Path path,
                                                    RefactoringContext context) {
        String symbolName = params.has("symbol") ? params.get("symbol").asText() : "symbol at position";

        List<String> suggestions = new ArrayList<>();

        // Ищем похожие символы
        try {
            List<SymbolInfo> allSymbols = context.getSymbolResolver().listSymbols(path);
            List<String> similar = allSymbols.stream()
                    .map(SymbolInfo::name)
                    .filter(name -> isSimilar(name, symbolName))
                    .limit(5)
                    .map(name -> "Did you mean '" + name + "'?")
                    .toList();
            suggestions.addAll(similar);
        } catch (Exception ignored) {}

        return RefactoringResult.error("rename",
                "Symbol '" + symbolName + "' not found in " + path.getFileName(),
                suggestions);
    }

    private boolean matchesKind(SymbolInfo symbol, String kind) {
        return switch (kind.toLowerCase()) {
            case "class" -> symbol.kind() == SymbolInfo.SymbolKind.CLASS;
            case "interface" -> symbol.kind() == SymbolInfo.SymbolKind.INTERFACE;
            case "method" -> symbol.kind() == SymbolInfo.SymbolKind.METHOD;
            case "function" -> symbol.kind() == SymbolInfo.SymbolKind.FUNCTION;
            case "field" -> symbol.kind() == SymbolInfo.SymbolKind.FIELD;
            case "variable" -> symbol.kind() == SymbolInfo.SymbolKind.VARIABLE;
            case "parameter" -> symbol.kind() == SymbolInfo.SymbolKind.PARAMETER;
            default -> true;
        };
    }

    /**
     * Проверяет соответствие символа указанной сигнатуре.
     * Позволяет избирательно переименовывать перегруженные методы.
     * Использует структурированные параметры из AST (ООП подход).
     *
     * @param symbol проверяемый символ
     * @param signature требуемая сигнатура в формате "(Type1, Type2)" или "()"
     * @param path путь к файлу (не используется, оставлен для совместимости)
     * @param context контекст рефакторинга (не используется)
     * @return true если сигнатура совпадает
     */
    private boolean matchesSignature(SymbolInfo symbol, String signature, Path path,
                                      RefactoringContext context) {
        // Только для методов/функций
        if (symbol.kind() != SymbolInfo.SymbolKind.METHOD &&
                symbol.kind() != SymbolInfo.SymbolKind.FUNCTION) {
            return true; // Для не-методов сигнатура не применима
        }

        // Используем структурированные параметры из AST
        if (symbol.parameters() != null) {
            return symbol.matchesParameterSignature(signature);
        }

        // Fallback: если параметры не извлечены, используем строковый парсинг
        return matchesSignatureFallback(symbol, signature, path);
    }

    /**
     * Fallback для matchesSignature когда параметры не извлечены из AST.
     */
    private boolean matchesSignatureFallback(SymbolInfo symbol, String signature, Path path) {
        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);
            int lineIdx = symbol.location().startLine() - 1;

            if (lineIdx < 0 || lineIdx >= lines.length) {
                return true;
            }

            String line = lines[lineIdx];
            String extractedSig = extractMethodSignature(line, symbol.name());
            if (extractedSig == null) {
                return true;
            }

            String normalizedExpected = normalizeSignature(signature);
            String normalizedActual = normalizeSignature(extractedSig);

            return normalizedExpected.equals(normalizedActual);

        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Извлекает сигнатуру метода из строки кода.
     * Использует balanced matching для корректной обработки вложенных скобок.
     *
     * @param line строка с определением метода
     * @param methodName имя метода
     * @return сигнатура в формате "(Type1, Type2)" или null
     */
    private String extractMethodSignature(String line, String methodName) {
        // Находим начало параметров после имени метода
        int nameIdx = line.indexOf(methodName);
        if (nameIdx < 0) return null;

        int openParen = line.indexOf('(', nameIdx + methodName.length());
        if (openParen < 0) return null;

        // Используем balanced matching для нахождения закрывающей скобки
        int closeParen = findMatchingParen(line, openParen);
        if (closeParen < 0) return null;

        String params = line.substring(openParen + 1, closeParen).trim();
        if (params.isEmpty()) {
            return "()";
        }

        // Разбиваем параметры с учётом вложенных generics
        List<String> paramList = splitByCommaBalanced(params);
        List<String> types = new ArrayList<>();

        for (String param : paramList) {
            String trimmed = param.trim();
            if (trimmed.isEmpty()) continue;

            String type = extractParameterType(trimmed);
            if (type != null) {
                types.add(type);
            }
        }

        return "(" + String.join(", ", types) + ")";
    }

    /**
     * Находит индекс закрывающей скобки, соответствующей открывающей.
     */
    private int findMatchingParen(String str, int openIndex) {
        if (openIndex < 0 || openIndex >= str.length() || str.charAt(openIndex) != '(') {
            return -1;
        }
        int depth = 1;
        for (int i = openIndex + 1; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '(' || c == '<' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == '>' || c == ']' || c == '}') {
                depth--;
                if (depth == 0 && c == ')') return i;
            }
        }
        return -1;
    }

    /**
     * Разбивает строку по запятым с учётом вложенных скобок и generics.
     */
    private List<String> splitByCommaBalanced(String str) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '<' || c == '(' || c == '[' || c == '{') {
                depth++;
                current.append(c);
            } else if (c == '>' || c == ')' || c == ']' || c == '}') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                result.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    /**
     * Извлекает тип параметра из объявления.
     * Поддерживает сложные generics типа Map<String, List<Integer>>.
     */
    private String extractParameterType(String paramDecl) {
        String trimmed = paramDecl.trim();

        // Удаляем модификаторы (final, @annotations)
        trimmed = trimmed.replaceAll("^(?:final\\s+|@\\w+\\s*)+", "");

        // Kotlin style: "name: Type" или "name: Type?"
        if (trimmed.contains(":")) {
            int colonIdx = trimmed.indexOf(':');
            String typeAndRest = trimmed.substring(colonIdx + 1).trim();
            // Извлекаем тип (может содержать generics)
            return extractTypeWithGenerics(typeAndRest);
        }

        // Java style: "Type name" или "Type<Generic> name" или "Type... name"
        // Находим последний идентификатор (имя параметра)
        // Тип - всё что до него

        // Находим границу между типом и именем
        // Тип заканчивается на: >, ], пробел после буквы, ...
        int lastSpace = -1;
        int depth = 0;
        for (int i = trimmed.length() - 1; i >= 0; i--) {
            char c = trimmed.charAt(i);
            if (c == '>' || c == ']' || c == ')') depth++;
            else if (c == '<' || c == '[' || c == '(') depth--;
            else if (Character.isWhitespace(c) && depth == 0) {
                lastSpace = i;
                break;
            }
        }

        if (lastSpace > 0) {
            String type = trimmed.substring(0, lastSpace).trim();
            // Обрабатываем varargs
            type = type.replace("...", "[]");
            return type;
        }

        // Fallback: возвращаем как есть (для простых типов без имени)
        if (trimmed.matches("[A-Z][\\w<>\\[\\],\\s?]*")) {
            return trimmed;
        }

        return null;
    }

    /**
     * Извлекает тип с generics из строки.
     */
    private String extractTypeWithGenerics(String str) {
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '<' || c == '[') {
                depth++;
                result.append(c);
            } else if (c == '>' || c == ']') {
                depth--;
                result.append(c);
            } else if (Character.isWhitespace(c) && depth == 0) {
                // Конец типа
                break;
            } else if (c == '?' && depth == 0) {
                // Kotlin nullable suffix
                result.append(c);
                break;
            } else {
                result.append(c);
            }
        }
        return result.toString().trim();
    }

    /**
     * Нормализует сигнатуру для сравнения.
     * Удаляет пробелы, приводит к единому формату.
     */
    private String normalizeSignature(String signature) {
        if (signature == null) return "()";

        // Убираем пробелы вокруг скобок и запятых
        String normalized = signature.trim()
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*\\)\\s*", ")")
                .replaceAll("\\s*,\\s*", ",");

        // Убираем generics для упрощённого сравнения
        // List<String> -> List
        normalized = normalized.replaceAll("<[^>]*>", "");

        // Убираем квалификацию пакетов
        // java.lang.String -> String
        normalized = normalized.replaceAll("\\b[a-z][\\w.]*\\.([A-Z])", "$1");

        return normalized;
    }

    /**
     * Форматирует символ с сигнатурой для вывода пользователю.
     * Использует структурированные параметры из AST если доступны.
     */
    private String formatSymbolWithSignature(SymbolInfo symbol, Path path, RefactoringContext context) {
        StringBuilder sb = new StringBuilder();
        sb.append(symbol.kind()).append(" at ").append(symbol.location().formatShort());

        if (symbol.kind() == SymbolInfo.SymbolKind.METHOD ||
                symbol.kind() == SymbolInfo.SymbolKind.FUNCTION) {

            // Используем структурированные параметры из AST
            if (symbol.parameters() != null && !symbol.parameters().isEmpty()) {
                sb.append(" signature: ").append(symbol.normalizedParameterSignature());
            }
            // Fallback: извлекаем из строки
            else {
                try {
                    String content = Files.readString(path);
                    String[] lines = content.split("\n", -1);
                    int lineIdx = symbol.location().startLine() - 1;

                    if (lineIdx >= 0 && lineIdx < lines.length) {
                        String sig = extractMethodSignature(lines[lineIdx], symbol.name());
                        if (sig != null) {
                            sb.append(" signature: ").append(sig);
                        }
                    }
                } catch (IOException ignored) {}
            }
        }

        return sb.toString();
    }

    private boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!Character.isJavaIdentifierStart(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) return false;
        }
        return true;
    }

    private boolean isSimilar(String a, String b) {
        if (a.equalsIgnoreCase(b)) return true;
        if (a.toLowerCase().contains(b.toLowerCase())) return true;
        if (b.toLowerCase().contains(a.toLowerCase())) return true;
        // Levenshtein distance <= 3
        return levenshteinDistance(a.toLowerCase(), b.toLowerCase()) <= 3;
    }

    private int levenshteinDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                        dp[i - 1][j] + 1,
                        dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    /**
     * Локация для переименования.
     */
    private record RenameLocation(Path path, int line, int startColumn, int endColumn) {}

    /**
     * Локация с уровнем уверенности.
     */
    private record ConfidentLocation(RenameLocation location, Confidence confidence) {
        enum Confidence {
            /** Найдено семантическим анализом (tree-sitter) */
            SEMANTIC,
            /** Найдено только текстовым поиском (потенциально ложное срабатывание) */
            TEXT_ONLY
        }
    }

    /**
     * Выполняет текстовый поиск символа в файлах проекта.
     * Используется для гибридного режима, когда семантический анализ может пропустить вхождения.
     *
     * @param symbolName имя символа для поиска
     * @param originPath исходный файл (для определения языка)
     * @param scope область поиска
     * @param context контекст рефакторинга
     * @return список локаций, найденных текстовым поиском
     */
    private List<RenameLocation> findTextReferences(String symbolName, Path originPath,
                                                      String scope, RefactoringContext context) {
        List<RenameLocation> locations = new ArrayList<>();
        String langId = LanguageDetector.detect(originPath).orElse(null);
        if (langId == null) return locations;

        String fileExtension = LanguageDetector.getFileExtension(langId).orElse(null);
        if (fileExtension == null) return locations;

        Pattern wordPattern = Pattern.compile("\\b" + Pattern.quote(symbolName) + "\\b");

        Path searchRoot = switch (scope) {
            case "file" -> originPath.getParent();
            case "directory" -> originPath.getParent();
            case "project" -> context.getProjectRoot();
            default -> originPath.getParent();
        };

        try {
            int maxDepth = "project".equals(scope) ? 15 : 1;
            boolean singleFile = "file".equals(scope);

            Files.walkFileTree(searchRoot, EnumSet.noneOf(FileVisitOption.class), maxDepth,
                    new SimpleFileVisitor<>() {
                        int fileCount = 0;

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (fileCount >= MAX_FILES_TO_SCAN) {
                                return FileVisitResult.TERMINATE;
                            }
                            if (singleFile && !file.equals(originPath)) {
                                return FileVisitResult.CONTINUE;
                            }
                            String fileName = file.getFileName().toString();
                            if (!fileName.endsWith("." + fileExtension)) {
                                return FileVisitResult.CONTINUE;
                            }

                            try {
                                String content = Files.readString(file);
                                if (content.contains(symbolName)) {
                                    String[] lines = content.split("\n", -1);
                                    for (int i = 0; i < lines.length; i++) {
                                        Matcher m = wordPattern.matcher(lines[i]);
                                        while (m.find()) {
                                            locations.add(new RenameLocation(
                                                    file, i + 1, m.start() + 1, m.end()));
                                        }
                                    }
                                    fileCount++;
                                }
                            } catch (IOException ignored) {}
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            String name = dir.getFileName().toString();
                            if (name.startsWith(".") || name.equals("node_modules") ||
                                    name.equals("build") || name.equals("target") ||
                                    name.equals("dist") || name.equals("out")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException ignored) {}

        return locations;
    }

    /**
     * Объединяет семантические и текстовые результаты с маркировкой уверенности.
     *
     * @param semanticLocations локации от семантического анализа
     * @param textLocations локации от текстового поиска
     * @return объединенный список с уровнями уверенности
     */
    private List<ConfidentLocation> mergeWithConfidence(List<RenameLocation> semanticLocations,
                                                         List<RenameLocation> textLocations) {
        // Создаем Set для быстрого поиска семантических локаций
        Set<String> semanticKeys = semanticLocations.stream()
                .map(loc -> loc.path + ":" + loc.line + ":" + loc.startColumn)
                .collect(Collectors.toSet());

        List<ConfidentLocation> result = new ArrayList<>();

        // Добавляем семантические с высокой уверенностью
        for (RenameLocation loc : semanticLocations) {
            result.add(new ConfidentLocation(loc, ConfidentLocation.Confidence.SEMANTIC));
        }

        // Добавляем текстовые, которых нет в семантических
        for (RenameLocation loc : textLocations) {
            String key = loc.path + ":" + loc.line + ":" + loc.startColumn;
            if (!semanticKeys.contains(key)) {
                result.add(new ConfidentLocation(loc, ConfidentLocation.Confidence.TEXT_ONLY));
            }
        }

        return result;
    }

    /**
     * Выполняет гибридное переименование.
     * Сначала семантический анализ, затем дополнение текстовым поиском.
     *
     * @param params параметры операции
     * @param context контекст рефакторинга
     * @param preview режим предпросмотра
     * @return результат рефакторинга
     */
    public RefactoringResult executeHybrid(JsonNode params, RefactoringContext context, boolean preview)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String newName = params.get("newName").asText();
        String scope = params.has("scope") ? params.get("scope").asText() : "project";
        boolean includeTextOnly = params.has("includeTextMatches")
                && params.get("includeTextMatches").asBoolean(false);

        // Находим символ
        SymbolInfo symbol = findSymbol(params, path, context);
        if (symbol == null) {
            return handleSymbolNotFound(params, path, context);
        }

        String oldName = symbol.name();

        // Находим семантические ссылки
        List<RenameLocation> semanticRefs = findAllReferences(symbol, path, scope, context);

        // Находим текстовые ссылки
        List<RenameLocation> textRefs = findTextReferences(oldName, path, scope, context);

        // Объединяем с маркировкой уверенности
        List<ConfidentLocation> allLocations = mergeWithConfidence(semanticRefs, textRefs);

        if (allLocations.isEmpty()) {
            return RefactoringResult.noChanges("rename",
                    "No references found for symbol '" + oldName + "'");
        }

        // Считаем статистику
        long semanticCount = allLocations.stream()
                .filter(cl -> cl.confidence == ConfidentLocation.Confidence.SEMANTIC)
                .count();
        long textOnlyCount = allLocations.stream()
                .filter(cl -> cl.confidence == ConfidentLocation.Confidence.TEXT_ONLY)
                .count();

        // Фильтруем по уровню уверенности
        List<RenameLocation> locationsToApply;
        if (includeTextOnly) {
            locationsToApply = allLocations.stream()
                    .map(ConfidentLocation::location)
                    .distinct()
                    .sorted(Comparator
                            .comparing((RenameLocation l) -> l.path.toString())
                            .thenComparing(RenameLocation::line)
                            .thenComparing(RenameLocation::startColumn))
                    .toList();
        } else {
            locationsToApply = allLocations.stream()
                    .filter(cl -> cl.confidence == ConfidentLocation.Confidence.SEMANTIC)
                    .map(ConfidentLocation::location)
                    .distinct()
                    .sorted(Comparator
                            .comparing((RenameLocation l) -> l.path.toString())
                            .thenComparing(RenameLocation::line)
                            .thenComparing(RenameLocation::startColumn))
                    .toList();
        }

        if (preview) {
            // Генерируем preview с информацией о гибридном режиме
            List<RefactoringResult.FileChange> changes = generatePreview(
                    locationsToApply, oldName, newName, context);

            List<String> suggestions = new ArrayList<>();
            if (textOnlyCount > 0 && !includeTextOnly) {
                suggestions.add(String.format(
                        "Found %d additional text-only matches. Use 'includeTextMatches: true' to include them.",
                        textOnlyCount));
                // Добавляем информацию о файлах с текстовыми совпадениями
                Map<Path, Long> textOnlyByFile = allLocations.stream()
                        .filter(cl -> cl.confidence == ConfidentLocation.Confidence.TEXT_ONLY)
                        .collect(Collectors.groupingBy(cl -> cl.location.path, Collectors.counting()));
                for (Map.Entry<Path, Long> entry : textOnlyByFile.entrySet()) {
                    suggestions.add(String.format("  - %s: %d uncertain match(es)",
                            entry.getKey().getFileName(), entry.getValue()));
                }
            }

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.PREVIEW)
                    .action("rename")
                    .summary(String.format("[HYBRID] Would rename '%s' to '%s': %d semantic, %d text-only matches",
                            oldName, newName, semanticCount, textOnlyCount))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(locationsToApply.size())
                    .suggestions(suggestions)
                    .build();
        }

        // Выполняем изменения
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Rename '" + oldName + "' to '" + newName + "'";
        context.beginTransaction(instruction);

        try {
            List<RefactoringResult.FileChange> changes = applyRename(
                    locationsToApply, oldName, newName, context);

            String txId = context.commitTransaction();

            int totalOccurrences = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            List<String> suggestions = new ArrayList<>();
            if (textOnlyCount > 0 && !includeTextOnly) {
                suggestions.add(String.format(
                        "WARNING: %d text-only matches were NOT renamed. " +
                        "Re-run with 'includeTextMatches: true' if needed.", textOnlyCount));
            }

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("rename")
                    .summary(String.format("[HYBRID] Renamed '%s' to '%s' in %d file(s) (%d occurrence(s), %d skipped)",
                            oldName, newName, changes.size(), totalOccurrences,
                            includeTextOnly ? 0 : textOnlyCount))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalOccurrences)
                    .transactionId(txId)
                    .suggestions(suggestions)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Rename failed: " + e.getMessage(), e);
        }
    }
}
