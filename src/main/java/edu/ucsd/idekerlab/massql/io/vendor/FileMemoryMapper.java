/*
 * VENDORED from MSDK -- github.com/msdk/msdk
 *   path:   msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/util/FileMemoryMapper.java
 *   commit: da2927a15c178b8ba9492d1e62571018bc70eecc
 *
 * Modified: package declaration only; otherwise byte-identical to upstream
 * See docs/VENDORED.md for the rationale and the full modification list.
 *
 * MSDK is dual-licensed LGPL-2.1 OR EPL-1.0. This project elects **EPL-1.0**.
 */
/*
 * (C) Copyright 2015-2017 by MSDK Development Team
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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

/**
 * <p>
 * Used to load a {@link java.io.File File} onto a
 * {@link io.github.msdk.io.mzml.util.ByteBufferInputStream ByteBufferInputStream}
 * </p>
 */
public abstract class FileMemoryMapper {

  /**
   * <p>
   * Used to load a {@link java.io.File File} onto a
   * {@link io.github.msdk.io.mzml.util.ByteBufferInputStream ByteBufferInputStream} *
   * </p>
   *
   * @param file the {@link java.io.File File} to be mapped
   * @return a {@link io.github.msdk.io.mzml2.util.io.ByteBufferInputStream ByteBufferInputStream}
   * @throws java.io.IOException if any
   */
  public static ByteBufferInputStream mapToMemory(File file) throws IOException {

    RandomAccessFile aFile = new RandomAccessFile(file, "r");
    FileChannel inChannel = aFile.getChannel();
    ByteBufferInputStream is = ByteBufferInputStream.map(inChannel, FileChannel.MapMode.READ_ONLY);
    aFile.close();

    return is;
  }
}
