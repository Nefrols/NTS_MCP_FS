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
  * 1. Обнаружение установленных клиентов (Gemini, LM Studio, Claude, Cursor, Antigravity, Copilot).
 * 2. Интерактивное добавление и удаление сервера из конфигураций.
 * 3. Сборку оптимизированного shadow JAR.
 * 4. Создание локального конфигурационного файла в корне проекта.
 * 5. Автоматическое создание бэкапов перед изменением настроек.
 */
public class McpIntegrator {

    /**
     * Манипулятор JSON с поддержкой красивого форматирования.
     */
    private static final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    
    /**
     * Имя сервера в конфигурации клиентов.
     */
    private static final String SERVER_NAME = "NTS-FileSystem-MCP";

    /**
     * Запись о поддерживаемом клиенте.
     */
    private record ClientInfo(String name, Path configPath) {}

    /**
     * Точка входа утилиты управления интеграциями.
     * Проводит поиск доступных клиентов и предлагает действия по управлению соединением.
     */
    public static void run() {
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

                int nextAction = clients.size() + 1;
                System.out.println("\nActions:");
                System.out.printf("1-%d. Install/Uninstall for specific client\n", clients.size());
                System.out.printf("%d.   Build and Create local mcp-config.json in project root\n", nextAction++);
                System.out.printf("%d.   Build project only (shadowJar)\n", nextAction++);
                System.out.printf("%d.   Exit\n", nextAction);
                System.out.print("> ");

                if (!scanner.hasNextLine()) break;
                String choiceStr = scanner.nextLine();

                if (String.valueOf(nextAction).equals(choiceStr)) {
                    System.out.println("Exiting...");
                    break;
                }

                if (String.valueOf(nextAction - 2).equals(choiceStr)) {
                    if (buildProject(projectRoot)) createLocalConfig(projectRoot);
                    continue;
                }
                if (String.valueOf(nextAction - 1).equals(choiceStr)) {
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
                            if (isRunningFromJar() || buildProject(projectRoot)) {
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

             // Antigravity
             clients.add(new ClientInfo("Antigravity", Paths.get(userHome, ".gemini", "antigravity", "mcp_config.json")));

             // GitHub Copilot (VS Code)
             String appData = System.getenv("APPDATA");
             if (appData != null) {
                 clients.add(new ClientInfo("Copilot (VS Code)", Paths.get(appData, "Code", "User", "mcp.json")));
             }

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
     * Проверяет, запущено ли приложение из JAR файла.
     * Используется для предотвращения лишних сборок проекта во время интеграции.
     */
    private static boolean isRunningFromJar() {
        try {
            var res = McpIntegrator.class.getResource("McpIntegrator.class");
            return res != null && "jar".equals(res.getProtocol());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Возвращает путь к JAR файлу, в котором запущен этот класс.
     *
     * @return Путь к JAR или null, если запущено не из JAR.
     */
    private static Path getRunningJarPath() {
        try {
            var location = McpIntegrator.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) return null;
            return Paths.get(location.toURI()).toAbsolutePath().normalize();
        } catch (Exception e) {
            return null;
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
     * Запускает сборку shadow JAR.
     * 
     * @param projectRoot Корень проекта.
     * @return true если сборка прошла успешно.
     */
    private static boolean buildProject(Path projectRoot) {
        System.out.println("\nStarting build process (shadowJar)...");
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        String gradlew = isWindows ? "gradlew.bat" : "./gradlew";
        Path gradlewPath = projectRoot.resolve(gradlew);

        try {
            ProcessBuilder pb = new ProcessBuilder(gradlewPath.toString(), "shadowJar", "--quiet");
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
     * Создает узел конфигурации сервера, указывающий на shadow JAR.
     * 
     * @param projectRoot Путь к проекту.
     * @return Объект JSON с параметрами запуска.
     */
    private static ObjectNode createServerNode(Path projectRoot) {
        ObjectNode server = mapper.createObjectNode();
        
        Path jarPath;
        if (isRunningFromJar()) {
            jarPath = getRunningJarPath();
            System.out.println("Using running JAR for integration: " + jarPath);
        } else {
            // Формируем путь к shadow JAR относительно корня проекта
            jarPath = projectRoot.resolve("app/build/libs/app-all.jar");
            if (!Files.exists(jarPath)) {
                System.err.println("Warning: Shadow JAR not found at: " + jarPath);
                System.err.println("Make sure you run 'Build' or 'gradlew shadowJar' manually.");
            }
        }

        server.put("command", "java");
        server.putArray("args").add("-jar").add(jarPath.toString());

        return server;
    }
}
