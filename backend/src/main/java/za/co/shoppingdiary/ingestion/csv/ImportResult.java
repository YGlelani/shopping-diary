package za.co.shoppingdiary.ingestion.csv;

import java.util.List;

public class ImportResult {

    private final int imported;
    private final int skipped;
    private final List<RowError> errors;

    public ImportResult(int imported, int skipped, List<RowError> errors) {
        this.imported = imported;
        this.skipped = skipped;
        this.errors = errors;
    }

    public int getImported() {
        return imported;
    }

    public int getSkipped() {
        return skipped;
    }

    public List<RowError> getErrors() {
        return errors;
    }

    public static class RowError {

        private final long rowNumber;
        private final String message;

        public RowError(long rowNumber, String message) {
            this.rowNumber = rowNumber;
            this.message = message;
        }

        public long getRowNumber() {
            return rowNumber;
        }

        public String getMessage() {
            return message;
        }
    }
}