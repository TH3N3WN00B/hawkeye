package gg.moonrise.hawkeye.entity.query;

/** Which events a query reads by their rolled-back flag. */
public enum RollbackState {

    /** Every event (lookups). */
    ANY,
    /** Events not rolled back (what a rollback undoes). */
    ACTIVE,
    /** Events rolled back (what a restore redoes). */
    ROLLED_BACK
}
