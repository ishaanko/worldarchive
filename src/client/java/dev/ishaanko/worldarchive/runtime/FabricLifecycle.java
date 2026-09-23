package dev.ishaanko.worldarchive.runtime;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Connects the integrated server's Fabric events to {@link LiveWorldBackups}, and quits the
 * runtime with the game. Every handler catches what WorldArchive throws: vanilla runs the stopping
 * event right before the world's final save, and anything thrown there, an error such as a
 * {@link LinkageError} included, would skip that save.
 */
final class FabricLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger("WorldArchive");

    private final LiveWorldBackups live;

    private final Runnable shutdown;

    // Guarded by this; the render and the server thread both use them.
    private IntegratedServer runningServer;

    private boolean quitting;

    FabricLifecycle(LiveWorldBackups live, Runnable shutdown) {
        this.live = Objects.requireNonNull(live, "live");
        this.shutdown = Objects.requireNonNull(shutdown, "shutdown");
    }

    void register() {
        ServerLifecycleEvents.SERVER_STARTED.register(this::serverStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> run(server, "closing the world", live::stopping));
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) ->
                run(server, "saving the world", port -> live.saved(port, flush, force)));
        ServerLifecycleEvents.SERVER_STOPPED.register(this::serverStopped);
        ClientTickEvents.END_CLIENT_TICK.register(client -> guard("checking for scheduled backups", live::tick));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clientStopping());
    }

    private void serverStarted(MinecraftServer server) {
        if (server instanceof IntegratedServer integrated) {
            synchronized (this) {
                runningServer = integrated;
            }
            run(server, "opening the world", live::started);
        }
    }

    /** After the last world stopped, a game that is quitting shuts the runtime down: its wait shows as "Saving world". */
    private void serverStopped(MinecraftServer server) {
        if (!(server instanceof IntegratedServer integrated)) {
            return;
        }
        run(server, "closing the world", live::stopped);
        boolean quit;
        synchronized (this) {
            if (runningServer == integrated) {
                runningServer = null;
            }
            quit = quitting && runningServer == null;
        }
        if (quit) {
            guard("quitting", shutdown);
        }
    }

    /** The game quits; with a world open, the runtime shuts down once that world stopped. */
    private void clientStopping() {
        boolean now;
        synchronized (this) {
            quitting = true;
            now = runningServer == null;
        }
        if (now) {
            guard("quitting", shutdown);
        }
    }

    /**
     * Runs a handler for an integrated server. Whatever it throws is logged and never reaches the
     * server's stop and save; the save waiting on that server fails.
     */
    private void run(MinecraftServer server, String event, Consumer<LiveServer> handler) {
        if (!(server instanceof IntegratedServer integrated)) {
            return;
        }
        LiveServer port = new IntegratedLiveServer(integrated);
        try {
            handler.accept(port);
        } catch (Throwable failure) {
            LOGGER.error("WorldArchive failed while {}; the game goes on without that backup", event, failure);
            guard("recovering from that failure", () -> live.abandon(port, failure));
        }
    }

    private static void guard(String event, Runnable handler) {
        try {
            handler.run();
        } catch (RuntimeException failure) {
            LOGGER.error("WorldArchive failed while {}", event, failure);
        }
    }

    /** Minecraft's integrated server as a {@link LiveServer}; two are equal when they wrap the same server. */
    private record IntegratedLiveServer(IntegratedServer server) implements LiveServer {
        @Override
        public Path worldDirectory() {
            return server.getWorldPath(LevelResource.ROOT);
        }

        @Override
        public String levelName() {
            return server.getWorldData().getLevelName();
        }

        @Override
        public void execute(Runnable task) {
            server.execute(task);
        }

        @Override
        public boolean isServerThread() {
            return server.isSameThread();
        }

        @Override
        public void saveAll() {
            server.saveEverything(false, true, true);
        }

        @Override
        public boolean isAutoSave() {
            return server.isAutoSave();
        }

        @Override
        public void setAutoSave(boolean enabled) {
            server.setAutoSave(enabled);
        }

        @Override
        public int tickCount() {
            return server.getTickCount();
        }
    }
}
