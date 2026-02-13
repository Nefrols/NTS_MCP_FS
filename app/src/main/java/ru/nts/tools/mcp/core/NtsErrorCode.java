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

import java.util.Map;

/**
 * Structured error codes for NTS MCP tools.
 * Each error has a human-readable message and a solution hint.
 *
 * <p>Example usage in tool output:
 * <pre>
 * [ERROR: FILE_NOT_FOUND]
 * Message: File not found
 * Solution: Check file path. Use nts_file_search to find files.
 * Context: path=src/Missing.java, action=read
 * </pre>
 */
public enum NtsErrorCode {

    // ============ File Errors ============

    FILE_NOT_FOUND("File not found",
            "Check file path. Use nts_file_search to find files."),

    FILE_NOT_READABLE("File not readable",
            "Check file permissions. Ensure the file is not locked."),

    FILE_IS_BINARY("Binary file detected",
            "Cannot read binary files as text. Use appropriate tools for binary content."),

    FILE_TOO_LARGE("File too large",
            "Use startLine/endLine to read in chunks. Max file size is limited."),

    FILE_LOCKED("File locked by another process",
            "Close the file in other applications and try again."),

    FILE_ENCODING_ERROR("Cannot decode file content",
            "Specify encoding parameter (e.g., 'windows-1251', 'UTF-8')."),

    // ============ Directory Errors ============

    DIRECTORY_NOT_FOUND("Directory not found",
            "Check directory path. Use nts_file_search to find directories."),

    DIRECTORY_NOT_EMPTY("Directory is not empty",
            "Use recursive=true to delete non-empty directories."),

    // ============ Token Errors ============

    TOKEN_REQUIRED("Access token required",
            "Read file with nts_file_read first to get a token."),

    TOKEN_INVALID_FORMAT("Invalid token format",
            "Token is corrupted or malformed. Re-read the file to get a fresh token."),

    TOKEN_EXPIRED("Token expired (content changed)",
            "File content changed since token was issued. " +
            "ACTION: nts_file_read(path='%path%', startLine=%start%, endLine=%end%) to get fresh token."),

    TOKEN_PATH_MISMATCH("Token path mismatch",
            "Token was issued for a different file. Use correct token for this file."),

    TOKEN_RANGE_MISMATCH("Token range insufficient",
            "Token covers lines %tokenStart%-%tokenEnd%, but edit targets lines %editStart%-%editEnd%. " +
            "ACTION: nts_file_read(path='%path%', startLine=%editStart%, endLine=%editEnd%) to get covering token."),

    TOKEN_EXTERNAL_CHANGE("Token invalidated by external change",
            "File was modified outside NTS. Re-read file to get fresh token."),

    // ============ Parameter Errors ============

    PARAM_MISSING("Required parameter missing",
            "Provide the required parameter. Check tool documentation."),

    PARAM_INVALID("Invalid parameter value",
            "Check parameter type and format. Refer to tool documentation."),

    PARAM_OUT_OF_RANGE("Parameter out of range",
            "Check parameter bounds. Value exceeds allowed limits."),

    PARAM_LINE_EXCEEDS("Line number exceeds file",
            "File has %totalLines% lines, requested line %line%. " +
            "ACTION: nts_file_read(path='%path%', action='info') to see actual line count."),

    PARAM_CONFLICT("Conflicting parameters",
            "Cannot use these parameters together. Check tool documentation."),

    // ============ Symbol Errors ============

    SYMBOL_NOT_FOUND("Symbol not found",
            "Symbol '%symbol%' not found in %path%. " +
            "ACTION: nts_code_navigate(action='symbols', path='%path%') to see available symbols."),

    SYMBOL_AMBIGUOUS("Multiple symbols found",
            "Specify symbolKind (class, method, function, field) to disambiguate."),

    PATTERN_NOT_FOUND("Pattern not found in file",
            "Regex '%pattern%' not found in %path%. " +
            "ACTION: nts_file_search(action='grep', pattern='%pattern%') to search across files. Check regex syntax."),

    // ============ Change Errors ============

    CHANGE_EXTERNAL("External change detected",
            "File was modified outside NTS. Previous content backed up. Re-read to continue."),

    CHANGE_CONFLICT("Edit conflict detected",
            "Content has changed since reading. expectedContent doesn't match current content."),

    // ============ Task Errors ============

    TASK_NOT_FOUND("Task not found",
            "Initialize task with nts_init first."),

    CHECKPOINT_NOT_FOUND("Checkpoint not found",
            "Check checkpoint name. Use action='journal' to list checkpoints."),

    NOTHING_TO_UNDO("Nothing to undo",
            "Undo stack is empty. No operations to reverse."),

    NOTHING_TO_REDO("Nothing to redo",
            "Redo stack is empty or was cleared by new edits."),

    // ============ Refactoring Errors ============

    REFACTOR_SCOPE_TOO_LARGE("Refactoring scope too large",
            "Too many files affected. Use scope='file' or 'directory' to limit."),

    REFACTOR_LANGUAGE_NOT_SUPPORTED("Language not supported for refactoring",
            "This file type doesn't support semantic refactoring. Use text replacement."),

    // ============ System Errors ============

    IO_ERROR("I/O error occurred",
            "Check disk space and permissions. Try again."),

    INTERNAL_ERROR("Internal error",
            "Unexpected error. Check logs for details.");

    private final String message;
    private final String solution;

    NtsErrorCode(String message, String solution) {
        this.message = message;
        this.solution = solution;
    }

    public String getMessage() {
        return message;
    }

    public String getSolution() {
        return solution;
    }

    /**
     * Formats error message with optional context.
     *
     * @param context Optional context map (path, action, etc.)
     * @return Formatted error string
     */
    public String format(Map<String, Object> context) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[ERROR: %s]\n", this.name()));
        sb.append(String.format("Message: %s\n", message));

        // Интерполяция %placeholder% в solution
        String resolvedSolution = solution;
        if (context != null) {
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                resolvedSolution = resolvedSolution.replace(
                        "%" + entry.getKey() + "%", String.valueOf(entry.getValue()));
            }
        }
        // Очищаем неиспользованные плейсхолдеры
        resolvedSolution = resolvedSolution.replaceAll("%\\w+%", "...");
        sb.append(String.format("Solution: %s", resolvedSolution));

        if (context != null && !context.isEmpty()) {
            sb.append("\nContext: ");
            boolean first = true;
            for (Map.Entry<String, Object> entry : context.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
                first = false;
            }
        }

        return sb.toString();
    }

    /**
     * Formats error message without context.
     *
     * @return Formatted error string
     */
    public String format() {
        return format(null);
    }
}
