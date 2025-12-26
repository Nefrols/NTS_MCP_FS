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
package ru.nts.tools.mcp.tools.refactoring;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Исключение при выполнении операции рефакторинга.
 */
public class RefactoringException extends Exception {

    private final List<String> suggestions;
    private final List<PartialChange> partialChanges;

    public RefactoringException(String message) {
        super(message);
        this.suggestions = new ArrayList<>();
        this.partialChanges = new ArrayList<>();
    }

    public RefactoringException(String message, Throwable cause) {
        super(message, cause);
        this.suggestions = new ArrayList<>();
        this.partialChanges = new ArrayList<>();
    }

    public RefactoringException(String message, List<String> suggestions) {
        super(message);
        this.suggestions = suggestions != null ? suggestions : new ArrayList<>();
        this.partialChanges = new ArrayList<>();
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public RefactoringException addSuggestion(String suggestion) {
        this.suggestions.add(suggestion);
        return this;
    }

    public List<PartialChange> getPartialChanges() {
        return partialChanges;
    }

    public RefactoringException addPartialChange(PartialChange change) {
        this.partialChanges.add(change);
        return this;
    }

    /**
     * Частичное изменение, которое было применено до ошибки.
     */
    public record PartialChange(
            Path path,
            boolean applied,
            String error
    ) {}

    // Фабричные методы для типичных ошибок
    public static RefactoringException symbolNotFound(String symbol, String file) {
        return new RefactoringException("Symbol '" + symbol + "' not found in " + file);
    }

    public static RefactoringException symbolNotFound(String symbol, String file, List<String> similar) {
        RefactoringException ex = new RefactoringException(
                "Symbol '" + symbol + "' not found in " + file,
                similar.stream().map(s -> "Did you mean '" + s + "'?").toList()
        );
        return ex;
    }

    public static RefactoringException ambiguousSymbol(String symbol, List<String> locations) {
        return new RefactoringException(
                "Symbol '" + symbol + "' is ambiguous. Found in multiple locations.",
                locations.stream().map(l -> "Found at: " + l).toList()
        );
    }

    public static RefactoringException fileNotFound(String path) {
        return new RefactoringException("File not found: " + path);
    }

    public static RefactoringException unsupportedLanguage(String language) {
        return new RefactoringException(
                "Language '" + language + "' is not supported for this operation",
                List.of("Supported languages: java, kotlin, javascript, typescript, python, go, rust")
        );
    }

    public static RefactoringException conflictingChanges(String description) {
        return new RefactoringException("Conflicting changes detected: " + description);
    }

    public static RefactoringException invalidParameter(String param, String reason) {
        return new RefactoringException("Invalid parameter '" + param + "': " + reason);
    }
}
