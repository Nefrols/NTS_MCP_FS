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

class RustSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractRustFunction() {
        String code = """
                fn add(a: i32, b: i32) -> i32 {
                    a + b
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractRustStruct() {
        String code = """
                struct User {
                    id: u32,
                    name: String,
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.STRUCT));
    }

    @Test
    void extractRustEnum() {
        String code = """
                enum Status {
                    Active,
                    Inactive,
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Status") && s.kind() == SymbolKind.ENUM));
    }

    @Test
    void extractRustTrait() {
        String code = """
                trait Printable {
                    fn print(&self);
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "rust");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Printable") && s.kind() == SymbolKind.TRAIT));
    }
}
