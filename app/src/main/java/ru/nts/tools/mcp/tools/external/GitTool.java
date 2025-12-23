// Aristo 22.12.2025
package ru.nts.tools.mcp.tools.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.nts.tools.mcp.core.McpTool;
import ru.nts.tools.mcp.core.ProcessExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Инструмент для выполнения базовых операций с системой контроля версий Git.
 * Спроектирован с учетом безопасности:
 * 1. Ограничен списком разрешенных локальных команд.
 * 2. Запрещает любые операции с удаленными репозиториями (push, pull, remote).
 * 3. Использует прямой вызов исполняемого файла Git для исключения shell-инъекций.
 * 4. Поддерживает контроль таймаутов и идентификацию задач.
 */
public class GitTool implements McpTool {

    /**
     * JSON манипулятор.
     */
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Белый список разрешенных подкоманд Git.
     * Включает только информационные и локальные команды модификации индекса/коммитов.
     */
    private static final Set<String> ALLOWED_CMDS = Set.of("status", "diff", "log", "add", "commit", "rev-parse", "branch");

    @Override
    public String getName() {
        return "nts_git_cmd";
    }

    @Override
    public String getDescription() {
        return "Local Git operations (status, diff, log, add, commit). NO remote push/pull.";
    }

    @Override
    public String getCategory() {
        return "external";
    }

    @Override
    public JsonNode getInputSchema() {
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        var props = schema.putObject("properties");

        props.putObject("command").put("type", "string").put("description", "Git subcommand.");

        props.putObject("args").put("type", "string").put("description", "Command arguments.");

        props.putObject("timeout").put("type", "integer").put("description", "Timeout in seconds (REQUIRED).");

        schema.putArray("required").add("command").add("timeout");
        return schema;
    }

    @Override
    public JsonNode execute(JsonNode params) throws Exception {
        String subCmd = params.get("command").asText();
        String extraArgs = params.path("args").asText("");
        long timeout = params.get("timeout").asLong();

        // Валидация подкоманды по белому списку
        if (!ALLOWED_CMDS.contains(subCmd)) {
            throw new SecurityException("Git subcommand '" + subCmd + "' is forbidden. Only local, informational commands are allowed: " + ALLOWED_CMDS);
        }

        List<String> command = new ArrayList<>();
        command.add("git");
        command.add(subCmd);

        if (!extraArgs.isEmpty()) {
            // Безопасный парсинг аргументов: каждый пробельный сегмент становится отдельным аргументом процесса.
            // Это предотвращает попытки выполнить несколько команд через ';' или '|', 
            // так как спецсимволы будут переданы как литеральные строки в Git.
            for (String arg : extraArgs.split("\\s+")) {
                if (!arg.isEmpty()) {
                    command.add(arg);
                }
            }
        }

        // Выполнение команды через защищенное ядро ProcessExecutor с контролем времени
        ProcessExecutor.ExecutionResult result = ProcessExecutor.execute(command, timeout);

        ObjectNode response = mapper.createObjectNode();
        var content = response.putArray("content").addObject();
        content.put("type", "text");

        // Формирование детального отчета для LLM
        StringBuilder sb = new StringBuilder();
        sb.append("Git task [").append(result.taskId()).append("] ");
        if (result.isRunning()) {
            sb.append("STILL RUNNING IN BACKGROUND\n");
        } else {
            sb.append("finished with exit code: ").append(result.exitCode()).append("\n");
        }

        sb.append("\nOutput:\n").append(result.output());

        content.put("text", sb.toString());
        return response;
    }
}
