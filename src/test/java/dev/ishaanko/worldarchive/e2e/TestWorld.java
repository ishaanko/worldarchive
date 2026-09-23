package dev.ishaanko.worldarchive.e2e;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

/** A small but realistic world folder under the engine's saves folder. */
record TestWorld(WorldId id, Path path, String name) {
    static TestWorld create(Engine engine, String name) throws IOException {
        return create(engine.saves, name);
    }

    /** Creates region, player, and data-pack files, an empty file, and a session lock. */
    static TestWorld create(Path saves, String name) throws IOException {
        Path path = saves.resolve(name);
        Files.createDirectories(path);
        TestWorld world = new TestWorld(new WorldIdentityStore().loadOrCreate(path), path, name);
        world.write("level.dat", bytes(4_096, 1));
        world.write("region/r.0.0.mca", bytes(256 * 1_024, 2));
        world.write("region/r.0.-1.mca", bytes(128 * 1_024, 3));
        world.write("playerdata/3f1a2b.dat", bytes(2_048, 4));
        world.write("datapacks/Ünïcødé pack/pack.mcmeta", "{\"pack\":{}}".getBytes(StandardCharsets.UTF_8));
        world.write("empty.txt", new byte[0]);
        world.write("session.lock", "locked by the game".getBytes(StandardCharsets.UTF_8));
        return world;
    }

    void write(String relative, byte[] contents) throws IOException {
        Path file = path.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, contents);
    }

    void delete(String relative) throws IOException {
        Files.delete(path.resolve(relative));
    }

    /** Every backed-up file by portable path; the session lock and WorldArchive metadata are excluded. */
    Map<String, byte[]> files() throws IOException {
        return filesOf(path);
    }

    static Map<String, byte[]> filesOf(Path root) throws IOException {
        Map<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!relative.equals("session.lock") && !relative.startsWith(".worldarchive/")) {
                    files.put(relative, Files.readAllBytes(file));
                }
            }
        }
        return files;
    }

    static void assertSameFiles(Map<String, byte[]> expected, Path actualRoot) throws IOException {
        Map<String, byte[]> actual = filesOf(actualRoot);
        assertEquals(expected.keySet(), actual.keySet());
        expected.forEach((file, contents) -> assertArrayEquals(contents, actual.get(file), file));
    }

    static byte[] bytes(int length, long seed) {
        byte[] value = new byte[length];
        new Random(seed).nextBytes(value);
        return value;
    }
}
