// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Юнит-тест для проверки логики извлечения прогресса из вывода Gradle.
 */
class GradleProgressTest {

    private final GradleTool tool = new GradleTool();

    @Test
    void testProgressExtraction() throws Exception {
        Method method = GradleTool.class.getDeclaredMethod("extractProgress", String.class);
        method.setAccessible(true);

        String output = "Starting Build\n" +
                        "> :app:compileJava [45%]\n" +
                        "> :app:processResources [80%]\n" +
                        "Some other logs";

        String progress = (String) method.invoke(tool, output);
        assertEquals("80%", progress, "Должен быть извлечен последний найденный процент");

        String noProgress = "Build failed quickly";
        assertNull(method.invoke(tool, noProgress), "Если прогресса нет, должен вернуться null");
    }
}