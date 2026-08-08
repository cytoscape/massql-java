package edu.ucsd.idekerlab.massql.spectra;

/**
 * Which peak column a reduction operates on.
 *
 * <p>{@link #I_NORM} and {@link #I_TIC_NORM} are pre-computed at freeze time rather than
 * derived on demand, so the engine reads them as plain columns.
 */
public enum Column {
    MZ,
    I,
    I_NORM,
    I_TIC_NORM
}
