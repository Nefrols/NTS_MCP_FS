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

class CSharpSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractCSharpClass() {
        String code = """
                public class Calculator {
                    public int Add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.CLASS));
    }

    @Test
    void extractCSharpInterface() {
        String code = """
                public interface IService {
                    void Start();
                    void Stop();
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("IService") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractCSharpStruct() {
        String code = """
                public struct Point {
                    public int X { get; set; }
                    public int Y { get; set; }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Point") && s.kind() == SymbolKind.STRUCT));
    }

    @Test
    void extractCSharpEnum() {
        String code = """
                public enum Status {
                    Active,
                    Inactive,
                    Pending
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Status") && s.kind() == SymbolKind.ENUM));
    }

    @Test
    void extractCSharpMethod() {
        String code = """
                public class Service {
                    public void Process() {
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Process") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractCSharpProperty() {
        String code = """
                public class User {
                    public string Name { get; set; }
                    public int Age { get; set; }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Name") && s.kind() == SymbolKind.PROPERTY));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Age") && s.kind() == SymbolKind.PROPERTY));
    }

    @Test
    void extractCSharpNamespace() {
        String code = """
                namespace MyApp.Services {
                    public class Service { }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.kind() == SymbolKind.NAMESPACE));
    }

    @Test
    void extractCSharpConstructor() {
        String code = """
                public class User {
                    public User(string name) {
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "csharp");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.CONSTRUCTOR));
    }
}
