package dev.ishaankot.worldarchive.storage.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Separate JVM fixture for process timeout and bounded-output tests. */
public final class NativeProcessFixture {
    private NativeProcessFixture() {
    }

    public static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "output" -> System.out.print(arguments[1].repeat(Integer.parseInt(arguments[2])));
            case "environment" -> System.out.print(System.getenv(arguments[1]));
            case "sleep" -> Thread.sleep(Long.parseLong(arguments[1]));
            case "spawn-inherited" -> spawnInherited(arguments);
            default -> throw new IllegalArgumentException("Unknown fixture operation");
        }
    }

    private static void spawnInherited(String[] arguments) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
        Process child = new ProcessBuilder(List.of(
                        java.toString(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        NativeProcessFixture.class.getName(),
                        "sleep",
                        arguments[2]))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        Files.writeString(Path.of(arguments[1]), Long.toString(child.pid()));
        Thread.sleep(Long.parseLong(arguments[3]));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }
}
