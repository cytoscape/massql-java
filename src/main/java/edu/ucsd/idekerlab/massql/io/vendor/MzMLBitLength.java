/*
 * VENDORED from MSDK -- github.com/msdk/msdk
 *   path:   msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLBitLength.java
 *   commit: da2927a15c178b8ba9492d1e62571018bc70eecc
 *
 * Modified: package declaration only; otherwise byte-identical to upstream
 * See docs/VENDORED.md for the rationale and the full modification list.
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

package edu.ucsd.idekerlab.massql.io.vendor;

/**
 * Enumeration of different types of precision bit lengths which are parsed by the MzML Parser
 */
public enum MzMLBitLength {
  THIRTY_TWO_BIT_INTEGER("MS:1000519"), // 32-bit integer
  SIXTEEN_BIT_FLOAT("MS:1000520"), // 16-bit float
  THIRTY_TWO_BIT_FLOAT("MS:1000521"), // 32-bit float
  SIXTY_FOUR_BIT_INTEGER("MS:1000522"), // 64-bit integer
  SIXTY_FOUR_BIT_FLOAT("MS:1000523"); // 64-bit float

  private final String accession;

  MzMLBitLength(String accession) {
    this.accession = accession;
  }

  /**
   * <p>getValue.</p>
   *
   * @return the CV Parameter accession of the precision bit length as {@link java.lang.String
   *         String}
   */
  public String getValue() {
    return accession;
  }

}
