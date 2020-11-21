package com.hjjg200.spigotSuite.util;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import org.apache.commons.codec.binary.Hex;

public final class ArchiveTest {

    private final static String CHARSET = "UTF-8";

    @Test
    public final void testArchiving() throws Exception {
        // Get path
        final String path = System.getenv("TEST_ARCHIVE_PATH");
        if(path == null || path.equals("")) throw new IOException("No path supplied");
        // Create tar.gz
        final Archive archive = new Archive(new File(path), null);
        final File targz = new File(path + ".tar.gz"); // no createnNewFile, copy creates new
        Files.copy(archive.inputStream(), targz.toPath());
        // Print checksum
        final byte[] hex = (new Hex(CHARSET)).encode(archive.digest());
        System.out.println(new String(hex, CHARSET));
    }

}
