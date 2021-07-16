package com.hjjg200.spigotSuite.chatBridge;

import java.util.Arrays;
import java.util.function.Consumer;
import java.nio.charset.StandardCharsets;
import java.util.Timer;
import java.util.TimerTask;
import java.util.ArrayList;
import java.io.Serializable;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageChannel;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import org.bukkit.event.EventHandler;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.server.BroadcastMessageEvent;
import org.bukkit.configuration.ConfigurationSection;

public final class DiscordPlugin extends AbstractPlugin {

    private final static String PLUGIN_NAME = "discord";
    private final static int JDA_MAX_LENGTH = 2000;
    private final static long BUFFER_DELAY = 2500L;
    private transient JDA jda;
    private transient String botToken;
    private transient String channelId;
    private transient String adminId;
    private ArrayList<DiscordAppender> appenders = new ArrayList<DiscordAppender>();

    private final class MessageListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent e) {
            final User author = e.getAuthor();
            final Message message = e.getMessage();
            final MessageChannel from = e.getChannel();
            if(jda.getSelfUser().getId().equals(author.getId())) return;

            switch(from.getType()) {
            case TEXT:
                if(!from.getId().equals(channelId)) return;
                if(listener == null) return;
                final Member member = e.getMember();
                listener.accept(new Event(member.getEffectiveName(),
                                message.getContentStripped()));
                break;
            }
        }
    }

    private final class DiscordAppender extends AbstractAppender {
        private String buffer = "";
        private Timer timer = null;

        public DiscordAppender(final Layout<? extends Serializable> layout) {
            super(DiscordAppender.class.getSimpleName(), null, layout, true, null);
        }

        public void flush() {
            if(buffer.length() == 0) return;

            sendMessageToTextChannel(adminId, buffer);
            buffer = "";
        }

        @Override
        public void append(LogEvent event) {
            if(adminId.equals("")) return;

            final String message = (String)getLayout().toSerializable(event);
            if(message.length() + buffer.length() > JDA_MAX_LENGTH) {
                flush();
            }
            buffer += message;

            if(timer == null) {
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    public void run() {
                        timer = null;
                        flush();
                    }
                }, BUFFER_DELAY);
            }
        }
    }

    public final String getName() {
        return PLUGIN_NAME;
    }

    public DiscordPlugin(final ConfigurationSection config) {
        super(config);
    }

    public void enable() {
        // Config
        botToken = config.getString("botToken");
        channelId = config.getString("channelId");
        assert !channelId.equals("") : "Invalid channelId supplied";
        adminId = config.getString("adminId");
        // Discord
        JDABuilder builder = JDABuilder.createDefault(botToken);
        builder.addEventListeners(new MessageListener());
        try {
            jda = builder.build().awaitReady();
            assert jda.getTextChannelById(channelId) != null;
            assert adminId.equals("") || jda.getTextChannelById(adminId) != null;
        } catch(Exception ex) {
            ex.printStackTrace();
        }
    }

    public void disable() {
        // Ensure all appenders are flushed
        for(final DiscordAppender appender : appenders) {
            appender.flush();
        }
        appenders.clear();
        jda.shutdown();
        jda = null;
    }


    private final void sendMessageToTextChannel(final String id, String message) {
        if(message.length() > JDA_MAX_LENGTH) {
            message = message.substring(0, JDA_MAX_LENGTH);
        }
        jda.getTextChannelById(id).sendMessage(message).queue();
    }

    public final void sendMessage(final String name, final String message) {
        final String n = name == null ? "" : "` " + name + " ` ";
        sendMessageToTextChannel(channelId, String.format("%s%s", n, message));
    }

    public final Appender createLogAppender(final Layout<? extends Serializable> layout) {
        final DiscordAppender appender =  new DiscordAppender(layout);
        appenders.add(appender);
        return appender;
    }

}
