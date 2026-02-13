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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Умный движок отката с поддержкой:
 * - Path Lineage: отслеживание файлов по ID через цепочки перемещений
 * - Dirty Directories: частичный откат при наличии внешних изменений
 * - CRC-based recovery: поиск "потерянных" файлов по хешу
 * - Pre-validation: проверка всех файлов до начала отката
 * - Git fallback: рекомендации по восстановлению через git
 */
public class SmartUndoEngine {

    private final FileLineageTracker lineageTracker;
    private final Path projectRoot;

    public SmartUndoEngine(FileLineageTracker lineageTracker, Path projectRoot) {
        this.lineageTracker = lineageTracker;
        this.projectRoot = projectRoot;
    }

    /**
     * Выполняет умный откат транзакции.
     *
     * @param snapshots карта: оригинальный путь -> содержимое бекапа (null если файл не существовал)
     * @param transactionDescription описание транзакции
     * @return детальный результат отката
     */
    public UndoResult smartUndo(Map<Path, byte[]> snapshots, String transactionDescription) {
        if (snapshots.isEmpty()) {
            return UndoResult.nothingToUndo();
        }

        // Фаза 1: Pre-validation
        ValidationResult validation = preValidate(snapshots);

        // Если все файлы недоступны - STUCK
        if (validation.allStuck) {
            return buildStuckResult(validation, transactionDescription);
        }

        // Фаза 2: Выполнение отката
        List<UndoResult.FileDetail> fileDetails = new ArrayList<>();
        boolean hasPartial = false;
        boolean hasRelocated = false;

        for (Map.Entry<Path, byte[]> entry : snapshots.entrySet()) {
            Path originalPath = entry.getKey();
            byte[] backupContent = entry.getValue();
            FileValidation fv = validation.files.get(originalPath);

            if (fv == null) {
                // Не должно происходить, но обработаем
                continue;
            }

            try {
                UndoResult.FileDetail detail = restoreFile(originalPath, backupContent, fv);
                fileDetails.add(detail);

                if (detail.status() == UndoResult.FileStatus.SKIPPED) {
                    hasPartial = true;
                } else if (detail.status() == UndoResult.FileStatus.RELOCATED) {
                    hasRelocated = true;
                }
            } catch (IOException e) {
                fileDetails.add(new UndoResult.FileDetail(
                        originalPath, null, fv.fileId,
                        UndoResult.FileStatus.CONFLICT,
                        "I/O error: " + e.getMessage()
                ));
                hasPartial = true;
            }
        }

        // Определяем финальный статус
        UndoResult.Status status;
        String message;

        if (hasPartial) {
            status = UndoResult.Status.PARTIAL;
            message = "Partial undo completed. Some files were skipped.";
        } else if (hasRelocated) {
            status = UndoResult.Status.RESOLVED_MOVE;
            message = "Undo completed. Some files were found at relocated paths.";
        } else {
            status = UndoResult.Status.SUCCESS;
            message = "Undo completed successfully.";
        }

        UndoResult.Builder builder = UndoResult.builder()
                .status(status)
                .message(message)
                .transactionDescription(transactionDescription);

        for (UndoResult.FileDetail fd : fileDetails) {
            builder.addFileDetail(fd.originalPath(), fd.resolvedPath(),
                    fd.fileId(), fd.status(), fd.message());
        }

        // Добавляем Git suggestion если есть проблемы
        if (hasPartial && GitUtils.isGitRepo(projectRoot)) {
            builder.gitSuggestion(generateGitSuggestion(fileDetails));
        }

        return builder.build();
    }

    /**
     * Pre-validation: проверяет все файлы перед откатом.
     */
    private ValidationResult preValidate(Map<Path, byte[]> snapshots) {
        Map<Path, FileValidation> files = new HashMap<>();
        int stuckCount = 0;

        for (Path originalPath : snapshots.keySet()) {
            FileValidation fv = validateFile(originalPath);
            files.put(originalPath, fv);

            if (fv.status == FileValidationStatus.STUCK) {
                stuckCount++;
            }
        }

        boolean allStuck = stuckCount == snapshots.size();
        return new ValidationResult(files, allStuck);
    }

    /**
     * Валидирует один файл.
     */
    private FileValidation validateFile(Path originalPath) {
        String fileId = lineageTracker.getFileId(originalPath);
        Path currentPath = originalPath;

        // Проверка 1: Файл существует по оригинальному пути?
        if (Files.exists(originalPath)) {
            return new FileValidation(
                    FileValidationStatus.AVAILABLE,
                    originalPath, fileId, null
            );
        }

        // Проверка 2: Файл был перемещён (есть ID)?
        if (fileId != null) {
            Path relocatedPath = lineageTracker.getCurrentPath(fileId);
            if (relocatedPath != null && Files.exists(relocatedPath)) {
                return new FileValidation(
                        FileValidationStatus.RELOCATED,
                        relocatedPath, fileId,
                        "Found at: " + relocatedPath
                );
            }
        }

        // Проверка 3: Файл был удалён?
        // Для отката удаления это нормально - файл будет восстановлен из бекапа
        // Но если файл должен существовать для merge - это проблема
        return new FileValidation(
                FileValidationStatus.DELETED,
                null, fileId,
                "File was deleted or moved outside tracking"
        );
    }

    /**
     * Восстанавливает один файл.
     */
    private UndoResult.FileDetail restoreFile(Path originalPath, byte[] backupContent,
                                               FileValidation validation) throws IOException {
        Path targetPath = validation.currentPath != null ? validation.currentPath : originalPath;

        // Случай 1: Восстановление удалённого файла (backupContent != null, файл не существует)
        if (backupContent != null && !Files.exists(targetPath)) {
            Files.createDirectories(targetPath.getParent());
            Files.write(targetPath, backupContent);
            return new UndoResult.FileDetail(
                    originalPath, targetPath, validation.fileId,
                    UndoResult.FileStatus.RESTORED,
                    "File restored (was deleted/missing)"
            );
        }

        // Случай 2: Удаление созданного файла (backupContent == null, файл существует)
        if (backupContent == null && Files.exists(targetPath)) {
            // Проверяем на dirty directory
            if (Files.isDirectory(targetPath)) {
                try (var stream = Files.list(targetPath)) {
                    if (stream.findAny().isPresent()) {
                        // Директория не пустая - skip
                        return new UndoResult.FileDetail(
                                originalPath, targetPath, validation.fileId,
                                UndoResult.FileStatus.SKIPPED,
                                "Directory not empty - cannot delete without recursive flag"
                        );
                    }
                }
            }
            FileUtils.safeDelete(targetPath);
            FileUtils.deleteEmptyParents(targetPath, projectRoot);
            return new UndoResult.FileDetail(
                    originalPath, targetPath, validation.fileId,
                    UndoResult.FileStatus.RESTORED,
                    "File removed (was created in this transaction)"
            );
        }

        // Случай 3: Откат изменений (backupContent != null, файл существует)
        if (backupContent != null && Files.exists(targetPath)) {
            Files.write(targetPath, backupContent);

            UndoResult.FileStatus status = validation.status == FileValidationStatus.RELOCATED
                    ? UndoResult.FileStatus.RELOCATED
                    : UndoResult.FileStatus.RESTORED;

            String message = validation.status == FileValidationStatus.RELOCATED
                    ? "Content restored at new path (file was moved)"
                    : "Content restored to original state";

            return new UndoResult.FileDetail(
                    originalPath, targetPath, validation.fileId,
                    status, message
            );
        }

        // Случай 4: Файл не требует изменений (backup == null и файл не существует)
        String detailMessage;
        if (backupContent == null && !Files.exists(targetPath)) {
            detailMessage = "File already absent (no backup needed)";
        } else {
            detailMessage = "Content already matches expected state";
        }

        return new UndoResult.FileDetail(
                originalPath, targetPath, validation.fileId,
                UndoResult.FileStatus.RESTORED,
                detailMessage
        );
    }

    /**
     * Строит результат STUCK когда все файлы недоступны.
     */
    private UndoResult buildStuckResult(ValidationResult validation, String description) {
        UndoResult.Builder builder = UndoResult.builder()
                .status(UndoResult.Status.STUCK)
                .message("Cannot undo: all files are unavailable")
                .transactionDescription(description);

        for (Map.Entry<Path, FileValidation> entry : validation.files.entrySet()) {
            FileValidation fv = entry.getValue();
            builder.addFileDetail(
                    entry.getKey(), fv.currentPath, fv.fileId,
                    UndoResult.FileStatus.NOT_FOUND,
                    fv.message
            );
        }

        if (GitUtils.isGitRepo(projectRoot)) {
            builder.gitSuggestion(generateGitRecoveryCommand(validation.files.keySet()));
        }

        return builder.build();
    }

    /**
     * Генерирует Git suggestion для частичного отката.
     */
    private String generateGitSuggestion(List<UndoResult.FileDetail> fileDetails) {
        StringBuilder sb = new StringBuilder();
        sb.append("To recover skipped files, try:\n");

        for (UndoResult.FileDetail fd : fileDetails) {
            if (fd.status() == UndoResult.FileStatus.SKIPPED ||
                    fd.status() == UndoResult.FileStatus.NOT_FOUND) {
                Path relativePath = projectRoot.relativize(fd.originalPath());
                sb.append("  git checkout HEAD -- ").append(relativePath).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Генерирует Git команду восстановления для STUCK.
     */
    private String generateGitRecoveryCommand(Set<Path> paths) {
        StringBuilder sb = new StringBuilder();
        sb.append("All files are unavailable. Try git recovery:\n\n");
        sb.append("# Restore all affected files:\n");

        for (Path path : paths) {
            Path relativePath = projectRoot.relativize(path);
            sb.append("git checkout HEAD -- ").append(relativePath).append("\n");
        }

        sb.append("\n# Or restore entire directory:\n");
        sb.append("git checkout HEAD -- .\n");

        return sb.toString();
    }

    // ==================== Search by CRC ====================

    /**
     * Ищет файл по CRC (когда файл "потерян" вне трекинга).
     *
     * @param expectedCrc ожидаемый CRC32C
     * @param maxFilesToScan максимум файлов для сканирования
     * @return найденный путь или null
     */
    public Path findLostFileByCrc(long expectedCrc, int maxFilesToScan) throws IOException {
        // Сначала проверяем индекс LineageTracker
        List<Path> indexed = lineageTracker.findByCrc(expectedCrc);
        for (Path p : indexed) {
            if (Files.exists(p)) {
                return p;
            }
        }

        // Deep search по файловой системе
        return lineageTracker.deepSearchByCrc(expectedCrc, projectRoot, maxFilesToScan);
    }

    // ==================== Inner Classes ====================

    private enum FileValidationStatus {
        AVAILABLE,   // Файл существует по оригинальному пути
        RELOCATED,   // Файл перемещён, но найден по ID
        DELETED,     // Файл удалён/потерян
        STUCK        // Невозможно восстановить
    }

    private record FileValidation(
            FileValidationStatus status,
            Path currentPath,
            String fileId,
            String message
    ) {}

    private record ValidationResult(
            Map<Path, FileValidation> files,
            boolean allStuck
    ) {}
}
