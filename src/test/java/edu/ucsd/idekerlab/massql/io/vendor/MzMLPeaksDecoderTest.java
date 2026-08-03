package edu.ucsd.idekerlab.massql.io.vendor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;
import java.util.zip.Deflater;

import org.junit.jupiter.api.Test;

/**
 * Proves the vendored decode layer in isolation, before any XML walking is built on top.
 *
 * <p>This is the layer everything else rests on: if it decodes wrongly, Step 8's bit-identity
 * gate fails and the cause looks like an XML-parsing bug. Testing it standalone means a
 * failure here points at exactly one place.
 *
 * <p><b>The 32-bit case is the important one.</b> pyteomics decodes a {@code 32-bit float}
 * array as {@code float32} and Python widens to double downstream, so the golden value is
 * {@code (double)(float)raw} — NOT a full-precision double. Verified below on raw bits.
 */
class MzMLPeaksDecoderTest {

    /** Encodes doubles as mzML's little-endian binary, then base64. */
    private static byte[] encode64(double[] values, boolean zlib) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) b.putDouble(v);
        return finish(b.array(), zlib);
    }

    /** Encodes as 32-bit floats — what a `precision="32"` mzML array contains. */
    private static byte[] encode32(double[] values, boolean zlib) {
        ByteBuffer b = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (double v : values) b.putFloat((float) v);
        return finish(b.array(), zlib);
    }

    private static byte[] finish(byte[] raw, boolean zlib) {
        if (!zlib) return Base64.getEncoder().encode(raw);
        Deflater d = new Deflater();
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!d.finished()) out.write(buf, 0, d.deflate(buf));
        d.end();
        return Base64.getEncoder().encode(out.toByteArray());
    }

    private static double[] decode(byte[] base64, int n, MzMLBitLength bits, MzMLCompressionType comp)
            throws Exception {
        MzMLBinaryDataInfo info = new MzMLBinaryDataInfo(base64.length, n);
        info.setBitLength(bits);
        info.setCompressionType(comp);
        info.setArrayType(MzMLArrayType.MZ);
        return MzMLPeaksDecoder.decodeToDouble(new ByteArrayInputStream(base64), info, new double[n]);
    }

    private static final double[] VALUES = {
        200.00018816645022,   // a real m/z from small.mzML -- NOT float32-exact
        810.79,
        123.456789012345,
        0.0,
        1.0,
    };

    @Test
    void sixtyFourBitUncompressedRoundTripsExactly() throws Exception {
        double[] got = decode(encode64(VALUES, false), VALUES.length,
                MzMLBitLength.SIXTY_FOUR_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        for (int i = 0; i < VALUES.length; i++) {
            assertEquals(Double.doubleToLongBits(VALUES[i]), Double.doubleToLongBits(got[i]),
                    "64-bit must be bit-exact at index " + i);
        }
    }

    @Test
    void thirtyTwoBitWidensFromFloatNotFromEightBytes() throws Exception {
        // THE bit-identity rule. A 32-bit array must decode to (double)(float)raw. Reading 8
        // bytes, or treating the bits as a double, produces values that are *nearly* right --
        // exactly the confusing near-miss Step 8 is built to catch.
        double[] got = decode(encode32(VALUES, false), VALUES.length,
                MzMLBitLength.THIRTY_TWO_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        for (int i = 0; i < VALUES.length; i++) {
            double expected = (double) (float) VALUES[i];
            assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(got[i]),
                    "index " + i + ": expected (double)(float)" + VALUES[i] + " = " + expected
                            + " but got " + got[i]);
        }
    }

    @Test
    void the32BitAnd64BitDecodesGenuinelyDiffer() throws Exception {
        // Guards the test above from being vacuous: if the sample values happened to be
        // float32-exact, the previous test would pass under a wrong implementation too.
        double[] as64 = decode(encode64(VALUES, false), VALUES.length,
                MzMLBitLength.SIXTY_FOUR_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        double[] as32 = decode(encode32(VALUES, false), VALUES.length,
                MzMLBitLength.THIRTY_TWO_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        assertNotEquals(as64[0], as32[0], "200.00018816645022 must not survive float32");
        assertNotEquals(as64[2], as32[2], "123.456789012345 must not survive float32");
        assertEquals(as64[3], as32[3], "0.0 is exact in both");
        assertEquals(as64[4], as32[4], "1.0 is exact in both");
    }

    @Test
    void zlibCompressedDecodesIdenticallyToUncompressed() throws Exception {
        double[] plain = decode(encode64(VALUES, false), VALUES.length,
                MzMLBitLength.SIXTY_FOUR_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        double[] zipped = decode(encode64(VALUES, true), VALUES.length,
                MzMLBitLength.SIXTY_FOUR_BIT_FLOAT, MzMLCompressionType.ZLIB);
        assertArrayEquals(plain, zipped);

        double[] plain32 = decode(encode32(VALUES, false), VALUES.length,
                MzMLBitLength.THIRTY_TWO_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        double[] zipped32 = decode(encode32(VALUES, true), VALUES.length,
                MzMLBitLength.THIRTY_TWO_BIT_FLOAT, MzMLCompressionType.ZLIB);
        assertArrayEquals(plain32, zipped32);
    }

    @Test
    void anEmptyArrayDecodesToAnEmptyResult() throws Exception {
        double[] got = decode(encode64(new double[0], false), 0,
                MzMLBitLength.SIXTY_FOUR_BIT_FLOAT, MzMLCompressionType.NO_COMPRESSION);
        assertEquals(0, got.length);
    }

    @Test
    void accessionSettersMapToTheRightEnums() {
        // The XML walk sets these from raw cvParam accession strings, so the mapping is
        // load-bearing: a wrong bit-length accession silently halves or doubles every value.
        // Note the bit-length and compression accessions live on the ENUMS, not in MzMLCV.
        MzMLBinaryDataInfo info = new MzMLBinaryDataInfo(0, 0);

        info.setBitLength("MS:1000523");
        assertEquals(MzMLBitLength.SIXTY_FOUR_BIT_FLOAT, info.getBitLength());
        info.setBitLength("MS:1000521");
        assertEquals(MzMLBitLength.THIRTY_TWO_BIT_FLOAT, info.getBitLength());

        info.setCompressionType("MS:1000574");
        assertEquals(MzMLCompressionType.ZLIB, info.getCompressionType());
        info.setCompressionType("MS:1000576");
        assertEquals(MzMLCompressionType.NO_COMPRESSION, info.getCompressionType());
        info.setCompressionType("MS:1002312");
        assertEquals(MzMLCompressionType.NUMPRESS_LINPRED, info.getCompressionType());

        info.setArrayType(MzMLCV.cvMzArray);
        assertEquals(MzMLArrayType.MZ, info.getArrayType());
        info.setArrayType(MzMLCV.cvIntensityArray);
        assertEquals(MzMLArrayType.INTENSITY, info.getArrayType());
    }

    @Test
    void theEnumAccessionsMatchTheMzMLSpecValuesTheXmlWalkWillLookFor() {
        // Pinned so a re-sync that renumbered anything fails here rather than in a decoder.
        assertEquals("MS:1000523", MzMLBitLength.SIXTY_FOUR_BIT_FLOAT.getValue());
        assertEquals("MS:1000521", MzMLBitLength.THIRTY_TWO_BIT_FLOAT.getValue());
        assertEquals("MS:1000514", MzMLArrayType.MZ.getAccession());
        assertEquals("MS:1000515", MzMLArrayType.INTENSITY.getAccession());
        assertEquals("MS:1000511", MzMLCV.cvMSLevel);
        assertEquals("MS:1000016", MzMLCV.MS_RT_SCAN_START);
        assertEquals("MS:1000744", MzMLCV.cvPrecursorMz);
        assertEquals("MS:1000041", MzMLCV.cvChargeState);
        assertEquals("MS:1000130", MzMLCV.cvPolarityPositive);
        assertEquals("MS:1000129", MzMLCV.cvPolarityNegative);
    }

    @Test
    void littleEndianReaderMatchesByteBuffer() throws Exception {
        // Our LittleEndianDataInput replaces Guava's; a big-endian slip here would corrupt
        // every mzML array while leaving mzXML (big-endian) looking fine.
        ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x01020304).putLong(0x0102030405060708L).putFloat(1.5f).putDouble(2.25);
        LittleEndianDataInput in = new LittleEndianDataInput(new ByteArrayInputStream(b.array()));
        assertEquals(0x01020304, in.readInt());
        assertEquals(0x0102030405060708L, in.readLong());
        assertEquals(1.5f, in.readFloat());
        assertEquals(2.25, in.readDouble());
    }
}
