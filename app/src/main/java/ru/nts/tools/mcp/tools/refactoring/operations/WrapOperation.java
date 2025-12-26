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
package ru.nts.tools.mcp.tools.refactoring.operations;

import com.fasterxml.jackson.databind.JsonNode;
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Операция обёртки кода.
 * Оборачивает выделенный код в try-catch, if, loop и другие конструкции.
 */
public class WrapOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "wrap";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required for wrap operation");
        }
        if (!params.has("startLine")) {
            throw new IllegalArgumentException("Parameter 'startLine' is required for wrap operation");
        }
        if (!params.has("wrapper")) {
            throw new IllegalArgumentException("Parameter 'wrapper' is required for wrap operation");
        }

        String wrapper = params.get("wrapper").asText();
        Set<String> validWrappers = Set.of(
                "try_catch", "try_finally", "try_with_resources",
                "if", "if_else", "for", "foreach", "while",
                "synchronized", "custom"
        );

        if (!validWrappers.contains(wrapper)) {
            throw new IllegalArgumentException("Invalid wrapper: '" + wrapper +
                    "'. Valid: " + validWrappers);
        }

        if (wrapper.equals("custom") && !params.has("template")) {
            throw new IllegalArgumentException("Parameter 'template' is required for custom wrapper");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        int startLine = params.get("startLine").asInt();
        int endLine = params.has("endLine") ? params.get("endLine").asInt() : startLine;
        String wrapper = params.get("wrapper").asText();

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Wrap lines " + startLine + "-" + endLine + " in " + wrapper;
        context.beginTransaction(instruction);

        try {
            context.backupFile(path);

            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            // Извлекаем код для обёртки
            List<String> codeToWrap = new ArrayList<>();
            for (int i = startLine - 1; i < endLine && i < lines.size(); i++) {
                codeToWrap.add(lines.get(i));
            }

            // Определяем базовый отступ
            String baseIndent = detectIndent(codeToWrap.get(0));

            // Генерируем обёрнутый код
            List<String> wrappedCode = generateWrapper(
                    wrapper, codeToWrap, baseIndent, langId, params);

            // Заменяем строки
            for (int i = endLine - 1; i >= startLine - 1 && i < lines.size(); i--) {
                lines.remove(i);
            }
            lines.addAll(startLine - 1, wrappedCode);

            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
            context.getTreeManager().invalidateCache(path);

            String txId = context.commitTransaction();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("wrap")
                    .summary(String.format("Wrapped lines %d-%d in %s", startLine, endLine, wrapper))
                    .addChange(new RefactoringResult.FileChange(
                            path, 1,
                            List.of(new RefactoringResult.ChangeDetail(
                                    startLine, 0,
                                    String.join("\n", codeToWrap).trim(),
                                    String.join("\n", wrappedCode).trim())),
                            null, null))
                    .affectedFiles(1)
                    .totalChanges(1)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Wrap failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        int startLine = params.get("startLine").asInt();
        int endLine = params.has("endLine") ? params.get("endLine").asInt() : startLine;
        String wrapper = params.get("wrapper").asText();

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);

            // Извлекаем код для обёртки
            List<String> codeToWrap = new ArrayList<>();
            for (int i = startLine - 1; i < endLine && i < lines.length; i++) {
                codeToWrap.add(lines[i]);
            }

            String baseIndent = detectIndent(codeToWrap.get(0));

            List<String> wrappedCode = generateWrapper(
                    wrapper, codeToWrap, baseIndent, langId, params);

            // Генерируем diff
            StringBuilder diff = new StringBuilder();
            diff.append("--- a/").append(path.getFileName()).append("\n");
            diff.append("+++ b/").append(path.getFileName()).append("\n");
            diff.append("@@ -").append(startLine).append(",").append(endLine - startLine + 1)
                    .append(" +").append(startLine).append(",").append(wrappedCode.size()).append(" @@\n");

            for (String line : codeToWrap) {
                diff.append("-").append(line).append("\n");
            }
            for (String line : wrappedCode) {
                diff.append("+").append(line).append("\n");
            }

            RefactoringResult.FileChange change = new RefactoringResult.FileChange(
                    path, 1,
                    List.of(new RefactoringResult.ChangeDetail(
                            startLine, 0,
                            String.join("\n", codeToWrap).trim(),
                            String.join("\n", wrappedCode).trim())),
                    null, diff.toString()
            );

            return RefactoringResult.preview("wrap", List.of(change));

        } catch (IOException e) {
            throw new RefactoringException("Failed to preview: " + e.getMessage(), e);
        }
    }

    /**
     * Генерирует обёрнутый код.
     */
    private List<String> generateWrapper(String wrapper, List<String> code,
                                          String baseIndent, String langId, JsonNode params) {
        JsonNode options = params.has("options") ? params.get("options") : null;

        return switch (wrapper) {
            case "try_catch" -> wrapTryCatch(code, baseIndent, langId, options);
            case "try_finally" -> wrapTryFinally(code, baseIndent, langId, options);
            case "try_with_resources" -> wrapTryWithResources(code, baseIndent, langId, options);
            case "if" -> wrapIf(code, baseIndent, langId, options);
            case "if_else" -> wrapIfElse(code, baseIndent, langId, options);
            case "for" -> wrapFor(code, baseIndent, langId, options);
            case "foreach" -> wrapForeach(code, baseIndent, langId, options);
            case "while" -> wrapWhile(code, baseIndent, langId, options);
            case "synchronized" -> wrapSynchronized(code, baseIndent, langId, options);
            case "custom" -> wrapCustom(code, baseIndent, params.get("template").asText());
            default -> code;
        };
    }

    private List<String> wrapTryCatch(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String exceptionType = getOption(options, "exceptionType", "Exception");
        String exceptionVar = getOption(options, "exceptionVar", "e");
        String catchBody = getOption(options, "catchBody", null);

        if (langId.equals("java") || langId.equals("kotlin")) {
            result.add(indent + "try {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "} catch (" + exceptionType + " " + exceptionVar + ") {");
            if (catchBody != null) {
                for (String bodyLine : catchBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    " + exceptionVar + ".printStackTrace();");
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "try:");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "except " + exceptionType + " as " + exceptionVar + ":");
            if (catchBody != null) {
                for (String bodyLine : catchBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    print(" + exceptionVar + ")");
            }

        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "try {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "} catch (" + exceptionVar + ") {");
            if (catchBody != null) {
                for (String bodyLine : catchBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    console.error(" + exceptionVar + ");");
            }
            result.add(indent + "}");
        }

        return result;
    }

    private List<String> wrapTryFinally(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String finallyBody = getOption(options, "finallyBody", null);

        if (langId.equals("java") || langId.equals("kotlin") ||
                langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "try {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "} finally {");
            if (finallyBody != null) {
                for (String bodyLine : finallyBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    // cleanup");
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "try:");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "finally:");
            if (finallyBody != null) {
                for (String bodyLine : finallyBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    pass  # cleanup");
            }
        }

        return result;
    }

    private List<String> wrapTryWithResources(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String resource = getOption(options, "resource", "resource");
        String resourceInit = getOption(options, "resourceInit", "new Resource()");

        if (langId.equals("java")) {
            result.add(indent + "try (" + resource + " = " + resourceInit + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "with " + resourceInit + " as " + resource + ":");
            for (String line : code) {
                result.add("    " + line);
            }
        }

        return result;
    }

    private List<String> wrapIf(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String condition = getOption(options, "condition", "true");

        if (langId.equals("java") || langId.equals("kotlin") ||
                langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "if (" + condition + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "if " + condition + ":");
            for (String line : code) {
                result.add("    " + line);
            }

        } else if (langId.equals("go") || langId.equals("rust")) {
            result.add(indent + "if " + condition + " {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");
        }

        return result;
    }

    private List<String> wrapIfElse(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String condition = getOption(options, "condition", "true");
        String elseBody = getOption(options, "elseBody", null);

        if (langId.equals("java") || langId.equals("kotlin") ||
                langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "if (" + condition + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "} else {");
            if (elseBody != null) {
                for (String bodyLine : elseBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    // else branch");
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "if " + condition + ":");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "else:");
            if (elseBody != null) {
                for (String bodyLine : elseBody.split("\n")) {
                    result.add(indent + "    " + bodyLine);
                }
            } else {
                result.add(indent + "    pass  # else branch");
            }
        }

        return result;
    }

    private List<String> wrapFor(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String init = getOption(options, "init", "int i = 0");
        String condition = getOption(options, "condition", "i < 10");
        String update = getOption(options, "update", "i++");

        if (langId.equals("java") || langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "for (" + init + "; " + condition + "; " + update + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            String range = getOption(options, "range", "range(10)");
            String var = getOption(options, "var", "i");
            result.add(indent + "for " + var + " in " + range + ":");
            for (String line : code) {
                result.add("    " + line);
            }
        }

        return result;
    }

    private List<String> wrapForeach(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String item = getOption(options, "item", "item");
        String collection = getOption(options, "collection", "items");

        if (langId.equals("java")) {
            String itemType = getOption(options, "itemType", "var");
            result.add(indent + "for (" + itemType + " " + item + " : " + collection + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "for (const " + item + " of " + collection + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "for " + item + " in " + collection + ":");
            for (String line : code) {
                result.add("    " + line);
            }
        }

        return result;
    }

    private List<String> wrapWhile(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String condition = getOption(options, "condition", "true");

        if (langId.equals("java") || langId.equals("kotlin") ||
                langId.equals("javascript") || langId.equals("typescript")) {
            result.add(indent + "while (" + condition + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("python")) {
            result.add(indent + "while " + condition + ":");
            for (String line : code) {
                result.add("    " + line);
            }
        }

        return result;
    }

    private List<String> wrapSynchronized(List<String> code, String indent, String langId, JsonNode options) {
        List<String> result = new ArrayList<>();
        String lock = getOption(options, "lock", "this");

        if (langId.equals("java")) {
            result.add(indent + "synchronized (" + lock + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");

        } else if (langId.equals("kotlin")) {
            result.add(indent + "synchronized(" + lock + ") {");
            for (String line : code) {
                result.add("    " + line);
            }
            result.add(indent + "}");
        }

        return result;
    }

    private List<String> wrapCustom(List<String> code, String indent, String template) {
        List<String> result = new ArrayList<>();

        // Заменяем ${code} на отступленный код
        String indentedCode = String.join("\n", code);

        String[] templateLines = template.split("\n");
        for (String line : templateLines) {
            if (line.contains("${code}")) {
                // Заменяем placeholder на код
                for (String codeLine : code) {
                    result.add(indent + line.replace("${code}", codeLine.trim()));
                }
            } else {
                result.add(indent + line);
            }
        }

        return result;
    }

    private String getOption(JsonNode options, String key, String defaultValue) {
        if (options != null && options.has(key)) {
            return options.get(key).asText();
        }
        return defaultValue;
    }

    private String detectIndent(String line) {
        StringBuilder indent = new StringBuilder();
        for (char c : line.toCharArray()) {
            if (c == ' ' || c == '\t') {
                indent.append(c);
            } else {
                break;
            }
        }
        return indent.toString();
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }
}
