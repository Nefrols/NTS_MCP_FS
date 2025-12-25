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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Утилита для безопасного выполнения внешних процессов (команд ОС) с поддержкой управления задачами.
 * Спроектирована для предотвращения распространенных уязвимостей при работе с LLM:
 * 1. Изоляция: Команды выполняются строго в рабочей директории проекта.
 * 2. Безопасность: Прямой запуск через ProcessBuilder без использования shell (cmd.exe/sh),
 * что делает невозможным выполнение цепочек команд через ';' или пайпы '|'.
 * 3. Стабильность: Жесткие лимиты на время выполнения (timeout) и объем возвращаемых данных.
 * 4. Управление задачами: Каждая команда получает уникальный идентификатор для отслеживания.
 *
 * Thread-safety: Все операции потокобезопасны для многосессионной работы.
 * Session-scoped: TaskId включает идентификатор сессии для предотвращения коллизий.
 */
public class ProcessExecutor {

    /**
     * Максимальное количество строк вывода, которое будет прочитано и сохранено в памяти для одной задачи.
     * Предотвращает переполнение памяти при запуске команд с бесконечным или огромным логом.
     */
    private static final int MAX_OUTPUT_LINES = 1000;

    /**
     * Реестр активных и недавно завершенных задач.
     * Ключ — уникальный taskId, значение — информация о запущенном процессе.
     */
    private static final Map<String, TaskInfo> taskRegistry = new ConcurrentHashMap<>();

    /**
     * Счетчик для генерации уникальных taskId внутри сессии.
     */
    private static final AtomicLong taskCounter = new AtomicLong(0);

    /**
     * Генерирует уникальный taskId с привязкой к сессии.
     * Формат: {sessionId_prefix}-{counter}-{uuid_suffix}
     */
    private static String generateTaskId() {
        SessionContext ctx = SessionContext.current();
        String sessionPrefix = ctx != null ? ctx.getSessionId().substring(0, Math.min(8, ctx.getSessionId().length())) : "default";
        long counter = taskCounter.incrementAndGet();
        String uuidSuffix = UUID.randomUUID().toString().substring(0, 8);
        return String.format("%s-%d-%s", sessionPrefix, counter, uuidSuffix);
    }

    /**
     * Выполняет внешнюю команду с заданным таймаутом ожидания.
     * Если таймаут превышен, процесс НЕ убивается сразу, а продолжает выполняться в фоне,
     * возвращая накопленный на данный момент вывод.
     *
     * @param command        Список аргументов команды. Первый элемент — исполняемый файл.
     * @param timeoutSeconds Максимальное время ожидания ответа в секундах.
     *
     * @return Объект {@link ExecutionResult}, содержащий код выхода, текст и taskId.
     *
     * @throws Exception Если возникла системная ошибка при запуске процесса.
     */
    public static ExecutionResult execute(List<String> command, long timeoutSeconds) throws Exception {
        Path root = PathSanitizer.getRoot();
        // Генерация уникального идентификатора с привязкой к сессии
        String taskId = generateTaskId();

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        // Объединяем stdout и stderr для простоты анализа логов моделью
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder outputAccumulator = new StringBuilder();

        // Регистрация задачи в глобальном реестре перед началом ожидания
        TaskInfo info = new TaskInfo(taskId, process, outputAccumulator, command);
        taskRegistry.put(taskId, info);

        try {
            // Асинхронное чтение вывода процесса в виртуальном потоке.
            // Это позволяет накапливать лог фоновой задачи, даже если основной поток MCP вернул управление по таймауту.
            Thread.ofVirtual().start(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    int linesRead = 0;
                    while ((line = reader.readLine()) != null) {
                        synchronized (outputAccumulator) {
                            if (linesRead < MAX_OUTPUT_LINES) {
                                outputAccumulator.append(line).append("\n");
                                linesRead++;
                            } else if (linesRead == MAX_OUTPUT_LINES) {
                                outputAccumulator.append("... [Output truncated due to limit] ...");
                                linesRead++;
                            }
                        }
                    }
                } catch (Exception ignored) {
                    // Ошибки чтения (например, при закрытии потока) игнорируем
                }
            });

            // Ожидание завершения процесса в рамках указанного лимита времени
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            String currentLog;
            synchronized (outputAccumulator) {
                currentLog = outputAccumulator.toString();
            }

            if (!finished) {
                // Таймаут достигнут, но процесс жив. Возвращаем taskId для последующего запроса лога через task_log.
                return new ExecutionResult(-1, "[TIMEOUT_REACHED] Task is still running in background.\n" + currentLog, taskId, true);
            }

            // Процесс завершился штатно. Удаляем его из реестра активных задач.
            taskRegistry.remove(taskId);
            return new ExecutionResult(process.exitValue(), currentLog, taskId, false);

        } catch (InterruptedException e) {
            // При прерывании потока MCP принудительно останавливаем дочерний процесс
            process.destroyForcibly();
            taskRegistry.remove(taskId);
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    /**
     * Возвращает накопленный на данный момент лог вывода для активной фоновой задачи.
     *
     * @param taskId Уникальный хеш задачи.
     *
     * @return Текст вывода или сообщение о том, что задача не найдена.
     */
    public static String getTaskLog(String taskId) {
        TaskInfo info = taskRegistry.get(taskId);
        if (info == null) {
            return "Task not found or already finished and removed from registry.";
        }
        synchronized (info.output) {
            return info.output.toString();
        }
    }

    /**
     * Принудительно завершает выполнение активной фоновой задачи.
     *
     * @param taskId Уникальный хеш задачи.
     *
     * @return true, если задача была найдена и остановлена.
     */
    public static boolean killTask(String taskId) {
        TaskInfo info = taskRegistry.remove(taskId);
        if (info != null && info.process.isAlive()) {
            info.process.destroyForcibly();
            return true;
        }
        return false;
    }

    /**
     * Контейнер для результата выполнения внешней команды.
     *
     * @param exitCode  Код выхода процесса (0 - успех, -1 - таймаут).
     * @param output    Текстовый вывод (stdout + stderr).
     * @param taskId    Уникальный идентификатор сессии выполнения.
     * @param isRunning true, если задача не успела завершиться и работает в фоне.
     */
    public record ExecutionResult(int exitCode, String output, String taskId, boolean isRunning) {
    }

    /**
     * Внутренняя структура для хранения информации о запущенном процессе.
     */
    private record TaskInfo(String id, Process process, StringBuilder output, List<String> command) {
    }
}
