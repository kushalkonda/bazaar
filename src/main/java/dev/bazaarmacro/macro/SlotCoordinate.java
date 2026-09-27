package dev.bazaarmacro.macro;

/**
 * Converts a spreadsheet-style coordinate (column letter + row number, e.g. "B2")
 * into a container slot index, for a chest-style GUI grid.
 *
 * <p>Column A is index 0, row 1 is the first row: {@code slotIndex = (row - 1) * width + columnIndex}.
 * A default width of 9 matches every standard Minecraft chest/generic-container row, and this
 * scheme reproduces Aether's own hardcoded Bazaar slot constants:
 * "B2" -> 10 (Buy Instantly), "H2" -> 16 (Custom Amount), "E2" -> 13 (Confirm).
 *
 * <p>A bare integer (e.g. "22") is also accepted and used as a raw slot index directly.
 */
public final class SlotCoordinate {
    public static final int DEFAULT_WIDTH = 9;

    private SlotCoordinate() {
    }

    public static int parse(String coordinate, int gridWidth) {
        if (coordinate == null) {
            throw new IllegalArgumentException("Coordinate is null");
        }
        String trimmed = coordinate.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Coordinate is empty");
        }

        if (trimmed.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(trimmed);
        }

        char columnChar = Character.toUpperCase(trimmed.charAt(0));
        if (columnChar < 'A' || columnChar > 'Z') {
            throw new IllegalArgumentException("Invalid coordinate: " + coordinate);
        }
        String rowPart = trimmed.substring(1);
        if (rowPart.isEmpty() || !rowPart.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Invalid coordinate: " + coordinate);
        }

        int columnIndex = columnChar - 'A';
        int row = Integer.parseInt(rowPart);
        if (row < 1) {
            throw new IllegalArgumentException("Row must be >= 1: " + coordinate);
        }
        return (row - 1) * gridWidth + columnIndex;
    }

    public static int parse(String coordinate) {
        return parse(coordinate, DEFAULT_WIDTH);
    }

    public static String toCoordinate(int slotIndex, int gridWidth) {
        int row = slotIndex / gridWidth + 1;
        int columnIndex = slotIndex % gridWidth;
        return "" + (char) ('A' + columnIndex) + row;
    }
}
