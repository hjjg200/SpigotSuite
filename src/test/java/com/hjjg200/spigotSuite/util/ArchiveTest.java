package com.hjjg200.spigotSuite.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.commons.codec.binary.Hex;

public final class ArchiveTest {

    private final static String TAR_GZ = ".tar.gz";
    private final static String CHARSET = "UTF-8";

    @Test
    public final void testArchiving() throws Exception {
        final String path = System.getenv("TEST_ARCHIVE_PATH");
        Assumptions.assumeFalse(path == null || "".equals(path));
        // Create tar.gz
        final Archive archive = Archive.compress(new File(path));
    }

    @Test
    public final void testDecompress() throws Exception {
        final String path = System.getenv("TEST_DECOMPRESS_PATH");
        Assumptions.assumeFalse(path == null || "".equals(path));
        // Decompress
        new Archive(path).decompress();
    }

}
