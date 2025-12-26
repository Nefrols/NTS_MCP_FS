/*
 * Copyright 2025 Aristo
 */
package ru.nts.tools.mcp.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EncodingRobustnessTest {

    @TempDir
    Path tempDir;

    @Test
    void testReadWindows1251MisidentifiedAsUtf8() throws IOException {
        Path file = tempDir.resolve("win1251.txt");
        // "Привет" в windows-1251
        byte[] win1251Bytes = new byte[]{(byte)0xCF, (byte)0xF0, (byte)0xE8, (byte)0xE2, (byte)0xE5, (byte)0xF2};
        Files.write(file, win1251Bytes);

        // EncodingUtils должен понять, что это не UTF-8 (isValidUtf8 провалится) 
        // и откатиться на windows-1251
        EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file);
        
        assertEquals("Привет", content.content());
        assertEquals(Charset.forName("windows-1251"), content.charset());
    }

    @Test
    void testWriteUnmappableCharactersShouldFailDescriptively() throws IOException {
        Path file = tempDir.resolve("unmappable.txt");
        Charset win1251 = Charset.forName("windows-1251");
        
        // Символ '🚀' (Emoji) не существует в windows-1251.
        // Мы хотим, чтобы система НЕ записывала его молча как '?', 
        // а выдавала ошибку, чтобы LLM знала о проблеме.
        String contentWithEmoji = "Rocket: 🚀";
        
        Exception exception = assertThrows(IOException.class, () -> {
            FileUtils.safeWrite(file, contentWithEmoji, win1251);
        });
        
        assertTrue(exception.getMessage().contains("unmappable"), "Error message should contain 'unmappable'");
    }

    @Test
    void testReadMalformedUtf8() throws IOException {
        Path file = tempDir.resolve("malformed.txt");
        // Валидный UTF-8 префикс + битый байт
        byte[] malformedUtf8 = new byte[]{'A', 'B', (byte)0xFF, 'C'};
        Files.write(file, malformedUtf8);

        // Должно прочитаться без исключений (благодаря new String(bytes, charset) в EncodingUtils)
        // Но кодировка должна определиться как windows-1251, так как это не валидный UTF-8
        EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file);
        
        assertNotNull(content.content());
        assertEquals(Charset.forName("windows-1251"), content.charset());
    }
        @Test
        void testReadWithForcedEncoding() throws IOException {
            Path file = tempDir.resolve("forced.txt");
            // "Привет" в windows-1251
            byte[] win1251Bytes = new byte[]{(byte)0xCF, (byte)0xF0, (byte)0xE8, (byte)0xE2, (byte)0xE5, (byte)0xF2};
            Files.write(file, win1251Bytes);

            // Принудительно читаем как windows-1251
            EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file, Charset.forName("windows-1251"));
            
            assertEquals("Привет", content.content());
            assertEquals(Charset.forName("windows-1251"), content.charset());
        }

        @Test
        void testFileConversion() throws IOException {
            Path file = tempDir.resolve("convert.txt");
            // Создаем в windows-1251
            byte[] win1251Bytes = new byte[]{(byte)0xCF, (byte)0xF0, (byte)0xE8, (byte)0xE2, (byte)0xE5, (byte)0xF2};
            Files.write(file, win1251Bytes);

            // В реальности мы бы вызвали EditFileTool с параметром encoding="UTF-8"
            // Здесь имитируем это поведение через FileUtils
            String content = "Привет";
            FileUtils.safeWrite(file, content, StandardCharsets.UTF_8);

            // Проверяем, что теперь файл в UTF-8
            byte[] bytes = Files.readAllBytes(file);
            String readBack = new String(bytes, StandardCharsets.UTF_8);
            assertEquals("Привет", readBack);
            
            // Убеждаемся, что байты соответствуют UTF-8 (Привет = 12 байт в UTF-8)
            assertEquals(12, bytes.length);
        }
            @Test
            void testReadWesternEuropeanEncoding() throws IOException {
                Path file = tempDir.resolve("latin1.txt");
                Charset latin1 = Charset.forName("ISO-8859-1");
                // "Héllò" в ISO-8859-1
                byte[] bytes = new byte[]{'H', (byte)0xE9, 'l', 'l', (byte)0xF2};
                Files.write(file, bytes);

                // Принудительно читаем как ISO-8859-1
                EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file, latin1);
                assertEquals("Héllò", content.content());
            }

            @Test
            void testReadUtf16() throws IOException {
                Path file = tempDir.resolve("utf16.txt");
                String text = "UTF-16 Text";
                Files.writeString(file, text, StandardCharsets.UTF_16);

                // Детектор должен сам справиться с UTF-16 благодаря BOM, который Files.writeString добавляет
                EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file);
                assertEquals(text, content.content());
                assertTrue(content.charset().name().startsWith("UTF-16"));
            }

            @Test
            void testConversionToCustomEncoding() throws IOException {
                Path file = tempDir.resolve("custom_convert.txt");
                Files.writeString(file, "Hello", StandardCharsets.UTF_8);

                // Конвертируем в ISO-8859-1
                Charset target = Charset.forName("ISO-8859-1");
                FileUtils.safeWrite(file, "Héllò", target);

                byte[] result = Files.readAllBytes(file);
                // Héllò в ISO-8859-1 это 5 байт (в UTF-8 было бы 7)
                assertEquals(5, result.length);
                assertEquals((byte)0xE9, result[1]); // 'é'
            }
                @Test
                void testEmptyFile() throws IOException {
                    Path file = tempDir.resolve("empty.txt");
                    Files.createFile(file);

                    EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file);
                    assertEquals("", content.content());
                    
                    assertDoesNotThrow(() -> FileUtils.safeWrite(file, "", StandardCharsets.UTF_8));
                }

                @Test
                void testOnlyBomFile() throws IOException {
                    Path file = tempDir.resolve("only_bom.txt");
                    byte[] bom = new byte[]{(byte)0xEF, (byte)0xBB, (byte)0xBF}; // UTF-8 BOM
                    Files.write(file, bom);

                    EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file, StandardCharsets.UTF_8);
                    assertEquals("", content.content(), "File with only BOM should be empty string");
                }

                @Test
                void testPartialBomShouldNotBeStripped() throws IOException {
                    Path file = tempDir.resolve("partial_bom.txt");
                    byte[] partial = new byte[]{(byte)0xEF, (byte)0xBB}; // Missing 0xBF
                    Files.write(file, partial);

                    // Используем ISO-8859-1, чтобы байты прочитались как отдельные символы
                    EncodingUtils.TextFileContent content = EncodingUtils.readTextFile(file, Charset.forName("ISO-8859-1"));
                    
                    // В ISO-8859-1 это два отдельных символа
                    assertEquals(2, content.content().length());
                }

                @Test
                void testExtremeConversionFailure() throws IOException {
                    Path file = tempDir.resolve("ascii_fail.txt");
                    String russian = "Текст";
                    Charset ascii = StandardCharsets.US_ASCII;

                    // Кириллица не влезает в 7-битный ASCII
                    IOException ex = assertThrows(IOException.class, () -> {
                        FileUtils.safeWrite(file, russian, ascii);
                    });
                    assertTrue(ex.getMessage().contains("unmappable"), "Should report unmappable characters");
                }

                @Test
                void testInvalidCharsetNameFallback() {
                    // Проверяем, что Charset.forName бросает исключение, которое наши инструменты должны обрабатывать
                    assertThrows(Exception.class, () -> Charset.forName("JUNK_ENCODING_NAME"));
                }
            }
