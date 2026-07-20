package dev.ishaankot.worldarchive.recovery;

/** Cancellation contract for maintenance work with explicit commit boundaries. */
interface OperationCancellation {
    void checkpoint() throws InterruptedException;

    <T> T mandatoryCommit(CheckedSupplier<T> operation) throws Exception;

    <T> T commitIfActive(CheckedSupplier<T> operation) throws Exception;

    <T> T pointOfNoReturn(CheckedSupplier<T> operation) throws Exception;

    @FunctionalInterface
    interface CheckedSupplier<T> {
        T get() throws Exception;
    }
}
