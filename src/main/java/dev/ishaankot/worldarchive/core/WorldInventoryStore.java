package dev.ishaankot.worldarchive.core;

import dev.ishaankot.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.Optional;

/** Durable per-world capture state stored outside every live world. */
public interface WorldInventoryStore {
    Optional<WorldInventory> load(WorldId worldId) throws IOException;

    void save(WorldId worldId, WorldInventory inventory) throws IOException;
}
