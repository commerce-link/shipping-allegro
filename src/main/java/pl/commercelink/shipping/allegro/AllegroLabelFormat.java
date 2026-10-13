package pl.commercelink.shipping.allegro;

import java.util.Locale;

/**
 * Label format chosen in the store's settings. The file format (PDF or ZPL) is fixed when the shipment is created;
 * the page size is sent when the label is downloaded (Allegro ignores it for ZPL).
 */
public enum AllegroLabelFormat {
    PDF_A6("PDF", "A6"),
    PDF_A4("PDF", "A4"),
    ZPL("ZPL", "A6");

    private final String fileFormat;
    private final String pageSize;

    AllegroLabelFormat(String fileFormat, String pageSize) {
        this.fileFormat = fileFormat;
        this.pageSize = pageSize;
    }

    public String fileFormat() {
        return fileFormat;
    }

    public String pageSize() {
        return pageSize;
    }

    /** A missing or unknown value (e.g. a format removed later) falls back to PDF A6 rather than breaking shipping. */
    public static AllegroLabelFormat from(String value) {
        if (value == null || value.isBlank()) {
            return PDF_A6;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return PDF_A6;
        }
    }
}
