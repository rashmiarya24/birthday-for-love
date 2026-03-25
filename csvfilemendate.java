package com.batch.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.nio.file.Path;

/**
 * Metadata describing a single .csv.gz file:
 * - the path on disk
 * - the fully-qualified entity class whose fields map to the CSV columns
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CsvFileMetadata {

    /** Full path to the .csv.gz file */
    private Path filePath;

    /**
     * The JPA entity class that this CSV maps to.
     * E.g. Product.class, Order.class, Customer.class …
     */
    private Class<?> entityClass;

    /** Convenient display name (derived from file name) */
    public String getFileName() {
        return filePath.getFileName().toString();
    }
}
