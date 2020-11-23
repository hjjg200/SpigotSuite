package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerJoinEvent;

import com.hjjg200.spigotSuite.util.Archive;
import com.hjjg200.spigotSuite.util.Resource;

final class S3Short {

    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final GetObjectRequest.Builder getObjectBuilder;
    private final PutObjectRequest.Builder putObjectBuilder;

    public S3Short(final S3Client client, final String bucket, final String prefix) {
        this.client = client;
        this.bucket = bucket;
        this.prefix = prefix;

        getObjectBuilder = GetObjectRequest.builder().bucket(bucket);
        putObjectBuilder = PutObjectRequest.builder().bucket(bucket);
    }

    private final static String contentTypeOf(final String key) {
        final String[] split = key.split("\\.");
        if(split.length < 2) return "";
        switch(split[split.length - 1]) {
        case "yml": return "application/x-yaml";
        case "gz": return "application/gzip";
        case "tar": return "application/x-tar";
        case "sha1": return "text/plain";
        }
        return "";
    }

    public final GetObjectResponse getObject(final String key, final File file) throws Exception {
        return client.getObject(
            getObjectBuilder.key(prefix + key).build(),
            file.toPath());
    }

    public final PutObjectResponse putObject(final String key, final File file) throws Exception {
        return client.putObject(
            putObjectBuilder.key(prefix + key).contentType(contentTypeOf(key)).build(),
            file.toPath());
    }

}

public final class Backup implements Module, Listener {

    private final static String NAME = Backup.class.getSimpleName();
    private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
    private final static long TICK_SECOND = 20L;
    private final static String UTF_8 = "UTF-8";
    private final static String HEAD = "HEAD";
    private final static String FULL = "FULL"; // full represents new head
    private final static String INDEX = "_index";
    private final static String VERSIONS = "versions";
    private final static String TAR = ".tar";
    private final static String SHA1 = ".sha1";
    private final static String YML = ".yml";
    private final static Semaphore MUTEX = new Semaphore(1);
    private final SpigotSuite ss;
    private final BukkitScheduler scheduler;
    private boolean enabled;
    private long interval;
    private File tempDir;
    private String s3Bucket; // = "my-s3-bucket"
    private Region s3Region; // = "ap-northeast-2"
    private String s3Prefix; // = "/backup/minecraft/"
    private S3Short s3Short;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        // TODO: fix this
        schedule();
    }

    // This class is run synchronously
    private final class BackupTask implements Runnable {
        @Override
        public void run() {
            try {
                MUTEX.acquire();
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            ss.getLogger().info("Starting to backup...");
            final Server server = ss.getServer();
            final CommandSender cs = server.getConsoleSender();
            server.dispatchCommand(cs, "save-all");
            server.dispatchCommand(cs, "save-off");
            scheduler.runTaskAsynchronously(ss, new AsyncTask());
        }
    }

    private final class AsyncTask implements Runnable {
        private final String time = getTime();
        private final void resources() throws Exception {
            // Always full backup
        }
        private final void plugins() throws Exception {
            // Only resources backup
            // Include enabled plugins' versions
        }
        private final void worlds() throws Exception {
            // Reverse incremental backup
            for(final World world : ss.getServer().getWorlds()) {
                final String name = String.format("%s_%s", world.getName(), world.getUID());
                // General
                final UnaryOperator<String> format = base -> name + "/" + base;
                // Create tempi directory
                final File worldTempDir = new File(tempDir, name);
                worldTempDir.mkdirs();
                // Get index file
                final String indexName = INDEX + YML;
                final File indexFile = new File(worldTempDir, indexName);
                YamlConfiguration index = new YamlConfiguration();
                try {
                    s3Short.getObject(format.apply(indexName), indexFile);
                    index = YamlConfiguration.loadConfiguration(indexFile);
                } catch(NoSuchKeyException ex) {
                } catch(Exception ex) {
                    throw ex;
                }
                // Versions
                List<String> versions = index.getStringList(VERSIONS);
                // Last modified time
                final YamlConfiguration md5s = new YamlConfiguration();
                // Head version
                if(versions.size() > 0) {
                    // Preserve only those that did not change
                    try {
                        // Prepare
                        final String headVersion = versions.get(versions.size() - 1);
                        final File headDir = new File(worldTempDir, HEAD);
                        headDir.mkdirs();
                        // Head sha1
                        final String headSha1Name = HEAD + TAR + SHA1;
                        s3Short.getObject(format.apply(headSha1Name), new File(headDir, headSha1Name));
                        // Head archive
                        final String headArchiveName = HEAD + TAR;
                        final Archive headArchive = new Archive(headDir, headArchiveName);
                        s3Short.getObject(format.apply(headArchiveName), headArchive);
                        // * Decompress head archive
                        headArchive.unpack();
                        // * Delete old head archive
                        headArchive.delete();
                        // Head md5s
                        final File headMd5sFile = new File(headDir, HEAD + YML);
                        s3Short.getObject(format.apply(HEAD + YML), headMd5sFile);
                        final YamlConfiguration headMd5s = YamlConfiguration.loadConfiguration(headMd5sFile);
                        // * Archive again -- changed files only
                        final String diffArchiveName = headVersion + TAR;
                        final Archive diffArchive = Archive.pack(
                            new File(headDir, world.getName()),
                            new File(worldTempDir, diffArchiveName),
                            entry -> {
                                final String md5 = entry.md5();
                                final String md5Key = DigestUtils.md5Hex(entry.getName());
                                md5s.set(md5Key, md5);
                                return !md5.equals(headMd5s.getString(md5Key));
                            });
                        System.out.println(diffArchive.count());
                        if(diffArchive.count() > 0) {
                            // Put archive
                            s3Short.putObject(format.apply(diffArchiveName), diffArchive);
                            // Put sha1
                            final File diffSha1File = new File(diffArchive.getPath() + SHA1);
                            s3Short.putObject(format.apply(diffArchiveName + SHA1), diffSha1File);
                            // Put md5s
                            s3Short.putObject(format.apply(headVersion + YML), headMd5sFile);
                            // End
                            ss.getLogger().log(Level.INFO, "Preserved the old versions of changed files");
                        } else {
                            // Remove this version from versions
                            versions.remove(headVersion);
                        }
                        //
                    } catch(Exception ex) {
                        ss.getLogger().log(Level.SEVERE, "Could not preserve the old world files");
                        ex.printStackTrace();
                    }
                }
                // Update index file
                versions.add(time);
                index.set(VERSIONS, versions);
                // Put updated index file
                index.save(indexFile);
                s3Short.putObject(format.apply(indexName), indexFile);
                // Full backup for current world
                final Archive fullArchive = Archive.pack(
                    world.getWorldFolder(),
                    new File(worldTempDir, FULL + TAR),
                    md5s.getValues(false).size() > 0
                        ? null
                        : entry -> {
                            md5s.set(DigestUtils.md5Hex(entry.getName()), entry.md5());
                            return true;
                        });
                // Put full archive
                s3Short.putObject(format.apply(HEAD + TAR), fullArchive);
                // Put full sha1
                s3Short.putObject(format.apply(HEAD + TAR + SHA1), new File(fullArchive.getPath() + SHA1));
                // Put full last modified times
                final File fullMd5sFile = new File(worldTempDir, FULL + YML);
                md5s.save(fullMd5sFile);
                s3Short.putObject(format.apply(HEAD + YML), fullMd5sFile);
                // End
                ss.getLogger().log(Level.INFO, "Successfully backed up {0}", name);
           }
        }
        @Override
        public void run() {
            boolean success = false;
            try {
                System.out.println(tempDir.getPath());
                try {
                    worlds();
                    success &= true;
                } catch(Exception ex) {
                    ex.printStackTrace();
                }
                try {
                    plugins();
                    resources();
                    success &= true;
                } catch(Exception ex) {
                    ex.printStackTrace();
                }
                // Empty temp directory
                Files.walk(tempDir.toPath())
                    .map(path -> path.toFile())
                    .filter(file -> file.isFile())
                    .forEach(file -> file.delete());
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            // unlock must be done async in order to prevent deadlock
            MUTEX.release();
            scheduler.scheduleSyncDelayedTask(ss, new PostTask(success));
        }
    }

    // This task is run synchronously after everything is done
    private final class PostTask implements Runnable {
        private final boolean success;
        public PostTask(final boolean success) {
            this.success = success;
        }
        @Override
        public void run() {
            if(success) {
                ss.getLogger().info("Backup successful");
            }
            final Server server = ss.getServer();
            server.dispatchCommand(server.getConsoleSender(), "save-on");
            schedule();
        }
    }

    private final void schedule() {
        // TODO: 30L -> 60L
        scheduler.scheduleSyncDelayedTask(ss, new BackupTask(), interval * TICK_SECOND * 30L);
    }

    private final String getTime() {
        return LocalDateTime.now().format(FORMATTER);
    }

    // public final long BackupSize -> entire backup size filtered by prefix

    // public final String[] versions -> list available versions

    // public final void assemble -> 

    public Backup(final SpigotSuite ss) {
        this.ss = ss;
        scheduler = ss.getServer().getScheduler();
    }

    public final void enable() throws Exception {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        // Java System Properties - aws.accessKeyId and aws.secretKey
        final FileConfiguration awsCredentials = YamlConfiguration.loadConfiguration(Resource.get(ss, NAME, "awsCredentials.yml"));
        System.setProperty("aws.accessKeyId", awsCredentials.getString("accessKeyId"));
        System.setProperty("aws.secretAccessKey", awsCredentials.getString("secretAccessKey"));
        // Configuration
        enabled = config.getBoolean("enabled");
        if(!enabled) throw new Module.DisabledException();
        interval = config.getLong("interval");
        assert interval > 0 : "Interval must be above 0";
        // * Temporary directory
        final String tempDirPath = config.getString("tempDir");
        final String tempPrefix = Backup.class.getName();
        if("".equals(tempDirPath)) {
            tempDir = Files.createTempDirectory(tempPrefix).toFile();
        } else {
            tempDir = Files.createTempDirectory(new File(tempDirPath).toPath(), tempPrefix).toFile();
        }
        // * S3 Configuration
        s3Region = Region.of(config.getString("s3Region")); // IllegalArgument
        s3Bucket = config.getString("s3Bucket");
        s3Prefix = config.getString("s3Prefix");
        // Prepare client
        final S3Client s3Client = S3Client.builder()
            .credentialsProvider(SystemPropertyCredentialsProvider.create())
            .region(s3Region)
            .build();
        // HeadBucket to check permissions
        final HeadBucketRequest headRequest = HeadBucketRequest.builder()
            .bucket(s3Bucket)
            .build();
        final HeadBucketResponse headResponse = s3Client.headBucket(headRequest);
        if(!headResponse.sdkHttpResponse().isSuccessful()) {
            throw new Exception("Could not access the specified S3 bucket with the supplied info");
        }
        // Short
        s3Short = new S3Short(s3Client, s3Bucket, s3Prefix);
        // Succesful configuration
        ss.getLogger().log(Level.INFO, "Backup is configured at every {0} minutes", interval);
        // Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void disable() {
        try {
            MUTEX.acquire();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public final String getName() {
        return NAME;
    }

}
