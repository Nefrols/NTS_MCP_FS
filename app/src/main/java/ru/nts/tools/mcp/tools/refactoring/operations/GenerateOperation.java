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
import org.treesitter.TSNode;
import org.treesitter.TSTree;
import ru.nts.tools.mcp.core.FileUtils;
import ru.nts.tools.mcp.core.PathSanitizer;
import ru.nts.tools.mcp.core.treesitter.LanguageDetector;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;
import ru.nts.tools.mcp.core.treesitter.TreeSitterManager;
import ru.nts.tools.mcp.tools.refactoring.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Операция генерации кода.
 * Генерирует getters, setters, constructors, builders и другой boilerplate код.
 */
public class GenerateOperation implements RefactoringOperation {

    @Override
    public String getName() {
        return "generate";
    }

    @Override
    public void validateParams(JsonNode params) throws IllegalArgumentException {
        if (!params.has("path")) {
            throw new IllegalArgumentException("Parameter 'path' is required for generate operation");
        }
        if (!params.has("what")) {
            throw new IllegalArgumentException("Parameter 'what' is required for generate operation");
        }

        String what = params.get("what").asText();
        Set<String> validTypes = Set.of(
                "getter", "getters", "setter", "setters", "accessors",
                "constructor", "builder", "equals_hashcode", "toString",
                "all_args_constructor", "no_args_constructor"
        );

        if (!validTypes.contains(what)) {
            throw new IllegalArgumentException("Invalid generate type: '" + what +
                    "'. Valid types: " + validTypes);
        }
    }

    @Override
    public RefactoringResult execute(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String what = params.get("what").asText();
        List<String> fieldNames = getFieldNames(params);

        // Определяем язык
        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        // Находим класс и поля
        ClassInfo classInfo = analyzeClass(path, fieldNames, context);
        if (classInfo == null) {
            throw new RefactoringException("No class found in file: " + path);
        }

        // Генерируем код
        String generatedCode = generateCode(what, classInfo, langId, params);
        if (generatedCode == null || generatedCode.isEmpty()) {
            return RefactoringResult.noChanges("generate", "Nothing to generate");
        }

        // Начинаем транзакцию
        String instruction = params.has("instruction")
                ? params.get("instruction").asText()
                : "Generate " + what + " for " + classInfo.className;
        context.beginTransaction(instruction);

        try {
            // Вставляем код
            RefactoringResult.FileChange change = insertGeneratedCode(
                    path, classInfo, generatedCode, context);

            String txId = context.commitTransaction();

            return RefactoringResult.builder()
                    .status(RefactoringResult.Status.SUCCESS)
                    .action("generate")
                    .summary(String.format("Generated %s for class '%s'", what, classInfo.className))
                    .addChange(change)
                    .affectedFiles(1)
                    .totalChanges(1)
                    .transactionId(txId)
                    .build();

        } catch (Exception e) {
            context.rollbackTransaction();
            throw new RefactoringException("Generation failed: " + e.getMessage(), e);
        }
    }

    @Override
    public RefactoringResult preview(JsonNode params, RefactoringContext context)
            throws RefactoringException {

        Path path = resolvePath(params.get("path").asText());
        String what = params.get("what").asText();
        List<String> fieldNames = getFieldNames(params);

        String langId = LanguageDetector.detect(path)
                .orElseThrow(() -> RefactoringException.unsupportedLanguage("unknown"));

        ClassInfo classInfo = analyzeClass(path, fieldNames, context);
        if (classInfo == null) {
            throw new RefactoringException("No class found in file: " + path);
        }

        String generatedCode = generateCode(what, classInfo, langId, params);
        if (generatedCode == null || generatedCode.isEmpty()) {
            return RefactoringResult.noChanges("generate", "Nothing to generate");
        }

        // Создаём preview
        StringBuilder diff = new StringBuilder();
        diff.append("--- a/").append(path.getFileName()).append("\n");
        diff.append("+++ b/").append(path.getFileName()).append("\n");
        diff.append("@@ +").append(classInfo.insertLine).append(" @@\n");
        for (String line : generatedCode.split("\n")) {
            diff.append("+").append(line).append("\n");
        }

        RefactoringResult.FileChange change = new RefactoringResult.FileChange(
                path, 1,
                List.of(new RefactoringResult.ChangeDetail(
                        classInfo.insertLine, 0, "", generatedCode.trim())),
                null, diff.toString()
        );

        return RefactoringResult.preview("generate", List.of(change));
    }

    /**
     * Анализирует класс и извлекает информацию о полях.
     * Использует AST для точного определения места вставки.
     */
    private ClassInfo analyzeClass(Path path, List<String> filterFields, RefactoringContext context)
            throws RefactoringException {

        try {
            List<SymbolInfo> symbols = context.getSymbolResolver().listSymbols(path);

            // Находим класс
            SymbolInfo classSymbol = symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.CLASS)
                    .findFirst()
                    .orElse(null);

            if (classSymbol == null) {
                return null;
            }

            // Находим поля
            List<FieldInfo> fields = symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.FIELD || s.kind() == SymbolKind.PROPERTY)
                    .filter(s -> classSymbol.name().equals(s.parentName()))
                    .filter(s -> filterFields.isEmpty() || filterFields.contains(s.name()))
                    .map(s -> new FieldInfo(s.name(), inferFieldType(s), isPrivate(s)))
                    .toList();

            // Находим существующие методы
            Set<String> existingMethods = symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.CONSTRUCTOR)
                    .filter(s -> classSymbol.name().equals(s.parentName()))
                    .map(SymbolInfo::name)
                    .collect(Collectors.toSet());

            // Используем AST для точного определения места вставки
            int insertLine = findPreciseInsertionPoint(path, classSymbol, symbols, context);

            return new ClassInfo(
                    classSymbol.name(),
                    fields,
                    existingMethods,
                    insertLine,
                    detectIndentation(path, classSymbol)
            );

        } catch (IOException e) {
            throw new RefactoringException("Failed to analyze class: " + e.getMessage(), e);
        }
    }

    /**
     * Находит точное место для вставки сгенерированного кода с использованием AST.
     * Вставка происходит после последнего поля или перед закрывающей скобкой класса.
     */
    private int findPreciseInsertionPoint(Path path, SymbolInfo classSymbol,
                                           List<SymbolInfo> symbols, RefactoringContext context)
            throws IOException {

        // Используем getParseResult для поддержки виртуального контента в batch
        TreeSitterManager.ParseResult parseResult = context.getParseResult(path);
        TSTree tree = parseResult.tree();
        String content = parseResult.content();

        // Находим узел class_body в AST
        TSNode root = tree.getRootNode();
        TSNode classBody = findClassBodyNode(root, classSymbol.name(), content);

        if (classBody != null) {
            // Находим позицию перед закрывающей скобкой класса
            int closingBraceLine = classBody.getEndPoint().getRow() + 1; // 1-based

            // Находим последнее поле в классе
            int lastFieldEndLine = symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.FIELD || s.kind() == SymbolKind.PROPERTY)
                    .filter(s -> classSymbol.name().equals(s.parentName()))
                    .mapToInt(s -> s.location().endLine())
                    .max()
                    .orElse(-1);

            // Находим первый метод в классе
            int firstMethodStartLine = symbols.stream()
                    .filter(s -> s.kind() == SymbolKind.METHOD || s.kind() == SymbolKind.CONSTRUCTOR)
                    .filter(s -> classSymbol.name().equals(s.parentName()))
                    .mapToInt(s -> s.location().startLine())
                    .min()
                    .orElse(Integer.MAX_VALUE);

            // Вставляем между полями и методами, или перед закрывающей скобкой
            if (lastFieldEndLine > 0 && firstMethodStartLine > lastFieldEndLine) {
                // Вставляем после последнего поля, перед первым методом
                return lastFieldEndLine + 1;
            } else if (lastFieldEndLine > 0) {
                // Есть только поля, вставляем после последнего поля
                return lastFieldEndLine + 1;
            } else {
                // Нет полей, вставляем перед закрывающей скобкой
                return closingBraceLine;
            }
        }

        // Fallback: используем конец класса - 1
        return classSymbol.location().endLine() - 1;
    }

    /**
     * Находит узел class_body для указанного класса.
     */
    private TSNode findClassBodyNode(TSNode node, String className, String content) {
        String nodeType = node.getType();

        // Проверяем, является ли это объявлением нужного класса
        if (nodeType.equals("class_declaration") || nodeType.equals("record_declaration")) {
            TSNode nameNode = findChildByType(node, "identifier");
            if (nameNode != null) {
                String name = getNodeText(nameNode, content);
                if (className.equals(name)) {
                    // Ищем class_body
                    return findChildByType(node, "class_body");
                }
            }
        }

        // Рекурсивно ищем в дочерних узлах
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                TSNode found = findClassBodyNode(child, className, content);
                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Находит дочерний узел по типу.
     */
    private TSNode findChildByType(TSNode parent, String type) {
        int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = parent.getChild(i);
            if (child != null && !child.isNull() && child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Извлекает текст узла (корректная обработка UTF-8).
     */
    private String getNodeText(TSNode node, String content) {
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        int start = node.getStartByte();
        int end = node.getEndByte();
        if (start >= 0 && end <= contentBytes.length && start < end) {
            return new String(contentBytes, start, end - start, StandardCharsets.UTF_8);
        }
        return "";
    }

    /**
     * Генерирует код в зависимости от типа.
     */
    private String generateCode(String what, ClassInfo classInfo, String langId, JsonNode params) {
        JsonNode options = params.has("options") ? params.get("options") : null;

        return switch (what) {
            case "getter" -> generateGetter(classInfo, langId, options);
            case "getters" -> generateGetters(classInfo, langId, options);
            case "setter" -> generateSetter(classInfo, langId, options);
            case "setters" -> generateSetters(classInfo, langId, options);
            case "accessors" -> generateAccessors(classInfo, langId, options);
            case "constructor", "all_args_constructor" -> generateConstructor(classInfo, langId, options, true);
            case "no_args_constructor" -> generateConstructor(classInfo, langId, options, false);
            case "builder" -> generateBuilder(classInfo, langId, options);
            case "equals_hashcode" -> generateEqualsHashCode(classInfo, langId, options);
            case "toString" -> generateToString(classInfo, langId, options);
            default -> null;
        };
    }

    private String generateGetters(ClassInfo classInfo, String langId, JsonNode options) {
        StringBuilder sb = new StringBuilder();
        String indent = classInfo.indent;

        for (FieldInfo field : classInfo.fields) {
            String getterName = "get" + capitalize(field.name);
            if (classInfo.existingMethods.contains(getterName)) {
                continue; // Пропускаем если уже существует
            }

            sb.append("\n");
            if (langId.equals("java") || langId.equals("kotlin")) {
                sb.append(indent).append("public ").append(field.type).append(" ")
                        .append(getterName).append("() {\n");
                sb.append(indent).append("    return this.").append(field.name).append(";\n");
                sb.append(indent).append("}\n");
            } else if (langId.equals("typescript") || langId.equals("javascript")) {
                sb.append(indent).append("get ").append(field.name).append("() {\n");
                sb.append(indent).append("    return this._").append(field.name).append(";\n");
                sb.append(indent).append("}\n");
            } else if (langId.equals("python")) {
                sb.append(indent).append("@property\n");
                sb.append(indent).append("def ").append(field.name).append("(self):\n");
                sb.append(indent).append("    return self._").append(field.name).append("\n");
            }
        }

        return sb.toString();
    }

    private String generateGetter(ClassInfo classInfo, String langId, JsonNode options) {
        if (classInfo.fields.isEmpty()) return "";
        ClassInfo singleField = new ClassInfo(classInfo.className,
                List.of(classInfo.fields.get(0)), classInfo.existingMethods,
                classInfo.insertLine, classInfo.indent);
        return generateGetters(singleField, langId, options);
    }

    private String generateSetters(ClassInfo classInfo, String langId, JsonNode options) {
        StringBuilder sb = new StringBuilder();
        String indent = classInfo.indent;
        boolean fluent = options != null && options.has("fluentSetters") &&
                options.get("fluentSetters").asBoolean();

        for (FieldInfo field : classInfo.fields) {
            String setterName = "set" + capitalize(field.name);
            if (classInfo.existingMethods.contains(setterName)) {
                continue;
            }

            sb.append("\n");
            if (langId.equals("java") || langId.equals("kotlin")) {
                String returnType = fluent ? classInfo.className : "void";
                sb.append(indent).append("public ").append(returnType).append(" ")
                        .append(setterName).append("(").append(field.type).append(" ")
                        .append(field.name).append(") {\n");
                sb.append(indent).append("    this.").append(field.name).append(" = ")
                        .append(field.name).append(";\n");
                if (fluent) {
                    sb.append(indent).append("    return this;\n");
                }
                sb.append(indent).append("}\n");
            } else if (langId.equals("typescript") || langId.equals("javascript")) {
                sb.append(indent).append("set ").append(field.name).append("(value) {\n");
                sb.append(indent).append("    this._").append(field.name).append(" = value;\n");
                sb.append(indent).append("}\n");
            } else if (langId.equals("python")) {
                sb.append(indent).append("@").append(field.name).append(".setter\n");
                sb.append(indent).append("def ").append(field.name).append("(self, value):\n");
                sb.append(indent).append("    self._").append(field.name).append(" = value\n");
            }
        }

        return sb.toString();
    }

    private String generateSetter(ClassInfo classInfo, String langId, JsonNode options) {
        if (classInfo.fields.isEmpty()) return "";
        ClassInfo singleField = new ClassInfo(classInfo.className,
                List.of(classInfo.fields.get(0)), classInfo.existingMethods,
                classInfo.insertLine, classInfo.indent);
        return generateSetters(singleField, langId, options);
    }

    private String generateAccessors(ClassInfo classInfo, String langId, JsonNode options) {
        return generateGetters(classInfo, langId, options) +
                generateSetters(classInfo, langId, options);
    }

    private String generateConstructor(ClassInfo classInfo, String langId, JsonNode options, boolean withParams) {
        StringBuilder sb = new StringBuilder();
        String indent = classInfo.indent;
        boolean addValidation = options != null && options.has("addValidation") &&
                options.get("addValidation").asBoolean();

        sb.append("\n");

        if (langId.equals("java")) {
            sb.append(indent).append("public ").append(classInfo.className).append("(");
            if (withParams && !classInfo.fields.isEmpty()) {
                sb.append(classInfo.fields.stream()
                        .map(f -> f.type + " " + f.name)
                        .collect(Collectors.joining(", ")));
            }
            sb.append(") {\n");

            if (withParams) {
                for (FieldInfo field : classInfo.fields) {
                    if (addValidation && !isPrimitive(field.type)) {
                        sb.append(indent).append("    this.").append(field.name)
                                .append(" = java.util.Objects.requireNonNull(")
                                .append(field.name).append(", \"").append(field.name)
                                .append(" must not be null\");\n");
                    } else {
                        sb.append(indent).append("    this.").append(field.name)
                                .append(" = ").append(field.name).append(";\n");
                    }
                }
            }
            sb.append(indent).append("}\n");

        } else if (langId.equals("kotlin")) {
            // Kotlin primary constructor in class declaration - not generated here
            sb.append(indent).append("constructor(");
            if (withParams && !classInfo.fields.isEmpty()) {
                sb.append(classInfo.fields.stream()
                        .map(f -> f.name + ": " + kotlinType(f.type))
                        .collect(Collectors.joining(", ")));
            }
            sb.append(") {\n");
            for (FieldInfo field : classInfo.fields) {
                sb.append(indent).append("    this.").append(field.name)
                        .append(" = ").append(field.name).append("\n");
            }
            sb.append(indent).append("}\n");

        } else if (langId.equals("typescript")) {
            sb.append(indent).append("constructor(");
            if (withParams && !classInfo.fields.isEmpty()) {
                sb.append(classInfo.fields.stream()
                        .map(f -> f.name + ": " + tsType(f.type))
                        .collect(Collectors.joining(", ")));
            }
            sb.append(") {\n");
            for (FieldInfo field : classInfo.fields) {
                sb.append(indent).append("    this.").append(field.name)
                        .append(" = ").append(field.name).append(";\n");
            }
            sb.append(indent).append("}\n");

        } else if (langId.equals("python")) {
            sb.append(indent).append("def __init__(self");
            if (withParams && !classInfo.fields.isEmpty()) {
                sb.append(", ");
                sb.append(classInfo.fields.stream()
                        .map(f -> f.name)
                        .collect(Collectors.joining(", ")));
            }
            sb.append("):\n");
            if (classInfo.fields.isEmpty()) {
                sb.append(indent).append("    pass\n");
            } else {
                for (FieldInfo field : classInfo.fields) {
                    sb.append(indent).append("    self.").append(field.name)
                            .append(" = ").append(field.name).append("\n");
                }
            }
        }

        return sb.toString();
    }

    private String generateBuilder(ClassInfo classInfo, String langId, JsonNode options) {
        if (!langId.equals("java")) {
            return "// Builder pattern is only supported for Java\n";
        }

        StringBuilder sb = new StringBuilder();
        String indent = classInfo.indent;

        // Builder class
        sb.append("\n");
        sb.append(indent).append("public static class Builder {\n");

        // Builder fields
        for (FieldInfo field : classInfo.fields) {
            sb.append(indent).append("    private ").append(field.type)
                    .append(" ").append(field.name).append(";\n");
        }
        sb.append("\n");

        // Builder setters
        for (FieldInfo field : classInfo.fields) {
            sb.append(indent).append("    public Builder ").append(field.name)
                    .append("(").append(field.type).append(" ").append(field.name).append(") {\n");
            sb.append(indent).append("        this.").append(field.name)
                    .append(" = ").append(field.name).append(";\n");
            sb.append(indent).append("        return this;\n");
            sb.append(indent).append("    }\n\n");
        }

        // Build method
        sb.append(indent).append("    public ").append(classInfo.className).append(" build() {\n");
        sb.append(indent).append("        return new ").append(classInfo.className).append("(");
        sb.append(classInfo.fields.stream().map(f -> f.name).collect(Collectors.joining(", ")));
        sb.append(");\n");
        sb.append(indent).append("    }\n");
        sb.append(indent).append("}\n\n");

        // Static builder() method
        sb.append(indent).append("public static Builder builder() {\n");
        sb.append(indent).append("    return new Builder();\n");
        sb.append(indent).append("}\n");

        return sb.toString();
    }

    private String generateEqualsHashCode(ClassInfo classInfo, String langId, JsonNode options) {
        if (!langId.equals("java")) {
            return "// equals/hashCode is only supported for Java\n";
        }

        StringBuilder sb = new StringBuilder();
        String indent = classInfo.indent;

        // equals
        sb.append("\n");
        sb.append(indent).append("@Override\n");
        sb.append(indent).append("public boolean equals(Object o) {\n");
        sb.append(indent).append("    if (this == o) return true;\n");
        sb.append(indent).append("    if (o == null || getClass() != o.getClass()) return false;\n");
        sb.append(indent).append("    ").append(classInfo.className).append(" that = (")
                .append(classInfo.className).append(") o;\n");
        sb.append(indent).append("    return ");

        if (classInfo.fields.isEmpty()) {
            sb.append("true");
        } else {
            sb.append(classInfo.fields.stream()
                    .map(f -> "java.util.Objects.equals(" + f.name + ", that." + f.name + ")")
                    .collect(Collectors.joining(" &&\n" + indent + "           ")));
        }
        sb.append(";\n");
        sb.append(indent).append("}\n\n");

        // hashCode
        sb.append(indent).append("@Override\n");
        sb.append(indent).append("public int hashCode() {\n");
        sb.append(indent).append("    return java.util.Objects.hash(");
        sb.append(classInfo.fields.stream().map(f -> f.name).collect(Collectors.joining(", ")));
        sb.append(");\n");
        sb.append(indent).append("}\n");

        return sb.toString();
    }

    private String generateToString(ClassInfo classInfo, String langId, JsonNode options) {
        StringBuilder sb = new StringBuilder();
        String indent = classInfo.indent;

        sb.append("\n");

        if (langId.equals("java")) {
            sb.append(indent).append("@Override\n");
            sb.append(indent).append("public String toString() {\n");
            sb.append(indent).append("    return \"").append(classInfo.className).append("{\" +\n");

            for (int i = 0; i < classInfo.fields.size(); i++) {
                FieldInfo field = classInfo.fields.get(i);
                sb.append(indent).append("           \"");
                if (i > 0) sb.append(", ");
                sb.append(field.name).append("=\" + ").append(field.name);
                if (i < classInfo.fields.size() - 1) {
                    sb.append(" +\n");
                } else {
                    sb.append(" +\n");
                }
            }
            sb.append(indent).append("           \"}\";\n");
            sb.append(indent).append("}\n");

        } else if (langId.equals("python")) {
            sb.append(indent).append("def __str__(self):\n");
            sb.append(indent).append("    return f\"").append(classInfo.className).append("(");
            sb.append(classInfo.fields.stream()
                    .map(f -> f.name + "={self." + f.name + "}")
                    .collect(Collectors.joining(", ")));
            sb.append(")\"\n");
        }

        return sb.toString();
    }

    /**
     * Вставляет сгенерированный код в файл.
     */
    private RefactoringResult.FileChange insertGeneratedCode(
            Path path, ClassInfo classInfo, String code, RefactoringContext context)
            throws IOException, RefactoringException {

        context.backupFile(path);

        String content = Files.readString(path);
        List<String> lines = new ArrayList<>(Arrays.asList(content.split("\n", -1)));

        // Вставляем код перед закрывающей скобкой класса
        int insertIndex = classInfo.insertLine - 1;
        if (insertIndex >= 0 && insertIndex <= lines.size()) {
            List<String> codeLines = Arrays.asList(code.split("\n"));
            lines.addAll(insertIndex, codeLines);
        }

        String newContent = String.join("\n", lines);
        FileUtils.safeWrite(path, newContent, StandardCharsets.UTF_8);

        context.getTreeManager().invalidateCache(path);

        return new RefactoringResult.FileChange(
                path, 1,
                List.of(new RefactoringResult.ChangeDetail(
                        classInfo.insertLine, 0, "", code.trim())),
                null, null
        );
    }

    // ==================== Utility Methods ====================

    private List<String> getFieldNames(JsonNode params) {
        List<String> fields = new ArrayList<>();
        if (params.has("fields")) {
            JsonNode fieldsNode = params.get("fields");
            if (fieldsNode.isArray()) {
                fieldsNode.forEach(n -> fields.add(n.asText()));
            } else if (fieldsNode.isTextual()) {
                if (!"all".equals(fieldsNode.asText())) {
                    fields.add(fieldsNode.asText());
                }
            }
        }
        return fields;
    }

    private String inferFieldType(SymbolInfo symbol) {
        if (symbol.type() != null && !symbol.type().isEmpty()) {
            return symbol.type();
        }
        // Default type
        return "Object";
    }

    private boolean isPrivate(SymbolInfo symbol) {
        String sig = symbol.signature();
        return sig != null && sig.contains("private");
    }

    private String detectIndentation(Path path, SymbolInfo classSymbol) {
        try {
            String content = Files.readString(path);
            String[] lines = content.split("\n");
            int classLine = classSymbol.location().startLine() - 1;
            if (classLine >= 0 && classLine < lines.length) {
                String line = lines[classLine];
                int spaces = 0;
                for (char c : line.toCharArray()) {
                    if (c == ' ') spaces++;
                    else if (c == '\t') spaces += 4;
                    else break;
                }
                return " ".repeat(spaces + 4); // Добавляем отступ для содержимого класса
            }
        } catch (IOException ignored) {}
        return "    ";
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private boolean isPrimitive(String type) {
        return Set.of("int", "long", "short", "byte", "float", "double", "boolean", "char")
                .contains(type);
    }

    private String kotlinType(String javaType) {
        return switch (javaType) {
            case "int" -> "Int";
            case "long" -> "Long";
            case "String" -> "String";
            case "boolean" -> "Boolean";
            default -> javaType;
        };
    }

    private String tsType(String javaType) {
        return switch (javaType) {
            case "int", "long", "float", "double" -> "number";
            case "String" -> "string";
            case "boolean" -> "boolean";
            default -> "any";
        };
    }

    private Path resolvePath(String pathStr) {
        Path path = Path.of(pathStr);
        if (!path.isAbsolute()) {
            path = PathSanitizer.getRoot().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    // ==================== Data Classes ====================

    private record ClassInfo(
            String className,
            List<FieldInfo> fields,
            Set<String> existingMethods,
            int insertLine,
            String indent
    ) {}

    private record FieldInfo(
            String name,
            String type,
            boolean isPrivate
    ) {}
}
