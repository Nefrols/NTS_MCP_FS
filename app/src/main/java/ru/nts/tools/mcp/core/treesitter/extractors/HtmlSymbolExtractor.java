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

public class HtmlSymbolExtractor implements LanguageSymbolExtractor {

    @Override
    public Optional<SymbolInfo> extractSymbol(TSNode node, String nodeType, Path path, String content, String parentName) {
        return switch (nodeType) {
            case "element" -> extractElement(node, path, content, parentName);
            case "script_element" -> extractScript(node, path, content, parentName);
            case "style_element" -> extractStyle(node, path, content, parentName);
            default -> Optional.empty();
        };
    }

    private Optional<SymbolInfo> extractElement(TSNode node, Path path, String content, String parentName) {
        TSNode startTag = findChildByType(node, "start_tag");
        if (startTag == null) return Optional.empty();

        TSNode tagName = findChildByType(startTag, "tag_name");
        if (tagName == null) return Optional.empty();

        String name = getNodeText(tagName, content);

        // Only extract significant elements with id attribute
        TSNode idAttr = findAttributeByName(startTag, "id", content);
        if (idAttr == null) {
            // Also check for named elements like form, a with name attribute
            if (!name.equals("form") && !name.equals("a") && !name.equals("iframe") &&
                !name.equals("img") && !name.equals("input") && !name.equals("map") &&
                !name.equals("meta") && !name.equals("object") && !name.equals("param") &&
                !name.equals("select") && !name.equals("textarea")) {
                return Optional.empty();
            }
            idAttr = findAttributeByName(startTag, "name", content);
            if (idAttr == null) return Optional.empty();
        }

        String idValue = getAttributeValue(idAttr, content);
        if (idValue == null || idValue.isEmpty()) return Optional.empty();

        Location location = nodeToLocation(node, path);

        return Optional.of(new SymbolInfo(idValue, SymbolKind.VARIABLE, name, null, null, location, parentName));
    }

    private Optional<SymbolInfo> extractScript(TSNode node, Path path, String content, String parentName) {
        TSNode startTag = findChildByType(node, "start_tag");
        if (startTag == null) return Optional.empty();

        TSNode srcAttr = findAttributeByName(startTag, "src", content);
        if (srcAttr != null) {
            String src = getAttributeValue(srcAttr, content);
            if (src != null && !src.isEmpty()) {
                Location location = nodeToLocation(node, path);
                return Optional.of(new SymbolInfo(src, SymbolKind.IMPORT, "script", null, null, location, parentName));
            }
        }
        return Optional.empty();
    }

    private Optional<SymbolInfo> extractStyle(TSNode node, Path path, String content, String parentName) {
        // Style elements are less useful for navigation, skip them
        return Optional.empty();
    }
}
