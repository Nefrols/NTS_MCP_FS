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
package ru.nts.tools.mcp.core.treesitter;

import org.treesitter.*;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;
import ru.nts.tools.mcp.core.treesitter.extractors.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

/**
 * Извлекает символы из AST дерева с помощью tree-sitter.
 * Использует прямой обход дерева для максимальной совместимости.
 */
public final class SymbolExtractor {

    private static final SymbolExtractor INSTANCE = new SymbolExtractor();

    private final Map<String, LanguageSymbolExtractor> extractors = new HashMap<>();

    private SymbolExtractor() {
        registerExtractors();
    }

    public static SymbolExtractor getInstance() {
        return INSTANCE;
    }

    private void registerExtractors() {
        extractors.put("java", new JavaSymbolExtractor());
        extractors.put("kotlin", new KotlinSymbolExtractor());
        extractors.put("javascript", new JavaScriptSymbolExtractor());
        extractors.put("tsx", new JavaScriptSymbolExtractor());
        extractors.put("typescript", new TypeScriptSymbolExtractor());
        extractors.put("python", new PythonSymbolExtractor());
        extractors.put("go", new GoSymbolExtractor());
        extractors.put("rust", new RustSymbolExtractor());
        extractors.put("c", new CSymbolExtractor());
        extractors.put("cpp", new CppSymbolExtractor());
        extractors.put("csharp", new CSharpSymbolExtractor());
        extractors.put("php", new PhpSymbolExtractor());
        extractors.put("html", new HtmlSymbolExtractor());
    }

    /**
     * Извлекает все определения символов из файла.
     */
    public List<SymbolInfo> extractDefinitions(TSTree tree, Path path, String content, String langId) {
        List<SymbolInfo> symbols = new ArrayList<>();
        TSNode root = tree.getRootNode();

        extractSymbolsRecursive(root, path, content, langId, null, symbols);

        return symbols;
    }

    /**
     * Рекурсивно обходит AST и извлекает символы.
     */
    private void extractSymbolsRecursive(TSNode node, Path path, String content,
                                          String langId, String parentName,
                                          List<SymbolInfo> symbols) {
        String nodeType = node.getType();

        // Извлекаем символ если это определение
        Optional<SymbolInfo> symbol = extractSymbolFromNode(node, nodeType, path, content, langId, parentName);
        symbol.ifPresent(symbols::add);

        // Определяем новое имя родителя для вложенных символов
        String newParentName = parentName;
        if (symbol.isPresent() && isContainerSymbol(symbol.get().kind())) {
            newParentName = symbol.get().name();
        }

        // Рекурсивно обходим дочерние узлы
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                extractSymbolsRecursive(child, path, content, langId, newParentName, symbols);
            }
        }
    }

    /**
     * Проверяет, является ли символ контейнером.
     */
    private boolean isContainerSymbol(SymbolKind kind) {
        return kind == SymbolKind.CLASS ||
                kind == SymbolKind.INTERFACE ||
                kind == SymbolKind.ENUM ||
                kind == SymbolKind.STRUCT ||
                kind == SymbolKind.TRAIT ||
                kind == SymbolKind.OBJECT ||
                kind == SymbolKind.MODULE ||
                kind == SymbolKind.NAMESPACE;
    }

    /**
     * Извлекает символ из узла AST.
     */
    private Optional<SymbolInfo> extractSymbolFromNode(TSNode node, String nodeType, Path path, String content,
                                                        String langId, String parentName) {
        LanguageSymbolExtractor extractor = extractors.get(langId);
        if (extractor != null) {
            return extractor.extractSymbol(node, nodeType, path, content, parentName);
        }
        return Optional.empty();
    }

    // ===================== УТИЛИТЫ =====================

    /**
     * Находит символ в указанной позиции.
     */
    public Optional<SymbolInfo> symbolAtPosition(TSTree tree, Path path, String content,
                                                  String langId, int line, int column) {
        List<SymbolInfo> allSymbols = extractDefinitions(tree, path, content, langId);

        return allSymbols.stream()
                .filter(s -> s.location().contains(line, column))
                .min((a, b) -> Integer.compare(a.location().lineSpan(), b.location().lineSpan()));
    }

    /**
     * Находит все ссылки на символ в файле.
     */
    public List<Location> findReferences(TSTree tree, Path path, String content,
                                          String langId, String symbolName) {
        List<Location> references = new ArrayList<>();
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        findReferencesRecursive(tree.getRootNode(), path, contentBytes, symbolName, references);
        return references;
    }

    private void findReferencesRecursive(TSNode node, Path path, byte[] contentBytes,
                                          String symbolName, List<Location> references) {
        String nodeType = node.getType();

        if (nodeType.equals("identifier") ||
                nodeType.equals("simple_identifier") ||
                nodeType.equals("type_identifier") ||
                nodeType.equals("property_identifier") ||
                nodeType.equals("field_identifier")) {

            String text = getNodeTextFromBytes(node, contentBytes);
            if (text.equals(symbolName)) {
                references.add(nodeToLocation(node, path));
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            TSNode child = node.getChild(i);
            if (child != null && !child.isNull()) {
                findReferencesRecursive(child, path, contentBytes, symbolName, references);
            }
        }
    }
}
