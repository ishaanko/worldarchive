package dev.ishaankot.worldarchive.runtime;

import dev.ishaankot.worldarchive.catalog.BackupCatalog;
import dev.ishaankot.worldarchive.config.WorldIdentityStore;
import dev.ishaankot.worldarchive.core.FileSystemBackupCaptureFactory;
import dev.ishaankot.worldarchive.core.FileWorldInventoryStore;
import dev.ishaankot.worldarchive.core.LockingWorldOperationGate;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import net.minecraft.client.Minecraft;

/** Long-lived collaborators shared by runtime state building and client-facing views. */
record RuntimeServices(
        Minecraft minecraft,
        Path storageRoot,
        BackupCatalog catalog,
        FileWorldInventoryStore inventoryStore,
        FileSystemBackupCaptureFactory captureFactory,
        WorldIdentityStore identityStore,
        LockingWorldOperationGate captureMutex,
        LockingWorldOperationGate operationGate,
        ExecutorService workerExecutor,
        Clock clock) {

    RuntimeServices {
        Objects.requireNonNull(minecraft, "minecraft");
        Objects.requireNonNull(storageRoot, "storageRoot");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(inventoryStore, "inventoryStore");
        Objects.requireNonNull(captureFactory, "captureFactory");
        Objects.requireNonNull(identityStore, "identityStore");
        Objects.requireNonNull(captureMutex, "captureMutex");
        Objects.requireNonNull(operationGate, "operationGate");
        Objects.requireNonNull(workerExecutor, "workerExecutor");
        Objects.requireNonNull(clock, "clock");
    }
}
