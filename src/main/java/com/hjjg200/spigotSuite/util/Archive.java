package com.hjjg200.spigotSuite.util;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.function.Predicate;
import java.security.MessageDigest;
import java.security.DigestInputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

public final class Archive {

    private long _count = 0;
    private final DigestInputStream sha1;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(new BufferedOutputStream(out)));
    public Archive(final File file, Predicate<File> filter) throws IOException {
        if(filter == null) filter = i -> true;
        put(file, filter);
        tar.close();
        sha1 = new DigestInputStream(new BufferedInputStream(new ByteArrayInputStream(out.toByteArray())), DigestUtils.getSha1Digest());
    }
    public final InputStream inputStream() {
        return sha1;
    }
    public final byte[] digest() {
        if(sha1.available() > 0) throw IOException("InputStream must be read entirely before digest evaluation");
        return sha1.getMessageDigest().digest();
    }
    public final long count() {
        return _count;
    }

    private final void put(final File file, final Predicate<File> filter) throws IOException {
        if(file.isDirectory()) {
            for(final File child : file.listFiles()) put(child, filter);
        } else {
            if(!filter.test(file)) return;
            tar.putArchiveEntry(new TarArchiveEntry(file));
            Files.copy(file.toPath(), tar);
            tar.closeArchiveEntry();
            _count++;
        }
    }

}
