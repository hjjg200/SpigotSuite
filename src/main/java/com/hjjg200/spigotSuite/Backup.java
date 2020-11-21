package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.concurrent.locks.ReentrantLock;
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
    private final static String CONTENT_TYPE = "Content-Type";
    private final SpigotSuite ss;
    private final BukkitScheduler scheduler;
    private final ReentrantLock mutex = new ReentrantLock();
    private final Thread hook = new Thread(){
        @Override
        public void run() {
            mutex.lock();
        }
    };
    private boolean enabled;
    private long interval;
    private String s3Bucket; // = "my-s3-bucket"
    private Region s3Region; // = "ap-northeast-2"
    private String s3Prefix; // = "/backup/minecraft/"
    private S3Client s3Client;
    private Timer timer;

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        schedule();
    }

    // This class is run synchronously
    private final class BackupTask implements Runnable {
        @Override
        public void run() {
            mutex.lock();
            ss.getLogger().info("Starting to backup...");
            final Server server = ss.getServer();
            final CommandSender cs = server.getConsoleSender();
            server.dispatchCommand(cs, "save-all");
            server.dispatchCommand(cs, "save-off");
            scheduler.runTaskAsynchronously(ss, new AsyncTask());
        }
    }

    private final class AsyncTask implements Runnable {
        @Override
        public void run() {
            boolean success = false;
            try {
                String time = LocalDateTime.now().format(FORMATTER);
                // Backup model is incremental
                for(final World world : ss.getServer().getWorlds()) {
                    final String name = String.format("%s_%s", world.getName(), world.getUID());
                    final File dataFile = Resource.get(ss, NAME, name + ".yml");
                    boolean initial = false; // Whether it is the initial backup
                    if(!dataFile.exists()) {
                        initial = true;
                        time = "00000000_initial";
                        dataFile.createNewFile();
                    }
                    final FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
                    final Predicate<File> filter = file -> {
                        final String path = file.getPath();
                        final long lastMod = file.lastModified();
                        final boolean mod = lastMod != data.getLong(path);
                        if(mod) data.set(path, lastMod);
                        return mod;
                    };
                    final Archive archive = new Archive(world.getWorldFolder(), filter);
                    if(archive.count() > 0) {
                        final String baseKey = String.format("%s%s/%s", s3Prefix, name, time);
                        final HashMap<String, String> metadata = new HashMap<String, String>();
                        final PutObjectRequest.Builder builder = PutObjectRequest.builder()
                          //.storageClass
                            .bucket(s3Bucket);
                        // Put archive
                        final PutObjectRequest gzipRequest = builder
                            .contentType("application/gzip")
                            .key(baseKey + ".tar.gz")
                            .build();
                        final PutObjectResponse gzipResponse = s3Client.putObject(gzipRequest, RequestBody.fromInputStream(archive.inputStream(), archive.size()));
                        if(!gzipResponse.sdkHttpResponse().isSuccessful()) throw new IOException("PutObject for archive failed");
                        // Put sha1
                        final PutObjectRequest sha1Request = builder
                            .contentType("plain/text")
                            .key(baseKey + ".tar.gz.sha1")
                            .build();
                        final PutObjectResponse sha1Response = s3Client.putObject(sha1Request, RequestBody.fromBytes(new Hex(UTF_8).encode(archive.digest())));
                    }
                    data.save(dataFile);
                }
                success = true;
            } catch(Exception ex) {
                ex.printStackTrace();
            } finally {
                // PostTask must be scheduled prior to mutex unlock
                scheduler.scheduleSyncDelayedTask(ss, new PostTask(success));
                // unlock must be done async in order to prevent deadlock
                mutex.unlock();
            }
        }
    }

    // This task is run synchronously after everything is done
    private final class PostTask implements Runnable {
        private final boolean success;
        public PostTask(final boolean success) {
            super();
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
        // TODO: 10L -> 60L
        scheduler.scheduleSyncDelayedTask(ss, new BackupTask(), interval * TICK_SECOND * 10L);
    }

    // public final long BackupSize -> entire backup size filtered by prefix

    public Backup(final SpigotSuite ss) {
        this.ss = ss;
        scheduler = ss.getServer().getScheduler();
    }

    public final void enable() throws Exception {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        enabled = config.getBoolean("enabled");
        if(!enabled) return;
        interval = config.getLong("interval");
        assert interval > 0 : "Interval must be above 0";
        s3Region = Region.of(config.getString("s3Region")); // IllegalArgument
        s3Bucket = config.getString("s3Bucket");
        s3Prefix = config.getString("s3Prefix");
        // Java System Properties - aws.accessKeyId and aws.secretKey
        final FileConfiguration awsCredentials = YamlConfiguration.loadConfiguration(Resource.get(ss, NAME, "awsCredentials.yml"));
        System.setProperty("aws.accessKeyId", awsCredentials.getString("accessKeyId"));
        System.setProperty("aws.secretKey", awsCredentials.getString("secretKey"));
        // Prepare client
        s3Client = S3Client.builder()
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
        // TODO: spigotsuite must disable modules that successfully enabled
        mutex.lock();
    }

    public final String getName() {
        return NAME;
    }

}
