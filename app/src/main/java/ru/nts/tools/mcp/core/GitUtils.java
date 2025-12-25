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

import java.nio.file.Path;
import java.util.ArrayList;
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
     * Возвращает Unified Diff для изменений в рабочем дереве и индексе.
     *
     * @param path Опциональный путь к конкретному файлу или директории (может быть null).
     * @param staged Если true, возвращает дифф только для подготовленных к коммиту изменений (--staged).
     * @return Строка с выводом git diff.
     */
    public static String getDiff(Path path, boolean staged) {
        try {
            List<String> cmd = new ArrayList<>(List.of("git", "diff", "--no-color"));
            if (staged) {
                cmd.add("--cached");
            }
            if (path != null) {
                Path root = PathSanitizer.getRoot();
                Path relativePath = root.relativize(path.toAbsolutePath().normalize());
                cmd.add(relativePath.toString());
            }

            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(cmd, 10);
            if (result.exitCode() != 0) {
                return "Git error (code " + result.exitCode() + "): " + result.output();
            }
            return result.output();
        } catch (Exception e) {
            return "Error getting diff: " + e.getMessage();
        }
    }

    /**
     * Проверяет, является ли указанная директория Git-репозиторием.
     *
     * @param path путь к директории
     * @return true если это Git-репозиторий
     */
    public static boolean isGitRepo(Path path) {
        try {
            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(
                    List.of("git", "-C", path.toString(), "rev-parse", "--is-inside-work-tree"),
                    DEFAULT_GIT_TIMEOUT);
            return result.exitCode() == 0 && result.output().trim().equals("true");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Создаёт Git stash как контрольную точку для последующего восстановления.
     * Используется как fallback для Deep Undo при невозможности восстановить файлы.
     *
     * @param message описание stash-контрольной точки
     * @return ID stash'а или null при ошибке
     */
    public static String createStashCheckpoint(String message) {
        try {
            // Сохраняем все изменения включая untracked файлы
            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(
                    List.of("git", "stash", "push", "-u", "-m", "NTS-MCP: " + message),
                    10);
            if (result.exitCode() == 0) {
                // Получаем ID последнего stash
                ProcessExecutor.ExecutionResult listResult = ProcessExecutor.execute(
                        List.of("git", "stash", "list", "--format=%gd", "-n", "1"),
                        DEFAULT_GIT_TIMEOUT);
                if (listResult.exitCode() == 0) {
                    return listResult.output().trim();
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Восстанавливает файлы из Git (HEAD или stash).
     *
     * @param paths список путей для восстановления
     * @param stashId ID stash для восстановления (null = HEAD)
     * @return сообщение о результате
     */
    public static String restoreFiles(List<Path> paths, String stashId) {
        try {
            Path root = PathSanitizer.getRoot();
            List<String> relativePaths = paths.stream()
                    .map(p -> root.relativize(p.toAbsolutePath().normalize()).toString())
                    .toList();

            StringBuilder result = new StringBuilder();

            if (stashId != null) {
                // Восстановление из stash
                for (String relPath : relativePaths) {
                    ProcessExecutor.ExecutionResult r = ProcessExecutor.execute(
                            List.of("git", "checkout", stashId, "--", relPath),
                            10);
                    if (r.exitCode() == 0) {
                        result.append("Restored from stash: ").append(relPath).append("\n");
                    } else {
                        result.append("Failed to restore: ").append(relPath).append("\n");
                    }
                }
            } else {
                // Восстановление из HEAD
                for (String relPath : relativePaths) {
                    ProcessExecutor.ExecutionResult r = ProcessExecutor.execute(
                            List.of("git", "checkout", "HEAD", "--", relPath),
                            10);
                    if (r.exitCode() == 0) {
                        result.append("Restored from HEAD: ").append(relPath).append("\n");
                    } else {
                        result.append("Failed to restore: ").append(relPath).append("\n");
                    }
                }
            }
            return result.toString();
        } catch (Exception e) {
            return "Git restore failed: " + e.getMessage();
        }
    }

    /**
     * Получает хеш текущего HEAD.
     * Используется для определения, были ли сделаны коммиты.
     */
    public static String getHeadCommit() {
        try {
            ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(
                    List.of("git", "rev-parse", "HEAD"),
                    DEFAULT_GIT_TIMEOUT);
            return result.exitCode() == 0 ? result.output().trim() : null;
        } catch (Exception e) {
            return null;
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