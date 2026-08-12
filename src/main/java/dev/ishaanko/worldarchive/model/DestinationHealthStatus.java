package dev.ishaanko.worldarchive.model;

/** Runtime readiness of a configured destination. */
public enum DestinationHealthStatus {
    HEALTHY,
    DISABLED,
    UNCONFIGURED,
    TOOL_MISSING,
    DEGRADED,
    UNAVAILABLE,
    AUTHENTICATION_REQUIRED
}
