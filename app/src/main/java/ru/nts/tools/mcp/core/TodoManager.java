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
 * Делегирует хранение активного TODO к session-scoped SessionContext.
 */
public class TodoManager {

    private static final Pattern TODO_ITEM_PATTERN = Pattern.compile("(?m)^\\s*([-*]|\\d+\\.)\\s+\\[([ xX])]\\s+(.*)$");

    /**
     * Устанавливает активный TODO для текущей сессии.
     * Вызывается из TodoTool при создании плана.
     */
    public static void setSessionTodo(String fileName) {
        SessionContext.currentOrDefault().setActiveTodoFile(fileName);
    }

    /**
     * Возвращает имя файла активного TODO текущей сессии.
     */
    public static String getSessionTodo() {
        return SessionContext.currentOrDefault().getActiveTodoFile();
    }

    /**
     * Сбрасывает состояние сессии (для тестов).
     */
    public static void reset() {
        SessionContext ctx = SessionContext.current();
        if (ctx != null) {
            ctx.setActiveTodoFile(null);
        }
    }

    public record HudInfo(String title, int done, int failed, int total, String nextTask, int nextId) {
        @Override
        public String toString() {
            String stats = TransactionManager.getSessionStats();
            int edits = TransactionManager.getTotalEdits();
            int undos = TransactionManager.getTotalUndos();

            // Получаем sessionId для отображения (важно для LLM!)
            SessionContext ctx = SessionContext.current();
            String sessionId = ctx != null ? ctx.getSessionId() : "none";

            StringBuilder sb = new StringBuilder();
            sb.append("[HUD sid:").append(sessionId).append("] ");

            if (title == null) {
                sb.append(stats);
                // Напоминание об undo даже без TODO плана — role-aware
                appendUndoReminder(sb, edits, undos);
                appendVerifyReminder(sb);
                return sb.toString();
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

            sb.append(title).append(" [").append(progress).append("]");

            if (nextTask != null) {
                // Укороченная версия следующей задачи
                String shortTask = nextTask.length() > 40 ? nextTask.substring(0, 37) + "..." : nextTask;
                sb.append(" → #").append(nextId).append(": ").append(shortTask);
            } else if (pending == 0 && total > 0) {
                sb.append(" → All done! Use nts_todo(action='close') to archive.");
            }

            sb.append(" | ").append(stats);

            // Напоминание об отметке задач (если есть pending)
            if (nextTask != null) {
                sb.append("\n[REMINDER: Mark task done: update(id=")
                  .append(nextId).append(", status='done')]");
            }

            // FOCUS hint: напоминание о текущей задаче
            if (nextTask != null && nextId > 0) {
                String shortTask = nextTask.length() > 50 ? nextTask.substring(0, 47) + "..." : nextTask;
                sb.append("\n[FOCUS: Current task #").append(nextId).append(": ").append(shortTask)
                  .append(". Complete it before moving on.]");
            }

            // Напоминание об undo (если много правок без отмен) — role-aware
            appendUndoReminder(sb, edits, undos);

            // Напоминание о верификации — role-aware
            appendVerifyReminder(sb);

            return sb.toString();
        }

        /**
         * Добавляет напоминание об undo, если много правок без использования отмены.
         * Показывается когда edits >= 4 и undos == 0.
         * Role-aware: показывает nts_session hint только если инструмент доступен.
         */
        private void appendUndoReminder(StringBuilder sb, int edits, int undos) {
            if (edits >= 4 && undos == 0) {
                if (TipFilter.canMention("nts_session")) {
                    sb.append("\n[TIP: ").append(edits).append(" edits without undo. ")
                      .append("If something went wrong, use nts_session(action='undo') to rollback ")
                      .append("instead of multiple fix edits. View history: nts_session(action='journal')]");
                }
            }
        }

        /**
         * Добавляет напоминание о верификации если много правок без проверки.
         * Role-aware: показывает nts_verify hint только если инструмент доступен.
         */
        private void appendVerifyReminder(StringBuilder sb) {
            int editsSinceVerify = TransactionManager.getEditsSinceLastVerify();
            if (editsSinceVerify >= 3 && TipFilter.canMention("nts_verify")) {
                sb.append("\n[TIP: ").append(editsSinceVerify)
                  .append(" edits without verification. Consider nts_verify(action='syntax') to check for errors.]");
            }
        }
    }

    /**
     * Возвращает путь к директории todos текущей сессии.
     */
    private static Path getTodosDir() {
        // Используем currentOrDefault() для согласованности с getSessionTodo()
        return SessionContext.currentOrDefault().getTodosDir();
    }

    public static HudInfo getHudInfo() {
        // Используем ТОЛЬКО TODO текущей сессии
        String sessionTodoFile = getSessionTodo();
        if (sessionTodoFile == null) {
            return new HudInfo(null, 0, 0, 0, null, 0);
        }

        Path todoDir = getTodosDir();
        Path activeFile = todoDir.resolve(sessionTodoFile);

        if (!Files.exists(activeFile)) {
            return new HudInfo(null, 0, 0, 0, null, 0);
        }

        try {
            List<String> lines = java.util.Arrays.asList(EncodingUtils.readTextFile(activeFile).content().split("\n", -1));
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

        String sessionTodoFile = getSessionTodo();
        if (sessionTodoFile == null) {
            return completed;
        }

        Path todoDir = getTodosDir();
        Path activeFile = todoDir.resolve(sessionTodoFile);

        if (!Files.exists(activeFile)) {
            return completed;
        }

        try {
            List<String> lines = java.util.Arrays.asList(EncodingUtils.readTextFile(activeFile).content().split("\n", -1));
            for (String line : lines) {
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
