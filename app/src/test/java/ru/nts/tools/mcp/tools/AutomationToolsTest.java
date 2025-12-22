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

import static org.junit.jupiter.api.Assertions.*;

class AutomationToolsTest {
    private final GradleTool gradleTool = new GradleTool();
    private final GitTool gitTool = new GitTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        // Ищем корень проекта (там где лежит gradlew или .git)
        Path current = Paths.get(".").toAbsolutePath().normalize();
        while (current != null) {
            if (new File(current.toFile(), "gradlew").exists() || new File(current.toFile(), "gradlew.bat").exists()) {
                PathSanitizer.setRoot(current);
                return;
            }
            current = current.getParent();
        }
    }

    @Test
    void testGitStatus() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("command", "status");

        var result = gitTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("Git command finished with exit code: 0"));
    }

    @Test
    void testGitForbiddenCommand() {
        ObjectNode params = mapper.createObjectNode();
        params.put("command", "push");

        assertThrows(SecurityException.class, () -> gitTool.execute(params));
    }

    @Test
    void testGradleHelp() throws Exception {
        ObjectNode params = mapper.createObjectNode();
        params.put("task", "help");

        var result = gradleTool.execute(params);
        String text = result.get("content").get(0).get("text").asText();
        
        assertTrue(text.contains("Gradle execution finished with exit code: 0"));
        assertTrue(text.contains("Welcome to Gradle") || text.contains("To see a list of available tasks"));
    }
}
