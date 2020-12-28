package com.hjjg200.spigotSuite;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.ServerSocket;
import java.net.Socket;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.OutputStreamAppender;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.command.CommandSender;
import org.bukkit.Server;

import com.hjjg200.spigotSuite.util.Log4jUtils;

public final class SimpleRcon implements Module {

    private final static String NAME = SimpleRcon.class.getSimpleName();

    private final SpigotSuite ss;
    private int port;
    private ServerSocket server = null;
    private Task task = null;

    class Task implements Runnable {
        final ServerSocket server;
        boolean running = true;
        Task(final ServerSocket server) {
            this.server = server;
        }
        public void close() throws Exception {
            running = false;
            server.close();
        }
        class SyncTask implements Callable<Void> {
            private final String command;
            public SyncTask(final String command) {
                this.command = command;
            }
            public Void call() {
                final Server server = ss.getServer();
                final CommandSender cs = server.getConsoleSender();
                server.dispatchCommand(cs, command);
                return null;
            }
        }
        public void run() {
            while(running) {
                try {
                    Socket socket = server.accept();
                    final BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    final String message = reader.readLine();
                    final Logger logger = (Logger)LogManager.getRootLogger();
                    final OutputStreamAppender appender = OutputStreamAppender
                        .createAppender(Log4jUtils.getLayout(),
                        null,
                        socket.getOutputStream(),
                        "SimpleRconAppender",
                        false,
                        true);

                    appender.start();
                    logger.addAppender(appender);
                    final Future<Void> future = ss.getServer().getScheduler().callSyncMethod(ss, new SyncTask(message));
                    future.get();
                    socket.close();
                    logger.removeAppender(appender);
                    appender.stop();
                } catch(Exception ex) {
                    continue;
                }
            }
        }
    };

    public SimpleRcon(final SpigotSuite ss) {
        this.ss = ss;
    }

    public void enable() throws Exception {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        port = config.getInt("port");
        server = new ServerSocket(port, 50, Inet4Address.getLoopbackAddress());
        task = new Task(server);
        CompletableFuture.runAsync(task);
    }

    public void disable() throws Exception {
        task.close();
    }

    public final String getName() {
        return NAME;
    }

}
