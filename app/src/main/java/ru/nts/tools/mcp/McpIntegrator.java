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
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * Утилита для автоматической интеграции и управления MCP сервером в клиентских приложениях.
 * Поддерживает:
 * 1. Обнаружение установленных клиентов (Gemini, LM Studio, Claude, Cursor).
 * 2. Интерактивное добавление и удаление сервера из конфигураций.
 * 3. Сборку оптимизированного дистрибутива (installDist).
 * 4. Создание локального конфигурационного файла в корне проекта.
 * 5. Автоматическое создание бэкапов перед изменением настроек.
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
    private static final String SERVER_NAME = "NTS-FileSystem-Server";

    /**
     * Запись о поддерживаемом клиенте.
     */
    private record ClientInfo(String name, Path configPath) {}

    /**
     * Точка входа утилиты управления интеграциями.
     * Проводит поиск доступных клиентов и предлагает действия по управлению соединением.
     * 
     * @param args Аргументы командной строки.
     */
    public static void main(String[] args) {
        System.out.println("=== MCP Connection Manager ===");
        
        try (Scanner scanner = new Scanner(System.in)) {
            // Определяем корень проекта как текущую рабочую директорию
            Path projectRoot = Paths.get(".").toAbsolutePath().normalize();
            System.out.println("Project root detected: " + projectRoot);

            while (true) {
                System.out.println("\n--- Discovery & Integration Status ---");
                
                List<ClientInfo> clients = discoverClients();
                for (int i = 0; i < clients.size(); i++) {
                    ClientInfo client = clients.get(i);
                    boolean found = Files.exists(client.configPath());
                    boolean integrated = found && isIntegrated(client.configPath());
                    
                    String status = integrated ? "INTEGRATED" : (found ? "FOUND" : "NOT FOUND");
                    System.out.printf("%d. [ %-10s ] %-10s (%s)\n", i + 1, status, client.name(), client.configPath());
                }

                System.out.println("\nActions:");
                System.out.println("1-5. Install/Uninstall for specific client");
                System.out.println("6.   Build and Create local mcp-config.json in project root");
                System.out.println("7.   Build project only (installDist)");
                System.out.println("8.   Exit");
                System.out.print("> ");

                if (!scanner.hasNextLine()) break;
                String choiceStr = scanner.nextLine();

                if ("8".equals(choiceStr)) {
                    System.out.println("Exiting...");
                    break;
                }

                if ("6".equals(choiceStr)) {
                    if (buildProject(projectRoot)) createLocalConfig(projectRoot);
                    continue;
                }
                if ("7".equals(choiceStr)) {
                    buildProject(projectRoot);
                    continue;
                }

                try {
                    int choice = Integer.parseInt(choiceStr);
                    if (choice >= 1 && choice <= clients.size()) {
                        ClientInfo client = clients.get(choice - 1);
                        if (!Files.exists(client.configPath())) {
                            System.out.println("Client configuration file not found.");
                            continue;
                        }

                        if (isIntegrated(client.configPath())) {
                            uninstall(client.configPath());
                        } else {
                            if (buildProject(projectRoot)) {
                                install(client.configPath(), projectRoot);
                            }
                        }
                    } else {
                        System.out.println("Invalid choice.");
                    }
                } catch (NumberFormatException e) {
                    System.out.println("Please enter a valid number.");
                }
            }
        } catch (Exception e) {
            System.err.println("Critical error during process: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Собирает список путей к конфигурациям известных MCP клиентов.
     */
    private static List<ClientInfo> discoverClients() {
        List<ClientInfo> clients = new ArrayList<>();
        String userHome = System.getProperty("user.home");

        // Gemini
        clients.add(new ClientInfo("Gemini CLI", Paths.get(userHome, ".gemini", "settings.json")));

        // Claude Code
        clients.add(new ClientInfo("Claude Code", Paths.get(userHome, ".claude.json")));

        // Qwen
        clients.add(new ClientInfo("Qwen CLI", Paths.get(userHome, ".qwen", "settings.json")));

        // Cursor
        clients.add(new ClientInfo("Cursor", Paths.get(userHome, ".cursor", "mcp.json")));
        
        // LM Studio
        clients.add(new ClientInfo("LM Studio", Paths.get(userHome, ".lmstudio", "mcp.json")));

        return clients;
    }

    /**
     * Проверяет, зарегистрирован ли уже наш сервер в указанном файле конфигурации.
     * 
     * @param settingsFile Путь к файлу настроек.
     * @return true если сервер найден в списке mcpServers.
     */
    private static boolean isIntegrated(Path settingsFile) {
        try {
            JsonNode root = mapper.readTree(settingsFile.toFile());
            return root.path("mcpServers").has(SERVER_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Устанавливает конфигурацию сервера в указанный файл.
     */
    private static void install(Path settingsFile, Path projectRoot) throws IOException {
        System.out.println("Installing to " + settingsFile.getFileName() + "...");
        createBackup(settingsFile);

        JsonNode rootNode = mapper.readTree(settingsFile.toFile());
        ObjectNode root = (rootNode != null && rootNode.isObject()) ? (ObjectNode) rootNode : mapper.createObjectNode();
        
        JsonNode mcpServersNode = root.path("mcpServers");
        ObjectNode mcpServers = mcpServersNode.isObject() ? (ObjectNode) mcpServersNode : root.putObject("mcpServers");

        mcpServers.set(SERVER_NAME, createServerNode(projectRoot));

        mapper.writeValue(settingsFile.toFile(), root);
        System.out.println("Installation successful!");
    }

    /**
     * Удаляет конфигурацию нашего сервера из указанного файла.
     * Перед изменением создается бэкап.
     * 
     * @param settingsFile Путь к файлу настроек.
     * @throws IOException При ошибках записи.
     */
    private static void uninstall(Path settingsFile) throws IOException {
        System.out.println("Uninstalling from " + settingsFile.getFileName() + "...");
        createBackup(settingsFile);

        ObjectNode root = (ObjectNode) mapper.readTree(settingsFile.toFile());
        JsonNode mcpServers = root.path("mcpServers");
        if (mcpServers.isObject()) {
            ((ObjectNode) mcpServers).remove(SERVER_NAME);
            mapper.writeValue(settingsFile.toFile(), root);
            System.out.println("Successfully removed.");
        }
    }

    /**
     * Создает страховую копию файла с временной меткой.
     * 
     * @param file Файл для бэкапа.
     * @throws IOException При ошибках копирования.
     */
    private static void createBackup(Path file) throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupFile = file.resolveSibling(file.getFileName().toString() + ".bak_" + timestamp);
        Files.copy(file, backupFile, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Backup created: " + backupFile);
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
