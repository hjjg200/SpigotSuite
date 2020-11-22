package com.hjjg200.spigotSuite.util;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;;
import java.util.function.Predicate;
import java.security.MessageDigest;
import java.security.DigestOutputStream;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

import org.apache.commons.codec.binary.Hex;

public final class Archive extends File {

    private final static String UTF_8 = "UTF-8";
    private final static String TAR_GZ = ".tar.gz";
    private final static String SHA1 = ".sha1";

    public Archive(final File parent, final String child) {
        super(parent, child);
    }
    public Archive(final String pathname) {
        super(pathname);
    }
    public Archive(final String parent, final String child) {
        super(parent, child);
    }
    // public File(final URI)

    private volatile boolean decompressed = false;
    private volatile long _count;
    public synchronized long count() {
        return _count;
    }
    public final synchronized void decompress() throws Exception {
        _decompress(null);
    }
    public final synchronized void decompress(final File parent) throws Exception {
        _decompress(parent);
    }
    private final synchronized void _decompress(File parent) throws Exception {
        // Default values
        if(parent == null) parent = this.getParentFile();
        // Start
        _count = 0;
        final TarArchiveInputStream is = new TarArchiveInputStream(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(this))));
        while(true) {
            final TarArchiveEntry entry = is.getNextTarEntry();
            if(entry == null) break;
            // Ensure parent directories
            final File file = new File(parent, entry.getName());
            file.getParentFile().mkdirs();
            // Each entry
            if(file.isDirectory()) continue;
            _count++;
            Files.copy(is, file.toPath());
        }
        is.close();
    }

    public static final Archive compress(final File source) throws Exception {
        return _compress(source, null, null);
    }
    public static final Archive compress(final File source, final Predicate<File> filter) throws Exception {
        return _compress(source, null, filter);
    }
    public static final Archive compress(final File source, final File dest) throws Exception {
        return _compress(source, dest, null);
    }
    public static final Archive compress(final File source, final File dest, final Predicate<File> filter) throws Exception {
        return _compress(source, dest, filter);
    }
    private static final Archive _compress(final File source, File dest, Predicate<File> filter) throws Exception {
        // Default
        if(dest == null) dest = new File(source.getPath() + TAR_GZ);
        if(filter == null) filter = i -> true;
        // Archive created at path
        final Archive archive = new Archive(dest.getPath());
        final DigestOutputStream sha1 = new DigestOutputStream(new FileOutputStream(archive), DigestUtils.getSha1Digest());
        final TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(new BufferedOutputStream(sha1)));
        sha1.on(true);
        // Archive
        archive._count = 0;
        final Predicate<File> ff = filter;
        final Path sourceParent = source.getParentFile().toPath();
        Files.walk(source.toPath())
            .forEach(each -> {
                final Path rel = sourceParent.relativize(each);
                final File file = each.toFile();
                if(file.isDirectory()) return;
                if(!ff.test(rel.toFile())) return;
                try {
                    tar.putArchiveEntry(new TarArchiveEntry(file, rel.toString()));
                    Files.copy(each, tar);
                    tar.closeArchiveEntry();
                } catch(Exception ex) {
                    ex.printStackTrace();
                }
                archive._count++;
            });
        tar.close();
        // Write sha1 file
        final File sha1File = new File(archive.getPath() + SHA1);
        Files.copy(new ByteArrayInputStream(new Hex(UTF_8).encode(sha1.getMessageDigest().digest())), sha1File.toPath());
        //
        return archive;
    }

}

