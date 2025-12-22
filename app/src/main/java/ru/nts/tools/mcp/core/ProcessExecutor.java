// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Утилита для безопасного выполнения внешних процессов (команд ОС).
 * Спроектирована для предотвращения распространенных уязвимостей при работе с LLM:
 * 1. Изоляция: Команды выполняются строго в рабочей директории проекта.
 * 2. Безопасность: Прямой запуск через ProcessBuilder без использования shell (cmd.exe/sh),
 * что делает невозможным выполнение цепочек команд через ';' или пайпы '|'.
 * 3. Стабильность: Жесткие лимиты на время выполнения (timeout) и объем возвращаемых данных (защита от зацикливания вывода).
 */
public class ProcessExecutor {

    /**
     * Максимальное количество строк вывода, которое будет прочитано и возвращено.
     * Предотвращает переполнение памяти при запуске команд с огромным логом.
     */
    private static final int MAX_OUTPUT_LINES = 500;

    /**
     * Время ожидания завершения процесса по умолчанию.
     */
    private static final long DEFAULT_TIMEOUT_MINUTES = 5;

    /**
     * Выполняет внешнюю команду и возвращает агрегированный результат.
     *
     * @param command Список аргументов команды. Первый элемент — исполняемый файл или скрипт.
     *
     * @return Объект {@link ExecutionResult}, содержащий код выхода и текстовый вывод.
     *
     * @throws Exception Если возникла системная ошибка, процесс был прерван или выполнение невозможно.
     */
    public static ExecutionResult execute(List<String> command) throws Exception {
        Path root = PathSanitizer.getRoot();

        // Настройка процесса: передаем аргументы напрямую в ОС как массив, минуя командный интерпретатор
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        // Объединяем stdout и stderr в один поток для упрощения анализа результата моделью
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try {
            // Потоковое чтение вывода процесса с лимитированием по строкам
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int linesRead = 0;
                while ((line = reader.readLine()) != null) {
                    if (linesRead < MAX_OUTPUT_LINES) {
                        output.append(line).append("\n");
                        linesRead++;
                    } else if (linesRead == MAX_OUTPUT_LINES) {
                        output.append("... [Output truncated due to size limit] ...");
                        linesRead++;
                    }
                }
            }

            // Ожидание завершения процесса с таймаутом
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                // Принудительное завершение при превышении времени ожидания
                process.destroyForcibly();
                return new ExecutionResult(-1, output.toString() + "\nERROR: Process timed out.");
            }

            return new ExecutionResult(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            // Корректная обработка прерывания потока Java (например, при закрытии сервера)
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // Гарантированное освобождение системных ресурсов процесса
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }

    /**
     * Контейнер для результата выполнения внешней команды.
     *
     * @param exitCode Код выхода процесса (0 обычно означает успех).
     * @param output   Текстовый вывод процесса (stdout + stderr).
     */
    public record ExecutionResult(int exitCode, String output) {
    }
}
