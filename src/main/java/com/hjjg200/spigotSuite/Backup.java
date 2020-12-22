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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

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

import com.hjjg200.spigotSuite.util.Archive;
import com.hjjg200.spigotSuite.util.Resource;

final class S3Short {

    private final S3Client client;
    private final String bucket;
    private final String prefix;
    private final GetObjectRequest.Builder getObjectBuilder;
    private final PutObjectRequest.Builder putObjectBuilder;

    public S3Short(final Region region, final String bucket, final String prefix) {
        client = S3Client.builder()
            .credentialsProvider(SystemPropertyCredentialsProvider.create())
            .region(region)
            .build();
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

    public final HeadBucketResponse headBucket() throws Exception {
        return client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
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
    private final static long TICK_MINUTE = 20L * 60L;
    private final static String UTF_8 = "UTF-8";
    private final static String INDEX = "_index";
    private final static String SIZE = "size";
    private final static String VERSIONS = "versions";
    private final static String PLUGINS = "plugins";
    private final static String RESOURCES = "resources";
    private final static String TAR = ".tar";
    private final static String SHA1 = ".sha1";
    private final static String YML = ".yml";
    private final static Semaphore MUTEX = new Semaphore(1);
    private final SpigotSuite ss;
    private final BukkitScheduler scheduler;
    private File lock;
    private volatile CompletableFuture<Void> future;
    private boolean enabled;
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private int taskId = -1;
    private long interval;
    private int cycleLength;
    private File[] resources;
    private File tempDir;
    private File cacheDir;
    private String s3Bucket; // = "my-s3-bucket"
    private Region s3Region; // = "ap-northeast-2"
    private String s3Prefix; // = "/backup/minecraft/"
    private S3Short s3Short;

    public final static class Version {
        public static enum Type {
            FULL,
            INCREMENTAL
        }
        private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
        private final static String SEPARATOR = "-"; // this must be regex compatible
        private final LocalDateTime _time;
        private final Type _type;

        public Version(final String string) {
            final String[] split = string.split(SEPARATOR);
            if(split.length < 2) throw new IllegalArgumentException("Malformed version");
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

    private final class CommandBackup implements CommandExecutor {
        @Override
        public boolean onCommand(final CommandSender sender, final Command command, final String label, final String[] args) {
            if(args.length == 0) return false;

            switch(args[0]) {
            case "info":
                if(!(sender instanceof Player)) return true;
                final Player player = (Player)sender;
                for(final File worldDir : cacheDir.listFiles()) {
                    if(!worldDir.isDirectory()) continue;
                    final YamlConfiguration index = YamlConfiguration.loadConfiguration(new File(worldDir, INDEX + YML));
                    final String worldName = worldDir.getName();
                    player.sendMessage("# " + worldName);
                    player.sendMessage(String.format("Size: %.2f GB", index.getDouble("size") / 1e+3));
                    player.sendMessage("Versions:");
                    for(final String version : index.getStringList(VERSIONS)) {
                        player.sendMessage("- " + version);
                    }
                    player.sendMessage("");
                }
                break;
            }

            return true;
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        schedule(interval * TICK_MINUTE);
    }

    // TODO: test doing scheduling backup when being disabled

    @EventHandler
    public void onServerLoad(ServerLoadEvent e) {
        // Check for file lock
        if(lock.exists()) {
            ss.getLogger().log(Level.SEVERE, "Backup file lock is found, doing an immediate backup");
            lock.delete();
            schedule(0);
        }
    }

    // This class is run synchronously
    private final class BackupTask implements Runnable {
        @Override
        public void run() {
            ss.getLogger().info("Starting to backup...");
            scheduled.set(false);
            taskId = -1;
            try {
                lock.createNewFile();
                future = new CompletableFuture<>();
                final Server server = ss.getServer();
                final CommandSender cs = server.getConsoleSender();
                server.dispatchCommand(cs, "save-all");
                server.dispatchCommand(cs, "save-off");
                CompletableFuture.runAsync(new AsyncTask());
            } catch(Exception ex) {
                ss.getLogger().log(Level.SEVERE, "Failed to initiate the backup procedure!");
                ex.printStackTrace();
            }
        }
    }

    private final class AsyncTask implements Runnable {
        private final LocalDateTime time = LocalDateTime.now();

        /*
         * -1 as folderCycleLength makes backup infinite incremental
         */
        private final void folder(final File[] sources, final String name, final int folderCycleLength) throws Exception {

            final UnaryOperator<String> format = base -> name + "/" + base;

            // Create temp directory
            final File folderTempDir = new File(tempDir, name);
            folderTempDir.mkdirs();

            // Get index file
            final String indexName = INDEX + YML;
            final File indexFile = new File(folderTempDir, indexName);
            YamlConfiguration index = new YamlConfiguration();
            try {
                s3Short.getObject(format.apply(indexName), indexFile);
                index = YamlConfiguration.loadConfiguration(indexFile);
            } catch(NoSuchKeyException ex) {
            } catch(Exception ex) {
                throw ex;
            }
            // * Versions
            List<String> versionStrings = index.getStringList(VERSIONS);

            // Determine the version for this backup
            final AtomicBoolean isFull = new AtomicBoolean(true);
            for(int i = versionStrings.size() - 1; i >= 0; i--) {
                if(folderCycleLength > 0 && versionStrings.size() - i >= folderCycleLength) {
                    break;
                } else if(new Version(versionStrings.get(i)).type().equals(Version.Type.FULL)) {
                    isFull.set(false);
                    break;
                }
            }
            final Version version = new Version(time, isFull.get() ? Version.Type.FULL : Version.Type.INCREMENTAL);
            final YamlConfiguration md5s = new YamlConfiguration();
            final YamlConfiguration headMd5s = new YamlConfiguration();

            // Head version
            if(versionStrings.size() > 0) {
                final Version headVersion = new Version(versionStrings.get(versionStrings.size() - 1));
                final File headMd5sFile = new File(folderTempDir, headVersion.toString() + YML);
                s3Short.getObject(format.apply(headVersion.toString() + YML), headMd5sFile);
                headMd5s.load(headMd5sFile);
            }

            // Archive accordingly
            final Archive archive = Archive.pack(
                sources,
                new File(folderTempDir, version + TAR),
                entry -> {
                    if(entry.isFile() == false) return false;
                    final String key = DigestUtils.md5Hex(entry.getName());
                    final String md5 = entry.md5();
                    md5s.set(key, md5);
                    return isFull.get() || !md5.equals(headMd5s.getString(key));
                });

            if(archive.count() > 0) {
                s3Short.putObject(format.apply(version.toString() + TAR), archive);
                s3Short.putObject(format.apply(version.toString() + TAR + SHA1), new File(archive.getPath() + SHA1));
                final File md5sFile = new File(folderTempDir, version.toString() + YML);
                md5s.save(md5sFile);
                s3Short.putObject(format.apply(version.toString() + YML), md5sFile);

                index.set(SIZE, index.getLong(SIZE) + Files.size(archive.toPath()) / 1e+6);
                versionStrings.add(version.toString());
                index.set(VERSIONS, versionStrings);
                index.save(indexFile);
                s3Short.putObject(format.apply(indexName), indexFile);

                final File folderCacheDir = new File(cacheDir, name);
                index.save(new File(folderCacheDir, INDEX + YML));

                ss.getLogger().log(Level.INFO, "Successfully backed up {0}", name);
            } else {
                ss.getLogger().log(Level.INFO, "Nothing to backup for {0}", name);
            }
        }
        @Override
        public void run() {
            boolean success = false;
            try {
                System.out.println(tempDir.getPath());
                tempDir.mkdirs();
                try {
                    for(final World world : ss.getServer().getWorlds()) {
                        folder(new File[]{world.getWorldFolder()}, String.format("%s_%s", world.getName(), world.getUID()), cycleLength);
                    }
                    success = success && true;
                } catch(Exception ex) {
                    ex.printStackTrace();
                }
                try {
                    folder(new File[]{new File(PLUGINS)}, PLUGINS, -1);
                    folder(resources, RESOURCES, -1);
                    success = success && true;
                } catch(Exception ex) {
                    ex.printStackTrace();
                }
                FileUtils.deleteDirectory(tempDir);
            } catch(Exception ex) {
                ex.printStackTrace();
            }
            // unlock must be done async in order to prevent deadlock
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
            lock.delete();
            running.set(false);
            future.complete(null);
            if(ss.getServer().getOnlinePlayers().size() > 0) schedule(interval * TICK_MINUTE);
        }
    }

    private final void schedule(final long later) {
        if(running.compareAndSet(false, true)) {
            taskId = scheduler.scheduleSyncDelayedTask(ss, new BackupTask(), later);
            scheduled.set(true);
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
        s3Short = new S3Short(
            Region.of(config.getString("s3Region")),
            config.getString("s3Bucket"),
            config.getString("s3Prefix")
        );
        s3Short.headBucket();

        ss.getLogger().log(Level.INFO, "Backup is configured at every {0} minutes", interval);
        ss.getCommand(NAME).setExecutor(new CommandBackup());
        ss.getServer().getPluginManager().registerEvents(this, ss);

    }

    public final void disable() {
        try {
            if(scheduled.compareAndSet(true, false)) {
                scheduler.cancelTask(taskId);
                new BackupTask().run();
            }
            if(future != null) {
                future.join();
            }
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public final String getName() {
        return NAME;
    }

}
