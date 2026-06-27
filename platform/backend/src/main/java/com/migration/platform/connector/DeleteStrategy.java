package com.migration.platform.connector;

/**
 * Resolves the delete-semantics conflict identified in the review (issue #25): the old setup
 * added a {@code __cdc_deleted} column AND ran {@code delete.enabled=true}, which contradict.
 * The strategy is now explicit and the source/sink configs are always generated consistently.
 */
public enum DeleteStrategy {
    /** Source deletes physically remove the target row (tombstones + sink delete.enabled=true). */
    HARD,
    /** Source deletes mark the row via {@code __cdc_deleted=true}; the row is retained. */
    SOFT
}
