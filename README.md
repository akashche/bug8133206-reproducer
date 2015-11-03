jtreg test for the JDK-8133206 bug
==================================

This project contains a Linux-only reproducer for the OpenJDK bug [8133206](https://bugs.openjdk.java.net/browse/JDK-8133206).

Reproducer tries to detect memory allocations in zlib's `inflate` function used
through [java.util.zip.Inflater#inflate](https://docs.oracle.com/javase/7/docs/api/java/util/zip/Inflater.html#inflate%28byte[],%20int,%20int%29). 
When input buffer contains all the compressed data and output buffer is big enough to fit the whole
decompressed data, then no allocations though [updatewindow](https://github.com/madler/zlib/blob/v1.2.8/inflate.c#L1234) should be done
by zlib.

It runs `InflaterAllocWorker` under [memcheck](http://valgrind.org/docs/manual/mc-manual.html) (valgrind) three times:

    - using `smallbuf` mode to cause a guaranteed `updatewindow` leak to check that leaks parsing from memcheck's output works correctly
    - using `noinflate` mode to have a result without "updatewindow" leaks
    - using `inflate` mode that will have `updatewindow` leaks on a jdk without [8133206 patch](http://cr.openjdk.java.net/~nikgor/8133206/jdk7u-dev/webrev.01/)

For test to success number of leaks from `noinflate` and `inflate` runs must be the same.

Compiled version of the [XSDHandler](http://hg.openjdk.java.net/jdk7u/jdk7u/jaxp/file/b5c74ec32065/src/com/sun/org/apache/xerces/internal/impl/xs/traversers/XSDHandler.java) class is used as a test input ZIP file.

See [inflate_flags_test](https://github.com/akashche/inflate_flags_test) project for more details
about zlib's `inflate` options in different zlib versions.

How to run
----------

To run this test `valrind` and `zlib-debuginfo` packages must be installed.

Running using jtreg:

    java -jar path/to/jtreg.jar -jdk:path/to/jdk InflaterAllocTest.java

License information
-------------------

This project is released under the [GNU General Public License, version 2](https://www.gnu.org/licenses/old-licenses/gpl-2.0.en.html).

Changelog
---------

**2015-11-03**

 * initial public version
