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
 * Операция перемещения символа.
 * Перемещает методы, классы, функции между файлами с обновлением импортов и ссылок.
 */
public class MoveOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "move";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required");
        }
        if (!params.has("symbol") && !params.has("line")) {
            throw new IllegalArgumentException("Either 'symbol' or 'line' is required");
        }
        if (!params.has("targetPath") && !params.has("targetClass")) {
            throw new IllegalArgumentException("Either 'targetPath' or 'targetClass' is required");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path sourcePath = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        String langId = LanguageDetector.detect(sourcePath)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        // Находим символ
        SymbolInfo symbol = findSymbol(params, sourcePath, context);
        if (symbol == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("symbol") ? params.get("symbol").asText() : "symbol",
                    sourcePath.toString());
        }

        // Определяем целевой файл
        Path targetPath;
        String targetClass = null;
        if (params.has("targetPath")) {
            targetPath = resolvePath(params.get("targetPath").asText());
        } else {
            targetClass = params.get("targetClass").asText();
            targetPath = findClassFile(targetClass, sourcePath, context);
            if (targetPath == null) {
                throw new RefactoringException("Target class not found: " + targetClass);
            }
        }

        // Извлекаем код символа
        String symbolCode = extractSymbolCode(symbol, sourcePath);

        // Находим все ссылки на символ
        List<Location> references = findReferences(symbol, sourcePath, scope, context);

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Move '" + symbol.name() + "' to " + targetPath.getFileName();
        context.beginTransaction(instruction);

        try {
            List<RefactoringResult.FileChange> changes = new ArrayList<>();

            // 1. Удаляем символ из исходного файла
            RefactoringResult.FileChange removeChange = removeSymbol(symbol, sourcePath, context);
            changes.add(removeChange);

            // 2. Добавляем символ в целевой файл
            int insertPosition = params.has("position")
                    ? params.get("position").asInt() : -1;
            RefactoringResult.FileChange addChange = addSymbol(
                    symbolCode, targetPath, targetClass, insertPosition, langId, context);
            changes.add(addChange);

            // 3. Обновляем импорты и ссылки
            String sourcePackage = extractPackage(sourcePath, langId, context);
            String targetPackage = extractPackage(targetPath, langId, context);

            if (!sourcePackage.equals(targetPackage)) {
                List<RefactoringResult.FileChange> importChanges = updateImports(
                        symbol, sourcePath, targetPath, references, langId, context);
                changes.addAll(importChanges);
            }

            // 4. Обновляем квалифицированные ссылки
            if (symbol.kind() == SymbolKind.CLASS) {
                List<RefactoringResult.FileChange> refChanges = updateQualifiedReferences(
                        symbol, sourcePackage, targetPackage, references, langId, context);
                changes.addAll(refChanges);
            }

            String txId = context.commitTransaction();

            int totalChanges = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("move")
                    .summary(String.format("Moved '%s' from %s to %s",
                            symbol.name(), sourcePath.getFileName(), targetPath.getFileName()))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalChanges)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Move failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path sourcePath = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        String langId = LanguageDetector.detect(sourcePath)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        SymbolInfo symbol = findSymbol(params, sourcePath, context);
        if (symbol == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("symbol") ? params.get("symbol").asText() : "symbol",
                    sourcePath.toString());
        }

        Path targetPath;
        if (params.has("targetPath")) {
            targetPath = resolvePath(params.get("targetPath").asText());
        } else {
            String targetClass = params.get("targetClass").asText();
            targetPath = findClassFile(targetClass, sourcePath, context);
            if (targetPath == null) {
                throw new RefactoringException("Target class not found: " + targetClass);
            }
        }

        String symbolCode = extractSymbolCode(symbol, sourcePath);
        List<Location> references = findReferences(symbol, sourcePath, scope, context);

        // Генерируем preview
        StringBuilder diff = new StringBuilder();
        diff.append("=== Move Operation ===\n");
        diff.append("Symbol: ").append(symbol.name()).append(" (").append(symbol.kind()).append(")\n");
        diff.append("From: ").append(sourcePath.getFileName()).append("\n");
        diff.append("To: ").append(targetPath.getFileName()).append("\n");
        diff.append("\n=== Code to Move ===\n");
        for (String line : symbolCode.split("\n")) {
            diff.append("  ").append(line).append("\n");
        }
        diff.append("\n=== References (").append(references.size()).append(") ===\n");
        for (Location ref : references) {
            diff.append("  ").append(ref.path().getFileName())
                    .append(":").append(ref.startLine()).append("\n");
        }

        return RefactoringResult.preview("move", List.of(
                new RefactoringResult.FileChange(
                        sourcePath, 1,
                        List.of(new RefactoringResult.ChangeDetail(
                                symbol.location().startLine(), 0,
                                symbolCode.trim().split("\n")[0] + "...",
                                "[MOVED to " + targetPath.getFileName() + "]")),
                        null, diff.toString())
        ));
    }

    private SymbolInfo findSymbol(JsonNode params, Path path, RefactoringContext context)
            throws RefactoringException {
        try {
            if (params.has("symbol")) {
                String symbolName = params.get("symbol").asText();
                List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
                return symbols.stream()
                        .filter(s -> s.name().equals(symbolName))
                        .filter(s -> canMove(s.kind()))
                        .findFirst()
                        .orElse(null);
            } else if (params.has("line")) {
                int line = params.get("line").asInt();
                int column = params.has("column") ? params.get("column").asInt() : 1;
                return context.getSymbolResolver().hover(path, line, column)
                        .filter(s -> canMove(s.kind()))
                        .orElse(null);
            }
            return null;
        } catch (IOException e) {
            throw new RefactoringException("Failed to find symbol: " + e.getMessage(), e);
        }
    }

    private boolean canMove(SymbolKind kind) {
        return kind == SymbolKind.METHOD ||
                kind == SymbolKind.FUNCTION ||
                kind == SymbolKind.CLASS ||
                kind == SymbolKind.INTERFACE ||
                kind == SymbolKind.FIELD ||
                kind == SymbolKind.CONSTANT;
    }

    /**
     * Извлекает код символа из файла.
     */
    private String extractSymbolCode(SymbolInfo symbol, Path path) throws RefactoringException {
        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);

            int startLine = symbol.location().startLine() - 1;
            int endLine = symbol.location().endLine() - 1;

            if (startLine < 0 || endLine >= lines.length) {
                throw new RefactoringException("Symbol location out of range");
            }

            StringBuilder code = new StringBuilder();
            for (int i = startLine; i <= endLine; i++) {
                code.append(lines[i]).append("\n");
            }

            return code.toString();

        } catch (IOException e) {
            throw new RefactoringException("Failed to extract symbol code: " + e.getMessage(), e);
        }
    }

    /**
     * Находит файл, содержащий указанный класс.
     */
    private Path findClassFile(String className, Path nearPath, RefactoringContext context) {
        try {
            // Ищем в той же директории и поддиректориях
            Path dir = nearPath.getParent();
            if (dir == null) {
                dir = PathSanitizer.getRoot();
            }

            return Files.walk(dir, 5)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java") ||
                            p.getFileName().toString().endsWith(".kt") ||
                            p.getFileName().toString().endsWith(".py") ||
                            p.getFileName().toString().endsWith(".ts") ||
                            p.getFileName().toString().endsWith(".js"))
                    .filter(p -> {
                        try {
                            List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(p);
                            return symbols.stream()
                                    .anyMatch(s -> s.name().equals(className) &&
                                            s.kind() == SymbolKind.CLASS);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);

        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Находит все ссылки на символ.
     */
    private List<Location> findReferences(SymbolInfo symbol, Path path, String scope,
                                           RefactoringContext context) throws RefactoringException {
        try {
            return context.getSymbolResolver().findReferences(
                    path, symbol.location().startLine(), symbol.location().startColumn(),
                    scope, true);
        } catch (IOException e) {
            throw new RefactoringException("Failed to find references: " + e.getMessage(), e);
        }
    }

    /**
     * Удаляет символ из исходного файла.
     */
    private RefactoringResult.FileChange removeSymbol(SymbolInfo symbol, Path path,
                                                        RefactoringContext context)
            throws RefactoringException {
        try {
            context.backupFile(path);

            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            int startLine = symbol.location().startLine() - 1;
            int endLine = symbol.location().endLine() - 1;

            StringBuilder removed = new StringBuilder();
            for (int i = endLine; i >= startLine && i < lines.size(); i--) {
                removed.insert(0, lines.remove(i) + "\n");
            }

            // Удаляем пустые строки, оставшиеся после удаления
            while (startLine < lines.size() && lines.get(startLine).trim().isEmpty()) {
                lines.remove(startLine);
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
                            removed.toString().trim().split("\n")[0] + "...",
                            "[REMOVED]")),
                    token.encode(), null, crc32c, lineCount);

        } catch (IOException e) {
            throw new RefactoringException("Failed to remove symbol: " + e.getMessage(), e);
        }
    }

    /**
     * Добавляет символ в целевой файл.
     */
    private RefactoringResult.FileChange addSymbol(String code, Path targetPath,
                                                     String targetClass, int position,
                                                     String langId, RefactoringContext context)
            throws RefactoringException {
        try {
            context.backupFile(targetPath);

            String content = Files.readString(targetPath);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            int insertLine;
            if (position > 0) {
                insertLine = position - 1;
            } else if (targetClass != null) {
                // Вставляем в конец целевого класса
                insertLine = findClassEnd(lines, targetClass, langId);
            } else {
                // Вставляем в конец файла
                insertLine = lines.size();
            }

            // Добавляем отступ если вставляем внутрь класса
            String indentedCode = code;
            if (targetClass != null) {
                indentedCode = indentCode(code, "    ");
            }

            List<String> codeLines = Arrays.asList(indentedCode.split("\n"));
            lines.addAll(insertLine, codeLines);

            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(targetPath, newContent, StandardCharsets.UTF_8);
            context.getTreeManager().invalidateCache(targetPath);

            // Вычисляем метаданные и регистрируем токен
            int lineCount = lines.size();
            long crc32c = LineAccessToken.computeRangeCrc(newContent);

            // Обновляем снапшот сессии для синхронизации с batch tools
            SessionContext.currentOrDefault().externalChanges()
                .updateSnapshot(targetPath, newContent, crc32c, StandardCharsets.UTF_8, lineCount);

            LineAccessToken token = LineAccessTracker.registerAccess(targetPath, 1, lineCount, newContent, lineCount);

            return new RefactoringResult.FileChange(
                    targetPath, 1,
                    List.of(new RefactoringResult.ChangeDetail(
                            insertLine + 1, 0,
                            "",
                            code.trim().split("\n")[0] + "...")),
                    token.encode(), null, crc32c, lineCount);

        } catch (IOException e) {
            throw new RefactoringException("Failed to add symbol: " + e.getMessage(), e);
        }
    }

    /**
     * Находит конец класса для вставки.
     */
    private int findClassEnd(List<String> lines, String className, String langId) {
        int braceCount = 0;
        boolean inClass = false;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);

            if (!inClass && line.contains("class " + className)) {
                inClass = true;
            }

            if (inClass) {
                for (char c : line.toCharArray()) {
                    if (c == '{') braceCount++;
                    if (c == '}') braceCount--;
                }

                if (inClass && braceCount == 0 && line.contains("}")) {
                    return i; // Вставляем перед закрывающей скобкой
                }
            }
        }

        return lines.size();
    }

    /**
     * Добавляет отступ к коду.
     */
    private String indentCode(String code, String indent) {
        return Arrays.stream(code.split("\n"))
                .map(line -> indent + line)
                .collect(Collectors.joining("\n"));
    }

    /**
     * Извлекает имя пакета из файла.
     * Использует AST для точного извлечения.
     */
    private String extractPackage(Path path, String langId, RefactoringContext context) {
        try {
            // Пробуем AST-based извлечение
            TreeSitterManager.ParseResult parseResult = context.getParseResult(path);
            TSNode root = parseResult.tree().getRootNode();
            String content = parseResult.content();

            String packageName = SymbolExtractorUtils.extractPackageName(root, content, langId);
            if (!packageName.isEmpty()) {
                return packageName;
            }

            // Fallback для Python
            if (langId.equals("python")) {
                return path.getParent().toString().replace("/", ".").replace("\\", ".");
            }

            // Fallback на regex
            return extractPackageRegex(content, langId);

        } catch (Exception e) {
            // Fallback на regex
            try {
                String content = Files.readString(path);
                return extractPackageRegex(content, langId);
            } catch (IOException ex) {
                return "";
            }
        }
    }

    /**
     * Fallback regex-based извлечение пакета.
     */
    private String extractPackageRegex(String content, String langId) {
        if (langId.equals("java") || langId.equals("kotlin")) {
            Pattern pattern = Pattern.compile("package\\s+([\\w.]+)");
            Matcher matcher = pattern.matcher(content);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "";
    }

    /**
     * Обновляет импорты во всех файлах со ссылками.
     */
    private List<RefactoringResult.FileChange> updateImports(
            SymbolInfo symbol, Path sourcePath, Path targetPath,
            List<Location> references, String langId, RefactoringContext context)
            throws RefactoringException {

        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        String sourcePackage = extractPackage(sourcePath, langId, context);
        String targetPackage = extractPackage(targetPath, langId, context);

        // Группируем ссылки по файлам
        Set<Path> affectedFiles = references.stream()
                .map(Location::path)
                .collect(Collectors.toSet());

        for (Path file : affectedFiles) {
            if (file.equals(sourcePath) || file.equals(targetPath)) {
                continue; // Эти файлы обрабатываются отдельно
            }

            try {
                context.backupFile(file);

                String content = Files.readString(file);
                String oldImport = buildImportStatement(sourcePackage, symbol.name(), langId);
                String newImport = buildImportStatement(targetPackage, symbol.name(), langId);

                if (content.contains(oldImport)) {
                    String newContent = content.replace(oldImport, newImport);
                    FileUtils.safeWrite(file, newContent, StandardCharsets.UTF_8);
                    context.getTreeManager().invalidateCache(file);

                    changes.add(new RefactoringResult.FileChange(
                            file, 1,
                            List.of(new RefactoringResult.ChangeDetail(
                                    1, 0, oldImport.trim(), newImport.trim())),
                            null, null));
                }

            } catch (IOException e) {
                // Continue with other files
            }
        }

        return changes;
    }

    /**
     * Строит оператор импорта.
     */
    private String buildImportStatement(String packageName, String symbolName, String langId) {
        if (langId.equals("java")) {
            return "import " + packageName + "." + symbolName + ";";
        } else if (langId.equals("kotlin")) {
            return "import " + packageName + "." + symbolName;
        } else if (langId.equals("python")) {
            return "from " + packageName + " import " + symbolName;
        } else if (langId.equals("typescript") || langId.equals("javascript")) {
            return "import { " + symbolName + " } from '" + packageName + "';";
        }
        return "";
    }

    /**
     * Обновляет квалифицированные ссылки (полные имена классов).
     */
    private List<RefactoringResult.FileChange> updateQualifiedReferences(
            SymbolInfo symbol, String sourcePackage, String targetPackage,
            List<Location> references, String langId, RefactoringContext context)
            throws RefactoringException {

        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        String oldQualified = sourcePackage + "." + symbol.name();
        String newQualified = targetPackage + "." + symbol.name();

        Map<Path, List<Location>> byFile = references.stream()
                .collect(Collectors.groupingBy(Location::path));

        for (Map.Entry<Path, List<Location>> entry : byFile.entrySet()) {
            Path file = entry.getKey();

            try {
                String content = Files.readString(file);

                if (content.contains(oldQualified)) {
                    context.backupFile(file);
                    String newContent = content.replace(oldQualified, newQualified);
                    FileUtils.safeWrite(file, newContent, StandardCharsets.UTF_8);
                    context.getTreeManager().invalidateCache(file);

                    int count = countOccurrences(content, oldQualified);
                    changes.add(new RefactoringResult.FileChange(
                            file, count,
                            List.of(new RefactoringResult.ChangeDetail(
                                    1, 0, oldQualified, newQualified)),
                            null, null));
                }

            } catch (IOException e) {
                // Continue with other files
            }
        }

        return changes;
    }

    private int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
