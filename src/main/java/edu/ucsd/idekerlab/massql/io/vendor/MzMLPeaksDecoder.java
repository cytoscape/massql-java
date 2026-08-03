/*
 * VENDORED from MSDK -- github.com/msdk/msdk
 *   path:   msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/data/MzMLPeaksDecoder.java
 *   commit: da2927a15c178b8ba9492d1e62571018bc70eecc
 *
 * Modified: Guava LittleEndianDataInputStream -> our LittleEndianDataInput; commons-io IOUtils.toByteArray -> InputStream.readAllBytes(); MSDKException -> MassqlException
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

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.zip.DataFormatException;
import java.util.zip.InflaterInputStream;



import edu.ucsd.idekerlab.massql.MassqlException;

/**
 * <p>
 * MzMLIntensityPeaksDecoder class.
 * </p>
 */
public class MzMLPeaksDecoder {

  /**
   * Converts a base64 encoded mz or intensity string used in mzML files to an array of floats. If
   * the original precision was 64 bit, you still get floats as output.
   *
   * @param binaryDataInfo meta-info about the compressed data
   * @throws java.util.zip.DataFormatException if any.
   * @throws java.io.IOException if any.
   * @return a float array containing the decoded values
   * @throws MassqlException if any.
   * @param inputStream a {@link java.io.InputStream} object.
   * @param data an array of float.
   */
  public static float[] decodeToFloat(InputStream inputStream, MzMLBinaryDataInfo binaryDataInfo,
      float[] data) throws DataFormatException, IOException, MassqlException {

    int lengthIn = binaryDataInfo.getEncodedLength();
    int numPoints = binaryDataInfo.getArrayLength();
    InputStream is = null;

    if (inputStream instanceof ByteBufferInputStream) {
      ByteBufferInputStream mappedByteBufferInputStream = (ByteBufferInputStream) inputStream;
      mappedByteBufferInputStream.constrain(binaryDataInfo.getPosition(), lengthIn);
      is = Base64.getDecoder().wrap(mappedByteBufferInputStream);
    } else {
      is = Base64.getDecoder().wrap(inputStream);
    }

    // for some reason there sometimes might be zero length <peaks> tags
    // (ms2 usually)
    // in this case we just return an empty result
    if (lengthIn == 0) {
      return new float[0];
    }

    InflaterInputStream iis = null;
    LittleEndianDataInput dis = null;
    byte[] bytes = null;

    if (data == null || data.length < numPoints)
      data = new float[numPoints];

    // first check for zlib compression, inflation must be done before
    // NumPress
    if (binaryDataInfo.getCompressionType() != null) {
      switch (binaryDataInfo.getCompressionType()) {
        case ZLIB:
        case NUMPRESS_LINPRED_ZLIB:
        case NUMPRESS_POSINT_ZLIB:
        case NUMPRESS_SHLOGF_ZLIB:
          iis = new InflaterInputStream(is);
          dis = new LittleEndianDataInput(iis);
          break;
        default:
          dis = new LittleEndianDataInput(is);
          break;
      }

      // Now we can check for NumPress
      int numDecodedDoubles;
      switch (binaryDataInfo.getCompressionType()) {
        case NUMPRESS_LINPRED:
        case NUMPRESS_LINPRED_ZLIB:
          bytes = dis.readAllBytes();
          numDecodedDoubles = MSNumpress.decodeLinear(bytes, bytes.length, data);
          if (numDecodedDoubles < 0) {
            throw new MassqlException("MSNumpress linear decoder failed");
          }
          return data;
        case NUMPRESS_POSINT:
        case NUMPRESS_POSINT_ZLIB:
          bytes = dis.readAllBytes();
          numDecodedDoubles = MSNumpress.decodePic(bytes, bytes.length, data);
          if (numDecodedDoubles < 0) {
            throw new MassqlException("MSNumpress positive integer decoder failed");
          }
          return data;
        case NUMPRESS_SHLOGF:
        case NUMPRESS_SHLOGF_ZLIB:
          bytes = dis.readAllBytes();
          numDecodedDoubles = MSNumpress.decodeSlof(bytes, bytes.length, data);
          if (numDecodedDoubles < 0) {
            throw new MassqlException("MSNumpress short logged float decoder failed");
          }
          return data;
        default:
          break;
      }
    } else {
      dis = new LittleEndianDataInput(is);
    }

    Integer precision;
    switch (binaryDataInfo.getBitLength()) {
      case THIRTY_TWO_BIT_FLOAT:
      case THIRTY_TWO_BIT_INTEGER:
        precision = 32;
        break;
      case SIXTY_FOUR_BIT_FLOAT:
      case SIXTY_FOUR_BIT_INTEGER:
        precision = 64;
        break;
      default:
        dis.close();
        throw new IllegalArgumentException(
            "Precision MUST be specified and be either 32-bit or 64-bit, "
                + "if MS-NUMPRESS compression was not used");
    }

    try {
      switch (precision) {
        case (32): {
          
          for (int i = 0; i < numPoints; i++) {
            data[i] = dis.readFloat();
          }
          break;
        }
        case (64): {

          for (int i = 0; i < numPoints; i++) {
            data[i] = (float) dis.readDouble();
          }
          break;
        }
        default: {
          dis.close();
          throw new IllegalArgumentException(
              "Precision can only be 32/64 bits, other values are not valid.");
        }
      }
    } catch (EOFException eof) {
      // If the stream reaches EOF unexpectedly, it is probably because the particular
      // scan/chromatogram didn't pass the Predicate
      throw new MassqlException(
          "Couldn't obtain values. Please make sure the scan/chromatogram passes the Predicate.");
    } finally {
      dis.close();
    }

    return data;
  }

  /**
   * Converts a base64 encoded mz or intensity string used in mzML files to an array of doubles. If
   * the original precision was 32 bit, you still get doubles as output.
   *
   * @param binaryDataInfo meta-info about encoded data
   * @throws java.util.zip.DataFormatException if any.
   * @throws java.io.IOException if any.
   * @return a double array containing the decoded values
   * @throws MassqlException if any.
   * @param inputStream a {@link java.io.InputStream} object.
   * @param data an array of double.
   */
  public static double[] decodeToDouble(InputStream inputStream, MzMLBinaryDataInfo binaryDataInfo,
      double[] data) throws DataFormatException, IOException, MassqlException {

    int lengthIn = binaryDataInfo.getEncodedLength();
    int numPoints = binaryDataInfo.getArrayLength();

    InputStream is = null;

    if (inputStream instanceof ByteBufferInputStream) {
      ByteBufferInputStream mappedByteBufferInputStream = (ByteBufferInputStream) inputStream;
      mappedByteBufferInputStream.constrain(binaryDataInfo.getPosition(), lengthIn);
      is = Base64.getDecoder().wrap(mappedByteBufferInputStream);
    } else {
      is = Base64.getDecoder().wrap(inputStream);
    }

    // for some reason there sometimes might be zero length <peaks> tags
    // (ms2 usually)
    // in this case we just return an empty result
    if (lengthIn == 0) {
      return new double[0];
    }

    InflaterInputStream iis = null;
    LittleEndianDataInput dis = null;
    byte[] bytes = null;

    if (data == null || data.length < numPoints)
      data = new double[numPoints];

    // first check for zlib compression, inflation must be done before
    // NumPress
    if (binaryDataInfo.getCompressionType() != null) {
      switch (binaryDataInfo.getCompressionType()) {
        case ZLIB:
        case NUMPRESS_LINPRED_ZLIB:
        case NUMPRESS_POSINT_ZLIB:
        case NUMPRESS_SHLOGF_ZLIB:
          iis = new InflaterInputStream(is);
          dis = new LittleEndianDataInput(iis);
          break;

        default:
          dis = new LittleEndianDataInput(is);
          break;
      }

      // Now we can check for NumPress
      int numDecodedDoubles;
      switch (binaryDataInfo.getCompressionType()) {
        case NUMPRESS_LINPRED:
        case NUMPRESS_LINPRED_ZLIB:
          bytes = dis.readAllBytes();
          numDecodedDoubles = MSNumpress.decodeLinear(bytes, bytes.length, data);
          if (numDecodedDoubles < 0) {
            throw new MassqlException("MSNumpress linear decoder failed");
          }
          return data;
        case NUMPRESS_POSINT:
        case NUMPRESS_POSINT_ZLIB:
          bytes = dis.readAllBytes();
          numDecodedDoubles = MSNumpress.decodePic(bytes, bytes.length, data);
          if (numDecodedDoubles < 0) {
            throw new MassqlException("MSNumpress positive integer decoder failed");
          }
          return data;
        case NUMPRESS_SHLOGF:
        case NUMPRESS_SHLOGF_ZLIB:
          bytes = dis.readAllBytes();
          numDecodedDoubles = MSNumpress.decodeSlof(bytes, bytes.length, data);
          if (numDecodedDoubles < 0) {
            throw new MassqlException("MSNumpress short logged float decoder failed");
          }
          return data;
        default:
          break;
      }
    } else {
      dis = new LittleEndianDataInput(is);
    }

    Integer precision;
    switch (binaryDataInfo.getBitLength()) {
      case THIRTY_TWO_BIT_FLOAT:
      case THIRTY_TWO_BIT_INTEGER:
        precision = 32;
        break;
      case SIXTY_FOUR_BIT_FLOAT:
      case SIXTY_FOUR_BIT_INTEGER:
        precision = 64;
        break;
      default:
        dis.close();
        throw new IllegalArgumentException(
            "Precision MUST be specified and be either 32-bit or 64-bit, "
                + "if MS-NUMPRESS compression was not used");
    }

    try {
      switch (precision) {
        case (32): {
          int asInt;

          for (int i = 0; i < numPoints; i++) {
            asInt = dis.readInt();
            data[i] = Float.intBitsToFloat(asInt);
          }
          break;
        }
        case (64): {
          long asLong;

          for (int i = 0; i < numPoints; i++) {
            asLong = dis.readLong();
            data[i] = Double.longBitsToDouble(asLong);
          }
          break;
        }
      }
    } catch (EOFException eof) {
      // If the stream reaches EOF unexpectedly, it is probably because the particular
      // scan/chromatogram didn't pass the Predicate
      throw new MassqlException(
          "Couldn't obtain values. Please make sure the scan/chromatogram passes the Predicate.");
    } finally {
      dis.close();
    }
    return data;
  }

}
