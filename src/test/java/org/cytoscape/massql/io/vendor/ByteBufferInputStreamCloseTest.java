package org.cytoscape.massql.io.vendor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Upstream never releases the mappings it creates, so a mapped file stays locked on Windows until
 * the collector happens to reach the buffer.
 *
 * <p>Note the failure mode these tests guard: touching an unmapped buffer aborts the JVM with
 * SIGSEGV rather than throwing. A regression here shows up as a crashed test worker, not a red
 * assertion.
 */
class ByteBufferInputStreamCloseTest {

    @TempDir Path tmp;

    private Path fileOf(int bytes) throws IOException {
        Path p = tmp.resolve("mapped.bin");
        byte[] content = new byte[bytes];
        for (int i = 0; i < bytes; i++) content[i] = (byte) i;
        Files.write(p, content);
        return p;
    }

    /**
     * {@code read()} counts down {@code remainingBytes}, which starts at zero, so a freshly mapped
     * stream reports EOF until {@code constrain} opens a window -- that is how the decoder drives
     * it.
     */
    private ByteBufferInputStream mapOf(Path p) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.READ)) {
            ByteBufferInputStream in = ByteBufferInputStream.map(ch, FileChannel.MapMode.READ_ONLY);
            in.constrain(0, Files.size(p));
            return in;
        }
    }

    @Test
    void readsBeforeCloseSucceed() throws IOException {
        try (ByteBufferInputStream in = mapOf(fileOf(64))) {
            assertEquals(0, in.read());
            assertEquals(1, in.read());
        }
    }

    @Test
    void readAfterCloseIsRefusedRatherThanTouchingUnmappedMemory() throws IOException {
        ByteBufferInputStream in = mapOf(fileOf(64));
        assertEquals(0, in.read());
        in.close();

        assertThrows(IllegalStateException.class, in::read);
        assertThrows(IllegalStateException.class, () -> in.read(new byte[8], 0, 8));
        assertThrows(IllegalStateException.class, in::available);
        assertThrows(IllegalStateException.class, in::position);
    }

    @Test
    void closeIsIdempotent() throws IOException {
        ByteBufferInputStream in = mapOf(fileOf(64));
        in.close();
        assertDoesNotThrow(in::close, "a second close must not unmap twice");
    }

    @Test
    void closingABorrowedStreamUnmapsNothing() {
        ByteBufferInputStream borrowed = new ByteBufferInputStream(ByteBuffer.allocate(16));
        borrowed.constrain(0, 16);
        assertDoesNotThrow(borrowed::close);
    }
}
