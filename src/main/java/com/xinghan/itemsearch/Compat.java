package com.xinghan.itemsearch;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.*;
import java.util.*;

/** Java 8 equivalents used by the portable search core. */
public final class Compat {
    private Compat() { }
    @SafeVarargs public static <T> List<T> list(T... values) { return listCopy(Arrays.asList(values)); }
    public static <T> List<T> listCopy(Collection<? extends T> values) { return Collections.unmodifiableList(new ArrayList<T>(values)); }
    @SafeVarargs public static <T> Set<T> set(T... values) { return setCopy(Arrays.asList(values)); }
    public static <T> Set<T> setCopy(Collection<? extends T> values) { return Collections.unmodifiableSet(new LinkedHashSet<T>(values)); }
    @SuppressWarnings("unchecked") public static <K,V> Map<K,V> map(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        Map<K,V> result = new LinkedHashMap<K,V>();
        for (int i = 0; i < pairs.length; i += 2) {
            K key = (K) Objects.requireNonNull(pairs[i]); V value = (V) Objects.requireNonNull(pairs[i+1]);
            if (result.put(key, value) != null) throw new IllegalArgumentException("Duplicate key: " + key);
        }
        return Collections.unmodifiableMap(result);
    }
    public static <K,V> Map<K,V> mapCopy(Map<? extends K,? extends V> values) { return Collections.unmodifiableMap(new LinkedHashMap<K,V>(values)); }
    public static Path path(String first, String... rest) { return Paths.get(first, rest); }
    public static String readString(Path file, Charset charset) throws IOException { return new String(Files.readAllBytes(file), charset); }
    public static Path writeString(Path file, CharSequence value, Charset charset, OpenOption... options) throws IOException { return Files.write(file, value.toString().getBytes(charset), options); }
    public static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    public static String strip(String value) {
        int start = 0, end = value.length();
        while (start < end) { int cp = value.codePointAt(start); if (!Character.isWhitespace(cp)) break; start += Character.charCount(cp); }
        while (end > start) { int cp = value.codePointBefore(end); if (!Character.isWhitespace(cp)) break; end -= Character.charCount(cp); }
        return value.substring(start, end);
    }
    public static boolean isBlank(String value) { return value == null || strip(value).isEmpty(); }
    public static int checkIndex(int index, int size) { if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Index " + index + " out of " + size); return index; }
}
