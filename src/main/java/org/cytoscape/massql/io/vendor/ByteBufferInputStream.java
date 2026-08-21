/*
 * VENDORED from MSDK -- github.com/msdk/msdk
 *   path:   msdk-io-mzml/src/main/java/io/github/msdk/io/mzml/util/ByteBufferInputStream.java
 *   commit: da2927a15c178b8ba9492d1e62571018bc70eecc
 *
 * Modified: package declaration, plus eager unmapping in close() -- upstream never releases the
 *   mappings it creates. See docs/VENDORED.md for the rationale and the full modification list.
 *
 * MSDK is dual-licensed LGPL-2.1 OR EPL-1.0. This project elects **EPL-1.0**.
 */
package org.cytoscape.massql.io.vendor;

/*
 * DSI utilities
 *
 * Copyright (C) 2007-2012 Sebastiano Vigna
 *
 * This library is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with this program;
 * if not, see <http://www.gnu.org/licenses/>.
 *
 */

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.util.Arrays;


/**
 * A bridge between byte {@linkplain ByteBuffer buffers} and {@linkplain InputStream input streams}.
 *
 * <p>
 * Java's {@linkplain FileChannel#map(MapMode, long, long) memory-mapping facilities} have the
 * severe limitation of mapping at most {@link java.lang.Integer#MAX_VALUE} bytes, as they expose the content
 * of a file using a {@link java.nio.MappedByteBuffer}. This class can
 * {@linkplain #map(FileChannel, FileChannel.MapMode) expose a file of arbitrary length} as a
 * {@linkplain RepositionableStream repositionable} {@link MeasurableInputStream} that is actually
 * based on an array of {@link java.nio.MappedByteBuffer}s, each mapping a <em>chunk</em> of
 * {@link #CHUNK_SIZE} bytes.
 *
 * @author Sebastiano Vigna
 * @since 1.2
 */
public class ByteBufferInputStream extends InputStream {
  private static int CHUNK_SHIFT = 30;

  /** The size of a chunk created by {@link #map(FileChannel, FileChannel.MapMode)}. */
  public static final long CHUNK_SIZE = 1L << CHUNK_SHIFT;

  /** The underlying byte buffers. */
  private final ByteBuffer[] byteBuffer;

  /**
   * An array parallel to {@link #byteBuffer} specifying which buffers do not need to be
   * {@linkplain ByteBuffer#duplicate() duplicated} before being used.
   */
  private final boolean[] readyToUse;

  /** The number of byte buffers. */
  private final int n;

  /** The current buffer. */
  private int curr;

  /** The current mark as a position, or -1 if there is no mark. */
  private long mark;

  /** The overall size of this input stream. */
  private final long size;

  /** The capacity of the last buffer. */
  private final int lastBufferCapacity;

  /** Number of bytes to be read before forcefully returning -1 */
  private long remainingBytes;

  /**
   * The mappings this stream created and must release, or null when it borrows buffers owned by
   * someone else.
   */
  private final MappedByteBuffer[] owned;

  /** Set by {@link #close()}; gates every buffer access. */
  private volatile boolean closed;

  /**
   * Creates a new byte-buffer input stream from a single {@link java.nio.ByteBuffer}.
   *
   * @param byteBuffer the underlying byte buffer.
   */
  public ByteBufferInputStream(final ByteBuffer byteBuffer) {
    this(new ByteBuffer[] {byteBuffer}, byteBuffer.capacity(), 0, new boolean[1]);
  }

  /**
   * Creates a new byte-buffer input stream.
   *
   * @param byteBuffer the underlying byte buffers.
   * @param size the sum of the {@linkplain ByteBuffer#capacity() capacities} of the byte buffers.
   * @param curr the current buffer (reading will start at this buffer from its current position).
   * @param readyToUse an array parallel to <code>byteBuffer</code> specifying which buffers do not
   *        need to be {@linkplain ByteBuffer#duplicate() duplicated} before being used (the process
   *        will happen lazily); the array will be used internally by the newly created byte-buffer
   *        input stream.
   */
  protected ByteBufferInputStream(final ByteBuffer[] byteBuffer, final long size, final int curr,
      final boolean[] readyToUse) {
    this(byteBuffer, size, curr, readyToUse, null);
  }

  private ByteBufferInputStream(final ByteBuffer[] byteBuffer, final long size, final int curr,
      final boolean[] readyToUse, final MappedByteBuffer[] owned) {
    this.owned = owned;
    this.byteBuffer = byteBuffer;
    this.n = byteBuffer.length;
    this.curr = curr;
    this.size = size;
    this.readyToUse = readyToUse;

    mark = -1;

    for (int i = 0; i < n; i++)
      if (i < n - 1 && byteBuffer[i].capacity() != CHUNK_SIZE)
        throw new IllegalArgumentException();
    lastBufferCapacity = byteBuffer[n - 1].capacity();
  }

  /**
   * Creates a new byte-buffer input stream by mapping a given file channel.
   *
   * @param fileChannel the file channel that will be mapped.
   * @param mapMode this must be {@link java.nio.channels.FileChannel.MapMode#READ_ONLY}.
   * @return a new byte-buffer input stream over the contents of <code>fileChannel</code>.
   * @throws java.io.IOException if any.
   */
  public static ByteBufferInputStream map(final FileChannel fileChannel, final MapMode mapMode)
      throws IOException {
    final long size = fileChannel.size();
    final int chunks = (int) ((size + (CHUNK_SIZE - 1)) / CHUNK_SIZE);
    final MappedByteBuffer[] owned = new MappedByteBuffer[chunks];
    final ByteBuffer[] byteBuffer = new ByteBuffer[chunks];
    for (int i = 0; i < chunks; i++) {
      owned[i] =
          fileChannel.map(mapMode, i * CHUNK_SIZE, Math.min(CHUNK_SIZE, size - i * CHUNK_SIZE));
      byteBuffer[i] = owned[i];
    }
    byteBuffer[0].position(0);
    final boolean[] readyToUse = new boolean[chunks];
    Arrays.fill(readyToUse, true);
    return new ByteBufferInputStream(byteBuffer, size, 0, readyToUse, owned);
  }

  private ByteBuffer byteBuffer(final int n) {
    if (closed)
      throw new IllegalStateException("stream is closed");
    if (readyToUse[n])
      return byteBuffer[n];
    readyToUse[n] = true;
    return byteBuffer[n] = byteBuffer[n].duplicate();
  }

  private long remaining() {
    return curr == n - 1 ? byteBuffer(curr).remaining()
        : byteBuffer(curr).remaining() + ((long) (n - 2 - curr) << CHUNK_SHIFT)
            + lastBufferCapacity;
  }

  /**
   * <p>available.</p>
   *
   * @return a int.
   */
  public int available() {
    final long available = remaining();
    return available <= Integer.MAX_VALUE ? (int) available : Integer.MAX_VALUE;
  }

  /** {@inheritDoc} */
  @Override
  public boolean markSupported() {
    return true;
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void mark(final int unused) {
    mark = position();
  }

  /** {@inheritDoc} */
  @Override
  public synchronized void reset() throws IOException {
    if (mark == -1)
      throw new IOException();
    position(mark);
  }

  /** {@inheritDoc} */
  @Override
  public long skip(final long n) throws IOException {
    final long toSkip = Math.min(remaining(), n);
    position(position() + toSkip);
    return toSkip;
  }

  private int readBuffer() {
    if (!byteBuffer(curr).hasRemaining()) {
      if (curr < n - 1)
        byteBuffer(++curr).position(0);
      else
        return -1;
    }

    return byteBuffer[curr].get() & 0xFF;
  }

  /** {@inheritDoc} */
  @Override
  public int read() {
    return (remainingBytes-- <= 0 ? -1 : readBuffer());
  }

  /** {@inheritDoc} */
  public int read(final byte[] b, final int offset, final int length) {
    if (length == 0)
      return 0;
    final long remaining = remaining();
    if (remaining == 0)
      return -1;
    final int realLength = (int) Math.min(remaining, length);
    int read = 0;
    while (read < realLength) {
      int rem = byteBuffer(curr).remaining();
      if (rem == 0)
        byteBuffer(++curr).position(0);
      byteBuffer[curr].get(b, offset + read, Math.min(realLength - read, rem));
      read += Math.min(realLength, rem);
    }
    return realLength;
  }

  /**
   * <p>length.</p>
   *
   * @return a long.
   */
  public long length() {
    return size;
  }

  /**
   * <p>position.</p>
   *
   * @return a long.
   */
  public long position() {
    return ((long) curr << CHUNK_SHIFT) + byteBuffer(curr).position();
  }

  /**
   * <p>position.</p>
   *
   * @param newPosition a long.
   */
  public void position(long newPosition) {
    newPosition = Math.min(newPosition, length());
    if (newPosition == length()) {
      final ByteBuffer buffer = byteBuffer(curr = n - 1);
      buffer.position(buffer.capacity());
      return;
    }

    curr = (int) (newPosition >>> CHUNK_SHIFT);
    byteBuffer(curr).position((int) (newPosition - ((long) curr << CHUNK_SHIFT)));
  }

  /**
   * <p>copy.</p>
   *
   * @return a {@link io.github.msdk.io.mzml.util.ByteBufferInputStream} object.
   */
  public ByteBufferInputStream copy() {
    return new ByteBufferInputStream(byteBuffer.clone(), size, curr, new boolean[n]);
  }

  /**
   * <p>constrain.</p>
   *
   * @param position a long.
   * @param remainingBytes a long.
   */
  public void constrain(long position, long remainingBytes) {
    this.position(position);
    this.remainingBytes = remainingBytes;
  }


  /**
   * Releases the mappings created by {@link #map(FileChannel, MapMode)}.
   *
   * <p>
   * Upstream inherits {@link InputStream}'s no-op close, so a mapping survives until the collector
   * reaches it -- and on Windows the mapped file stays locked for that whole time. Reading a buffer
   * after it is unmapped aborts the JVM rather than throwing, which is why {@link #byteBuffer(int)}
   * refuses to hand one out once {@link #closed} is set.
   */
  @Override
  public void close() {
    if (closed)
      return;
    closed = true;
    if (owned == null)
      return;
    for (int i = 0; i < owned.length; i++) {
      unmap(owned[i]);
      owned[i] = null;
    }
  }

  private static void unmap(final MappedByteBuffer buffer) {
    if (buffer == null || INVOKE_CLEANER == null)
      return;
    try {
      INVOKE_CLEANER.invoke(UNSAFE, buffer);
    } catch (Throwable ignored) {
      // A JDK that withdraws invokeCleaner leaves the mapping to the collector, as upstream does.
    }
  }

  /** {@code sun.misc.Unsafe}, reached reflectively; {@code jdk.unsupported} opens {@code sun.misc}. */
  private static final Object UNSAFE;

  /**
   * {@code Unsafe.invokeCleaner(ByteBuffer)}, or null if it is withdrawn or the reflective access is
   * denied -- in which case unmapping is skipped and the collector frees the mapping, as upstream.
   */
  private static final MethodHandle INVOKE_CLEANER;

  static {
    Object unsafe = null;
    MethodHandle invokeCleaner = null;
    try {
      final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      final Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      unsafe = theUnsafe.get(null);
      invokeCleaner = MethodHandles.lookup().findVirtual(unsafeClass, "invokeCleaner",
          MethodType.methodType(void.class, ByteBuffer.class));
    } catch (Throwable t) {
      unsafe = null;
      invokeCleaner = null;
    }
    UNSAFE = unsafe;
    INVOKE_CLEANER = invokeCleaner;
  }

}
