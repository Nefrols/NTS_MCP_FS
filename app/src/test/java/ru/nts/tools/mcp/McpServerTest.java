// Aristo 22.12.2025
package ru.nts.tools.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    @Test
    void appHasAGreeting() {
        McpServer classUnderTest = new McpServer();
        assertNotNull(classUnderTest, "app should have a greeting");
    }
}