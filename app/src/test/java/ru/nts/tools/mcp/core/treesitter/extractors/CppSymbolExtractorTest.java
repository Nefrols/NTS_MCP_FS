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

class CppSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractCppClass() {
        String code = """
                class Calculator {
                public:
                    int add(int a, int b);
                private:
                    int result;
                };
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractCppFunction() {
        String code = """
                int multiply(int a, int b) {
                    return a * b;
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("multiply") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractCppNamespace() {
        String code = """
                namespace MyLib {
                    void init();
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("MyLib") && s.kind() == SymbolKind.NAMESPACE));
    }

    @Test
    void extractCppStruct() {
        String code = """
                struct Vector3 {
                    float x, y, z;
                };
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "cpp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Vector3") && s.kind() == SymbolKind.STRUCT));
    }
}
