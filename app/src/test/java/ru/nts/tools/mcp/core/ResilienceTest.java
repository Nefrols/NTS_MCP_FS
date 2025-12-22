// Aristo 22.12.2025
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.nts.tools.mcp.tools.ReadFileTool;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ResilienceTest {
    private final ReadFileTool readFileTool = new ReadFileTool();

    @Test
    void testBinaryFileDetection(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path binFile = tempDir.resolve("binary.dat");
        
        // Создаем файл с NULL-байтом
        try (FileOutputStream fos = new FileOutputStream(binFile.toFile())) {
            fos.write(new byte[]{0, 1, 2, 3});
        }

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var params = mapper.createObjectNode().put("path", "binary.dat");

        // Ожидаем ошибку детекции кодировки (бинарный файл)
        assertThrows(java.io.IOException.class, () -> readFileTool.execute(params));
    }

    @Test
    void testLargeFileProtection(@TempDir Path tempDir) throws Exception {
        PathSanitizer.setRoot(tempDir);
        Path largeFile = tempDir.resolve("huge.txt");
        
        // Создаем файл > 10MB
        byte[] data = new byte[11 * 1024 * 1024];
        Files.write(largeFile, data);

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var params = mapper.createObjectNode().put("path", "huge.txt");

        // Ожидаем SecurityException (размер превышен)
        assertThrows(SecurityException.class, () -> readFileTool.execute(params));
    }
}
