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
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Операция изменения сигнатуры метода.
 * Позволяет добавлять, удалять, переименовывать и переупорядочивать параметры,
 * менять тип возврата и модификаторы доступа.
 */
public class ChangeSignatureOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "change_signature";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required");
        }
        if (!params.has("methodName") && !params.has("symbol") && !params.has("line")) {
            throw new IllegalArgumentException("Either 'methodName', 'symbol', or 'line' is required");
        }
        // Должен быть хотя бы один параметр изменения
        if (!params.has("newName") && !params.has("parameters") &&
                !params.has("returnType") && !params.has("accessModifier")) {
            throw new IllegalArgumentException(
                    "At least one change is required: newName, parameters, returnType, or accessModifier");
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        // Находим метод
        SymbolInfo method = findMethod(params, path, context);
        if (method == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("methodName") ? params.get("methodName").asText() : "method",
                    path.toString());
        }

        // Парсим текущую сигнатуру
        SignatureInfo currentSignature = parseSignature(method, path, langId);

        // Строим новую сигнатуру
        SignatureInfo newSignature = buildNewSignature(currentSignature, params);

        // Находим все вызовы
        List<Location> callSites = findCallSites(method, path, scope, context);

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Change signature of '" + method.name() + "'";
        context.beginTransaction(instruction);

        try {
            List<RefactoringResult.FileChange> changes = new ArrayList<>();

            // 1. Обновляем объявление метода
            RefactoringResult.FileChange declChange = updateDeclaration(
                    method, currentSignature, newSignature, path, langId, context);
            changes.add(declChange);

            // 2. Обновляем все вызовы
            Map<Path, List<Location>> callsByFile = callSites.stream()
                    .collect(Collectors.groupingBy(Location::path));

            for (Map.Entry<Path, List<Location>> entry : callsByFile.entrySet()) {
                Path filePath = entry.getKey();
                List<Location> fileCalls = entry.getValue();

                RefactoringResult.FileChange callChange = updateCallSites(
                        fileCalls, currentSignature, newSignature, filePath, langId, context);
                if (callChange != null) {
                    changes.add(callChange);
                }
            }

            String txId = context.commitTransaction();

            int totalChanges = changes.stream()
                    .mapToInt(RefactoringResult.FileChange::occurrences)
                    .sum();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("change_signature")
                    .summary(String.format("Changed signature of '%s' and updated %d call site(s)",
                            newSignature.name, callSites.size()))
                    .changes(changes)
                    .affectedFiles(changes.size())
                    .totalChanges(totalChanges)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Change signature failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String scope = params.has("scope") ? params.get("scope").asText() : "project";

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        SymbolInfo method = findMethod(params, path, context);
        if (method == null) {
            throw RefactoringException.symbolNotFound(
                    params.has("methodName") ? params.get("methodName").asText() : "method",
                    path.toString());
        }

        SignatureInfo currentSignature = parseSignature(method, path, langId);
        SignatureInfo newSignature = buildNewSignature(currentSignature, params);
        List<Location> callSites = findCallSites(method, path, scope, context);

        // Генерируем preview
        List<RefactoringResult.FileChange> changes = new ArrayList<>();

        StringBuilder diff = new StringBuilder();
        diff.append("=== Signature Change ===\n");
        diff.append("-").append(formatSignature(currentSignature, langId)).append("\n");
        diff.append("+").append(formatSignature(newSignature, langId)).append("\n");

        if (!callSites.isEmpty()) {
            diff.append("\n=== Call Sites (").append(callSites.size()).append(") ===\n");
            for (Location call : callSites) {
                diff.append("  ").append(call.path().getFileName())
                        .append(":").append(call.startLine()).append("\n");
            }
        }

        changes.add(new RefactoringResult.FileChange(
                path, 1 + callSites.size(),
                List.of(new RefactoringResult.ChangeDetail(
                        method.location().startLine(), 0,
                        formatSignature(currentSignature, langId),
                        formatSignature(newSignature, langId))),
                null, diff.toString()));

        return RefactoringResult.preview("change_signature", changes);
    }

    private SymbolInfo findMethod(JsonNode params, Path path, RefactoringContext context)
            throws RefactoringException {
        try {
            // Поддержка как methodName, так и symbol (унификация с CodeRefactorTool schema)
            String methodName = null;
            if (params.has("methodName")) {
                methodName = params.get("methodName").asText();
            } else if (params.has("symbol")) {
                methodName = params.get("symbol").asText();
            }

            if (methodName != null) {
                final String searchName = methodName;
                List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);
                return symbols.stream()
                        .filter(s -> s.name().equals(searchName))
                        .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.FUNCTION)
                        .findFirst()
                        .orElse(null);
            } else if (params.has("line")) {
                int line = params.get("line").asInt();
                int column = params.has("column") ? params.get("column").asInt() : 1;
                return context.getSymbolResolver().hover(path, line, column)
                        .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.FUNCTION)
                        .orElse(null);
            }
            return null;
        } catch (IOException e) {
            throw new RefactoringException("Failed to find method: " + e.getMessage(), e);
        }
    }

    /**
     * Парсит текущую сигнатуру метода.
     */
    private SignatureInfo parseSignature(SymbolInfo method, Path path, String langId)
            throws RefactoringException {
        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n", -1);
            int lineIndex = method.location().startLine() - 1;

            if (lineIndex < 0 || lineIndex >= lines.length) {
                throw new RefactoringException("Method line out of range");
            }

            String line = lines[lineIndex];
            return parseSignatureLine(line, method.name(), langId);

        } catch (IOException e) {
            throw new RefactoringException("Failed to read file: " + e.getMessage(), e);
        }
    }

    /**
     * Парсит строку с сигнатурой метода.
     */
    private SignatureInfo parseSignatureLine(String line, String methodName, String langId) {
        String accessModifier = "public";
        String returnType = "void";
        List<ParameterInfo> parameters = new ArrayList<>();

        if (langId.equals("java") || langId.equals("kotlin")) {
            // Паттерн: [модификатор] [тип] имя(параметры)
            Pattern sigPattern = Pattern.compile(
                    "(public|private|protected|internal)?\\s*" +
                            "(static\\s+)?" +
                            "(?:fun\\s+)?" + // Kotlin
                            "([\\w<>\\[\\],\\s]+?)\\s+" +
                            Pattern.quote(methodName) +
                            "\\s*\\(([^)]*)\\)");
            Matcher matcher = sigPattern.matcher(line);

            if (matcher.find()) {
                if (matcher.group(1) != null) {
                    accessModifier = matcher.group(1);
                }
                returnType = matcher.group(3).trim();
                String paramsStr = matcher.group(4).trim();
                parameters = parseParameters(paramsStr, langId);
            }
        } else if (langId.equals("python")) {
            // Python: def name(self, param1, param2):
            Pattern sigPattern = Pattern.compile(
                    "def\\s+" + Pattern.quote(methodName) + "\\s*\\(([^)]*)\\)");
            Matcher matcher = sigPattern.matcher(line);
            if (matcher.find()) {
                String paramsStr = matcher.group(1).trim();
                parameters = parseParameters(paramsStr, langId);
            }
            accessModifier = methodName.startsWith("_") ? "private" : "public";
            returnType = ""; // Python doesn't have explicit return type in def
        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            // JS/TS: [async] name(params) или function name(params)
            Pattern sigPattern = Pattern.compile(
                    "(?:async\\s+)?(?:function\\s+)?" +
                            Pattern.quote(methodName) +
                            "\\s*\\(([^)]*)\\)(?:\\s*:\\s*([\\w<>\\[\\]]+))?");
            Matcher matcher = sigPattern.matcher(line);
            if (matcher.find()) {
                String paramsStr = matcher.group(1).trim();
                parameters = parseParameters(paramsStr, langId);
                if (matcher.group(2) != null) {
                    returnType = matcher.group(2);
                }
            }
        }

        return new SignatureInfo(methodName, accessModifier, returnType, parameters, line);
    }

    /**
     * Парсит список параметров.
     */
    private List<ParameterInfo> parseParameters(String paramsStr, String langId) {
        List<ParameterInfo> params = new ArrayList<>();
        if (paramsStr.isEmpty()) {
            return params;
        }

        String[] parts = paramsStr.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.equals("self") || trimmed.equals("this")) {
                continue;
            }

            ParameterInfo param = parseParameter(trimmed, langId);
            if (param != null) {
                params.add(param);
            }
        }

        return params;
    }

    /**
     * Парсит один параметр.
     */
    private ParameterInfo parseParameter(String param, String langId) {
        if (langId.equals("java") || langId.equals("kotlin")) {
            // Java: Type name или Type... name
            Pattern pattern = Pattern.compile("([\\w<>\\[\\].]+)\\s+(\\w+)");
            Matcher matcher = pattern.matcher(param);
            if (matcher.find()) {
                return new ParameterInfo(matcher.group(2), matcher.group(1), null);
            }
        } else if (langId.equals("python")) {
            // Python: name или name: type или name=default
            Pattern pattern = Pattern.compile("(\\w+)(?:\\s*:\\s*([\\w\\[\\]]+))?(?:\\s*=\\s*(.+))?");
            Matcher matcher = pattern.matcher(param);
            if (matcher.find()) {
                String name = matcher.group(1);
                String type = matcher.group(2) != null ? matcher.group(2) : "Any";
                String defaultVal = matcher.group(3);
                return new ParameterInfo(name, type, defaultVal);
            }
        } else if (langId.equals("javascript") || langId.equals("typescript")) {
            // TS: name: type или name: type = default
            Pattern pattern = Pattern.compile("(\\w+)(?:\\s*:\\s*([\\w<>\\[\\]]+))?(?:\\s*=\\s*(.+))?");
            Matcher matcher = pattern.matcher(param);
            if (matcher.find()) {
                String name = matcher.group(1);
                String type = matcher.group(2) != null ? matcher.group(2) : "any";
                String defaultVal = matcher.group(3);
                return new ParameterInfo(name, type, defaultVal);
            }
        }

        // Fallback: просто имя
        return new ParameterInfo(param.trim(), "Object", null);
    }

    /**
     * Строит новую сигнатуру на основе изменений.
     */
    private SignatureInfo buildNewSignature(SignatureInfo current, JsonNode params) {
        String newName = params.has("newName")
                ? params.get("newName").asText() : current.name;
        String newAccessModifier = params.has("accessModifier")
                ? params.get("accessModifier").asText() : current.accessModifier;
        String newReturnType = params.has("returnType")
                ? params.get("returnType").asText() : current.returnType;

        List<ParameterInfo> newParams;
        if (params.has("parameters")) {
            newParams = buildNewParameters(current.parameters, params.get("parameters"));
        } else {
            newParams = new ArrayList<>(current.parameters);
        }

        return new SignatureInfo(newName, newAccessModifier, newReturnType, newParams, "");
    }

    /**
     * Строит новый список параметров на основе изменений.
     */
    private List<ParameterInfo> buildNewParameters(List<ParameterInfo> current, JsonNode paramsNode) {
        List<ParameterInfo> result = new ArrayList<>();

        // paramsNode может быть:
        // 1. Массивом объектов {name, type, defaultValue, action}
        // 2. Объектом с add/remove/reorder полями

        if (paramsNode.isArray()) {
            for (JsonNode paramNode : paramsNode) {
                String action = paramNode.has("action") ? paramNode.get("action").asText() : "keep";

                if (action.equals("add")) {
                    String name = paramNode.get("name").asText();
                    String type = paramNode.has("type") ? paramNode.get("type").asText() : "Object";
                    String defaultVal = paramNode.has("defaultValue")
                            ? paramNode.get("defaultValue").asText() : null;
                    result.add(new ParameterInfo(name, type, defaultVal));

                } else if (action.equals("keep") || action.equals("rename") || action.equals("retype")) {
                    String name = paramNode.get("name").asText();
                    ParameterInfo existing = current.stream()
                            .filter(p -> p.name.equals(name))
                            .findFirst()
                            .orElse(null);

                    if (existing != null) {
                        String newName = paramNode.has("newName")
                                ? paramNode.get("newName").asText() : existing.name;
                        String newType = paramNode.has("type")
                                ? paramNode.get("type").asText() : existing.type;
                        String defaultVal = paramNode.has("defaultValue")
                                ? paramNode.get("defaultValue").asText() : existing.defaultValue;
                        result.add(new ParameterInfo(newName, newType, defaultVal));
                    }
                }
                // action == "remove" - не добавляем в результат
            }
        } else if (paramsNode.isObject()) {
            // Обрабатываем add/remove/reorder
            Set<String> toRemove = new HashSet<>();
            if (paramsNode.has("remove")) {
                for (JsonNode r : paramsNode.get("remove")) {
                    toRemove.add(r.asText());
                }
            }

            // Сначала добавляем существующие (кроме удалённых)
            for (ParameterInfo p : current) {
                if (!toRemove.contains(p.name)) {
                    result.add(p);
                }
            }

            // Добавляем новые
            if (paramsNode.has("add")) {
                for (JsonNode addNode : paramsNode.get("add")) {
                    String name = addNode.get("name").asText();
                    String type = addNode.has("type") ? addNode.get("type").asText() : "Object";
                    String defaultVal = addNode.has("defaultValue")
                            ? addNode.get("defaultValue").asText() : null;
                    int position = addNode.has("position")
                            ? addNode.get("position").asInt() : result.size();
                    result.add(Math.min(position, result.size()),
                            new ParameterInfo(name, type, defaultVal));
                }
            }

            // Переупорядочиваем
            if (paramsNode.has("reorder")) {
                List<String> order = new ArrayList<>();
                for (JsonNode r : paramsNode.get("reorder")) {
                    order.add(r.asText());
                }
                result.sort(Comparator.comparingInt(p -> {
                    int idx = order.indexOf(p.name);
                    return idx >= 0 ? idx : Integer.MAX_VALUE;
                }));
            }
        }

        return result;
    }

    /**
     * Находит все места вызова метода.
     */
    private List<Location> findCallSites(SymbolInfo method, Path path, String scope,
                                          RefactoringContext context) throws RefactoringException {
        try {
            List<Location> refs = context.getSymbolResolver().findReferences(
                    path, method.location().startLine(), method.location().startColumn(),
                    scope, false);

            // Убираем само объявление
            Location defLoc = method.location();
            return refs.stream()
                    .filter(r -> !(r.path().equals(defLoc.path()) &&
                            r.startLine() == defLoc.startLine()))
                    .toList();

        } catch (IOException e) {
            throw new RefactoringException("Failed to find call sites: " + e.getMessage(), e);
        }
    }

    /**
     * Обновляет объявление метода.
     */
    private RefactoringResult.FileChange updateDeclaration(
            SymbolInfo method, SignatureInfo oldSig, SignatureInfo newSig,
            Path path, String langId, RefactoringContext context) throws RefactoringException {
        try {
            context.backupFile(path);

            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            int lineIndex = method.location().startLine() - 1;
            String oldLine = lines.get(lineIndex);

            // Генерируем новую строку с сигнатурой
            String newLine = replaceSignature(oldLine, oldSig, newSig, langId);
            lines.set(lineIndex, newLine);

            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
            context.getTreeManager().invalidateCache(path);

            return new RefactoringResult.FileChange(
                    path, 1,
                    List.of(new RefactoringResult.ChangeDetail(
                            method.location().startLine(), 0,
                            oldLine.trim(), newLine.trim())),
                    null, null);

        } catch (IOException e) {
            throw new RefactoringException("Failed to update declaration: " + e.getMessage(), e);
        }
    }

    /**
     * Заменяет сигнатуру в строке.
     */
    private String replaceSignature(String line, SignatureInfo oldSig, SignatureInfo newSig,
                                     String langId) {
        // Находим старую сигнатуру и заменяем на новую
        String oldPattern = Pattern.quote(oldSig.name) + "\\s*\\([^)]*\\)";
        String newSignatureStr = newSig.name + "(" + formatParameters(newSig.parameters, langId) + ")";

        // Заменяем модификатор доступа если изменился
        String result = line;
        if (!oldSig.accessModifier.equals(newSig.accessModifier)) {
            result = result.replace(oldSig.accessModifier, newSig.accessModifier);
        }

        // Заменяем тип возврата если изменился
        if (!oldSig.returnType.equals(newSig.returnType) && !oldSig.returnType.isEmpty()) {
            result = result.replace(oldSig.returnType + " " + oldSig.name,
                    newSig.returnType + " " + newSig.name);
        }

        // Заменяем имя и параметры
        result = result.replaceFirst(oldPattern, Matcher.quoteReplacement(newSignatureStr));

        return result;
    }

    /**
     * Обновляет вызовы метода.
     */
    private RefactoringResult.FileChange updateCallSites(
            List<Location> calls, SignatureInfo oldSig, SignatureInfo newSig,
            Path path, String langId, RefactoringContext context) throws RefactoringException {
        try {
            context.backupFile(path);

            String content = Files.readString(path);
            List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

            List<RefactoringResult.ChangeDetail> details = new ArrayList<>();

            // Обрабатываем снизу вверх
            List<Location> sorted = calls.stream()
                    .sorted(Comparator.comparingInt(Location::startLine).reversed())
                    .toList();

            for (Location call : sorted) {
                int lineIndex = call.startLine() - 1;
                if (lineIndex >= 0 && lineIndex < lines.size()) {
                    String oldLine = lines.get(lineIndex);
                    String newLine = updateCallInLine(oldLine, oldSig, newSig, langId);

                    if (!oldLine.equals(newLine)) {
                        lines.set(lineIndex, newLine);
                        details.add(new RefactoringResult.ChangeDetail(
                                call.startLine(), call.startColumn(),
                                oldLine.trim(), newLine.trim()));
                    }
                }
            }

            if (details.isEmpty()) {
                return null;
            }

            String newContent = String.join("\n", lines);
            FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);
            context.getTreeManager().invalidateCache(path);

            return new RefactoringResult.FileChange(path, details.size(), details, null, null);

        } catch (IOException e) {
            throw new RefactoringException("Failed to update call sites: " + e.getMessage(), e);
        }
    }

    /**
     * Обновляет вызов метода в строке.
     */
    private String updateCallInLine(String line, SignatureInfo oldSig, SignatureInfo newSig,
                                     String langId) {
        // Паттерн для вызова: name(args)
        Pattern callPattern = Pattern.compile(
                Pattern.quote(oldSig.name) + "\\s*\\(([^)]*)\\)");
        Matcher matcher = callPattern.matcher(line);

        if (matcher.find()) {
            String oldArgs = matcher.group(1);
            String newArgs = transformArguments(oldArgs, oldSig.parameters, newSig.parameters);
            String replacement = newSig.name + "(" + newArgs + ")";
            return line.substring(0, matcher.start()) + replacement + line.substring(matcher.end());
        }

        return line;
    }

    /**
     * Трансформирует аргументы вызова.
     */
    private String transformArguments(String oldArgs, List<ParameterInfo> oldParams,
                                        List<ParameterInfo> newParams) {
        if (oldArgs.trim().isEmpty() && newParams.isEmpty()) {
            return "";
        }

        String[] args = oldArgs.split(",");
        Map<String, String> argByParamName = new LinkedHashMap<>();

        // Сопоставляем аргументы со старыми параметрами
        for (int i = 0; i < args.length && i < oldParams.size(); i++) {
            argByParamName.put(oldParams.get(i).name, args[i].trim());
        }

        // Строим новые аргументы
        List<String> newArgs = new ArrayList<>();
        for (ParameterInfo newParam : newParams) {
            if (argByParamName.containsKey(newParam.name)) {
                // Существующий аргумент
                newArgs.add(argByParamName.get(newParam.name));
            } else if (newParam.defaultValue != null) {
                // Новый параметр со значением по умолчанию
                newArgs.add(newParam.defaultValue);
            } else {
                // Новый параметр без значения - вставляем placeholder
                newArgs.add("/* " + newParam.name + " */");
            }
        }

        return String.join(", ", newArgs);
    }

    /**
     * Форматирует параметры для вывода.
     */
    private String formatParameters(List<ParameterInfo> params, String langId) {
        if (langId.equals("java") || langId.equals("kotlin")) {
            return params.stream()
                    .map(p -> p.type + " " + p.name)
                    .collect(Collectors.joining(", "));
        } else if (langId.equals("python")) {
            return params.stream()
                    .map(p -> p.name + (p.defaultValue != null ? "=" + p.defaultValue : ""))
                    .collect(Collectors.joining(", "));
        } else {
            return params.stream()
                    .map(p -> p.name + ": " + p.type +
                            (p.defaultValue != null ? " = " + p.defaultValue : ""))
                    .collect(Collectors.joining(", "));
        }
    }

    /**
     * Форматирует сигнатуру для вывода.
     */
    private String formatSignature(SignatureInfo sig, String langId) {
        StringBuilder sb = new StringBuilder();

        if (langId.equals("java") || langId.equals("kotlin")) {
            sb.append(sig.accessModifier).append(" ");
            sb.append(sig.returnType).append(" ");
            sb.append(sig.name).append("(");
            sb.append(formatParameters(sig.parameters, langId));
            sb.append(")");
        } else if (langId.equals("python")) {
            sb.append("def ").append(sig.name).append("(");
            sb.append(formatParameters(sig.parameters, langId));
            sb.append(")");
        } else {
            sb.append(sig.name).append("(");
            sb.append(formatParameters(sig.parameters, langId));
            sb.append(")");
            if (!sig.returnType.isEmpty()) {
                sb.append(": ").append(sig.returnType);
            }
        }

        return sb.toString();
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private record ParameterInfo(String name, String type, String defaultValue) {}

    private record SignatureInfo(
            String name,
            String accessModifier,
            String returnType,
            List<ParameterInfo> parameters,
            String originalLine
    ) {}
}
