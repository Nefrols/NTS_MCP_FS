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
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.tools.external.GradleTool;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тесты для инструмента Gradle (GradleTool).
 */
class GradleToolTest {

    private final GradleTool tool = new GradleTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void testParseSmartSummary() throws Exception {
        Method method = GradleTool.class.getDeclaredMethod("parseSmartSummary", String.class);
        method.setAccessible(true);

        String output = "10 tests completed, 2 failed, 0 skipped\n" +
                        "MyFile.java:42: error: something went wrong\n" +
                        "com.test.MyTest > testSomething FAILED\n" +
                        "BUILD FAILED";

        String summary = (String) method.invoke(tool, output);
        assertTrue(summary.contains("[TESTS] Total: 10, Failed: 2"));
        assertTrue(summary.contains("[ERROR] MyFile.java:42"));
        assertTrue(summary.contains("[FAIL] com.test.MyTest > testSomething"));
        assertTrue(summary.contains("[STATUS] BUILD FAILED"));
    }

    @Test
    void testProgressExtraction() throws Exception {
        Method method = GradleTool.class.getDeclaredMethod("extractProgress", String.class);
        method.setAccessible(true);

        String output = "Starting Build\n" +
                        "> :app:compileJava [45%]\n" +
                        "> :app:processResources [80%]\n" +
                        "Some other logs";

        String progress = (String) method.invoke(tool, output);
        assertEquals("80%", progress);

        String noProgress = "Build failed quickly";
        assertNull(method.invoke(tool, noProgress));
    }
}