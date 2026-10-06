package com.xinghan.itemsearch;

import java.util.UUID;

/** Validates chat references against the result set that generated the link. */
public final class ResultReferences {
    private static final String STALE = "reference.stale";

    private ResultReferences() {}

    /** Converts a one-based result number to the list's zero-based index. */
    public static int index(UUID currentId, String suppliedId, String indexText, int resultCount) {
        validateToken(currentId, suppliedId);
        if (resultCount < 1) throw new IllegalArgumentException(Language.tr("reference.empty-results"));
        return positiveNumber(indexText, resultCount, Language.tr("reference.index-bounds", "maximum", resultCount)) - 1;
    }

    /** Page numbers remain one-based for the command and result renderer. */
    public static int page(UUID currentId, String suppliedId, String pageText, int pages) {
        validateToken(currentId, suppliedId);
        if (pages < 1) throw new IllegalArgumentException(Language.tr("reference.empty-pages"));
        return positiveNumber(pageText, pages, Language.tr("reference.page-bounds", "maximum", pages));
    }

    private static void validateToken(UUID currentId, String suppliedId) {
        if (currentId == null || suppliedId == null) throw new IllegalArgumentException(Language.tr(STALE));
        try {
            UUID supplied = UUID.fromString(suppliedId);
            // UUID.fromString also accepts abbreviated groups such as 1-1-1-1-1.
            // Chat links always use the full canonical UUID; reject alternate spellings.
            if (!supplied.toString().equalsIgnoreCase(suppliedId) || !currentId.equals(supplied)) {
                throw new IllegalArgumentException(Language.tr(STALE));
            }
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(Language.tr(STALE));
        }
    }

    private static int positiveNumber(String text, int maximum, String boundsMessage) {
        if (text == null || !text.matches("[0-9]+")) throw new IllegalArgumentException(boundsMessage);
        try {
            int value = Integer.parseInt(text);
            if (value < 1 || value > maximum) throw new IllegalArgumentException(boundsMessage);
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(boundsMessage);
        }
    }
}
