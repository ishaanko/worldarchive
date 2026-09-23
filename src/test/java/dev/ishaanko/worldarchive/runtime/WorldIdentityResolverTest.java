package dev.ishaanko.worldarchive.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.ishaanko.worldarchive.config.WorldArchiveConfig;
import dev.ishaanko.worldarchive.config.WorldConfig;
import dev.ishaanko.worldarchive.config.WorldIdentityStore;
import dev.ishaanko.worldarchive.model.WorldId;
import dev.ishaanko.worldarchive.ui.model.BackupWorldContext;
import dev.ishaanko.worldarchive.ui.model.BackupWorldSelection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** How a world folder the game lists or opens gets its identity, with real folders and links. */
final class WorldIdentityResolverTest {
    private final WorldIdentityStore identities = new WorldIdentityStore();

    private final List<Path> found = new CopyOnWriteArrayList<>();

    private final List<Path> copies = new CopyOnWriteArrayList<>();

    @TempDir
    Path root;

    /** A game folder moved to another drive and linked back: its worlds are keyed by their real folder. */
    @Test
    void aWorldBehindALinkedSavesFolderIsKeyedByItsRealFolder() throws IOException {
        Path realSaves = Files.createDirectories(root.resolve("other drive/saves"));
        Path saves = link(root.resolve("game/saves"), realSaves);
        Path realWorld = world(realSaves.resolve("World"));
        WorldIdentityResolver resolver = resolver(saves);

        BackupWorldContext resolved = resolved(resolver, selection(saves, "World"));

        assertEquals(realWorld.toRealPath(), resolved.worldDirectory());
        assertTrue(resolved.matches(selection(saves, "World")));
        assertTrue(resolver.isRegistered(resolved.worldId(), realWorld.toRealPath()));
        assertEquals(List.of(realWorld.toRealPath()), found);
    }

    @Test
    void aWorldFolderThatIsALinkIsKeyedByItsRealFolderAndKeepsItsListedName() throws IOException {
        Path saves = Files.createDirectories(root.resolve("game/saves"));
        Path realWorld = world(root.resolve("elsewhere/My World"));
        link(saves.resolve("Linked"), realWorld);
        WorldIdentityResolver resolver = resolver(saves);

        BackupWorldContext resolved = resolved(resolver, selection(saves, "Linked"));

        assertEquals(realWorld.toRealPath(), resolved.worldDirectory());
        assertEquals("Linked", resolved.storageName());
        assertEquals(saves, resolved.worldsDirectory());
    }

    /** A folder copied in a file manager carries the original's identity; it must never share its backups. */
    @Test
    void aCopyIsRefusedWhileTheOriginalIsThereAndResolvesOnceItHasItsOwnIdentity() throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path original = world(saves.resolve("World"));
        WorldId originalId = identities.loadOrCreate(original);
        Path copy = copyWorld(original, saves.resolve("World - Copy"));
        WorldIdentityResolver resolver = resolver(saves);
        resolver.configure(settings(WorldConfig.defaults(originalId, original.toRealPath())));

        assertInstanceOf(WorldIdentityResolver.Resolution.Refused.class, resolver.resolve(selection(saves, "World - Copy")));
        assertEquals(List.of(copy.toRealPath()), copies);

        identities.giveCopyItsOwnIdentity(copy, originalId);
        BackupWorldContext resolved = resolved(resolver, selection(saves, "World - Copy"));

        assertNotEquals(originalId, resolved.worldId());
        assertTrue(resolver.isRegistered(originalId, original.toRealPath()));
    }

    /** The settings still name the old folder of a renamed world; that folder is gone, so the world moves. */
    @Test
    void aRenamedWorldKeepsItsIdentityInItsNewFolder() throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path renamed = world(saves.resolve("New Name"));
        WorldId worldId = identities.loadOrCreate(renamed);
        WorldIdentityResolver resolver = resolver(saves);
        resolver.configure(settings(WorldConfig.defaults(worldId, saves.resolve("Old Name"))));

        BackupWorldContext resolved = resolved(resolver, selection(saves, "New Name"));

        assertEquals(worldId, resolved.worldId());
        assertTrue(resolver.isRegistered(worldId, renamed.toRealPath()));
        assertFalse(resolver.isRegistered(worldId, saves.resolve("Old Name")));
        assertEquals(List.of(renamed.toRealPath()), found);
    }

    /** A restore that reuses the name of a deleted world gets that folder; the old identity's claim is stale. */
    @Test
    void aWorldRestoredIntoAnOldFolderTakesItOver() throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path folder = world(saves.resolve("World"));
        WorldId restored = identities.loadOrCreate(folder);
        WorldId deleted = WorldId.create();
        WorldIdentityResolver resolver = resolver(saves);
        resolver.configure(settings(WorldConfig.defaults(deleted, folder.toRealPath())));

        assertTrue(resolver.register(restored, folder));

        assertTrue(resolver.isRegistered(restored, folder.toRealPath()));
        assertFalse(resolver.isRegistered(deleted, folder.toRealPath()));
    }

    /** The settings always win: a claim they contradict goes when they load. */
    @Test
    void theSettingsWinOverAnEarlierClaim() throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path folder = world(saves.resolve("World"));
        WorldIdentityResolver resolver = resolver(saves);
        BackupWorldContext resolved = resolved(resolver, selection(saves, "World"));
        WorldId configured = WorldId.create();

        resolver.configure(settings(WorldConfig.defaults(configured, folder.toRealPath())));

        assertTrue(resolver.isRegistered(configured, folder.toRealPath()));
        assertFalse(resolver.isRegistered(resolved.worldId(), folder.toRealPath()));
    }

    @Test
    void anUnreadableIdentityFailsSoTheOpenWorldIsTriedAgainLater() throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        Path folder = world(saves.resolve("World"));
        identities.loadOrCreate(folder);
        Files.writeString(folder.resolve(".worldarchive/world.json"), "not json");

        assertThrows(IOException.class, () -> resolver(saves).resolve(selection(saves, "World")));
    }

    /** A world found later inside a backup folder pauses backups, although the settings passed their own check. */
    @Test
    void aBackupFolderInsideAWorldFoundLaterPausesBackups() throws IOException {
        Path saves = Files.createDirectories(root.resolve("saves"));
        world(saves.resolve("World"));
        WorldIdentityResolver resolver = resolver(saves);
        WorldArchiveConfig defaults = WorldArchiveConfig.defaults();
        resolver.configure(defaults.withZip(defaults.zip().withDestination(Optional.of(saves.resolve("World/backups")))));
        assertTrue(resolver.storageIssue().isEmpty());

        resolved(resolver, selection(saves, "World"));

        assertTrue(resolver.storageIssue().orElseThrow().startsWith("Backups are paused"));
    }

    private WorldIdentityResolver resolver(Path saves) {
        return new WorldIdentityResolver(saves, identities, new WorldIdentityResolver.Listener() {
            @Override
            public void found(WorldId worldId, Path worldDirectory) {
                found.add(worldDirectory);
            }

            @Override
            public void copyFound(Path copy) {
                copies.add(copy);
            }
        });
    }

    private static BackupWorldContext resolved(WorldIdentityResolver resolver, BackupWorldSelection selection)
            throws IOException {
        return assertInstanceOf(WorldIdentityResolver.Resolution.Resolved.class, resolver.resolve(selection)).world();
    }

    private static BackupWorldSelection selection(Path saves, String folder) {
        return new BackupWorldSelection(saves.resolve(folder), saves, folder, folder);
    }

    private static WorldArchiveConfig settings(WorldConfig world) {
        return WorldArchiveConfig.defaults().withWorlds(List.of(world));
    }

    private static Path world(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.write(folder.resolve("level.dat"), new byte[] {1, 2, 3});
        return folder;
    }

    private static Path copyWorld(Path original, Path copy) throws IOException {
        world(copy);
        Files.createDirectories(copy.resolve(".worldarchive"));
        Files.copy(original.resolve(".worldarchive/world.json"), copy.resolve(".worldarchive/world.json"));
        return copy;
    }

    private static Path link(Path link, Path target) throws IOException {
        Files.createDirectories(link.getParent());
        try {
            return Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException unsupported) {
            Assumptions.abort("This file system cannot create symbolic links");
            throw unsupported;
        }
    }
}
