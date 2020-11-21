package com.hjjg200.spigotSuite;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.apache.commons.codec.binary.Hex;

import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.core.sync.RequestBody;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import com.hjjg200.spigotSuite.util.Archive;
import com.hjjg200.spigotSuite.util.Resource;

public final class Backup implements Module {

    private final static String NAME = Backup.class.getSimpleName();
    private final static DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss");
    private final static String UTF_8 = "UTF-8";
    private final SpigotSuite ss;
    private final ReentrantLock mutex = new ReentrantLock();
    private final Thread hook = new Thread(){
        @Override
        public void run() {
            mutex.lock();
        }
    };
    private long interval;
    private String s3Bucket; // = "my-s3-bucket"
    private Region s3Region; // = "ap-northeast-2"
    private String s3Prefix; // = "/backup/minecraft/"
    private File s3Credentials;
    private Timer timer;

    public final class BackupTask extends TimerTask {
        @Override
        public void run() {
            mutex.lock();
            final Server server = ss.getServer();
            final CommandSender cs = server.getConsoleSender();
            server.dispatchCommand(cs, "save-all");
            server.dispatchCommand(cs, "save-off");
            // Time
            final String time = LocalDateTime.now().format(FORMATTER);

            // AWS Credentials
            try {
                for(final World world : ss.getServer().getWorlds()) {
                    final String name = String.format("%s_%s", world.getName(), world.getUID());
                    final File dataFile = Resource.get(ss, NAME, name + ".yml");
                    boolean initial = false;
                    if(!dataFile.exists()) {
                        initial = true;
                        dataFile.createNewFile();
                    }
                    final FileConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
                    final Predicate<File> filter = file -> {
                        final String path = file.getPath();
                        final long priorLastMod = data.getLong(path); // defaults to 0
                        final long lastMod = file.lastModified();
                        final boolean b = lastMod != priorLastMod;
                        if(b) data.set(path, lastMod);
                        return b;
                    };
                    final Archive archive = new Archive(world.getWorldFolder(), filter);
                    if(archive.count() > 0) {
                        final String baseKey = String.format("%s%s/%s", s3Prefix, name, time);
                        final S3Client s3Client = S3Client.builder()
                            .region(s3Region)
                            .build();
                        // Put archive
                        final HashMap<String, String> meta = new HashMap<String, String>();
                        meta.put("content-type", "application/gzip");
                        final PutObjectRequest gzReq = PutObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(baseKey + ".tar.gz")
                            .metadata(meta)
                            .build();
                        s3Client.putObject(gzReq, RequestBody.fromInputStream(archive.inputStream(), archive.size()));
                        // Put sha1
                        meta.put("content-type", "plain/text");
                        final PutObjectRequest sha1Req = PutObjectRequest.builder()
                            .bucket(s3Bucket)
                            .key(baseKey + ".tar.gz.sha1")
                            .metadata(meta)
                            .build();
                        s3Client.putObject(sha1Req, RequestBody.fromBytes(new Hex(UTF_8).encode(archive.digest())));
                    }
                    data.save(dataFile);
                }
            } catch(Exception e) {
                // TODO: AWS-wise exception handling
                e.printStackTrace();
            } finally {
                server.dispatchCommand(cs, "save-on");
                mutex.unlock();
                // Schedule next backup
                schedule();
            }
        }
    }

    public Backup(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        interval = config.getLong("interval");
        assert interval > 0 : "Interval must be above 0";
        s3Region = Region.of(config.getString("s3Region")); // IllegalArgument
        s3Bucket = config.getString("s3Bucket");
        s3Prefix = config.getString("s3Prefix");
        // Java System Properties - aws.accessKeyId and aws.secretKey
        final FileConfiguration awsCredentials = YamlConfiguration.loadConfiguration(Resource.get(ss, NAME, "aws_credentials.yml"));
        System.setProperty("aws.accessKeyId", awsCredentials.getString("accessKeyId"));
        System.setProperty("aws.secretKey", awsCredentials.getString("secretKey"));
        Runtime.getRuntime().addShutdownHook(hook);
    }

    public final void disable() {
        mutex.lock();
        timer.cancel();
        timer = null;
        Runtime.getRuntime().removeShutdownHook(hook);
        mutex.unlock();
    }

    public final String getName() {
        return NAME;
    }

    private final void schedule() {
        timer = new Timer();
        timer.schedule(new BackupTask(), interval * 60L * 1000L);
    }

}
