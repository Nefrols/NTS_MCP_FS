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

class PythonSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractPythonClass() {
        String code = """
                class Calculator:
                    def __init__(self):
                        self.result = 0
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractPythonFunctions() {
        String code = """
                def greet(name):
                    print(f"Hello, {name}!")

                def add(a, b):
                    return a + b
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("greet") && s.kind() == SymbolKind.FUNCTION));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractPythonMethods() {
        String code = """
                class Service:
                    def __init__(self):
                        pass

                    def process(self):
                        pass
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "python");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("__init__") && s.kind() == SymbolKind.CONSTRUCTOR));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("process") && s.kind() == SymbolKind.METHOD));
    }
}
