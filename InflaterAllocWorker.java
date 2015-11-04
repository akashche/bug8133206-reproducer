/*
 * Copyright (c) 2015, Red Hat, Inc. and/or its affiliates.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 */

import java.io.*;
import java.util.zip.Inflater;

/**
 * Main class, should be run under the memcheck (valgrind)
 * with "leak-check" and "show-reachable" enabled.
 *
 * Expected leaks have the following trace:
 * malloc <- updatewindow <- inflate <- Java_java_util_zip_Inflater_inflateBytes .
 *
 * zlib's debuginfo must be installed if JDK uses system zlib.
 *
 * @author akashche
 */
public class InflaterAllocWorker {

    private static final int COMPRESSED_LEN = 39546; // 0x9a7a
    private static final int UNCOMPRESSED_LEN = 103727; // 0x01952f
    private static final int HEADER_LEN = 74; // 30 [fixed] + 16 [filename] + 28 [metadata]

    /**
     * Inflates an entry from XSDHandler.class.zip (path to it is a first argument)
     * differently depending on specified 'mode' (second argument).
     * 'inflate' mode inflates in a single pass, 'smallbuf' mode inflates doing multiple passes,
     * 'noinflate' (or any other mode) is a no-op.
     * Stops the process immediately after the inflating to prevent "Inflater" finalizer from running.
     *
     * @param args two arguments: path to XSDHandler.class.zip and a mode
     */
    public static void main(String[] args) throws Exception {
        if (2 != args.length) {
            throw new RuntimeException("ERROR: invalid number of arguments specified: [" + args.length + "]," +
                    " expected first argument: 'path/to/XSDHandler.class.zip'," +
                    " expected second argument 'inflate', 'smallbuf' or 'noinflate'");
        }
        System.out.println("INFO: Running in mode: [" + args[1] + "]");
        byte[] comp = readCompressed(new File(args[0]));
        Inflater inf = new Inflater(true);
        inflateInternal(inf, comp, args[1]);
        // trying to end process abruptly to prevent running finalizer that will call "inflateEnd"
        System.exit(0);
    }

    /**
     * Inflates specified compressed data differently depending on a specified mode.
     * 'inflate' mode inflates in a single pass, 'smallbuf' mode inflates doing multiple passes,
     * 'noinflate' (or any other mode) is a no-op
     *
     * @param comp compressed data
     * @param mode 'inflate', 'smallbuf' or 'noinflate'
     */
    private static void inflateInternal(Inflater inf, byte[] comp, String mode) throws Exception {
        byte[] uncomp;
        if ("inflate".equals(mode)) {
            uncomp = new byte[UNCOMPRESSED_LEN];
        } else if ("smallbuf".equals(mode)) {
            uncomp = new byte[8192];
        } else {
            return;
        }
        inf.setInput(comp);
        int uncompCount = 0;
        while (uncompCount < UNCOMPRESSED_LEN) {
            int infRes = inf.inflate(uncomp, 0, uncomp.length);
            if (0 == infRes) break;
            uncompCount += infRes;
        }
        if (UNCOMPRESSED_LEN != uncompCount) {
            throw new RuntimeException("ERROR: inflate operation failed," +
                    " expected decompressed bytes: [" + UNCOMPRESSED_LEN + "]," +
                    " actual decompressed bytes: [" + uncompCount + "]");
        }
        System.out.println("INFO: inflate exited successfully");
    }

    /**
     * Reads deflated ZIP entry from the XSDHandler.class.zip file skipping ZIP
     * metadata
     *
     * @param zipFile XSDHandler.class.zip file
     * @return byte array containing deflated ZIP entry
     */
    private static byte[] readCompressed(File zipFile) throws IOException {
        byte[] data = new byte[COMPRESSED_LEN];
        InputStream is = null;
        try {
            is = new BufferedInputStream(new FileInputStream(zipFile));
            long skipped = is.skip(HEADER_LEN);
            if (HEADER_LEN != skipped) {
                throw new RuntimeException("ERROR: Error skipping header in input ZIP" +
                        " file: [" + zipFile.getAbsolutePath() + "]," +
                        " expected: [" + HEADER_LEN + "], skipped: [" + skipped + "]");
            }
            int read_res = is.read(data);
            if (data.length != read_res) {
                throw new RuntimeException("ERROR: Error reading data from input ZIP" +
                        " file: [" + zipFile.getAbsolutePath() + "]," +
                        " expected: [" + data.length + "], read: [" + read_res + "]");
            }
            return data;
        } catch (FileNotFoundException e) {
            throw new RuntimeException("ERROR: Input ZIP file not found: [" + zipFile.getAbsolutePath() + "]", e);
        } finally {
            closeQuietly(is);
        }
    }

    /**
     * Closed the closeable printing stacktrace on exception,
     * no-op on null input
     *
     * @param closeable closable instance to close
     */
    private static void closeQuietly(Closeable closeable) {
        if (null != closeable) {
            try {
                closeable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}
