// Aristo 23.12.2025
package ru.nts.tools.mcp.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Утилита для генерации текстовых различий (diff) между версиями контента.
 * Реализует упрощенный формат Unified Diff, совместимый с большинством инструментов визуализации.
 */
public class DiffUtils {

    /**
     * Генерирует Unified Diff между старым и новым контентом.
     *
     * @param fileName Имя файла для заголовка diff.
     * @param oldContent Исходный текст.
     * @param newContent Измененный текст.
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

        // Используем упрощенный алгоритм LCS (Longest Common Subsequence) для поиска различий
        int[][] matrix = computeLCSMatrix(oldLines, newLines);
        
        List<DiffLine> diffLines = new ArrayList<>();
        buildDiff(matrix, oldLines, newLines, oldLines.size(), newLines.size(), diffLines);
        Collections.reverse(diffLines);

        // Группировка в чанки (hunks)
        // Для простоты сейчас выводим все одним чанком, если изменений немного.
        // В идеале тут должна быть логика разделения на @@ -L,l +L,l @@
        
        diff.append("@@ -1,").append(oldLines.size()).append(" +1,").append(newLines.size()).append(" @@\n");
        for (DiffLine line : diffLines) {
            switch (line.type) {
                case INSERT -> diff.append("+").append(line.text).append("\n");
                case DELETE -> diff.append("-").append(line.text).append("\n");
                case EQUAL -> diff.append(" ").append(line.text).append("\n");
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
            result.add(new DiffLine(DiffType.EQUAL, a.get(i - 1)));
            buildDiff(matrix, a, b, i - 1, j - 1, result);
        } else if (j > 0 && (i == 0 || matrix[i][j - 1] >= matrix[i - 1][j])) {
            result.add(new DiffLine(DiffType.INSERT, b.get(j - 1)));
            buildDiff(matrix, a, b, i, j - 1, result);
        } else if (i > 0 && (j == 0 || matrix[i][j - 1] < matrix[i - 1][j])) {
            result.add(new DiffLine(DiffType.DELETE, a.get(i - 1)));
            buildDiff(matrix, a, b, i - 1, j, result);
        }
    }

    private enum DiffType { EQUAL, INSERT, DELETE }
    private record DiffLine(DiffType type, String text) {}
}
