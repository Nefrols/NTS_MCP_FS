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
package ru.nts.tools.mcp.core.treesitter.extractors;

import org.treesitter.TSNode;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.Location;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.nio.file.Path;
import java.util.Optional;

import static ru.nts.tools.mcp.core.treesitter.SymbolExtractorUtils.*;

public class PythonSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "function_definition" -> extractFunction(node, path, content, parentName);
            case "class_definition" -> extractClass(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractFunction(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPythonDocstring(node, content);

        SymbolKind kind = name.equals("__init__") ? SymbolKind.CONSTRUCTOR :
                (parentName != null ? SymbolKind.METHOD : SymbolKind.FUNCTION);

        return Optional.of(new SymbolInfo(name, kind, null, null, doc, location, parentName));
    }

    private Optional<SymbolInfo> extractClass(TSNode node, Path path, String content, String parentName) {
        TSNode nameNode = findChildByType(node, "identifier");
        if (nameNode == null) return Optional.empty();

        String name = getNodeText(nameNode, content);
        Location location = nodeToLocation(node, path);
        String doc = extractPythonDocstring(node, content);

        return Optional.of(new SymbolInfo(name, SymbolKind.CLASS, null, null, doc, location, parentName));
    }

    private String extractPythonDocstring(TSNode node, String content) {
        TSNode body = findChildByType(node, "block");
        if (body == null) return null;

        if (body.getChildCount() > 0) {
            TSNode firstStmt = body.getChild(0);
            if (firstStmt != null && firstStmt.getType().equals("expression_statement")) {
                if (firstStmt.getChildCount() > 0) {
                    TSNode expr = firstStmt.getChild(0);
                    if (expr != null && expr.getType().equals("string")) {
                        String docstring = getNodeText(expr, content);
                        if (docstring.startsWith("\"\"\"") || docstring.startsWith("'''")) {
                            return docstring.substring(3, docstring.length() - 3).trim();
                        }
                    }
                }
            }
        }
        return null;
    }
}
