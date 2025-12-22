// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Утилита для безопасного выполнения внешних процессов.
 * Особенности безопасности:
 * 1. Не использует командную оболочку (cmd.exe/sh), что исключает shell-инъекции.
 * 2. Работает только в рамках корня проекта.
 * 3. Ограничивает время выполнения и объем возвращаемых данных.
 */
public class ProcessExecutor {

    private static final int MAX_OUTPUT_LINES = 500;
    private static final long DEFAULT_TIMEOUT_MINUTES = 5;

    /**
     * Выполняет команду и возвращает результат.
     * 
     * @param command Список аргументов команды (первый элемент - исполняемый файл).
     * @return Объект с результатом выполнения (stdout + stderr + exit code).
     * @throws Exception Если выполнение прервано или произошла системная ошибка.
     */
    public static ExecutionResult execute(List<String> command) throws Exception {
        Path root = PathSanitizer.getRoot();
        
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(root.toFile());
        pb.redirectErrorStream(true); // Объединяем stdout и stderr для простоты анализа LLM

        Process process = pb.start();
        StringBuilder output = new StringBuilder();

        try {
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

            boolean finished = process.waitFor(DEFAULT_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(-1, output.toString() + "\nERROR: Process timed out.");
            }

            return new ExecutionResult(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            // Если поток прерван — убиваем дочерний процесс
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // Гарантируем закрытие потоков и освобождение ресурсов процесса
            if (process.isAlive()) {
                process.destroy();
            }
        }
    }

    /**
     * Результат выполнения процесса.
     */
    public record ExecutionResult(int exitCode, String output) {}
}