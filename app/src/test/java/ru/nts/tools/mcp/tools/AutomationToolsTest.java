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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для инструментов автоматизации (Git и Gradle).
 * Проверяют корректность выполнения внешних команд, механизмы поиска wrapper-скриптов
 * и соблюдение политик безопасности при вызове команд ОС.
 */
class AutomationToolsTest {

    /**
     * Тестируемый инструмент запуска Gradle задач.
     */
    private final GradleTool gradleTool = new GradleTool();

    /**
     * Тестируемый инструмент выполнения Git команд.
     */
    private final GitTool gitTool = new GitTool();

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Инициализация окружения перед каждым тестом.
     * Динамически находит корень проекта (наличие gradlew) для обеспечения работоспособности
     * тестов в различных окружениях (IDE, CLI, CI).
     */
    @BeforeEach
    void setUp() {
        // Рекурсивный поиск корня репозитория вверх по дереву каталогов
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
     * Тестирует выполнение базовой команды Git (status).
     * Проверяет успешность вызова и формат возвращаемого отчета.
     */
    @Test
    void testGitStatus() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("command", "status");

        var result = gitTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Код выхода 0 гарантирует наличие Git в системе и корректность аргументов
        assertTrue(text.contains("Git command finished with exit code: 0"), "Git status должен завершаться успешно");
    }

    /**
     * Тестирует механизм блокировки запрещенных команд Git.
     * Убеждается, что попытки выполнения команд работы с удаленными репозиториями (push) пресекаются.
     */
    @Test
    void testGitForbiddenCommand() {
        ObjectNode params = mapper.createObjectNode();
        params.put("command", "push");

        // Ожидаем исключение безопасности
        assertThrows(SecurityException.class, () -> gitTool.execute(params), "Команды сетевого взаимодействия Git должны быть запрещены");
    }

    /**
     * Тестирует запуск стандартной задачи Gradle (help).
     * Проверяет автоматическое обнаружение gradlew и захват вывода процесса.
     */
    @Test
    void testGradleHelp() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("task", "help");

        var result = gradleTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();

        // Проверка успешного завершения задачи
        assertTrue(text.contains("Gradle execution finished with exit code: 0"), "Задача Gradle help должна завершаться успешно");
        // Проверка наличия ключевых слов в выводе
        assertTrue(text.contains("Welcome to Gradle") || text.contains("To see a list of available tasks"), "Вывод должен содержать справочную информацию Gradle");
    }
}