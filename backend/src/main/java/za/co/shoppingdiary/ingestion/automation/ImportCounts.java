package za.co.shoppingdiary.ingestion.automation;

final class ImportCounts {
    int examined;
    int imported;
    int skipped;
    int review;
    String checkpoint;
    private final StringBuilder samples=new StringBuilder();

    void skip(String reason) {
        skipped++;
        sample(reason);
    }

    void sample(String reason) {
        if (reason==null || samples.length()>=550) return;
        if (!samples.isEmpty()) samples.append(" | ");
        samples.append(reason,0,Math.min(reason.length(),590-samples.length()));
    }

    String detail() { return samples.isEmpty() ? null : samples.toString(); }
}
