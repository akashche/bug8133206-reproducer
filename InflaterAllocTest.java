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

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParserFactory;
import java.io.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @test
 * @bug 8133206
 * @requires (os.family == "linux") & (jdk.version.major >= 7)
 * @summary Detects memory allocations in zlib's "inflate" function used
 *          through java.util.zip.Inflater#inflate. When input buffer contains
 *          all the compressed data and output buffer is big enough to fit the whole
 *          decompressed data, then no allocations though "updatewindow" should be done
 *          by zlib.
 *
 *          Runs InflaterAllocWorker under memcheck (valgrind) three times:
 *           - using "smallbuf" mode to cause a guaranteed "updatewindow" leak
 *             to check that leaks parsing from memcheck's output works correctly
 *           - using "noinflate" mode to have a result without "updatewindow" leaks
 *           - using "inflate" mode that will have "updatewindow" leaks on a jdk
 *             without 8133206 patch
 *
 *          For test to success number of leaks from "noinflate" and "inflate" runs
 *          must be the same.
 *
 *         Compiled version of the http://hg.openjdk.java.net/jdk7u/jdk7u/jaxp/file/b5c74ec32065/src/com/sun/org/apache/xerces/internal/impl/xs/traversers/XSDHandler.java
 *         class is used as a test input ZIP file.
 *
 * @compile InflaterAllocWorker.java
 * @run main InflaterAllocTest
 * @author akashche@redhat.com
 */
public class InflaterAllocTest {
    private static final File MEMCHECK_SMALLBUF_OUT = new File("InflaterAllocWorker.smallbuf.memcheck.xml");
    private static final File MEMCHECK_INFLATE_OUT = new File("InflaterAllocWorker.inflate.memcheck.xml");
    private static final File MEMCHECK_NO_INFLATE_OUT = new File("InflaterAllocWorker.noinflate.memcheck.xml");

    /**
     * Intented to be run with jtreg
     *
     * @param args none
     */
    public static void main(String[] args) throws Exception {
        // run in smallbuf mode causing 'updatewindow' leak on any zlib version
        System.out.println("Starting worker in 'smallbuf' mode");
        runWorker("smallbuf", MEMCHECK_SMALLBUF_OUT);
        int smallbufLeaks = countLeaks(MEMCHECK_SMALLBUF_OUT);
        if (0 == smallbufLeaks) {
            throw new RuntimeException("Test failed," +
                    " 'smallbuf' mode leaks were not detected, check: [" + MEMCHECK_SMALLBUF_OUT + "],");
        }
        System.out.println("'smallbuf' leaks count: [" + smallbufLeaks + "]");

        // run in 'noinflate' mode without 'updatewindow' leaks
        System.out.println("Starting worker in 'noinflate' mode");
        runWorker("noinflate", MEMCHECK_NO_INFLATE_OUT);
        int noInflateLeaks = countLeaks(MEMCHECK_NO_INFLATE_OUT);
        System.out.println("'noinflate' leaks count: [" + noInflateLeaks + "]");

        // run in 'inflate' mode causing 'updatewindow' leaks only on unpatched jdk
        System.out.println("Starting worker in 'inflate' mode");
        runWorker("inflate", MEMCHECK_INFLATE_OUT);
        int inflateLeaks = countLeaks(MEMCHECK_INFLATE_OUT);
        System.out.println("'inflate' leaks count: [" + inflateLeaks + "]");

        // check 'inflate' leaks count
        if (inflateLeaks != noInflateLeaks) {
            throw new RuntimeException("Test failed," +
                    " 'noinflate' mode leaks count: [" + noInflateLeaks + "]," +
                    " 'inflate' mode leaks count: [" + inflateLeaks + "]");
        }

        System.out.println("Test passed");
    }

    /**
     * Runs process with a command:
     * {@code
     * /path/to/valgrind \
     *     --tool=memcheck \
     *     --leak-check=yes \
     *     --show-reachable=yes \
     *     --xml=yes \
     *     --xml-file=[out] \
     *     /path/to/java \
     *     -cp [test.classes] \
     *     InflaterAllocWorker \
     *     /path/to/XSDHandler.class.zip \
     *     [mode]
     * }
     *
     * @param mode 'inflate', 'smallbuf' or 'noinflate':
     *             'inflate' mode inflates in a single pass,
     *             'smallbuf' mode inflates doing multiple passes,
     *             'noinflate' (or any other mode) is a no-op
     * @param out memcheck's output XML file
     */
    private static void runWorker(String mode, File out) throws Exception {
        File java = findJava();
        File classpath = new File(System.getProperty("test.classes"));
        File inputFile = new File(System.getProperty("test.src"), "XSDHandler.class.zip");
        File workerOutFile = new File("InflaterAllocWorker." + mode + ".out");
        File valgrind = findValgrind();
        int inflateCode = new ProcessBuilder(valgrind.getAbsolutePath(),
                // valgrind options
                "--tool=memcheck", "--leak-check=yes", "--show-reachable=yes",
                "--xml=yes", "--xml-file=" + out.getAbsolutePath(),
                // java executable
                java.getAbsolutePath(),
                // java options
                "-cp", classpath.getAbsolutePath(), "InflaterAllocWorker",
                // worker process options
                inputFile.getAbsolutePath(), mode)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(workerOutFile))
                .start()
                .waitFor();
        if (0 != inflateCode) {
            throw new RuntimeException("Test error: [" + mode + "] subprocess returned code: [" + inflateCode + "]");
        }
    }

    /**
     * count a number of 'updatewindow' leaks in a specified XML file
     *
     * @param file XML file with memcheck's output
     * @return number of 'updatewindow' leaks found
     */
    private static int countLeaks(File file) throws Exception{
        XMLReader xmlReader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        UpdatewindowLeaskCountHandler ha = new UpdatewindowLeaskCountHandler();
        xmlReader.setContentHandler(ha);
        InputStream is = null;
        try {
            is = new FileInputStream(file);
            Reader reader = new BufferedReader(new InputStreamReader(is, UTF_8));
            xmlReader.parse(new InputSource(reader));
        } finally {
            closeQuietly(is);
        }
        return ha.getLeaksCount();
    }

    /**
     * Returns a path to the valgrind executable
     *
     * @return path to the valgrind executable
     */
    private static File findValgrind() {
        File valgrindSystem = new File("/usr/bin/valgrind");
        if (valgrindSystem.exists() && valgrindSystem.isFile()) {
            return valgrindSystem;
        }
        File valgrindLocal = new File("/usr/local/bin/valgrind");
        if (valgrindLocal.exists() && valgrindLocal.isFile()) {
            return valgrindLocal;
        }
        throw new RuntimeException("Cannot find valgrind executable, tried paths:" +
                " [" + valgrindSystem.getAbsolutePath() +"] and: [" + valgrindLocal + "]");
    }

    /**
     * Returns a path to the java executable using jtreg's "test.jdk" property
     *
     * @return path to the java executable
     */
    private static File findJava() {
        File javaHome = new File(System.getProperty("test.jdk"));
        if (!(javaHome.exists() && javaHome.isDirectory())) {
            throw new RuntimeException("Invalid test.java property: [" + javaHome.getAbsolutePath() +"]");
        }
        File javaUnix = new File(javaHome, "bin" + File.separator + "java");
        if (javaUnix.exists() && javaUnix.isFile()) {
            return javaUnix;
        }
        File javaWindows = new File(javaHome, "bin" + File.separator + "java.exe");
        if (javaWindows.exists() && javaWindows.isFile()) {
            return javaWindows;
        }
        throw new RuntimeException("Cannot find java executable, tried paths:" +
                " [" + javaUnix.getAbsolutePath() +"] and: [" + javaWindows + "]");
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

    /**
     * SAX handler that count a number of 'updatewindow' leaks
     */
    private static class UpdatewindowLeaskCountHandler extends DefaultHandler {
        private enum ElState {EL_START, EL_STACK, EL_FN}

        private enum ChState {CH_START, CH_MALLOC, CH_UDATEWINDOW, CH_INFLATE}

        private ElState elState = ElState.EL_START;
        private ChState chState = ChState.CH_START;
        private int leaksCount = 0;

        /**
         * Detects <fn> elements and sets the element state to FN to
         * enable #characters method
         *
         * @param uri The Namespace URI, or the empty string if the
         *        element has no Namespace URI or if Namespace
         *        processing is not being performed.
         * @param localName The local name (without prefix), or the
         *        empty string if Namespace processing is not being
         *        performed.
         * @param qName The qualified name (with prefix), or the
         *        empty string if qualified names are not available.
         * @param attributes The attributes attached to the element.  If
         *        there are no attributes, it shall be an empty
         *        Attributes object.
         * @exception org.xml.sax.SAXException Any SAX exception, possibly
         *            wrapping another exception.
         * @see org.xml.sax.ContentHandler#startElement
         */
        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            switch (elState) {
                case EL_START:
                    if ("stack".equals(qName)) {
                        elState = ElState.EL_STACK;
                    }
                    break;
                case EL_STACK:
                    if ("fn".equals(qName)) {
                        elState = ElState.EL_FN;
                    }
            }
        }

        /**
         * Increments the leak counter if four consecutive <fn> entries in a <stack> element:
         * malloc <- updatewindow <- inflate <- Java_java_util_zip_Inflater_inflateBytes
         *
         * @param ch The characters.
         * @param start The start position in the character array.
         * @param length The number of characters to use from the
         *               character array.
         * @exception org.xml.sax.SAXException Any SAX exception, possibly
         *            wrapping another exception.
         * @see org.xml.sax.ContentHandler#characters
         */
        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (elState != ElState.EL_FN) {
                return;
            }
            String st = new String(ch, start, length);
            switch (chState) {
                case CH_START:
                    if ("malloc".equals(st)) {
                        chState = ChState.CH_MALLOC;
                    }
                    break;
                case CH_MALLOC:
                    if ("updatewindow".equals(st)) {
                        chState = ChState.CH_UDATEWINDOW;
                    } else {
                        chState = ChState.CH_START;
                    }
                    break;
                case CH_UDATEWINDOW:
                    chState = "inflate".equals(st) ? ChState.CH_INFLATE : ChState.CH_START;
                    break;
                case CH_INFLATE:
                    leaksCount += "Java_java_util_zip_Inflater_inflateBytes".equals(st) ? 1 : 0;
                    chState = ChState.CH_START;
                    break;
            }
            elState = ElState.EL_STACK;
        }

        /**
         * Switches the element state back to START when going out of "stack" element
         *
         * @param uri The Namespace URI, or the empty string if the
         *        element has no Namespace URI or if Namespace
         *        processing is not being performed.
         * @param localName The local name (without prefix), or the
         *        empty string if Namespace processing is not being
         *        performed.
         * @param qName The qualified name (with prefix), or the
         *        empty string if qualified names are not available.
         * @exception org.xml.sax.SAXException Any SAX exception, possibly
         *            wrapping another exception.
         * @see org.xml.sax.ContentHandler#endElement
         */
        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (elState == ElState.EL_STACK && "stack".equals(qName))
                elState = ElState.EL_START;
        }

        /**
         * Returns number of 'updatewindow' leaks detected
         *
         * @return number of 'updatewindow' leaks detected
         */
        public int getLeaksCount() {
            return leaksCount;
        }
    }

}
