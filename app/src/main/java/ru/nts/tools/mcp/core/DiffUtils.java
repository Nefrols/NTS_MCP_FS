// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Утилита для генерации текстовых различий (diff) между версиями контента.
 * Реализует формат Unified Diff с поддержкой чанков (hunks) для экономии места.
 */
public class DiffUtils {

    private static final int CONTEXT_SIZE = 3;

    /**
     * Генерирует Unified Diff между старым и новым контентом.
     *
     * @param fileName   Имя файла для заголовка diff.
     * @param oldContent Исходный текст.
     * @param newContent Измененный текст.
     *
     * @return Строка в формате Unified Diff.
     */
    public static String getUnifiedDiff(String fileName, String oldContent, String newContent) {
        if (oldContent.equals(newContent)) {
            return "";
        }

        List<String> oldLines = oldContent.isEmpty() ? List.of() : Arrays.asList(oldContent.split("\n", -1));
        List<String> newLines = newContent.isEmpty() ? List.of() : Arrays.asList(newContent.split("\n", -1));

        StringBuilder diff = new StringBuilder();
        diff.append("--- ").append(fileName).append(" (original)\n");
        diff.append("+++ ").append(fileName).append(" (modified)\n");

        int[][] matrix = computeLCSMatrix(oldLines, newLines);
        List<DiffLine> diffLines = new ArrayList<>();
        buildDiff(matrix, oldLines, newLines, oldLines.size(), newLines.size(), diffLines);
        Collections.reverse(diffLines);

        // Логика разбиения на чанки (hunks)
        List<Hunk> hunks = clusterIntoHunks(diffLines);
        for (Hunk hunk : hunks) {
            diff.append(String.format("@@ -%d,%d +%d,%d @@\n", hunk.oldStart, hunk.oldLen, hunk.newStart, hunk.newLen));
            for (DiffLine line : hunk.lines) {
                switch (line.type) {
                    case INSERT -> diff.append("+").append(line.text).append("\n");
                    case DELETE -> diff.append("-").append(line.text).append("\n");
                    case EQUAL -> diff.append(" ").append(line.text).append("\n");
                }
            }
        }

        return diff.toString().trim();
    }

    private static int[][] computeLCSMatrix(List<String> a, List<String> b) {
        int[][] matrix = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++) {
            for (int j = 1; j <= b.size(); j++) {
                if (a.get(i - 1).equals(b.get(j - 1))) {
                    matrix[i][j] = matrix[i - 1][j - 1] + 1;
                } else {
                    matrix[i][j] = Math.max(matrix[i - 1][j], matrix[i][j - 1]);
                }
            }
        }
        return matrix;
    }

    private static void buildDiff(int[][] matrix, List<String> a, List<String> b, int i, int j, List<DiffLine> result) {
        if (i > 0 && j > 0 && a.get(i - 1).equals(b.get(j - 1))) {
            result.add(new DiffLine(DiffType.EQUAL, a.get(i - 1), i, j));
            buildDiff(matrix, a, b, i - 1, j - 1, result);
        } else if (j > 0 && (i == 0 || matrix[i][j - 1] >= matrix[i - 1][j])) {
            result.add(new DiffLine(DiffType.INSERT, b.get(j - 1), i, j));
            buildDiff(matrix, a, b, i, j - 1, result);
        } else if (i > 0 && (j == 0 || matrix[i][j - 1] < matrix[i - 1][j])) {
            result.add(new DiffLine(DiffType.DELETE, a.get(i - 1), i, j));
            buildDiff(matrix, a, b, i - 1, j, result);
        }
    }

    private static List<Hunk> clusterIntoHunks(List<DiffLine> lines) {
        List<Hunk> hunks = new ArrayList<>();
        Hunk current = null;

        for (int i = 0; i < lines.size(); i++) {
            DiffLine line = lines.get(i);
            boolean isChanged = line.type != DiffType.EQUAL;

            // Если линия изменена или находится в радиусе контекста изменений
            if (isChanged || isNearChange(lines, i)) {
                if (current == null) {
                    current = new Hunk();
                    // Начало чанка (1-based)
                    current.oldStart = findOldStart(line, lines, i);
                    current.newStart = findNewStart(line, lines, i);
                }
                current.lines.add(line);
                if (line.type != DiffType.INSERT) current.oldLen++;
                if (line.type != DiffType.DELETE) current.newLen++;
            } else {
                if (current != null) {
                    hunks.add(current);
                    current = null;
                }
            }
        }
        if (current != null) hunks.add(current);
        return hunks;
    }

    private static boolean isNearChange(List<DiffLine> lines, int index) {
        for (int i = Math.max(0, index - CONTEXT_SIZE); i <= Math.min(lines.size() - 1, index + CONTEXT_SIZE); i++) {
            if (lines.get(i).type != DiffType.EQUAL) return true;
        }
        return false;
    }

    private static int findOldStart(DiffLine line, List<DiffLine> allLines, int index) {
        // Для первого элемента чанка определяем его реальный номер строки в старом файле
        int pos = 1;
        for (int i = 0; i < index; i++) {
            if (allLines.get(i).type != DiffType.INSERT) pos++;
        }
        return pos;
    }

    private static int findNewStart(DiffLine line, List<DiffLine> allLines, int index) {
        int pos = 1;
        for (int i = 0; i < index; i++) {
            if (allLines.get(i).type != DiffType.DELETE) pos++;
        }
        return pos;
    }

    private enum DiffType {EQUAL, INSERT, DELETE}

    private record DiffLine(DiffType type, String text, int oldIdx, int newIdx) {
    }

    private static class Hunk {
        int oldStart, oldLen, newStart, newLen;
        List<DiffLine> lines = new ArrayList<>();
    }
}