package dev.ishaanko.worldarchive.core;

import dev.ishaanko.worldarchive.model.WorldId;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Map-backed inventory store with an optional simulated load failure. */
final class InMemoryInventoryStore implements WorldInventoryStore {
    final Map<WorldId, WorldInventory> values = new ConcurrentHashMap<>();

    IOException loadFailure;

    @Override
    public Optional<WorldInventory> load(WorldId worldId) throws IOException {
        if (loadFailure != null) {
            throw loadFailure;
        }
        return Optional.ofNullable(values.get(worldId));
    }

    @Override
    public void save(WorldId worldId, WorldInventory inventory) {
        values.put(worldId, inventory);
    }
}
