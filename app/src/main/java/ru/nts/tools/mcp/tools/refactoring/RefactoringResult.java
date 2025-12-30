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

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Результат операции рефакторинга.
 */
public record RefactoringResult(
        Status status,
        String action,
        String summary,
        List<FileChange> changes,
        int affectedFiles,
        int totalChanges,
        String transactionId,
        String error,
        List<String> suggestions,
        String diff,
        JsonNode details
) {
    public enum Status {
        SUCCESS,
        PREVIEW,
        ERROR,
        NO_CHANGES,
        PARTIAL
    }

    /**
     * Изменение в файле.
     */
    public record FileChange(
            Path path,
            int occurrences,
            List<ChangeDetail> details,
            String newToken,
            String diff,
            long crc32c,
            int lineCount
    ) {
        /**
         * Конструктор для обратной совместимости (без crc32c и lineCount).
         */
        public FileChange(Path path, int occurrences, List<ChangeDetail> details,
                          String newToken, String diff) {
            this(path, occurrences, details, newToken, diff, 0, 0);
        }
    }

    /**
     * Детали конкретного изменения.
     */
    public record ChangeDetail(
            int line,
            int column,
            String before,
            String after
    ) {}

    // Builder pattern для удобного создания результата
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Status status = Status.SUCCESS;
        private String action;
        private String summary;
        private List<FileChange> changes = new ArrayList<>();
        private int affectedFiles;
        private int totalChanges;
        private String transactionId;
        private String error;
        private List<String> suggestions = new ArrayList<>();
        private String diff;
        private JsonNode details;

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder summary(String summary) {
            this.summary = summary;
            return this;
        }

        public Builder changes(List<FileChange> changes) {
            this.changes = changes;
            return this;
        }

        public Builder addChange(FileChange change) {
            this.changes.add(change);
            return this;
        }

        public Builder affectedFiles(int count) {
            this.affectedFiles = count;
            return this;
        }

        public Builder totalChanges(int count) {
            this.totalChanges = count;
            return this;
        }

        public Builder transactionId(String id) {
            this.transactionId = id;
            return this;
        }

        public Builder error(String error) {
            this.status = Status.ERROR;
            this.error = error;
            return this;
        }

        public Builder suggestions(List<String> suggestions) {
            this.suggestions = suggestions;
            return this;
        }

        public Builder addSuggestion(String suggestion) {
            this.suggestions.add(suggestion);
            return this;
        }

        public Builder diff(String diff) {
            this.diff = diff;
            return this;
        }

        public Builder details(JsonNode details) {
            this.details = details;
            return this;
        }

        public RefactoringResult build() {
            return new RefactoringResult(
                    status, action, summary, changes,
                    affectedFiles, totalChanges, transactionId,
                    error, suggestions, diff, details
            );
        }
    }

    // Фабричные методы
    public static RefactoringResult success(String action, String summary,
                                             List<FileChange> changes, String transactionId) {
        return new RefactoringResult(
                Status.SUCCESS, action, summary, changes,
                changes.size(),
                changes.stream().mapToInt(FileChange::occurrences).sum(),
                transactionId, null, List.of(), null, null
        );
    }

    public static RefactoringResult preview(String action, List<FileChange> changes) {
        return new RefactoringResult(
                Status.PREVIEW, action,
                "Preview: " + changes.size() + " files would be modified",
                changes, changes.size(),
                changes.stream().mapToInt(FileChange::occurrences).sum(),
                null, null, List.of(), null, null
        );
    }

    public static RefactoringResult error(String action, String error, List<String> suggestions) {
        return new RefactoringResult(
                Status.ERROR, action, null, List.of(),
                0, 0, null, error, suggestions != null ? suggestions : List.of(), null, null
        );
    }

    public static RefactoringResult noChanges(String action, String reason) {
        return new RefactoringResult(
                Status.NO_CHANGES, action, reason, List.of(),
                0, 0, null, null, List.of(), null, null
        );
    }
}
