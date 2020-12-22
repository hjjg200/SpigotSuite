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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.Iterator;
import java.security.MessageDigest;
import java.security.DigestOutputStream;
import java.util.NoSuchElementException;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;

import org.apache.commons.codec.binary.Hex;

public final class Archive extends File {

    private final static String UTF_8 = "UTF-8";
    private final static String TAR = ".tar";
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

    public final static class Entry extends TarArchiveEntry {
        private File file = null;
        private InputStream in = null;
        public Entry(final File file, final String name) {
            super(file, name);
            this.file = file;
        }
        public Entry(final InputStream in, final String name) {
            super(name);
            this.in = in;
        }
        public Entry(final TarArchiveEntry tarEntry, final TarArchiveInputStream in) {
            super(tarEntry.getName());
            this.in = in;
        }
        public final String md5() {
            try {
                InputStream stream;
                if(file != null) {
                    stream = Files.newInputStream(file.toPath());
                } else {
                    in.reset();
                    stream = in;
                }
                return DigestUtils.md5Hex(new BufferedInputStream(stream));
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            return "";
        }
    }

    public final void forEach(Consumer<Entry> consumer) {
        try {
            final TarArchiveInputStream is = new TarArchiveInputStream(new BufferedInputStream(new FileInputStream(this)));
            while(true) {
                final TarArchiveEntry entry = is.getNextTarEntry();
                if(entry == null) break;
                // Ensure parent directories
                consumer.accept(new Entry(entry, is));
                // Each entry
                if(entry.isDirectory()) continue;
            }
            is.close();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public final synchronized void unpack() throws Exception {
        _unpack(null);
    }
    public final synchronized void unpack(final File parent) throws Exception {
        _unpack(parent);
    }
    private final synchronized void _unpack(File parent) throws Exception {
        // Default values
        if(parent == null) parent = this.getParentFile();
        // Start
        final TarArchiveInputStream is = new TarArchiveInputStream(new BufferedInputStream(new FileInputStream(this)));
        while(true) {
            final TarArchiveEntry entry = is.getNextTarEntry();
            if(entry == null) break;
            // Ensure parent directories
            final File file = new File(parent, entry.getName());
            file.getParentFile().mkdirs();
            // Each entry
            if(file.isDirectory()) continue;
            Files.copy(is, file.toPath());
        }
        is.close();
    }

    public static final Archive pack(final File[] sources, final File dest) throws Exception {
        return _pack(sources, dest, null);
    }
    public static final Archive pack(final File[] sources, final File dest, final Predicate<Entry> filter) throws Exception {
        return _pack(sources, dest, filter);
    }
    private static final Archive _pack(final File[] sources, File dest, Predicate<Entry> filter) throws Exception {
        // Default
        if(filter == null) filter = i -> true;
        // Archive created at path
        final Archive archive = new Archive(dest.getPath());
        final DigestOutputStream sha1 = new DigestOutputStream(Files.newOutputStream(archive.toPath()), DigestUtils.getSha1Digest());
        final TarArchiveOutputStream tar = new TarArchiveOutputStream(new BufferedOutputStream(sha1));
        tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
        tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
        // Archive
        for(final File source : sources) {
            File parentFile = source.getParentFile();
            final Path parent = parentFile == null
                ? Paths.get("")
                : parentFile.toPath();
            // Walk
            final Iterator<Entry> it = Files.walk(source.toPath())
                .map(path -> new Entry(path.toFile(), parent.relativize(path).toString()))
                .filter(filter)
                .iterator();
            while(true) {
                try {
                    final TarArchiveEntry entry = it.next();
                    tar.putArchiveEntry(entry);
                    Files.copy(entry.getFile().toPath(), tar);
                    tar.closeArchiveEntry();
                } catch(NoSuchElementException ex) {
                    break;
                } catch(Exception ex) {
                    throw ex;
                }
            }
        }
        tar.close();
        // Write sha1 file
        final File sha1File = new File(archive.getPath() + SHA1);
        Files.copy(new ByteArrayInputStream(new Hex(UTF_8).encode(sha1.getMessageDigest().digest())), sha1File.toPath());
        //
        return archive;
    }

}

