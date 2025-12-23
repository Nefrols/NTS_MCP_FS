// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.GitUtils;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Инструмент для визуализации структуры проекта в виде дерева.
 * Помогает LLM быстро понять архитектуру и расположение файлов без необходимости
 * перечислять содержимое каждой директории отдельно.
 * 
 * Особенности:
 * 1. Визуальный вывод: Генерирует ASCII-дерево.
 * 2. Контроль глубины: Позволяет ограничить уровень вложенности.
 * 3. Фильтрация: Игнорирует защищенные пути и поддерживает пользовательские паттерны исключений.
 * 4. Безопасность: Использует PathSanitizer.
 */
public class ProjectStructureTool implements McpTool {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String getName() {
        return "nts_project_structure";
    }

    @Override
    public String getDescription() {
        return "Generates an ASCII tree view of the project structure. Useful for architectural overview.";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("path").put("type", "string").put("description", "Root directory for the tree (default: current root).");
        props.putObject("maxDepth").put("type", "integer").put("description", "Maximum recursion depth (default: 3).");

        props.putObject("autoIgnore").put("type", "boolean").put("description", "Automatically ignore files from .gitignore and standard artifact folders (build, .gradle, .idea).");

        var ignore = props.putObject("ignorePatterns");
        ignore.put("type", "array");
        ignore.putObject("items").put("type", "string");
        ignore.put("description", "Glob patterns to ignore (e.g. '*.class', 'build/').");

        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String pathStr = params.path("path").asText(".");
        int maxDepth = params.path("maxDepth").asInt(3);
        boolean autoIgnore = params.path("autoIgnore").asBoolean(false);
        List<String> ignorePatterns = new ArrayList<>();
        if (params.has("ignorePatterns")) {
            for (JsonNode pattern : params.get("ignorePatterns")) {
                ignorePatterns.add(pattern.asText());
            }
        }

        // Санитизация пути
        Path rootPath = PathSanitizer.sanitize(pathStr, true);
        if (!Files.isDirectory(rootPath)) {
            throw new IllegalArgumentException("Path is not a directory or does not exist: '" + pathStr + "'. Please provide a valid directory path.");
        }

        // Сбор игнорируемых путей
        Set<String> autoIgnoredPaths = new HashSet<>();
        if (autoIgnore) {
            autoIgnoredPaths.addAll(GitUtils.getIgnoredPaths());
            autoIgnoredPaths.add(".gradle");
            autoIgnoredPaths.add(".idea");
            autoIgnoredPaths.add("build");
            autoIgnoredPaths.add(".git"); // На всякий случай, хотя PathSanitizer тоже должен их фильтровать
        }

        // Построение дерева
        StringBuilder sb = new StringBuilder();
        sb.append(rootPath.getFileName()).append("/\n");

        // Используем TreeVisitor для эффективного обхода
        TreeVisitor visitor = new TreeVisitor(rootPath, maxDepth, ignorePatterns, autoIgnoredPaths, sb);
        Files.walkFileTree(rootPath, EnumSet.noneOf(FileVisitOption.class), maxDepth, visitor);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", sb.toString());
        return response;
    }

    /**
     * Visitor для рекурсивного обхода и построения дерева.
     */
    private static class TreeVisitor extends SimpleFileVisitor<Path> {
        private final Path root;
        private final int maxDepth;
        private final List<PathMatcher> ignoreMatchers;
        private final Set<String> autoIgnoredPaths;
        private final StringBuilder sb;

        // Для отслеживания состояния "последний ли элемент" на каждом уровне вложенности
        private final Map<Integer, Boolean> lastNodes = new HashMap<>();

        public TreeVisitor(Path root, int maxDepth, List<String> ignorePatterns, Set<String> autoIgnoredPaths, StringBuilder sb) {
            this.root = root;
            this.maxDepth = maxDepth;
            this.autoIgnoredPaths = autoIgnoredPaths;
            this.sb = sb;
            this.ignoreMatchers = ignorePatterns.stream()
                    .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                    .collect(Collectors.toList());
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
            if (PathSanitizer.isProtected(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            // Явный контроль глубины
            if (root.relativize(dir).getNameCount() > maxDepth) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (shouldIgnore(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }

            if (!dir.equals(root)) {
                appendNode(dir, true);
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
            if (PathSanitizer.isProtected(file)) {
                return FileVisitResult.CONTINUE;
            }
            // Явный контроль глубины
            if (root.relativize(file).getNameCount() > maxDepth) {
                return FileVisitResult.CONTINUE;
            }
            if (shouldIgnore(file)) {
                return FileVisitResult.CONTINUE;
            }

            appendNode(file, false);
            return FileVisitResult.CONTINUE;
        }

        private boolean shouldIgnore(Path path) {
            Path relative = root.relativize(path);
            String relativeStr = relative.toString().replace('\\', '/');

            // Проверка авто-игнорирования
            if (autoIgnoredPaths.contains(relativeStr) || autoIgnoredPaths.contains(path.getFileName().toString())) {
                return true;
            }

            // Проверка пользовательских паттернов
            for (PathMatcher matcher : ignoreMatchers) {
                if (matcher.matches(relative) || matcher.matches(path.getFileName())) {
                    return true;
                }
            }
            return false;
        }

        private void appendNode(Path path, boolean isDir) throws IOException {
            Path relative = root.relativize(path);
            int level = relative.getNameCount();

            // Проверка, является ли текущий узел последним в своей директории
            Path parent = path.getParent();
            boolean isLast = isLastInDirectory(path, parent);
            lastNodes.put(level, isLast);

            // Формирование префикса отступов
            for (int i = 1; i < level; i++) {
                if (lastNodes.getOrDefault(i, false)) {
                    sb.append("    ");
                } else {
                    sb.append("│   ");
                }
            }

            // Сам узел
            sb.append(isLast ? "└── " : "├── ");
            sb.append(path.getFileName());
            if (isDir) {
                sb.append("/");
            }
            sb.append("\n");
        }

        /**
         * Определяет, является ли путь последним элементом в родительской директории с учетом фильтрации.
         */
        private boolean isLastInDirectory(Path path, Path parent) throws IOException {
            if (parent == null) {
                return true;
            }

            try (var stream = Files.list(parent)) {
                List<Path> siblings = stream
                        .filter(p -> !PathSanitizer.isProtected(p))
                        .filter(p -> !shouldIgnore(p))
                        .sorted(Comparator.comparing(Path::getFileName))
                        .collect(Collectors.toList());

                if (siblings.isEmpty()) {
                    return true;
                }
                return siblings.get(siblings.size() - 1).equals(path);
            }
        }
    }
}
