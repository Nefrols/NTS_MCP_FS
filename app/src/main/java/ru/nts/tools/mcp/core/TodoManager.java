// Aristo 25.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Менеджер планов (Todo Manager).
 * Обеспечивает парсинг Markdown планов и формирование данных для AI-HUD.
 *
 * ВАЖНО: Работает только с TODO текущей сессии (не с файлами прошлых сессий).
 */
public class TodoManager {

    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("(?m)^\\s*([-*]|\\d+\\.)\\s+\\[([ xX])]\\s+(.*)$");

    // Активный TODO текущей сессии (только один на сессию)
    private static volatile String sessionTodoFile = null;

    /**
     * Устанавливает активный TODO для текущей сессии.
     * Вызывается из TodoTool при создании плана.
     */
    public static void setSessionTodo(String fileName) {
        sessionTodoFile = fileName;
    }

    /**
     * Возвращает имя файла активного TODO текущей сессии.
     */
    public static String getSessionTodo() {
        return sessionTodoFile;
    }

    /**
     * Сбрасывает состояние сессии (для тестов).
     */
    public static void reset() {
        sessionTodoFile = null;
    }

    public record HudInfo(String title, int done, int failed, int total, String nextTask, int nextId) {
        @Override
        public String toString() {
            String stats = TransactionManager.getSessionStats();

            if (title == null) {
                return String.format("[HUD] %s", stats);
            }

            // Компактный прогресс: ✓3 ✗1 ○2 = 3 done, 1 failed, 2 pending
            StringBuilder progress = new StringBuilder();
            if (done > 0) progress.append("✓").append(done);
            if (failed > 0) {
                if (!progress.isEmpty()) progress.append(" ");
                progress.append("✗").append(failed);
            }
            int pending = total - done - failed;
            if (pending > 0) {
                if (!progress.isEmpty()) progress.append(" ");
                progress.append("○").append(pending);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[HUD] ");
            sb.append(title).append(" [").append(progress).append("]");

            if (nextTask != null) {
                // Укороченная версия следующей задачи
                String shortTask = nextTask.length() > 40 ? nextTask.substring(0, 37) + "..." : nextTask;
                sb.append(" → #").append(nextId).append(": ").append(shortTask);
            } else if (pending == 0 && total > 0) {
                sb.append(" → All tasks done!");
            }

            sb.append(" | ").append(stats);
            return sb.toString();
        }
    }

    public static HudInfo getHudInfo() {
        // Используем ТОЛЬКО TODO текущей сессии
        if (sessionTodoFile == null) {
            return new HudInfo(null, 0, 0, 0, null, 0);
        }

        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        Path activeFile = todoDir.resolve(sessionTodoFile);

        if (!Files.exists(activeFile)) {
            return new HudInfo(null, 0, 0, 0, null, 0);
        }

        try {
            List<String> lines = Files.readAllLines(activeFile);
            String title = "Untitled";
            int done = 0;
            int failed = 0;
            int total = 0;
            String nextTask = null;
            int nextId = 0;
            int currentId = 0;

            for (String line : lines) {
                if (line.startsWith("# ")) {
                    title = line.substring(2).trim();
                    if (title.startsWith("TODO: ")) title = title.substring(6);
                    continue;
                }

                Matcher m = TODO_ITEM_PATTERN.matcher(line);
                if (m.find()) {
                    total++;
                    currentId++;
                    String marker = m.group(2);
                    if ("x".equals(marker)) {
                        done++;
                    } else if ("X".equals(marker)) {
                        failed++;
                    } else if (nextTask == null) {
                        nextTask = m.group(3).trim();
                        nextId = currentId;
                    }
                }
            }
            return new HudInfo(title, done, failed, total, nextTask, nextId);
        } catch (IOException e) {
            return new HudInfo("Error", 0, 0, 0, null, 0);
        }
    }

    /**
     * Возвращает список выполненных задач для коммита.
     * Использует только TODO текущей сессии.
     */
    public static List<String> getCompletedTasks() {
        List<String> completed = new ArrayList<>();

        if (sessionTodoFile == null) {
            return completed;
        }

        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        Path activeFile = todoDir.resolve(sessionTodoFile);

        if (!Files.exists(activeFile)) {
            return completed;
        }

        try {
            for (String line : Files.readAllLines(activeFile)) {
                Matcher m = TODO_ITEM_PATTERN.matcher(line);
                if (m.find()) {
                    String marker = m.group(2);
                    // x = done, X = failed - оба считаются завершенными для отчета
                    if ("x".equals(marker) || "X".equals(marker)) {
                        completed.add(m.group(3).trim());
                    }
                }
            }
        } catch (IOException ignored) {}
        return completed;
    }
}
