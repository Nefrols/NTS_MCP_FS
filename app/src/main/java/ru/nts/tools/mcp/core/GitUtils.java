// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Утилита для взаимодействия с системой контроля версий Git.
 * Используется для обогащения ответов инструментов MCP актуальной информацией о состоянии репозитория.
 * Позволяет LLM мгновенно видеть, как её действия (правка, создание, перемещение) отражаются на статусе файлов в Git.
 */
public class GitUtils {

    /**
     * Стандартный таймаут для быстрых информационных команд Git.
     */
    private static final long DEFAULT_GIT_TIMEOUT = 5;

    /**
     * Возвращает список путей, которые игнорируются Git в текущем репозитории.
     * Использует 'git clean -ndX' для получения списка игнорируемых файлов и директорий.
     *
     * @return Множество строк с относительными путями игнорируемых объектов.
     */
    public static Set<String> getIgnoredPaths() {
        Set<String> ignored = new HashSet<>();
        try {
            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(
                    List.of("git", "clean", "-ndX"), DEFAULT_GIT_TIMEOUT);
            
            if (result.exitCode() == 0) {
                // Вывод формата: "Would remove path/to/file"
                for (String line : result.output().split("\n")) {
                    if (line.startsWith("Would remove ")) {
                        String path = line.substring("Would remove ".length()).trim();
                        // Убираем слеши в конце для папок
                        if (path.endsWith("/") || path.endsWith("\\")) {
                            path = path.substring(0, path.length() - 1);
                        }
                        ignored.add(path);
                    }
                }
            }
        } catch (Exception ignoredErr) {
            // Если Git недоступен — возвращаем пустой набор
        }
        return ignored;
    }

    /**
     * Возвращает краткое описание статуса файла в Git.
     * Использует команду 'git status --porcelain' для получения машиночитаемого и стабильного вывода.
     *
     * @param path Абсолютный или относительный путь к файлу.
     *
     * @return Человекочитаемая строка статуса (например, "Modified (unstaged)", "Untracked")
     * или пустая строка, если файл не является частью Git-репозитория или произошла ошибка.
     */
    public static String getFileStatus(Path path) {
        try {
            // Вычисляем путь относительно корня проекта для корректной передачи в команду Git
            Path root = PathSanitizer.getRoot();
            Path relativePath = root.relativize(path.toAbsolutePath().normalize());

            // Выполнение команды получения статуса только для конкретного файла
            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(List.of("git", "status", "--porcelain", relativePath.toString()), DEFAULT_GIT_TIMEOUT);

            if (result.exitCode() == 0 && !result.output().isBlank()) {
                String out = result.output().trim();
                // Парсинг формата porcelain: "XY path"
                // X - статус в индексе (staged), Y - статус в рабочем дереве (unstaged)
                if (out.length() >= 2) {
                    char x = out.charAt(0);
                    char y = out.charAt(1);
                    return translateStatus(x, y);
                }
            }

            // Если porcelain пуст, файл может быть либо неизмененным (Unchanged), либо вообще не отслеживаться (Untracked)
            // Выполняем проверку на отслеживаемость через ls-files
            ProcessExecutor.ExecutionResult trackCheck = ProcessExecutor.execute(List.of("git", "ls-files", "--error-unmatch", relativePath.toString()), DEFAULT_GIT_TIMEOUT);
            if (trackCheck.exitCode() != 0) {
                return "Untracked";
            }

            return "Unchanged";
        } catch (Exception e) {
            // В случае системных ошибок (Git не установлен, путь вне репозитория) 
            // возвращаем пустую строку, чтобы не засорять основной вывод инструмента.
            return "";
        }
    }

    /**
     * Преобразует технические коды статуса Git (из формата porcelain) в понятные для LLM описания.
     *
     * @param x Код статуса в индексе.
     * @param y Код статуса в рабочем каталоге.
     *
     * @return Текстовое описание состояния.
     */
    private static String translateStatus(char x, char y) {
        if (x == '?' && y == '?') {
            return "Untracked";
        }
        if (x == 'A') {
            return "Added to index";
        }
        if (y == 'M') {
            return "Modified (unstaged)";
        }
        if (x == 'M') {
            return "Modified (staged)";
        }
        if (y == 'D') {
            return "Deleted";
        }
        if (x == 'R') {
            return "Renamed";
        }
        return "Changed (" + x + y + ")";
    }
}