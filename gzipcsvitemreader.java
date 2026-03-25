package com.batch.reader;

import com.batch.model.CsvFileMetadata;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.*;

import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

/**
 * Dynamic ItemReader that:
 *  1. Opens a .csv.gz file
 *  2. Decompresses it via GZIPInputStream (no temp file → memory-efficient)
 *  3. Reads the header row to discover column names
 *  4. Maps each data row to the target entity class via reflection
 *
 * One instance is created per file inside the step scope.
 */
@Slf4j
public class GzipCsvItemReader implements ItemReader<Object>, ItemStream {

    private final CsvFileMetadata metadata;
    private final int chunkSize;

    private CSVReader csvReader;
    private String[] headers;
    private Map<String, Field> fieldMap;   // header name → entity field
    private long lineCount = 0;

    public GzipCsvItemReader(CsvFileMetadata metadata, int chunkSize) {
        this.metadata = metadata;
        this.chunkSize = chunkSize;
    }

    // ─── ItemStream ───────────────────────────────────────────

    @Override
    public void open(ExecutionContext executionContext) {
        try {
            InputStream fileIn   = new BufferedInputStream(
                new java.io.FileInputStream(metadata.getFilePath().toFile()), 64 * 1024);
            InputStream gzipIn   = new GZIPInputStream(fileIn, 64 * 1024);
            Reader      reader   = new InputStreamReader(gzipIn, StandardCharsets.UTF_8);

            csvReader = new CSVReader(reader);

            // Read header row
            String[] rawHeaders = csvReader.readNext();
            if (rawHeaders == null) {
                throw new IllegalStateException("CSV file is empty: " + metadata.getFileName());
            }

            headers  = normalizeHeaders(rawHeaders);
            fieldMap = buildFieldMap(metadata.getEntityClass(), headers);

            log.info("[{}] Opened. Columns detected: {}", metadata.getFileName(), Arrays.toString(headers));

        } catch (IOException | CsvException e) {
            throw new ItemStreamException("Failed to open file: " + metadata.getFilePath(), e);
        }
    }

    @Override
    public void update(ExecutionContext executionContext) {
        executionContext.putLong("lines.read." + metadata.getFileName(), lineCount);
    }

    @Override
    public void close() {
        if (csvReader != null) {
            try {
                csvReader.close();
                log.info("[{}] Closed. Total lines read: {}", metadata.getFileName(), lineCount);
            } catch (IOException e) {
                log.warn("Error closing reader for {}", metadata.getFileName(), e);
            }
        }
    }

    // ─── ItemReader ───────────────────────────────────────────

    @Override
    public Object read() throws Exception {
        String[] row = csvReader.readNext();
        if (row == null) {
            return null;  // signals end of file to Spring Batch
        }
        lineCount++;
        return mapRowToEntity(row);
    }

    // ─── Internals ────────────────────────────────────────────

    private Object mapRowToEntity(String[] row) throws Exception {
        Object instance = metadata.getEntityClass().getDeclaredConstructor().newInstance();

        for (int i = 0; i < Math.min(headers.length, row.length); i++) {
            Field field = fieldMap.get(headers[i]);
            if (field == null) continue;   // CSV has a column we don't care about

            String rawValue = row[i] == null ? "" : row[i].trim();
            if (rawValue.isEmpty()) continue;

            field.setAccessible(true);
            field.set(instance, convertValue(rawValue, field.getType()));
        }

        return instance;
    }

    /** Build a normalised-name → Field map for the entity class. */
    private Map<String, Field> buildFieldMap(Class<?> clazz, String[] headers) {
        Map<String, Field> map = new HashMap<>();
        // Walk up the class hierarchy to catch inherited fields
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                map.put(normalize(f.getName()), f);
            }
        }
        // Also map using @Column(name=…) if present
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                jakarta.persistence.Column col = f.getAnnotation(jakarta.persistence.Column.class);
                if (col != null && !col.name().isEmpty()) {
                    map.put(normalize(col.name()), f);
                }
            }
        }
        return map;
    }

    private String[] normalizeHeaders(String[] raw) {
        String[] norm = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            norm[i] = normalize(raw[i]);
        }
        return norm;
    }

    /** Lowercase + strip non-alphanumeric so "Product Name" == "productname" == "product_name". */
    private String normalize(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** Basic type conversion — extend as needed. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convertValue(String value, Class<?> type) {
        if (type == String.class)                    return value;
        if (type == Integer.class || type == int.class)   return Integer.parseInt(value);
        if (type == Long.class    || type == long.class)  return Long.parseLong(value);
        if (type == Double.class  || type == double.class) return Double.parseDouble(value);
        if (type == Float.class   || type == float.class)  return Float.parseFloat(value);
        if (type == Boolean.class || type == boolean.class) return Boolean.parseBoolean(value);
        if (type == java.math.BigDecimal.class)      return new java.math.BigDecimal(value);
        if (type == java.time.LocalDate.class)       return java.time.LocalDate.parse(value);
        if (type == java.time.LocalDateTime.class)   return java.time.LocalDateTime.parse(value);
        if (type.isEnum())                           return Enum.valueOf((Class<Enum>) type, value);
        log.warn("No converter for type {}; storing raw String", type.getSimpleName());
        return value;
    }
}
