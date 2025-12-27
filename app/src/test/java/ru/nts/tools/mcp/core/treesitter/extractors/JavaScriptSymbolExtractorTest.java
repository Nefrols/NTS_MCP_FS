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

import org.junit.jupiter.api.Test;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo;
import ru.nts.tools.mcp.core.treesitter.SymbolInfo.SymbolKind;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaScriptSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractJavaScriptFunctions() {
        String code = """
                function greet(name) {
                    console.log(`Hello, ${name}!`);
                }

                const add = (a, b) => a + b;
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "javascript");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.FUNCTION));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractJavaScriptClass() {
        String code = """
                class User {
                    constructor(name) {
                        this.name = name;
                    }

                    greet() {
                        console.log(`Hello, ${this.name}!`);
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "javascript");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.CLASS));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("constructor") && s.kind() == SymbolKind.CONSTRUCTOR));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.METHOD));
    }
}
