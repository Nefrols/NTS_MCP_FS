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
package ru.nts.tools.mcp.tools.navigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.*;
import ru.nts.tools.mcp.core.treesitter.*;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.CRC32C;

/**
 * MCP Tool для навигации по коду с использованием tree-sitter.
 * <p>
 * Функции:
 * - Go to Definition: переход к определению символа
 * - Find References: поиск всех использований символа
 * - Hover: информация о символе
 * - List Symbols: все символы в файле
 * <p>
 * Интеграция с LAT: результаты содержат токены доступа для редактирования.
 */
public class CodeNavigateTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();
    private final SymbolResolver resolver = SymbolResolver.getInstance();

    @Override
    public String getName() {
        return "nts_code_navigate";
    }

    @Override
    public String getDescription() {
        return """
            LSP-like code navigation (tree-sitter).

            ACTIONS:
            - definition: Go to symbol definition
            - references: Find all usages (scope: file|directory|project)
            - hover: Symbol info (type, signature, docs)
            - symbols: List all symbols in file

            INPUT: path + (line/column OR symbol name)

            LANGUAGES: Java, Kotlin, JS/TS/TSX, Python, Go, Rust, C/C++, C#, PHP, HTML

            TIP: Use 'references' before nts_code_refactor to preview affected locations.
            Returns TOKENs for nts_edit_file.
            """;
    }

    @Override
    public String getCategory() {
        return "navigation";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("action").put("type", "string").put("description",
                "Navigation action: 'definition' (go to), 'references' (find all), 'hover' (info), 'symbols' (outline).");

        props.putObject("path").put("type", "string").put("description",
                "File path (relative or absolute). REQUIRED.");

        props.putObject("line").put("type", "integer").put("description",
                "Line number (1-based). Required for position-based search. Alternative to 'symbol'.");

        props.putObject("column").put("type", "integer").put("description",
                "Column number (1-based). Default: 1. Used with 'line' for exact positioning.");

        props.putObject("symbol").put("type", "string").put("description",
                "Symbol name to search. Alternative to line/column. " +
                "Use when you know the symbol name but not its exact position.");

        props.putObject("scope").put("type", "string").put("description",
                "Search scope for references: 'file', 'directory', 'project'. Default: 'project'.");

        props.putObject("includeDeclaration").put("type", "boolean").put("description",
                "Include declaration in references result. Default: true.");

        var required = schema.putArray("required");
        required.add("action");
        required.add("path");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String action = params.path("action").asText("");
        if (action.isEmpty()) {
            throw new IllegalArgumentException("Parameter 'action' is required.");
        }

        String pathStr = params.path("path").asText("");
        if (pathStr.isEmpty()) {
            throw new IllegalArgumentException("Parameter 'path' is required.");
        }

        Path path = PathSanitizer.sanitize(pathStr, true);
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("File not found: " + pathStr);
        }

        // Проверяем поддержку языка
        Optional<String> langId = LanguageDetector.detect(path);
        if (langId.isEmpty()) {
            throw new IllegalArgumentException("Unsupported file type: " + path.getFileName() +
                    ". Supported: " + String.join(", ", LanguageDetector.getSupportedLanguages()));
        }

        return switch (action) {
            case "definition" -> executeDefinition(path, params);
            case "references" -> executeReferences(path, params);
            case "hover" -> executeHover(path, params);
            case "symbols" -> executeSymbols(path);
            default -> throw new IllegalArgumentException(
                    "Unknown action: " + action + ". Valid: definition, references, hover, symbols");
        };
    }

    /**
     * Go to Definition: находит определение символа.
     * Поддерживает поиск по позиции (line/column) или по имени символа.
     */
    private JsonNode executeDefinition(Path path, JsonNode params) throws IOException {
        int line = params.path("line").asInt(0);
        int column = params.path("column").asInt(1);
        String symbolName = params.path("symbol").asText(null);

        Optional<SymbolInfo> definition;

        if (symbolName != null && !symbolName.isEmpty()) {
            // Поиск по имени символа
            definition = resolver.findDefinitionByName(path, symbolName);
        } else if (line > 0) {
            // Поиск по позиции
            definition = resolver.findDefinition(path, line, column);
        } else {
            throw new IllegalArgumentException(
                    "Either 'line' (with optional 'column') or 'symbol' parameter is required for 'definition' action.");
        }

        if (definition.isEmpty()) {
            String searchDesc = (symbolName != null && !symbolName.isEmpty())
                    ? "symbol '" + symbolName + "'"
                    : path.getFileName() + ":" + line + ":" + column;
            return createTextResponse("No definition found for " + searchDesc);
        }

        SymbolInfo def = definition.get();
        Location loc = def.location();

        // Регистрируем доступ и получаем токен
        String token = registerAccessForLocation(loc);

        // Читаем контекст
        String context = readContext(loc, 2);

        StringBuilder sb = new StringBuilder();
        sb.append("**Definition found:**\n\n");
        sb.append("- **Symbol**: `").append(def.name()).append("`\n");
        sb.append("- **Kind**: ").append(def.kind()).append("\n");
        if (def.type() != null) {
            sb.append("- **Type**: `").append(def.type()).append("`\n");
        }
        if (def.signature() != null) {
            sb.append("- **Signature**: `").append(def.signature()).append("`\n");
        }
        if (def.parentName() != null) {
            sb.append("- **Parent**: `").append(def.parentName()).append("`\n");
        }
        sb.append("- **Location**: `").append(loc.format()).append("`\n");
        sb.append("- **TOKEN**: `").append(token).append("`\n");

        if (def.documentation() != null && !def.documentation().isEmpty()) {
            sb.append("\n**Documentation:**\n```\n").append(def.documentation()).append("\n```\n");
        }

        sb.append("\n**Context:**\n```").append(getLanguageForCodeBlock(loc.path())).append("\n");
        sb.append(context);
        sb.append("\n```");

        return createTextResponse(sb.toString());
    }

    /**
     * Find References: находит все использования символа.
     * Поддерживает поиск по позиции (line/column) или по имени символа.
     */
    private JsonNode executeReferences(Path path, JsonNode params) throws IOException {
        int line = params.path("line").asInt(0);
        int column = params.path("column").asInt(1);
        String scope = params.path("scope").asText("project");
        boolean includeDeclaration = params.path("includeDeclaration").asBoolean(true);
        String symbolName = params.path("symbol").asText(null);

        List<Location> references;

        if (symbolName != null && !symbolName.isEmpty()) {
            // Поиск по имени символа
            references = resolver.findReferencesByName(path, symbolName, scope, includeDeclaration);
        } else if (line > 0) {
            // Поиск по позиции
            references = resolver.findReferences(path, line, column, scope, includeDeclaration);
        } else {
            throw new IllegalArgumentException(
                    "Either 'line' (with optional 'column') or 'symbol' parameter is required for 'references' action.");
        }

        if (references.isEmpty()) {
            String searchDesc = (symbolName != null && !symbolName.isEmpty())
                    ? "symbol '" + symbolName + "'"
                    : path.getFileName() + ":" + line + ":" + column;
            return createTextResponse("No references found for " + searchDesc);
        }

        // Группируем по файлам
        Map<Path, List<Location>> byFile = references.stream()
                .collect(Collectors.groupingBy(Location::path, LinkedHashMap::new, Collectors.toList()));

        StringBuilder sb = new StringBuilder();
        sb.append("**Found ").append(references.size()).append(" reference(s):**\n\n");

        for (var entry : byFile.entrySet()) {
            Path file = entry.getKey();
            List<Location> locs = entry.getValue();

            sb.append("**").append(getRelativePath(file)).append("** (").append(locs.size()).append("):\n");

            // Группируем близкие строки для оптимизации токенов
            List<int[]> ranges = groupLocationsIntoRanges(locs);

            for (int[] range : ranges) {
                String token = registerAccessForRange(file, range[0], range[1]);

                sb.append("  - Lines ").append(range[0]).append("-").append(range[1]);
                sb.append(" | TOKEN: `").append(token).append("`\n");

                // Показываем строки
                List<String> lines = readLines(file, range[0], range[1]);
                for (int i = 0; i < lines.size(); i++) {
                    int lineNum = range[0] + i;
                    String lineContent = lines.get(i);
                    // Отмечаем строки с ссылками
                    boolean hasRef = locs.stream().anyMatch(l -> l.startLine() == lineNum);
                    String marker = hasRef ? ">" : " ";
                    sb.append("    ").append(marker).append(String.format("%4d", lineNum)).append(": ");
                    sb.append(truncate(lineContent, 80)).append("\n");
                }
            }
            sb.append("\n");
        }

        return createTextResponse(sb.toString());
    }

    /**
     * Hover: информация о символе.
     * Поддерживает поиск по позиции (line/column) или по имени символа.
     */
    private JsonNode executeHover(Path path, JsonNode params) throws IOException {
        int line = params.path("line").asInt(0);
        int column = params.path("column").asInt(1);
        String symbolName = params.path("symbol").asText(null);

        Optional<SymbolInfo> symbol;

        if (symbolName != null && !symbolName.isEmpty()) {
            // Поиск по имени символа
            symbol = resolver.hoverByName(path, symbolName);
        } else if (line > 0) {
            // Поиск по позиции
            symbol = resolver.hover(path, line, column);
        } else {
            throw new IllegalArgumentException(
                    "Either 'line' (with optional 'column') or 'symbol' parameter is required for 'hover' action.");
        }

        if (symbol.isEmpty()) {
            String searchDesc = (symbolName != null && !symbolName.isEmpty())
                    ? "symbol '" + symbolName + "'"
                    : path.getFileName() + ":" + line + ":" + column;
            return createTextResponse("No symbol found for " + searchDesc);
        }

        SymbolInfo sym = symbol.get();

        StringBuilder sb = new StringBuilder();
        sb.append("**").append(sym.name()).append("** (").append(sym.kind()).append(")\n\n");

        if (sym.signature() != null) {
            sb.append("```").append(getLanguageForCodeBlock(path)).append("\n");
            sb.append(sym.signature()).append("\n");
            sb.append("```\n\n");
        }

        if (sym.type() != null) {
            sb.append("**Type**: `").append(sym.type()).append("`\n");
        }

        if (sym.parentName() != null) {
            sb.append("**Defined in**: `").append(sym.parentName()).append("`\n");
        }

        sb.append("**Location**: `").append(sym.location().format()).append("`\n");

        if (sym.documentation() != null && !sym.documentation().isEmpty()) {
            sb.append("\n**Documentation:**\n").append(sym.documentation()).append("\n");
        }

        return createTextResponse(sb.toString());
    }

    /**
     * List Symbols: все символы в файле.
     */
    private JsonNode executeSymbols(Path path) throws IOException {
        List<SymbolInfo> symbols = resolver.listSymbols(path);

        if (symbols.isEmpty()) {
            return createTextResponse("No symbols found in " + path.getFileName());
        }

        // Регистрируем доступ ко всему файлу
        String content = Files.readString(path);
        int lineCount = (int) content.lines().count();
        String token = registerAccessForRange(path, 1, lineCount);

        StringBuilder sb = new StringBuilder();
        sb.append("**Symbols in ").append(path.getFileName()).append("** (")
                .append(symbols.size()).append(" total)\n");
        sb.append("TOKEN: `").append(token).append("`\n\n");

        // Группируем по типу
        Map<SymbolKind, List<SymbolInfo>> byKind = symbols.stream()
                .collect(Collectors.groupingBy(SymbolInfo::kind, LinkedHashMap::new, Collectors.toList()));

        for (var entry : byKind.entrySet()) {
            SymbolKind kind = entry.getKey();
            List<SymbolInfo> syms = entry.getValue();

            sb.append("**").append(kind).append("** (").append(syms.size()).append("):\n");

            for (SymbolInfo sym : syms) {
                String indent = sym.parentName() != null ? "  " : "";
                sb.append(indent).append("- `").append(sym.name()).append("`");
                if (sym.type() != null) {
                    sb.append(": ").append(sym.type());
                }
                sb.append(" (line ").append(sym.location().startLine()).append(")\n");
            }
            sb.append("\n");
        }

        return createTextResponse(sb.toString());
    }

    // ===================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====================

    /**
     * Регистрирует доступ к локации и возвращает токен.
     */
    private String registerAccessForLocation(Location loc) throws IOException {
        return registerAccessForRange(loc.path(), loc.startLine(), loc.endLine());
    }

    /**
     * Регистрирует доступ к диапазону строк и возвращает токен.
     */
    private String registerAccessForRange(Path path, int startLine, int endLine) throws IOException {
        String content = Files.readString(path);
        int lineCount = (int) content.lines().count();
        long crc = calculateCrc(content);

        LineAccessToken token = LineAccessTracker.registerAccess(
                path, startLine, endLine, crc, lineCount);

        return token.encode();
    }

    /**
     * Вычисляет CRC32C.
     */
    private long calculateCrc(String content) {
        CRC32C crc = new CRC32C();
        crc.update(content.getBytes());
        return crc.getValue();
    }

    /**
     * Читает контекст вокруг локации.
     */
    private String readContext(Location loc, int contextLines) throws IOException {
        int start = Math.max(1, loc.startLine() - contextLines);
        int end = loc.endLine() + contextLines;

        List<String> lines = readLines(loc.path(), start, end);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            int lineNum = start + i;
            String marker = (lineNum >= loc.startLine() && lineNum <= loc.endLine()) ? ">" : " ";
            sb.append(marker).append(String.format("%4d", lineNum)).append("| ").append(lines.get(i)).append("\n");
        }

        return sb.toString().stripTrailing();
    }

    /**
     * Читает строки из файла.
     */
    private List<String> readLines(Path path, int startLine, int endLine) throws IOException {
        List<String> allLines = Files.readAllLines(path);
        int start = Math.max(0, startLine - 1);
        int end = Math.min(allLines.size(), endLine);

        if (start >= allLines.size()) {
            return Collections.emptyList();
        }

        return allLines.subList(start, end);
    }

    /**
     * Группирует локации в диапазоны для оптимизации токенов.
     */
    private List<int[]> groupLocationsIntoRanges(List<Location> locations) {
        if (locations.isEmpty()) {
            return Collections.emptyList();
        }

        List<int[]> ranges = new ArrayList<>();
        int[] current = null;

        for (Location loc : locations) {
            if (current == null) {
                current = new int[]{loc.startLine(), loc.endLine()};
            } else if (loc.startLine() <= current[1] + 3) {
                // Расширяем текущий диапазон
                current[1] = Math.max(current[1], loc.endLine());
            } else {
                ranges.add(current);
                current = new int[]{loc.startLine(), loc.endLine()};
            }
        }

        if (current != null) {
            ranges.add(current);
        }

        return ranges;
    }

    /**
     * Получает относительный путь от корня проекта.
     */
    private String getRelativePath(Path path) {
        Path projectRoot = PathSanitizer.getRoot();
        try {
            return projectRoot.relativize(path).toString().replace('\\', '/');
        } catch (Exception e) {
            return path.getFileName().toString();
        }
    }

    /**
     * Определяет язык для code block.
     */
    private String getLanguageForCodeBlock(Path path) {
        return LanguageDetector.detect(path).orElse("");
    }

    /**
     * Обрезает строку до указанной длины.
     */
    private String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen - 3) + "...";
    }

    /**
     * Создает текстовый ответ.
     */
    private JsonNode createTextResponse(String text) {
        ObjectNode result = mapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode textNode = content.addObject();
        textNode.put("type", "text");
        textNode.put("text", text);
        return result;
    }
}
