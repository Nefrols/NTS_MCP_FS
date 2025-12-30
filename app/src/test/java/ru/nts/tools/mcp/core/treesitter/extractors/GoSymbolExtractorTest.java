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

import static org.junit.jupiter.api.Assertions.*;

class GoSymbolExtractorTest extends AbstractSymbolExtractorTest {

    @Test
    void extractGoFunction() {
        String code = """
                package main

                func add(a, b int) int {
                    return a + b
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("add") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoStruct() {
        String code = """
                package main

                type User struct {
                    ID   int
                    Name string
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("User") && s.kind() == SymbolKind.STRUCT));
    }

    // ==================== Edge Cases ====================

    @Test
    void extractGoMultipleParamsSameType() {
        String code = """
                package main

                func process(a, b, c int) int {
                    return a + b + c
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        SymbolInfo func = symbols.stream()
                .filter(s -> s.name().equals("process"))
                .findFirst()
                .orElse(null);

        assertNotNull(func);
        if (func.parameters() != null) {
            assertEquals(3, func.parameters().size());
            assertEquals("a", func.parameters().get(0).name());
            assertEquals("int", func.parameters().get(0).type());
        }
    }

    @Test
    void extractGoMethod() {
        String code = """
                package main

                type Calculator struct {
                    value int
                }

                func (c *Calculator) Add(n int) int {
                    return c.value + n
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Calculator") && s.kind() == SymbolKind.STRUCT));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Add") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractGoInterface() {
        String code = """
                package main

                type Reader interface {
                    Read(p []byte) (n int, err error)
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Reader") && s.kind() == SymbolKind.INTERFACE));
    }

    @Test
    void extractGoVariadicFunction() {
        String code = """
                package main

                func sum(nums ...int) int {
                    total := 0
                    for _, n := range nums {
                        total += n
                    }
                    return total
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        SymbolInfo func = symbols.stream()
                .filter(s -> s.name().equals("sum"))
                .findFirst()
                .orElse(null);

        assertNotNull(func);
    }

    @Test
    void extractGoMultipleReturnValues() {
        String code = """
                package main

                func divide(a, b int) (int, error) {
                    if b == 0 {
                        return 0, errors.New("division by zero")
                    }
                    return a / b, nil
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("divide") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoNamedReturnValues() {
        String code = """
                package main

                func split(sum int) (x, y int) {
                    x = sum * 4 / 9
                    y = sum - x
                    return
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("split") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoGenericFunction() {
        String code = """
                package main

                func Map[T, U any](s []T, f func(T) U) []U {
                    r := make([]U, len(s))
                    for i, v := range s {
                        r[i] = f(v)
                    }
                    return r
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Map") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoEmbeddedStruct() {
        String code = """
                package main

                type Person struct {
                    Name string
                    Age  int
                }

                type Employee struct {
                    Person
                    Company string
                    Role    string
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Person") && s.kind() == SymbolKind.STRUCT));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Employee") && s.kind() == SymbolKind.STRUCT));
    }

    @Test
    void extractGoFunctionTypes() {
        String code = """
                package main

                type Handler func(req *Request) (*Response, error)

                func process(h Handler) {}
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("process") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoChannelParameters() {
        String code = """
                package main

                func worker(jobs <-chan int, results chan<- int) {
                    for j := range jobs {
                        results <- j * 2
                    }
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("worker") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoPointerReceiver() {
        String code = """
                package main

                type Counter struct {
                    count int
                }

                func (c *Counter) Increment() {
                    c.count++
                }

                func (c Counter) Value() int {
                    return c.count
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Increment") && s.kind() == SymbolKind.METHOD));
        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Value") && s.kind() == SymbolKind.METHOD));
    }

    @Test
    void extractGoContextParameter() {
        String code = """
                package main

                import "context"

                func fetchData(ctx context.Context, id string) (*Data, error) {
                    return nil, nil
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("fetchData") && s.kind() == SymbolKind.FUNCTION));
    }

    @Test
    void extractGoEmptyInterface() {
        String code = """
                package main

                type Any interface{}

                func process(v interface{}) {}
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("Any")));
        assertTrue(symbols.stream().anyMatch(s -> s.name().equals("process")));
    }

    @Test
    void extractGoStructTags() {
        String code = """
                package main

                type Config struct {
                    Host string `json:"host" env:"APP_HOST"`
                    Port int    `json:"port" env:"APP_PORT"`
                }
                """;

        List<SymbolInfo> symbols = parseAndExtract(code, "go");

        assertTrue(symbols.stream().anyMatch(s ->
                s.name().equals("Config") && s.kind() == SymbolKind.STRUCT));
    }
}
