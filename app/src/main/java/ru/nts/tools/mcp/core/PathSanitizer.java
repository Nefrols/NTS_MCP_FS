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
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Утилита для проверки, нормализации и защиты путей файловой системы (Path Sanitizer).
 * Реализует механизм "песочницы" (sandboxing), предотвращая:
 * 1. Выход за пределы рабочей директории проекта (Path Traversal).
 * 2. Доступ к скрытым системным файлам и папкам инфраструктуры (.git, .gradle, .nts).
 * 3. Попытки загрузки сверхбольших файлов, способных вызвать переполнение памяти (OOM).
 *
 * Поддерживает множественные корневые директории (roots) согласно MCP протоколу.
 */
public class PathSanitizer {

    /**
     * Список корневых директорий проекта. Все операции должны ограничиваться этими путями.
     * Использует CopyOnWriteArrayList для thread-safe доступа при динамическом обновлении roots.
     */
    private static final List<Path> roots = new CopyOnWriteArrayList<>();

    /**
     * Основной (первый) корень для обратной совместимости.
     * Устанавливается при вызове setRoot() или как первый элемент setRoots().
     */
    private static volatile Path primaryRoot = Paths.get(".").toAbsolutePath().normalize();

    static {
        // Инициализация с текущей директорией по умолчанию
        roots.add(primaryRoot);
    }

    /**
     * Максимально допустимый размер файла для текстовой обработки (10 MB).
     * Служит предохранителем против зависания сервера при попытке чтения гигантских логов или дампов.
     */
    private static final long MAX_TEXT_FILE_SIZE = 10 * 1024 * 1024;

    /**
     * Набор имен файлов и папок, доступ к которым для LLM заблокирован или ограничен.
     * Включает инфраструктурные компоненты (Git, Gradle) и служебную директорию транзакций MCP.
     */
    private static final Set<String> PROTECTED_NAMES = Set.of(".git", ".gradle", "gradle", "gradlew", "gradlew.bat", "build.gradle.kts", "settings.gradle.kts", "app/build.gradle.kts");

    /**
     * Callback для ленивого обновления roots при отказе в доступе.
     * Вызывается когда запрошенный путь не попадает ни в один из текущих roots.
     * Позволяет перезапросить roots у MCP-клиента без зависимости core → McpServer.
     *
     * Контракт: callback вызывается синхронно и должен обновить roots через setRoots()
     * до возврата. Возвращает true если roots были обновлены.
     */
    private static volatile java.util.function.BooleanSupplier rootRefreshCallback;

    /**
     * Cooldown между попытками обновления roots (предотвращает спам запросов).
     */
    private static volatile long lastRootRefreshAttempt = 0;
    private static final long ROOT_REFRESH_COOLDOWN_MS = 5_000;

    /**
     * Устанавливает callback для ленивого обновления roots.
     * Вызывается из McpServer при инициализации.
     */
    public static void setRootRefreshCallback(java.util.function.BooleanSupplier callback) {
        rootRefreshCallback = callback;
    }

    /**
     * Корневая директория для хранения задач (~/.nts/).
     * Находится в домашней директории пользователя, аналогично ~/.claude/ или ~/.gemini/.
     */
    private static volatile Path taskRoot = Paths.get(System.getProperty("user.home"), ".nts").toAbsolutePath().normalize();

    /**
     * Переопределяет корень проекта (единственный root).
     * В основном используется в модульных тестах для изоляции тестового окружения во временных папках.
     * Также используется при установке PROJECT_ROOT из переменной окружения.
     *
     * @param newRoot Новый путь, который будет считаться корнем "песочницы".
     */
    public static void setRoot(Path newRoot) {
        Path normalized = newRoot.toAbsolutePath().normalize();
        primaryRoot = normalized;
        roots.clear();
        roots.add(normalized);
    }

    /**
     * Устанавливает список корневых директорий проекта.
     * Используется при получении roots от MCP клиента.
     * Первый элемент списка становится primaryRoot для обратной совместимости.
     *
     * @param newRoots Список путей, которые будут считаться разрешенными корнями.
     */
    public static void setRoots(List<Path> newRoots) {
        if (newRoots == null || newRoots.isEmpty()) {
            return;
        }

        List<Path> normalized = new ArrayList<>();
        for (Path root : newRoots) {
            normalized.add(root.toAbsolutePath().normalize());
        }

        primaryRoot = normalized.get(0);
        roots.clear();
        roots.addAll(normalized);
    }

    /**
     * Возвращает неизменяемый список всех корневых директорий.
     *
     * @return Список объектов {@link Path} для всех разрешенных корней.
     */
    public static List<Path> getRoots() {
        return Collections.unmodifiableList(new ArrayList<>(roots));
    }

    /**
     * Выполняет санитарную проверку и нормализацию пути.
     * Гарантирует, что итоговый абсолютный путь находится строго внутри одного из корней проекта.
     *
     * @param requestedPath  Путь, переданный LLM (может быть абсолютным, относительным или содержать '..').
     * @param allowProtected Если true, разрешает чтение системных файлов (например, для анализа build.gradle).
     *                       Если false, блокирует любые операции с защищенными объектами.
     *
     * @return Абсолютный нормализованный объект {@link Path}.
     *
     * @throws SecurityException Если путь ведет за пределы всех корней или файл защищен политикой безопасности.
     */
    public static Path sanitize(String requestedPath, boolean allowProtected) {
        Path target;
        // Предварительная нормализация разделителей для Windows
        String normalizedRequest = requestedPath.replace('\\', '/');
        Path requested = Paths.get(normalizedRequest);

        // Преобразование в абсолютный путь
        if (requested.isAbsolute()) {
            target = requested.toAbsolutePath().normalize();
        } else {
            // Для относительных путей используем primary root
            target = primaryRoot.resolve(normalizedRequest).toAbsolutePath().normalize();
        }

        // Проверка Path Traversal: итоговый путь обязан начинаться с префикса одного из корней
        boolean isWithinRoot = false;
        for (Path root : roots) {
            if (target.startsWith(root)) {
                isWithinRoot = true;
                break;
            }
        }

        if (!isWithinRoot) {
            // Ленивое обновление roots: перезапрашиваем у клиента перед отказом.
            // Покрывает случай когда клиент не отправляет notifications/roots/list_changed
            // (например, Claude Code при /add-dir).
            long now = System.currentTimeMillis();
            if (rootRefreshCallback != null && (now - lastRootRefreshAttempt) > ROOT_REFRESH_COOLDOWN_MS) {
                lastRootRefreshAttempt = now;
                try {
                    boolean updated = rootRefreshCallback.getAsBoolean();
                    if (updated) {
                        // Roots обновились — проверяем заново
                        for (Path root : roots) {
                            if (target.startsWith(root)) {
                                isWithinRoot = true;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Callback не сработал — продолжаем с исходной ошибкой
                }
            }
        }

        if (!isWithinRoot) {
            String rootsInfo = roots.size() == 1
                ? "(Root: " + roots.get(0) + ")"
                : "(Roots: " + roots + ")";
            throw new SecurityException("Access denied: path is outside of working directory: " + requestedPath + " " + rootsInfo);
        }

        // Проверка политик защиты инфраструктуры
        if (!allowProtected) {
            if (isProtected(target)) {
                throw new SecurityException("Access denied: file or directory is protected by project security policy.");
            }
        }

        return target;
    }

    /**
     * Находит корневую директорию, которой принадлежит указанный путь.
     *
     * @param path Путь для проверки.
     * @return Корень, содержащий этот путь, или null если путь не принадлежит ни одному корню.
     */
    public static Path findContainingRoot(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        for (Path root : roots) {
            if (normalized.startsWith(root)) {
                return root;
            }
        }
        return null;
    }

    /**
     * Проверяет файл на соответствие лимитам размера.
     * Предотвращает загрузку бинарного "мусора" или гигантских файлов в контекст LLM.
     *
     * @param path Путь к проверяемому файлу.
     *
     * @throws IOException       Если возникла ошибка при определении размера файла.
     * @throws SecurityException Если размер файла превышает константу {@link #MAX_TEXT_FILE_SIZE}.
     */
    public static void checkFileSize(Path path) throws IOException {
        if (Files.exists(path) && Files.isRegularFile(path)) {
            long size = Files.size(path);
            if (size > MAX_TEXT_FILE_SIZE) {
                throw new SecurityException(String.format("File is too large (%d bytes). Limit is %d bytes.", size, MAX_TEXT_FILE_SIZE));
            }
        }
    }

    /**
     * Определяет, является ли указанный путь частью защищенной инфраструктуры.
     * Выполняет как быструю проверку по имени, так и глубокую проверку всех сегментов пути.
     *
     * @param path Путь для анализа.
     *
     * @return true, если хотя бы один сегмент пути совпадает с защищенным именем.
     */
    public static boolean isProtected(Path path) {
        // Быстрая проверка имени самого файла/папки
        if (PROTECTED_NAMES.contains(path.getFileName().toString())) {
            return true;
        }

        // Рекурсивная проверка всех родительских сегментов (для вложенных системных файлов)
        for (Path part : path) {
            if (PROTECTED_NAMES.contains(part.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Возвращает основной (первый) корень рабочей директории.
     * Для обратной совместимости с кодом, ожидающим единственный root.
     *
     * @return Объект {@link Path} основного корня.
     */
    public static Path getRoot() {
        return primaryRoot;
    }

    /**
     * Возвращает корень для хранения задач (~/.nts/).
     * Задачи хранятся отдельно от рабочей директории проекта.
     */
    public static Path getTaskRoot() {
        return taskRoot;
    }

    /**
     * Переопределяет корень хранения задач (для тестов).
     */
    public static void setTaskRoot(Path newRoot) {
        taskRoot = newRoot.toAbsolutePath().normalize();
    }
}