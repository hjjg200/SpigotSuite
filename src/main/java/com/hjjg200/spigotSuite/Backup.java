package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.commons.codec.binary.Hex;

import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;

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

public final class Backup implements Module, Listener {

    private final static String NAME = Backup.class.getSimpleName();
    private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
    private final static long TICK_SECOND = 20L;
    private final static String UTF_8 = "UTF-8";
    private final static String HEAD = "HEAD";
    private final static String FULL = "FULL"; // full represents new head
    private final static String INDEX = "_index";
    private final static String VERSIONS = "versions";
    private final static String APP_YML = "application/x-yaml";
    private final static String APP_GZ = "application/gzip";
    private final static String TXT_PL = "text/plain";
    private final static String TAR_GZ = ".tar.gz";
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
    private S3Client s3Client;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
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
                final GetObjectRequest.Builder getObjectBuilder = GetObjectRequest
                    .builder()
                    .bucket(s3Bucket);
                final PutObjectRequest.Builder putObjectBuilder = PutObjectRequest
                    .builder()
                    .bucket(s3Bucket);
                // General
                final UnaryOperator<String> formatKey = base -> s3Prefix + name + "/" + base;
                // Create temp directory
                final File worldTempDir = new File(tempDir, name);
                worldTempDir.mkdirs();
                // Get index file
                final String indexName = INDEX + YML;
                final File indexFile = new File(worldTempDir, indexName);
                final GetObjectRequest indexRequest = getObjectBuilder
                    .key(formatKey(indexName))
                    .build();
                final GetObjectResponse indexResponse = s3Client.getObject(indexRequest, indexFile.toPath());
                final YamlConfiguration index = new YamlConfiguration();
                if(indexInputStream.response().isSuccessful()) {
                    index.loadConfiguration(indexFile);
                } else if(indexInputStream.response().statusCode() != 404) {
                    throw new Exception("Index file foe " + name + " could not be accessed");
                }
                // Versions
                List<String> versions = index.getStringList(VERSIONS);
                if(versions == null) {
                    versions = new ArrayList<String>();
                }
                // Cache last modified time
                final YamlConfiguration lastMods = new YamlConfiguration();
                Files.walk(world.getWorldFolder().toPath())
                    .map(path -> path.toFile())
                    .filter(file -> file.isFile())
                    .forEach(file -> lastMods.set(file.getPath(), file.lastModified()));
                // Head version
                if(initial == false) {
                    // Preserve only those that did not change
                    try {
                        final String headVersion = versions.get(versions.size() - 1);
                        // Head sha1
                        final String headSha1Name = HEAD + TAR_GZ + SHA1;
                        final GetObjectRequest headSha1Request = getObjectBuilder
                            .key(formatKey(headSha1Name))
                            .build();
                        final GetObjectResponse headSha1Response = s3Client.getObject(
                            headSha1Request,
                            new File(worldTempDir, headSha1Name).toPath());
                        assert headSha1Response.isSuccessful() : "The head version is not accessible or damaged";
                        // Head archive
                        final String headArchiveName = HEAD + TAR_GZ;
                        final GetObjectRequest headArchiveRequest = getObjectBuilder
                            .key(formatKey(headArchiveName))
                            .build();
                        final File headArchiveFile = new File(worldTempDir, headArchiveName);
                        final GetObjectResponse headArchiveResponse = s3Client.getObject(
                            headArchiveRequest,
                            headArchiveFile.toPath());
                        assert headArchiveResponse.isSuccessful() : "The head archive is not accessible";
                        // * Decompress head archive
                        final File headDir = new File(worldTempDir, HEAD);
                        headDir.mkdirs();
                        Archive.decompress(headArchiveFile, headDir);
                        // * Delete old head archive
                        headArchiveFile.delete();
                        // Head last modified times
                        final GetObjectRequest headLastModsRequest = getObjectBuilder
                            .key(formatKey(HEAD + YML))
                            .build();
                        final File headLastModsFile = new File(worldTempDir, HEAD + YML);
                        final GetObjectResponse headLastModsResponse = s3Client.getObject(headLastModsRequest, headLastModsFile.toPath());
                        assert headLastModsResponse.isSuccessful() : "The head last modified times are not accessible";
                        final YamlConfiguration headLastMods = YamlConfiguration.loadConfiguration(headLastModsFile);
                        // * Decompress the archive
                        // * Archive again -- changed files only
                        final String diffArchiveName = headVersion + TAR_GZ;
                        final File diffArchiveFile = new File(worldTempDir, diffArchiveName);
                        final Archive diffArchive = new Archive(
                            new File(headArchiveDir, world.getName()),
                            diffArchiveFile,
                            file -> lastMods.getLong(file.getPath()) != headLastMods.getLong(file.getPath()));
                        if(diffArchive.count() > 0) {
                            // Put archive
                            final PutObjectRequest diffArchiveRequest = putObjectBuilder
                                .key(formatKey(headVersion + TAR_GZ))
                                .contentType(APP_GZ)
                                .build();
                            final PutObjectResponse diffArchiveResponse = s3Client.putObject(diffArchiveRequest, diffArchiveFile.toPath());
                            assert diffArchiveResponse.isSuccessful() : "Could not put diff archive to the bucket";
                            // Put sha1
                            final PutObjectRequest diffSha1Request = putObjectBuilder
                                .key(formatKey(headVersion + TAR_GZ + SHA1))
                                .contentType(TXT_PL)
                                .build();
                            final PutObjectResponse diffSha1Response = s3Client.putObject(
                                diffSha1Request,
                                RequestBody.fromBytes(new Hex(UTF_8).encode(diffArchive.digest())));
                            assert diffSha1Response.isSuccessful() : "Could not put diff sha1 to the bucket";
                            // Put last mods
                            final PutObjectRequest diffLastModsRequest = putObjectBuilder
                                .key(formatKey(headVersion + YML))
                                .contentType(APP_YML)
                                .build();
                            final PutObjectResponse diffLastModsResponse = s3Client.putObject(diffLastModsRequest, headLastModsFile.toPath());
                            assert diffLastModsResponse.isSuccessful() : "Coult not put diff last modified times to the bucket";
                            // End
                            ss.getLogger().log(Level.INFO, "Preserved the old versions of changed files");
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
                final PutObjectRequest putIndexRequest = putObjectBuilder
                    .contentType(APP_YML)
                    .key(formatKey(INDEX + YML))
                    .build();
                final PutObjectResponse putIndexResponse = s3Client.putObject(putIndexRequest, indexFile);
                assert putIndexResponse.isSuccessful() : "Failed to update the index file";
                // Full backup for current world
                final File fullArchiveFile = new File(worldTempDir, FULL + TAR_GZ);
                final Archive fullArchive = new Archive(
                    world.getWorldFolder(),
                    fullArchiveFile.toPath(),
                    null);
                if(fullArchive.count() > 0) {
                    // Put full archive
                    final PutObjectRequest fullArchiveRequest = putObjectBuilder
                        .contentType(APP_GZ)
                        .key(formatKey(time + TAR_GZ))
                        .build();
                    final PutObjectResponse fullArchiveResponse = s3Client.putObejct(
                        fullArchiveRequest,
                        fullArchiveFile.toPath());
                    assert fullArchiveResponse.isSuccessful() : "Failed to put the full archive";
                    // Put full sha1
                    final PutObjectRequest fullSha1Request = putObjectBuilder
                        .contentType(TXT_PL)
                        .key(formatKey(time + TAR_GZ_SHA1))
                        .build();
                    final PutObjectResponse fullSha1Response = s3Client.putObject(
                        fullSha1Request,
                        RequestBody.fromBytes(new Hex(UTF_8).encode(fullArchive.digest())));
                    assert fullSha1Response.isSuccessful() : "Failed to put the full sha1";
                    // Put full last modified times
                    final File fullLastModsFile = new File(worldTempDir, FULL + YML);
                    lastMods.save(fullLastModsFile);
                    final PutObjectRequest fullLastModsRequest = putObjectBuilder
                        .contentType(APP_YML)
                        .key(formatKey(time + YML))
                        .build();
                    final PutObjectResponse fullLastModsResponse = s3Client.putObject(fullLastModsRequest, fullLastModsFile.toPath());
                    assert fullLastModsResponse.isSuccessful() : "Failed to put the full last modified times";
                    // End
                    ss.getLogger().log(Level.INFO, "Successfully backed up {0}", name);
                } else {
                    ss.getLogger().log(Level.INFO, "Nothing to backup for {0}", name);
                }
           }
        }
        @Override
        public void run() {
            boolean success = false;
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
        scheduler.scheduleSyncDelayedTask(ss, new BackupTask(), interval * TICK_SECOND * 60L);
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
        if("".equals(tempDirPath) {
            tempDir = new File("temp_" + getTime());
        } else {
            tempDir = new File(tempDirPath);
        }
        if(tempDir.exists()) throw new IOException("The specified temporary directory already exists!");
        tempDir.mkdirs();
        // * S3 Configuration
        s3Region = Region.of(config.getString("s3Region")); // IllegalArgument
        s3Bucket = config.getString("s3Bucket");
        s3Prefix = config.getString("s3Prefix");
        // Prepare client
        s3Client = S3Client.builder()
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
        // Remove temporary directory
        assert tempDir.delete() : "Failed to delete the temporary directory";
    }

    public final String getName() {
        return NAME;
    }

}
