package com.mohsenzamni.mrreviewer.dto;

/**
 * A precise pointer to a code location that supports or contradicts an acceptance criterion.
 *
 * @param file  repository-relative file path, e.g. {@code src/main/java/com/example/Foo.java}
 * @param lines line reference in the diff, e.g. {@code "42"} or {@code "42-67"}
 */
public record Evidence(String file, String lines) {}
