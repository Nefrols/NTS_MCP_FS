// Aristo 22.12.2025
package ru.nts.tools.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.core.PathSanitizer;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструментов автоматизации (Git и Gradle) с поддержкой таймаутов и управления задачами.
 * Проверяют:
 * 1. Корректность выполнения команд с обязательным параметром timeout.
 * 2. Механизм генерации и отслеживания taskId (хешей задач).
 * 3. Возможность получения промежуточных логов через task_log.
 * 4. Возможность принудительного завершения задач через task_kill.
 */
class AutomationToolsTest {

    private final GradleTool gradleTool = new GradleTool();
    private final GitTool gitTool = new GitTool();
    private final TaskLogTool logTool = new TaskLogTool();
    private final TaskKillTool killTool = new TaskKillTool();
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Динамическая инициализация корня проекта перед тестами.
     */
    @BeforeEach
    void setUp() {
        Path current = Paths.get(".").toAbsolutePath().normalize();
        while (current != null) {
            if (new File(current.toFile(), "gradlew").exists() || new File(current.toFile(), "gradlew.bat").exists()) {
                PathSanitizer.setRoot(current);
                return;
            }
            current = current.getParent();
        }
    }

    /**
     * Тестирует выполнение команды Git с указанием таймаута.
     */
    @Test
    void testGitStatusWithTimeout() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("command", "status");
        params.put("timeout", 10);

        var result = gitTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Проверка наличия статуса выполнения и идентификатора задачи
        assertTrue(text.contains("finished with exit code: 0") || text.contains("Git task"), "Output should contain task info or finish status. Got: " + text);
    }

    /**
     * Тестирует полный цикл управления фоновой задачей (запуск -> логирование -> остановка).
     */
    @Test
    void testTaskManagement() throws Exception {
        // Запускаем команду с намеренно коротким таймаутом для перевода в фоновый режим
        ObjectNode params = mapper.createObjectNode();
        params.put("command", "log");
        params.put("args", "--all");
        params.put("timeout", 1);

        var result = gitTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Извлекаем сгенерированный taskId из формата вывода: Git task [taskId] ...
        int startIdx = text.indexOf("[");
        int endIdx = text.indexOf("]");

        if (startIdx != -1 && endIdx > startIdx) {
            String taskId = text.substring(startIdx + 1, endIdx);

            // 1. Проверяем получение лога по хешу
            ObjectNode logParams = mapper.createObjectNode().put("taskId", taskId);
            var logResult = logTool.execute(logParams);
            String logText = logResult.get("content").get(0).get("text").asText();
            assertTrue(logText.contains("Current log for task"), "Log output should be returned for taskId: " + taskId);

            // 2. Проверяем остановку задачи по хешу
            ObjectNode killParams = mapper.createObjectNode().put("taskId", taskId);
            var killResult = killTool.execute(killParams);
            String killText = killResult.get("content").get(0).get("text").asText();
            assertTrue(killText.contains("successfully killed") || killText.contains("already finished"), "Task termination should be confirmed or handle already finished tasks");
        }
    }

    /**
     * Тестирует выполнение задачи Gradle с таймаутом.
     */
    @Test
    void testGradleHelpWithTimeout() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("task", "help");
        params.put("timeout", 30);

        var result = gradleTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        assertTrue(text.contains("finished with exit code: 0") || text.contains("Gradle task"), "Output should contain task info. Got: " + text);
    }
}
