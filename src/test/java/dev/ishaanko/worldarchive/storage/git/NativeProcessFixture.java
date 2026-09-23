package dev.ishaanko.worldarchive.storage.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** A separate JVM that the runner tests start in place of Git. */
public final class NativeProcessFixture {
    private NativeProcessFixture() {
    }

    public static void main(String[] arguments) throws Exception {
        switch (arguments[0]) {
            case "output" -> System.out.print(arguments[1].repeat(Integer.parseInt(arguments[2])));
            case "environment" -> System.out.print(System.getenv(arguments[1]));
            case "sleep" -> Thread.sleep(Long.parseLong(arguments[1]));
            case "ticks" -> ticks(Integer.parseInt(arguments[1]), Long.parseLong(arguments[2]));
            case "fail" -> fail(arguments[1], arguments[2]);
            case "spawn-inherited" -> spawnInherited(arguments);
            default -> throw new IllegalArgumentException("Unknown fixture operation");
        }
    }

    /** Prints one dot per interval, like Git's progress output during a long transfer. */
    private static void ticks(int count, long intervalMillis) throws InterruptedException {
        for (int tick = 0; tick < count; tick++) {
            System.err.print('.');
            System.err.flush();
            Thread.sleep(intervalMillis);
        }
    }

    /** Writes some output, then an error, and exits the way Git does after a fatal error. */
    private static void fail(String output, String error) {
        System.out.print(output);
        System.out.flush();
        System.err.print(error);
        System.err.flush();
        System.exit(128);
    }

    /** Starts a sleeping child that shares this process's output, then sleeps itself. */
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
