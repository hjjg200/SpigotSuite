package com.hjjg200.spigotSuite;

public final class Backup implements Module {

    private final static String NAME = Backup.class.getSimpleName();
    private final static String UTF_8 = "UTF-8";
    private final static File AWS_SHARED_CREDENTIALS_FILE = new File(new File(System.getProperty("user.home"), ".aws"), "credentials");
    private final SpigotSuite ss;
    private final ReentrantLock mutex = new ReentrantLock();
    private String s3Bucket; // = "my-s3-bucket"
    private Region s3Region; // = "ap-northeast-2"
    private String s3Prefix; // = "/backup/minecraft/"
    private File s3Credentials;

    public final class BackupTask extends TimerTask {
        @Override
        public void run() {
            mutex.Lock();
            // Save-all
            // Save-off

            // AWS Credentials
            boolean credentialCopied = !AWS_SHARED_CREDENTIALS_FILE.exists();
            if(s3Credentials.exists() && credentialCopied) {
                Files.copy(s3Credentials.toPath(), AWS_SHARED_CREDENTIALS_FILE.toPath());
            } else if(!credentialCopied && s3Credentials.exists()) {
                throw IOException("AWS credentials file already exists at $AWS_SHARED_CREDENTIALS_FILE");
            }
            for(final World world : ss.getServer().getWorlds()) {
                final String name = String.format("%s_%s", world.getName(), world.getUUID());
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
                    try {
                        final AmazonS3Client s3Client = AmazonS3ClientBuilder().standard().withRegion(s3Region).build();
                        // Put archive
                        final ObjectMetadata metadata = new ObjectMetadata();
                        metadata.setContentType("application/gzip");
                        s3Client.putObject(s3Bucket, baseKey + ".tar.gz", archive.inputStream(), metadata);
                        // Put sha1
                        s3Client.putObject(s3Bucket, baseKey + ".tar.gz.sha1", new String((new Hex(UTF_8)).encode(archive.digest()), UTF_8));
                    } catch(AmazonServiceException e) {
                        e.printStackTrace();
                    } catch(SdkClientException e) {
                        e.printStackTrace();
                    }
                }
                data.save(dataFile);
            }
            // Save-on
            if(credentialCopied) AWS_SHARED_CREDENTIALS_FILE.delete();
            mutex.Unlock();
        }
    }

    public Backup(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        s3Region = Region.fromValue(config.getString("s3Region")); // IllegalArgument
        s3Bucket = config.getString("s3Bucket");
        s3Prefix = config.getString("s3Prefix");
        s3Credentials = new File(ss.getDataFolder(), config.getString("s3Credentials"));
    }

}
