// Aristo 22.12.2025
package ru.nts.tools.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Scanner;

/**
 * Утилита для автоматической интеграции MCP сервера в клиентские приложения.
 * Поддерживает:
 * 1. Сборку оптимизированного дистрибутива (installDist).
 * 2. Интеграцию в глобальный конфиг Gemini (.gemini/settings.json).
 * 3. Создание локального конфигурационного файла в корне проекта.
 * 4. Автоматическое создание бэкапов перед изменением настроек.
 * <p>
 * Использование дистрибутива вместо Gradle Wrapper обеспечивает мгновенный запуск
 * и исключает "засорение" стандартного вывода служебными сообщениями сборщика.
 */
public class McpIntegrator {

    /**
     * Манипулятор JSON с поддержкой красивого форматирования.
     */
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    
    /**
     * Имя сервера в конфигурации клиентов.
     */
    private static final String SERVER_NAME = "L2NTS-FileSystem-Server";

    /**
     * Точка входа утилиты интеграции.
     * 
     * @param args Аргументы командной строки.
     */
    public static void main(String[] args) {
        System.out.println("=== MCP Integration & Build Utility ===");
        
        try (Scanner scanner = new Scanner(System.in)) {
            // Определяем корень проекта как текущую рабочую директорию
            Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
            System.out.println("Project root detected: " + projectRoot);

            System.out.println("\nSelect an action:");
            System.out.println("1. Build and Integrate into Gemini Global Config (~/.gemini/settings.json)");
            System.out.println("2. Build and Create local mcp-config.json in project root");
            System.out.println("3. Build project only (installDist)");
            System.out.println("4. Exit");
            System.out.print("> ");

            if (!scanner.hasNextLine()) return;
            String choice = scanner.nextLine();

            switch (choice) {
                case "1" -> {
                    if (buildProject(projectRoot)) {
                        integrateWithGemini(projectRoot);
                    }
                }
                case "2" -> {
                    if (buildProject(projectRoot)) {
                        createLocalConfig(projectRoot);
                    }
                }
                case "3" -> buildProject(projectRoot);
                default -> System.out.println("Exiting...");
            }
        } catch (Exception e) {
            System.err.println("Critical error during process: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Запускает сборку дистрибутива через Gradle Task 'installDist'.
     * Создает в app/build/install/app готовую структуру с бинарными файлами и всеми зависимостями.
     * 
     * @param projectRoot Корень проекта.
     * @return true если сборка прошла успешно.
     */
    private static boolean buildProject(Path projectRoot) {
        System.out.println("\nStarting build process (installDist)...");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String gradlew = isWindows ? "gradlew.bat" : "./gradlew";
        Path gradlewPath = projectRoot.resolve(gradlew);

        try {
            ProcessBuilder pb = new ProcessBuilder(gradlewPath.toString(), "installDist", "--quiet");
            pb.directory(projectRoot.toFile());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Чтение вывода сборки в реальном времени
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("  [gradle] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Build successful!");
                return true;
            } else {
                System.err.println("Build failed with exit code: " + exitCode);
                return false;
            }
        } catch (Exception e) {
            System.err.println("Failed to execute build command: " + e.getMessage());
            return false;
        }
    }

    /**
     * Выполняет поиск и модификацию глобальных настроек расширения Gemini.
     * Перед любым изменением создается страховая копия оригинального файла.
     * 
     * @param projectRoot Абсолютный путь к корню проекта.
     * @throws IOException При ошибках работы с файловой системой.
     */
    private static void integrateWithGemini(Path projectRoot) throws IOException {
        String userHome = System.getProperty("user.home");
        Path geminiDir = Paths.get(userHome, ".gemini");
        Path settingsFile = geminiDir.resolve("settings.json");

        if (!Files.exists(settingsFile)) {
            System.err.println("Gemini settings not found at expected path: " + settingsFile);
            return;
        }

        // Создание бэкапа с временной меткой в названии
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupFile = settingsFile.resolveSibling("settings.json.bak_" + timestamp);
        Files.copy(settingsFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Backup created: " + backupFile);

        // Чтение текущего конфига
        JsonNode rootNode = mapper.readTree(settingsFile.toFile());
        ObjectNode root = (rootNode != null && rootNode.isObject()) ? (ObjectNode) rootNode : mapper.createObjectNode();
        
        // Безопасный поиск секции mcpServers
        JsonNode mcpServersNode = root.path("mcpServers");
        ObjectNode mcpServers = mcpServersNode.isObject() ? (ObjectNode) mcpServersNode : root.putObject("mcpServers");

        // Добавление/обновление конфигурации нашего сервера (теперь указывает на бинарный дистрибутив)
        mcpServers.set(SERVER_NAME, createServerNode(projectRoot));

        // Сохранение изменений
        mapper.writeValue(settingsFile.toFile(), root);
        System.out.println("Integration successful! Please restart your Gemini client.");
    }

    /**
     * Генерирует независимый JSON файл конфигурации в корне текущего проекта.
     * 
     * @param projectRoot Абсолютный путь к корню проекта.
     * @throws IOException При ошибках записи файла.
     */
    private static void createLocalConfig(Path projectRoot) throws IOException {
        Path configFile = projectRoot.resolve("mcp-config.json");
        ObjectNode config = mapper.createObjectNode();
        
        ObjectNode servers = config.putObject("mcpServers");
        servers.set(SERVER_NAME, createServerNode(projectRoot));

        mapper.writeValue(configFile.toFile(), config);
        System.out.println("Local config created: " + configFile);
    }

    /**
     * Создает узел конфигурации сервера, указывающий на скомпилированный бинарный файл дистрибутива.
     * 
     * @param projectRoot Путь к проекту.
     * @return Объект JSON с параметрами запуска.
     */
    private static ObjectNode createServerNode(Path projectRoot) {
        ObjectNode server = mapper.createObjectNode();
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        // Формируем путь к исполняемому скрипту в папке installDist
        Path binPath = projectRoot.resolve("app/build/install/app/bin");
        Path executable = binPath.resolve(isWindows ? "app.bat" : "app");
        
        if (!Files.exists(executable)) {
            System.err.println("Warning: Binary executable not found at: " + executable);
            System.err.println("Make sure you chose a 'Build' option or run 'gradlew installDist' manually.");
        }

        server.put("command", executable.toString());
        server.putArray("args"); // В дистрибутиве аргументы для запуска основного класса уже встроены в скрипку

        // Пробрасываем корень проекта в переменные окружения
        ObjectNode env = server.putObject("env");
        env.put("PROJECT_ROOT", projectRoot.toString());
        
        return server;
    }
}
