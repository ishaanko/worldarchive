package dev.ishaanko.worldarchive.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** A lang key that the code names but en_us.json lacks shows the raw key to players. */
class LangKeysTest {
    private static final Pattern KEY_IN_CODE = Pattern.compile("\"(screen\\.worldarchive\\.[a-z0-9_.]*[a-z0-9_])\"");

    private static final Pattern KEY_IN_LANG_FILE = Pattern.compile("^\\s*\"([^\"]+)\"\\s*:", Pattern.MULTILINE);

    @Test
    void everyKeyTheCodeNamesHasEnglishText() throws IOException {
        Set<String> english = matches(
                KEY_IN_LANG_FILE,
                Files.readString(Path.of("src/main/resources/assets/worldarchive/lang/en_us.json")));
        Set<String> named = new TreeSet<>();
        for (Path root : Set.of(Path.of("src/main/java"), Path.of("src/client/java"))) {
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(file -> file.toString().endsWith(".java"))
                        .forEach(file -> named.addAll(matches(KEY_IN_CODE, read(file))));
            }
        }

        assertFalse(named.isEmpty());
        named.removeAll(english);
        assertEquals(Set.of(), named);
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.results().map(result -> result.group(1)).collect(Collectors.toSet());
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
