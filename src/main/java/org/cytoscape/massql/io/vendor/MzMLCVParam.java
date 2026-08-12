/*
 * VENDORED from MSDK -- github.com/msdk/msdk
 *   path:   msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLCVParam.java
 *   commit: da2927a15c178b8ba9492d1e62571018bc70eecc
 *
 * Modified: package declaration only; otherwise byte-identical to upstream.
 * Vendored solely so MzMLCV can stay byte-identical -- MzMLCV declares four unused
 * static MzMLCVParam fields for MSDK's mzML EXPORT path, which we never use. Deleting
 * them would have been the alternative, but keeping the constants table unmodified
 * makes a future re-sync a clean diff.
 * See docs/VENDORED.md.
 *
 * MSDK is dual-licensed LGPL-2.1 OR EPL-1.0. This project elects **EPL-1.0**.
 */
/*
 * (C) Copyright 2015-2016 by MSDK Development Team
 *
 * This software is dual-licensed under either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1 as published by the Free
 * Software Foundation
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by the Eclipse Foundation.
 */

package org.cytoscape.massql.io.vendor;

import java.util.Optional;

/**
 * A CV Parameter object which contains the CV Parameter's accession and value, name & unitAccession
 * if available
 */
public class MzMLCVParam {

  private final String accession;
  private final Optional<String> value;
  private final Optional<String> name;
  private final Optional<String> unitAccession;

  /**
   * <p>
   * Constructor for MzMLCVParam
   * </p>
   *
   * @param accession the CV Parameter accession as {@link java.lang.String String}
   * @param value the CV Parameter value as {@link java.lang.String String}
   * @param name the CV Parameter name as {@link java.lang.String String}
   * @param unitAccession the CV Parameter unit accession as {@link java.lang.String String}
   */
  public MzMLCVParam(String accession, String value, String name, String unitAccession) {
    if (accession == null)
      throw new IllegalArgumentException("Accession can't be null");
    if (accession.length() == 0)
      throw new IllegalArgumentException("Accession can't be an empty string");
    this.accession = accession;
    if (value != null && value.length() == 0)
      value = null;
    this.value = Optional.ofNullable(value);
    if (name != null && name.length() == 0)
      name = null;
    this.name = Optional.ofNullable(name);
    if (unitAccession != null && unitAccession.length() == 0)
      unitAccession = null;
    this.unitAccession = Optional.ofNullable(unitAccession);
  }

  /**
   * <p>Getter for the field <code>accession</code>.</p>
   *
   * @return the CV Parameter accession as {@link java.lang.String String}
   */
  public String getAccession() {
    return accession;
  }


  /**
   * <p>Getter for the field <code>value</code>.</p>
   *
   * @return the CV Parameter value as {@link java.util.Optional Optional<String>}
   */
  public Optional<String> getValue() {
    return value;
  }

  /**
   * <p>Getter for the field <code>name</code>.</p>
   *
   * @return the CV Parameter name as {@link java.util.Optional Optional<String>}
   */
  public Optional<String> getName() {
    return name;
  }

  /**
   * <p>Getter for the field <code>unitAccession</code>.</p>
   *
   * @return the CV Parameter unit accession as {@link java.util.Optional Optional<String>}
   */
  public Optional<String> getUnitAccession() {
    return unitAccession;
  }

}
