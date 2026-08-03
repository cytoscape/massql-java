/*
 * VENDORED from MSDK -- github.com/msdk/msdk
 *   path:   msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLCompressionType.java
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
 * Enumeration of different compression types which are parsed by the MzML Parser
 */
public enum MzMLCompressionType {
  NUMPRESS_LINPRED("MS:1002312", "MS-Numpress linear prediction compression"), //
  NUMPRESS_POSINT("MS:1002313", "MS-Numpress positive integer compression"), //
  NUMPRESS_SHLOGF("MS:1002314", "MS-Numpress short logged float compression"), //
  ZLIB("MS:1000574", "zlib compression"), //
  NO_COMPRESSION("MS:1000576", "no compression"), //
  NUMPRESS_LINPRED_ZLIB("MS:1002746",
      "MS-Numpress linear prediction compression followed by zlib compression"), //
  NUMPRESS_POSINT_ZLIB("MS:1002747",
      "MS-Numpress positive integer compression followed by zlib compression"), //
  NUMPRESS_SHLOGF_ZLIB("MS:1002748",
      "MS-Numpress short logged float compression followed by zlib compression");

  private String accession;
  private String name;

  MzMLCompressionType(String accession, String name) {
    this.accession = accession;
    this.name = name;
  }

  /**
   * <p>Getter for the field <code>accession</code>.</p>
   *
   * @return the CV Parameter accession of the compression type as {@link java.lang.String String}
   */
  public String getAccession() {
    return accession;
  }

  /**
   * <p>Getter for the field <code>name</code>.</p>
   *
   * @return the CV Parameter name of the compression type as {@link java.lang.String String}
   */
  public String getName() {
    return name;
  }

}


