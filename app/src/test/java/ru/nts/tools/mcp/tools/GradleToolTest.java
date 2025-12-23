// Aristo 23.12.2025
package ru.nts.tools.mcp.tools;

import ru.nts.tools.mcp.tools.fs.*;
import ru.nts.tools.mcp.tools.editing.*;
import ru.nts.tools.mcp.tools.session.*;
import ru.nts.tools.mcp.tools.external.*;
import ru.nts.tools.mcp.tools.planning.*;
import ru.nts.tools.mcp.tools.system.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты для GradleTool.
 * Проверяют логику постобработки вывода (Smart Summary).
 */
class GradleToolTest {

    private final GradleTool tool = new GradleTool();

    @Test
    void testParseSmartSummary() throws Exception {
        String mockOutput = """
                > Task :app:compileJava
                D:/project/App.java:10: error: cannot find symbol
                > Task :app:test
                ru.nts.Test > testSomething() FAILED
                10 tests completed, 1 failed, 0 skipped
                BUILD FAILED in 2s
                """;

        // Используем рефлексию для доступа к приватному методу разбора
        Method method = GradleTool.class.getDeclaredMethod("parseSmartSummary", String.class);
        method.setAccessible(true);
        String summary = (String) method.invoke(tool, mockOutput);

        assertTrue(summary.contains("[TESTS] Total: 10, Failed: 1, Skipped: 0"));
        assertTrue(summary.contains("[ERROR] D:/project/App.java:10"));
        assertTrue(summary.contains("[FAIL] ru.nts.Test > testSomething()"));
        assertTrue(summary.contains("[STATUS] BUILD FAILED"));
    }
}
