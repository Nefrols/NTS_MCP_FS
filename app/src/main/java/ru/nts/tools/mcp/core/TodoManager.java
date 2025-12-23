// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Менеджер планов (Todo Manager).
 * Обеспечивает парсинг Markdown планов и формирование данных для AI-HUD.
 */
public class TodoManager {

    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("(?m)^\\s*([-*]|\\d+\\.)\\s+\\[([ xX])]\\s+(.*)$");


    public record HudInfo(String title, int done, int total, String nextTask) {
        @Override
        public String toString() {
            String stats = TransactionManager.getSessionStats();
            if (title == null) return "[HUD] No active plan. | " + stats;
            return String.format("[HUD] Plan: %s | Progress: %d/%d | Next: %s | %s", 
                title, done, total, nextTask != null ? nextTask : "All done!", stats);
        }
    }

    public static HudInfo getHudInfo() {
        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) return new HudInfo(null, 0, 0, null);

        try (Stream<Path> s = Files.list(todoDir)) {
            Path activeFile = s.filter(p -> p.getFileName().toString().startsWith("TODO_"))
                    .max(Comparator.comparing(Path::getFileName))
                    .orElse(null);

            if (activeFile == null) return new HudInfo(null, 0, 0, null);

            List<String> lines = Files.readAllLines(activeFile);
            String title = "Untitled";
            int done = 0;
            int total = 0;
            String nextTask = null;

            for (String line : lines) {
                if (line.startsWith("# ")) {
                    title = line.substring(2).trim();
                    if (title.startsWith("TODO: ")) title = title.substring(6);
                    continue;
                }

                Matcher m = TODO_ITEM_PATTERN.matcher(line);
                if (m.find()) {
                    total++;
                    boolean isDone = !m.group(2).trim().isEmpty();
                    if (isDone) {
                        done++;
                    } else if (nextTask == null) {
                        nextTask = m.group(3).trim();
                    }
                }
            }
            return new HudInfo(title, done, total, nextTask);
        } catch (IOException e) {
            return new HudInfo("Error reading plan", 0, 0, null);
        }
    }

    /**
     * Возвращает список выполненных задач для коммита.
     */
    public static List<String> getCompletedTasks() {
        List<String> completed = new ArrayList<>();
        Path todoDir = PathSanitizer.getRoot().resolve(".nts/todos");
        if (!Files.exists(todoDir)) return completed;

        try (Stream<Path> s = Files.list(todoDir)) {
            Path activeFile = s.filter(p -> p.getFileName().toString().startsWith("TODO_"))
                    .max(Comparator.comparing(Path::getFileName))
                    .orElse(null);

            if (activeFile != null) {
                for (String line : Files.readAllLines(activeFile)) {
                    Matcher m = TODO_ITEM_PATTERN.matcher(line);
                    if (m.find() && !m.group(2).trim().isEmpty()) {
                        completed.add(m.group(3).trim());
                    }
                }
            }
        } catch (IOException ignored) {}
        return completed;
    }
}
