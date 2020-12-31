package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;
import java.util.Timer;
import java.util.logging.Level;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.function.BiConsumer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.util.stream.Stream;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.ChatColor;

import com.hjjg200.spigotSuite.util.Archive;
import com.hjjg200.spigotSuite.util.Resource;

public final class Backup implements Module, Listener {

    private final static String NAME = Backup.class.getSimpleName();
    private final static long TICK_MINUTE = 20L * 60L;
    private final static String INDEX = "_index";
    private final static String SIZE = "size";
    private final static String VERSIONS = "versions";
    private final static String VERSIONS_INFO = "versionsInfo";
    private final static String PLUGINS = "plugins";
    private final static String RESOURCES = "resources";
    private final static String TAR = ".tar";
    private final static String SHA1 = ".sha1";
    private final static String YML = ".yml";
    private final static Semaphore MUTEX = new Semaphore(1);
    private final SpigotSuite ss;
    private final BukkitScheduler scheduler;
    private File lock;
    private Semaphore mutex = new Semaphore(1);
    private CompletableFuture<Void> future;
    private boolean enabled;
    private int taskId = -1;
    private long interval;
    private int cycleLength;
    private int maxCycles;
    private File[] resources;
    private File tempDir;
    private File cacheDir;
    private String s3Bucket; // = "my-s3-bucket"
    private Region s3Region; // = "ap-northeast-2"
    private String s3Prefix; // = "/backup/minecraft/"

    public final static class Version {

        public static enum Type {
            FULL,
            INCREMENTAL
        }

        private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
        private final static String SEPARATOR = "-";
        private final LocalDateTime _time;
        private final Type _type;

        public Version(final String string) {
            final String[] split = string.split(SEPARATOR);
            if(split.length < 2) {
                throw new IllegalArgumentException("Malformed version string");
            }
            _time = LocalDateTime.from(FORMATTER.parse(split[0]));
            _type = Type.valueOf(Type.class, split[1]);
        }

        public Version(final LocalDateTime time, final Type type) {
            _time = time;
            _type = type;
        }

        public final Type type() {
            return _type;
        }

        public final LocalDateTime time() {
            return _time;
        }

        public final String toString() {
            return _time.format(FORMATTER) + SEPARATOR + _type.name();
        }

        public final boolean equals(final Version rhs) {
            return this._time.equals(rhs._time) && this._type.equals(rhs._type);
        }

    }

    // S3-related
    private final S3Client createS3Client() {
        return S3Client.builder()
                       .credentialsProvider(SystemPropertyCredentialsProvider.create())
                       .region(s3Region)
                       .build();
    }

    private final static String contentTypeOf(final String suffix) {
        final String[] split = suffix.split("\\.");
        if(split.length < 2) return "";
        switch(split[split.length - 1]) {
        case "yml": return "application/x-yaml";
        case "gz": return "application/gzip";
        case "tar": return "application/x-tar";
        case "sha1": return "text/plain";
        }
        return "";
    }

    private final void headBucket(final S3Client client) throws Exception {
        client.headBucket(HeadBucketRequest.builder().bucket(s3Bucket).build());
    }

    private final void getObject(final S3Client client, final String suffix, final Path path) throws Exception {
        client.getObject(GetObjectRequest.builder()
                                         .bucket(s3Bucket)
                                         .key(s3Prefix + suffix)
                                         .build(),
                         path);

    }

    private final void putObject(final S3Client client, final String suffix, final Path path) throws Exception {
        client.putObject(PutObjectRequest.builder()
                                         .bucket(s3Bucket)
                                         .key(s3Prefix + suffix)
                                         .build(),
                         path);
    }

    private final void deleteObjects(final S3Client client, final Collection<String> suffixes) throws Exception {

        final List<ObjectIdentifier> identifiers = new ArrayList<ObjectIdentifier>();
        for(final String suffix : suffixes) {
            identifiers.add(ObjectIdentifier.builder().key(s3Prefix + suffix).build());
        }
        final Delete delete = Delete.builder().objects(identifiers).build();
        client.deleteObjects(DeleteObjectsRequest.builder()
                                                 .bucket(s3Bucket)
                                                 .delete(delete)
                                                 .build());

    }

    private final void multipartUpload(final S3Client client, final String suffix, final Path path) throws Exception {

        final long contentSize = Files.size(path);
        long partSize = (long)Math.max(
                5 * 1024 * 1024,
                Math.ceil((double)contentSize / 10000.0d)); // Max upload parts

        final String uploadId = client.createMultipartUpload(CreateMultipartUploadRequest.builder()
                                                                                         .bucket(s3Bucket)
                                                                                         .key(s3Prefix + suffix)
                                                                                         .contentType(contentTypeOf(suffix))
                                                                                         .build()).uploadId();
        final List<CompletedPart> completedParts = new ArrayList<CompletedPart>();
        final InputStream in = Files.newInputStream(path);

        long position = 0;
        for(int i = 1; position < contentSize; i++) {
            partSize = Math.min(partSize, (contentSize - position));
            final UploadPartRequest partRequest = UploadPartRequest.builder()
                                                                   .bucket(s3Bucket)
                                                                   .contentLength(partSize)
                                                                   .key(s3Prefix + suffix)
                                                                   .partNumber(i)
                                                                   .uploadId(uploadId)
                                                                   .build();
            final UploadPartResponse partResponse = client.uploadPart(partRequest,
                                                                      RequestBody.fromInputStream(in, partSize));
            completedParts.add(CompletedPart.builder()
                                            .partNumber(i)
                                            .eTag(partResponse.eTag())
                                            .build());
            position += partSize;
        }
        in.close();

        final CompletedMultipartUpload completedMultipart = CompletedMultipartUpload.builder()
                                                                                    .parts(completedParts)
                                                                                    .build();
        final CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                                                                                             .bucket(s3Bucket)
                                                                                             .key(s3Prefix + suffix)
                                                                                             .uploadId(uploadId)
                                                                                             .multipartUpload(completedMultipart)
                                                                                             .build();
        client.completeMultipartUpload(completeRequest);

    }

    // Commands
    private final class CommandBackupInfo implements CommandExecutor {
        @Override
        public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {

            final String type;
            if(args.length > 0) {
                switch(args[0]) {
                case "all":
                case "size":
                    break;
                default:
                    return false;
                }
                type = args[0];
            } else {
                type = "size";
            }

            // Overall info
            sender.sendMessage(ChatColor.DARK_AQUA + "Backup Summary" + ChatColor.RESET);
            if(enabled) {
                sender.sendMessage(String.format("interval: %d minutes", interval));
                sender.sendMessage(String.format("cycleLength: %d", cycleLength));
                sender.sendMessage(String.format("maxCycles: %d", maxCycles));
            } else {
                sender.sendMessage(ChatColor.YELLOW + "Backup is currently disabled!" + ChatColor.RESET);
            }

            // Specific info
            for(final File dir : cacheDir.listFiles()) {
                if(!dir.isDirectory()) continue;

                sender.sendMessage("");

                final YamlConfiguration index = YamlConfiguration.loadConfiguration(new File(dir, INDEX + YML));
                final String name = dir.getName();
                sender.sendMessage(ChatColor.GREEN + "[" + name + "]" + ChatColor.RESET);
                // Print summary
                final List<String> versionStrings = index.getStringList(VERSIONS);
                final ConfigurationSection versionsInfo = index.getConfigurationSection(VERSIONS_INFO);
                // * Size
                double size = 0.0d;
                for(final String vstr : versionsInfo.getKeys(false)) {
                    final ConfigurationSection info = versionsInfo.getConfigurationSection(vstr);
                    size += info.getDouble(SIZE);
                }
                size /= 1e+3;
                sender.sendMessage("Size: " + String.format("%.2f GB", size));
                // * Latest backup
                sender.sendMessage("Latest backup: "
                                   + ChatColor.AQUA
                                   + versionStrings.get(versionStrings.size() - 1)
                                   + ChatColor.RESET);
                // * Version list
                if("all".equals(type)) {
                    sender.sendMessage(String.format("(%d versions)", versionStrings.size()));
                    for(final String vstr : versionStrings) {
                        sender.sendMessage(ChatColor.GRAY + "- " + vstr + ChatColor.RESET);
                    }
                }
            }

            return true;
        }
    }

    // File only checksum for tar archives
    private final class CommandBackupChecksum implements CommandExecutor {
        @Override
        public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {

            if(args.length == 0) return false;

            final Archive archive = new Archive(args[0]);
            if(!archive.exists()) {
                sender.sendMessage(ChatColor.YELLOW + "The specified file does not exist" + ChatColor.RESET);
                return true;
            }

            final List<String> sorted = new ArrayList<String>();
            final Map<String, String> checksums = new HashMap<String, String>();
            archive.forEach(entry -> {
                if(entry.isFile() == false) return;
                final String name = entry.getName();
                sorted.add(name);
                checksums.put(name, entry.md5());
            });
            sorted.sort(null);

            // Digest
            final DigestOutputStream digest = new DigestOutputStream(
                new ByteArrayOutputStream(), DigestUtils.getSha1Digest());
            try {
                for(int i = 0; i < sorted.size(); i++) {
                    digest.write(checksums.get(sorted.get(i)).getBytes(StandardCharsets.UTF_8));
                }
            } catch(Exception ex) {
                ex.printStackTrace();
                sender.sendMessage(ChatColor.YELLOW + "Failed evaluation" + ChatColor.RESET);
                return false;
            }

            // Result
            final String checksum = new String(new Hex(StandardCharsets.UTF_8).encode(digest.getMessageDigest().digest()), StandardCharsets.UTF_8);

            sender.sendMessage(ChatColor.GREEN + args[0] + ChatColor.RESET);
            sender.sendMessage(checksum);
            sender.sendMessage(ChatColor.GRAY + "* This is the checksum of concatenation of hex representations of each entry's md5 checksum. Entries are sorted by its name");

            // Compare with current server's file
            Path parent = Paths.get("./"); // default parent
            Stream<Path> stream = null;
            if(args.length >= 2) {
Switch:
                switch(args[1]) {
                case "plugins":
                    try {
                        stream = Files.walk(Paths.get(PLUGINS));
                    } catch(Exception ex) {
                        ex.printStackTrace();
                        sender.sendMessage(ChatColor.YELLOW + "Exception occurred" + ChatColor.RESET);
                        return true;
                    }
                    break;
                case "resources":
                    final Stream.Builder<Path> builder = Stream.builder();
                    for(final File resource : resources) {
                        builder.accept(resource.toPath());
                    }
                    stream = builder.build();
                    break;
                case "world":
                    if(args.length < 3) {
                        sender.sendMessage(ChatColor.YELLOW + "World name must be supplied" + ChatColor.RESET);
                        return false;
                    }
                    final String worldName = args[2];
                    for(final World world : ss.getServer().getWorlds()) {
                        if(world.getName().equals(worldName)) {
                            try {
                                final File dir = world.getWorldFolder();
                                parent = dir.getParentFile().toPath();
                                stream = Files.walk(dir.toPath());
                            } catch(Exception ex) {
                                ex.printStackTrace();
                                sender.sendMessage(ChatColor.YELLOW + "Exception occurred" + ChatColor.RESET);
                                return true;
                            }
                            break Switch;
                        }
                    }
                    // World not found
                    sender.sendMessage(ChatColor.YELLOW + "World of the supplied name was not found" + ChatColor.RESET);
                    return false;
                default:
                    sender.sendMessage(ChatColor.YELLOW + "Wrong backup type is supplied" + ChatColor.RESET);
                    return false;
                }

                // Iterate
                int entireCount = 0;
                long entireSize = 0L;
                final List<String> dissimilarFiles = new ArrayList<String>();
                int similarCount = 0;
                long similarSize = 0L;
                final List<String> notFoundFiles = new ArrayList<String>();
                final Set<String> surplusFiles = new HashSet<String>(checksums.keySet());
                final Iterator<Path> iterator = stream
                    .filter(each -> Files.isRegularFile(each))
                    .iterator();
                Iterable<Path> iterable = () -> iterator;
                try {
                    for(final Path path : iterable) {
                        final long size = Files.size(path);

                        entireCount++;
                        entireSize += size;

                        final String key = parent.relativize(path).toString();
                        final String md5_1 = checksums.get(key);
                        if(md5_1 == null) {
                            notFoundFiles.add(key);
                            continue;
                        }

                        surplusFiles.remove(key);

                        final String md5_0 = DigestUtils.md5Hex(Files.newInputStream(path));
                        if(md5_0.equals(md5_1)) {
                            similarCount++;
                            similarSize += size;
                        } else {
                            dissimilarFiles.add(key);
                        }
                    }
                } catch(Exception ex) {
                    ex.printStackTrace();
                    sender.sendMessage(ChatColor.YELLOW + "Exception occurred" + ChatColor.RESET);
                    return true;
                }

                // Print
                sender.sendMessage("");
                sender.sendMessage(ChatColor.AQUA + "[Likeness]" + ChatColor.RESET);
                final boolean exact = similarCount == entireCount
                                      && notFoundFiles.size() == 0
                                      && surplusFiles.size() == 0;
                final ChatColor color = exact ? ChatColor.GREEN : ChatColor.YELLOW;
                sender.sendMessage(color + (exact
                                   ? "The supplied tar archive is entirely identical to current files"
                                   : "The supplied tar archive is not identical"));
                sender.sendMessage(color + String.format("%d", similarCount)
                                   + ChatColor.GRAY + String.format("/%d", entireCount)
                                   + ChatColor.RESET + " files"
                                   + ChatColor.GRAY + " ("
                                   + color + String.format("%.2f", (double)similarSize / (double)entireSize * 100.0d)
                                   + ChatColor.RESET + "%"
                                   + ChatColor.GRAY + ")"
                                   + ChatColor.RESET);

                if(!exact) {
                    final BiConsumer<String, Collection<String>> consumer = (expr, collection) -> {
                        if(collection.size() == 0) return;
                        sender.sendMessage("");
                        sender.sendMessage(color + String.format("(%d %s)", collection.size(), expr) + ChatColor.RESET);
                        sender.sendMessage(ChatColor.GRAY
                                           + String.join(ChatColor.RESET + "\n" + ChatColor.GRAY, collection)
                                           + ChatColor.RESET);
                    };
                    consumer.accept("dissimilar files", dissimilarFiles);
                    consumer.accept("files not found", notFoundFiles);
                    consumer.accept("surplus files", surplusFiles);
                }

            }

            return true;

        }
    }

    private final class CommandBackupOverride implements CommandExecutor {
        @Override
        public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
            schedule(0, true);
            return true;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        schedule(interval * TICK_MINUTE, false);
    }

    // TODO: test doing scheduling backup when being disabled

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        // Check for file lock
        if(lock.exists()) {
            ss.getLogger().log(Level.SEVERE, "Backup file lock is found, doing an immediate backup");
            lock.delete();
            schedule(0, false);
        }
    }

    // This class is run synchronously
    private final class BackupTask implements Runnable {
        private final boolean forceFull;
        public BackupTask(final boolean forceFull) {
            this.forceFull = forceFull;
        }
        @Override
        public void run() {
            try {
                mutex.acquire();
                ss.getLogger().info("Starting to backup...");
                taskId = -1;
                lock.createNewFile();
                future = new CompletableFuture<>();
                mutex.release();
                // Save and start
                final Server server = ss.getServer();
                final CommandSender cs = server.getConsoleSender();
                server.dispatchCommand(cs, "save-all");
                server.dispatchCommand(cs, "save-off");
                CompletableFuture.runAsync(new AsyncTask(forceFull));
            } catch(Exception ex) {
                ss.getLogger().log(Level.SEVERE, "Failed to initiate the backup procedure!");
                ex.printStackTrace();
            }
        }
    }

    private final class AsyncTask implements Runnable {

        private final boolean forceFull;
        public AsyncTask(final boolean forceFull) {
            this.forceFull = forceFull;
        }

        private final LocalDateTime time = LocalDateTime.now();
        private final void folder(final File[] sources, final String name, final int folderCycleLength) throws Exception {

            final UnaryOperator<String> format = base -> name + "/" + base;

            // Create temp directory
            final File folderTempDir = new File(tempDir, name);
            folderTempDir.mkdirs();

            // S3 Client
            final S3Client s3Client = createS3Client();

            // Get index file
            final String indexName = INDEX + YML;
            final File indexFile = new File(folderTempDir, indexName);
            YamlConfiguration index = new YamlConfiguration();
            try {
                getObject(s3Client, format.apply(indexName), indexFile.toPath());
                index = YamlConfiguration.loadConfiguration(indexFile);
            } catch(NoSuchKeyException ex) {
                // Create new index file
                index.createSection(VERSIONS);
                index.createSection(VERSIONS_INFO);
            } catch(Exception ex) {
                throw ex;
            }
            // * Versions
            List<String> versionStrings = index.getStringList(VERSIONS);
            final ConfigurationSection versionsInfo = index.getConfigurationSection(VERSIONS_INFO);

            // Determine the version for this backup
            final AtomicBoolean isFull = new AtomicBoolean(true);
            if(forceFull == false) {
                for(int i = versionStrings.size() - 1; i >= 0; i--) {
                    if(folderCycleLength > 0 && versionStrings.size() - i >= folderCycleLength) {
                        break;
                    } else if(new Version(versionStrings.get(i)).type().equals(Version.Type.FULL)) {
                        isFull.set(false);
                        break;
                    }
                }
            }
            final Version version = new Version(time, isFull.get() ? Version.Type.FULL : Version.Type.INCREMENTAL);
            final YamlConfiguration md5s = new YamlConfiguration();
            final YamlConfiguration headMd5s = new YamlConfiguration();

            // Head version
            if(versionStrings.size() > 0) {
                final Version headVersion = new Version(versionStrings.get(versionStrings.size() - 1));
                final File headMd5sFile = new File(folderTempDir, headVersion.toString() + YML);
                getObject(s3Client, format.apply(headVersion.toString() + YML), headMd5sFile.toPath());
                headMd5s.load(headMd5sFile);
            }

            // Archive accordingly
            final AtomicInteger count = new AtomicInteger(0);
            final Archive archive = Archive.pack(
                sources,
                new File(folderTempDir, version + TAR),
                entry -> {
                    if(entry.isFile() == false) return false;
                    final String key = DigestUtils.md5Hex(entry.getName());
                    final String md5 = entry.md5();
                    md5s.set(key, md5);
                    final boolean result = isFull.get() || !md5.equals(headMd5s.getString(key));
                    if(result) {
                        count.addAndGet(1);
                    }
                    return result;
                });

            if(count.get() > 0) {
                final String vstr = version.toString();
                // Put files
                // * Tar, checksum, index
                final File md5sFile = new File(folderTempDir, version.toString() + YML);
                md5s.save(md5sFile);
                multipartUpload(s3Client, format.apply(vstr + TAR), archive.toPath());
                putObject(s3Client, format.apply(vstr + TAR + SHA1), Paths.get(archive.getPath() + SHA1));
                putObject(s3Client, format.apply(vstr + YML), md5sFile.toPath());
                // * Add version
                versionStrings.add(vstr);
                versionsInfo.createSection(vstr);
                final ConfigurationSection info = versionsInfo.getConfigurationSection(vstr);
                info.set(SIZE, Files.size(archive.toPath()) / 1e+6);
                // Check for cycle count
                int cycleCount = 0;
                for(final String v : versionStrings) {
                    if(new Version(v).type().equals(Version.Type.FULL)) cycleCount++;
                }
                if(maxCycles > 0) {
                    for(; cycleCount > maxCycles; cycleCount--) {
                        // Files to delete
                        final ArrayList<String> versionsToDelete = new ArrayList<String>();
                        final ArrayList<String> filesToDelete = new ArrayList<String>();
                        for(final String each : versionStrings) {
                            if(new Version(each).type().equals(Version.Type.FULL)
                                && filesToDelete.size() > 0)
                                break;
                            versionsToDelete.add(each);
                            filesToDelete.add(format.apply(each + TAR));
                            filesToDelete.add(format.apply(each + TAR + SHA1));
                            filesToDelete.add(format.apply(each + YML));
                        }
                        // Delete request
                        deleteObjects(s3Client, filesToDelete);
                        // Remove from index file
                        for(final String toDelete : versionsToDelete) {
                            versionStrings.remove(toDelete);
                            versionsInfo.set(toDelete, null);
                        }
                    }
                }
                // Finalize backup
                index.set(VERSIONS, versionStrings);
                index.set(VERSIONS_INFO, versionsInfo);
                index.save(indexFile);
                putObject(s3Client, format.apply(indexName), indexFile.toPath());
                final File folderCacheDir = new File(cacheDir, name);
                index.save(new File(folderCacheDir, INDEX + YML));

                // End S3
                s3Client.close();

                ss.getLogger().log(Level.INFO, "Successfully backed up {0}", name);
            } else {
                ss.getLogger().log(Level.INFO, "Nothing to backup for {0}", name);
            }
        }
        @Override
        public void run() {
            boolean success = true;
            try {
                tempDir.mkdirs();
                try {
                    for(final World world : ss.getServer().getWorlds()) {
                        folder(new File[]{world.getWorldFolder()},
                               String.format("%s_%s", world.getName(), world.getUID()),
                               cycleLength);
                    }
                } catch(Exception ex) {
                    success = false;
                    ex.printStackTrace();
                }
                try {
                    folder(new File[]{new File(PLUGINS)}, PLUGINS, cycleLength);
                    folder(resources, RESOURCES, cycleLength);
                } catch(Exception ex) {
                    success = false;
                    ex.printStackTrace();
                }
                FileUtils.deleteDirectory(tempDir);
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            // After backup process
            if(success) {
                ss.getLogger().info("Backup successful");
            }
            try {
                mutex.acquire();
                lock.delete();
                future.complete(null);
                future = null;
                mutex.release();
                if(ss.getServer().getOnlinePlayers().size() > 0) {
                    schedule(interval * TICK_MINUTE, false);
                }
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            // Post backup
            try {
                scheduler.scheduleSyncDelayedTask(ss, new PostTask());
            } catch(IllegalPluginAccessException ex) {
                // Try to dispatch command while being disabled
            }
        }
    }

    // This task is run synchronously after everything is done
    private final class PostTask implements Runnable {
        @Override
        public void run() {
            final Server server = ss.getServer();
            server.dispatchCommand(server.getConsoleSender(), "save-on");
        }
    }

    private final void schedule(final long later, final boolean override) {
        try {
            mutex.acquire();
            if(taskId == -1 && future == null) {
                try {
                    taskId = scheduler.scheduleSyncDelayedTask(ss, new BackupTask(override), later);
                } catch(IllegalPluginAccessException ex) {
                    // Attempt to schedule when being disabled
                }
            } else if(override && taskId != -1 && future == null) {
                taskId = scheduler.scheduleSyncDelayedTask(ss, new BackupTask(true), later);
            }
            mutex.release();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public Backup(final SpigotSuite ss) {
        this.ss = ss;
        scheduler = ss.getServer().getScheduler();
    }

    public final void enable() throws Exception {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);

        // Resources
        // * File lock
        lock = new File(NAME + ".lock");
        // * Cache file is the cached index file after the backup
        cacheDir = new File(NAME + ".cache");
        cacheDir.mkdirs();

        // Configuration
        enabled = config.getBoolean("enabled");
        if(!enabled) throw new Module.DisabledException();
        interval = config.getLong("interval");
        assert interval > 0 : "Interval must be above 0";
        cycleLength = config.getInt("cycleLength");
        assert cycleLength > 0 : "Cycle length must be above 0";
        maxCycles = config.getInt("maxCycles");
        final List<File> resourcesList = new ArrayList<File>();
        for(final String resource : config.getStringList(RESOURCES)) {
            resourcesList.add(new File(resource));
        }
        resources = resourcesList.toArray(new File[0]);
        // * Temporary directory
        final String tempDirPath = config.getString("tempDir");
        final String tempPrefix = Backup.class.getName();
        if("".equals(tempDirPath)) {
            tempDir = Files.createTempDirectory(tempPrefix).toFile();
        } else {
            tempDir = Files.createTempDirectory(new File(tempDirPath).toPath(), tempPrefix).toFile();
        }

        // AWS
        // * Credentials
        final FileConfiguration awsCredentials = YamlConfiguration.loadConfiguration(Resource.get(ss, NAME, "awsCredentials.yml"));
        System.setProperty("aws.accessKeyId", awsCredentials.getString("accessKeyId"));
        System.setProperty("aws.secretAccessKey", awsCredentials.getString("secretAccessKey"));
        // * Client
        s3Region = Region.of(config.getString("s3Region"));
        s3Bucket = config.getString("s3Bucket");
        s3Prefix = config.getString("s3Prefix");
        headBucket(createS3Client());

        ss.getLogger().log(Level.INFO, "Backup is configured at every {0} minutes", interval);
        ss.getCommand(NAME + "info").setExecutor(new CommandBackupInfo());
        ss.getCommand(NAME + "checksum").setExecutor(new CommandBackupChecksum());
        ss.getCommand(NAME + "override").setExecutor(new CommandBackupOverride());
        ss.getServer().getPluginManager().registerEvents(this, ss);

    }

    public final void disable() {
        try {
            mutex.acquire();
            if(taskId != -1) {
                scheduler.cancelTask(taskId);
                mutex.release();
                new BackupTask(false).run();
                future.join();
            } else if(future != null) {
                mutex.release();
                future.join();
            } else {
                mutex.release();
            }
        } catch(Exception ex) {
            ex.printStackTrace();
        }
        new PostTask().run();
    }

    public final String getName() {
        return NAME;
    }

}
