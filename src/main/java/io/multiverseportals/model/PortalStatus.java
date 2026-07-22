package io.multiverseportals.model;

public enum PortalStatus {
    ACTIVE,
    /** MULTI: searching for a live compatible destination to bind. */
    BINDING,
    /** MULTI: bind search timed out / failed (will retry). */
    BIND_FAILED,
    BROKEN_LOCAL,
    BROKEN_REMOTE,
    DISABLED,
    PENDING_PAIR
}
