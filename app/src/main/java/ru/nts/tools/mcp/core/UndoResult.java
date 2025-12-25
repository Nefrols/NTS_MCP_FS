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
package ru.nts.tools.mcp.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Результат операции undo с детальной информацией о статусе.
 *
 * Поддерживает новые статусы для Deep Undo:
 * - RESOLVED_MOVE: файл найден по новому пути через Path Lineage
 * - MERGED_UNDO: правки применены к перемещённому файлу
 * - PARTIAL: частичный откат (dirty directory)
 * - ORPHANED: файл удалён, требуется сначала отменить удаление
 * - GIT_FALLBACK: рекомендация использовать git checkout
 */
public class UndoResult {

    /**
     * Статус операции undo.
     */
    public enum Status {
        /** Успешный откат */
        SUCCESS("Undo completed successfully"),

        /** Файл был перемещён, но найден по ID и откат выполнен */
        RESOLVED_MOVE("File was moved but located via Path Lineage"),

        /** Правки применены к файлу, который был перемещён */
        MERGED_UNDO("Edits applied to relocated file"),

        /** Частичный откат: некоторые файлы пропущены (dirty directory) */
        PARTIAL("Partial undo: some files skipped"),

        /** Файл был удалён в последующих операциях, требуется сначала отменить удаление */
        ORPHANED("File was deleted in later transaction - undo deletion first"),

        /** Невозможно откатить: файл не найден и не восстановим */
        STUCK("Cannot undo: file not found and unrecoverable"),

        /** Рекомендация использовать git для восстановления */
        GIT_FALLBACK("Recommend using git checkout to restore"),

        /** Нет операций для отмены */
        NOTHING_TO_UNDO("No operations to undo");

        private final String description;

        Status(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Детали о файле в операции undo.
     */
    public record FileDetail(
            Path originalPath,
            Path resolvedPath,
            String fileId,
            FileStatus status,
            String message
    ) {}

    /**
     * Статус отдельного файла.
     */
    public enum FileStatus {
        RESTORED,       // Успешно восстановлен
        RELOCATED,      // Найден по другому пути
        SKIPPED,        // Пропущен (dirty directory)
        NOT_FOUND,      // Не найден
        DELETED,        // Был удалён
        CONFLICT        // Конфликт (файл изменён внешним инструментом)
    }

    private final Status status;
    private final String message;
    private final String transactionDescription;
    private final List<FileDetail> fileDetails;
    private final String gitSuggestion;

    private UndoResult(Builder builder) {
        this.status = builder.status;
        this.message = builder.message;
        this.transactionDescription = builder.transactionDescription;
        this.fileDetails = Collections.unmodifiableList(new ArrayList<>(builder.fileDetails));
        this.gitSuggestion = builder.gitSuggestion;
    }

    public Status getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getTransactionDescription() {
        return transactionDescription;
    }

    public List<FileDetail> getFileDetails() {
        return fileDetails;
    }

    public String getGitSuggestion() {
        return gitSuggestion;
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS ||
                status == Status.RESOLVED_MOVE ||
                status == Status.MERGED_UNDO;
    }

    public boolean isPartial() {
        return status == Status.PARTIAL;
    }

    public boolean isFailed() {
        return status == Status.STUCK ||
                status == Status.ORPHANED;
    }

    public boolean needsGitFallback() {
        return status == Status.GIT_FALLBACK;
    }

    /**
     * Форматирует результат для отображения пользователю.
     */
    public String format() {
        StringBuilder sb = new StringBuilder();

        sb.append("[").append(status.name()).append("] ");
        sb.append(message);

        if (transactionDescription != null) {
            sb.append("\nTransaction: ").append(transactionDescription);
        }

        if (!fileDetails.isEmpty()) {
            sb.append("\n\nFiles:");
            for (FileDetail fd : fileDetails) {
                sb.append("\n  ");
                switch (fd.status()) {
                    case RESTORED -> sb.append("[OK] ");
                    case RELOCATED -> sb.append("[MOVED] ");
                    case SKIPPED -> sb.append("[SKIP] ");
                    case NOT_FOUND -> sb.append("[MISS] ");
                    case DELETED -> sb.append("[DEL] ");
                    case CONFLICT -> sb.append("[CONFLICT] ");
                }
                sb.append(fd.originalPath());
                if (fd.resolvedPath() != null && !fd.resolvedPath().equals(fd.originalPath())) {
                    sb.append(" -> ").append(fd.resolvedPath());
                }
                if (fd.message() != null) {
                    sb.append(" (").append(fd.message()).append(")");
                }
            }
        }

        if (gitSuggestion != null) {
            sb.append("\n\n[GIT RECOVERY OPTION]\n").append(gitSuggestion);
        }

        return sb.toString();
    }

    /**
     * Создаёт успешный результат.
     */
    public static UndoResult success(String transactionDescription) {
        return new Builder()
                .status(Status.SUCCESS)
                .message("Undone: " + transactionDescription)
                .transactionDescription(transactionDescription)
                .build();
    }

    /**
     * Создаёт результат "нечего отменять".
     */
    public static UndoResult nothingToUndo() {
        return new Builder()
                .status(Status.NOTHING_TO_UNDO)
                .message("No operations to undo.")
                .build();
    }

    /**
     * Создаёт builder для кастомного результата.
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder для UndoResult.
     */
    public static class Builder {
        private Status status = Status.SUCCESS;
        private String message;
        private String transactionDescription;
        private final List<FileDetail> fileDetails = new ArrayList<>();
        private String gitSuggestion;

        public Builder status(Status status) {
            this.status = status;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder transactionDescription(String desc) {
            this.transactionDescription = desc;
            return this;
        }

        public Builder addFileDetail(Path originalPath, Path resolvedPath,
                                     String fileId, FileStatus status, String message) {
            fileDetails.add(new FileDetail(originalPath, resolvedPath, fileId, status, message));
            return this;
        }

        public Builder gitSuggestion(String suggestion) {
            this.gitSuggestion = suggestion;
            return this;
        }

        public UndoResult build() {
            if (message == null) {
                message = status.getDescription();
            }
            return new UndoResult(this);
        }
    }
}
