// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.List;

/**
 * Утилита для взаимодействия с Git.
 * Используется для обогащения ответов инструментов информацией о состоянии репозитория.
 */
public class GitUtils {

    /**
     * Возвращает краткий статус файла в Git (Modified, Added, Untracked и т.д.).
     * Использует команду git status --porcelain.
     * 
     * @param path Путь к файлу.
     * @return Строка со статусом или пустая строка, если файл не в репозитории или произошла ошибка.
     */
    public static String getFileStatus(Path path) {
        try {
            // Используем относительный путь от корня для Git
            Path root = PathSanitizer.getRoot();
            Path relativePath = root.relativize(path.toAbsolutePath().normalize());
            
            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(
                List.of("git", "status", "--porcelain", relativePath.toString())
            );

            if (result.exitCode() == 0 && !result.output().isBlank()) {
                String out = result.output().trim();
                // Парсим вывод porcelain: "XY path"
                // X - статус в индексе, Y - статус в рабочем дереве
                if (out.length() >= 2) {
                    char x = out.charAt(0);
                    char y = out.charAt(1);
                    return translateStatus(x, y);
                }
            }
            
            // Проверяем, отслеживается ли файл вообще
            ProcessExecutor.ExecutionResult trackCheck = ProcessExecutor.execute(
                List.of("git", "ls-files", "--error-unmatch", relativePath.toString())
            );
            if (trackCheck.exitCode() != 0) {
                return "Untracked";
            }

            return "Unchanged";
        } catch (Exception e) {
            return ""; // В случае ошибки просто не выводим статус
        }
    }

    /**
     * Переводит технические символы статуса Git в человекочитаемый вид.
     */
    private static String translateStatus(char x, char y) {
        if (x == '?' && y == '?') return "Untracked";
        if (x == 'A') return "Added to index";
        if (y == 'M') return "Modified (unstaged)";
        if (x == 'M') return "Modified (staged)";
        if (y == 'D') return "Deleted";
        if (x == 'R') return "Renamed";
        return "Changed (" + x + y + ")";
    }
}
