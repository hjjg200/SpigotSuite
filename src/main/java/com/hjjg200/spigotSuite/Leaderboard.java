package com.hjjg200.spigotSuite;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Iterator;
import java.util.Collections;
import java.util.Arrays;
import java.text.DecimalFormat;

import org.bukkit.OfflinePlayer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.Statistic;
import org.bukkit.Material;

public final class Leaderboard implements Module {

    public final static String NAME = Leaderboard.class.getSimpleName();
    public final static long TICK_MINUTE = 20L * 60L;

    private final SpigotSuite ss;
    private int taskId = -1;

    public Leaderboard(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() throws Exception {

        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        final boolean shuffle = config.getBoolean("shuffle");
        final long interval = config.getLong("interval") * TICK_MINUTE;
        if(interval <= 0) {
            throw new Module.DisabledException();
        }
        // Stats
        final ConfigurationSection statsConfig = config.getConfigurationSection("stats");
        final Set<String> keySet = statsConfig.getKeys(false);
        if(keySet.size() == 0) {
            throw new Module.DisabledException();
        }

        // Schedule
        final List<String> keyList = new ArrayList<String>();
        keyList.addAll(keySet);
        taskId = ss.getServer().getScheduler().scheduleSyncRepeatingTask(ss, new Runnable() {
            Iterator<String> it = null;
            @Override
            public void run() {
                if(it == null || it.hasNext() == false) {
                    if(shuffle) {
                        Collections.shuffle(keyList);
                    }
                    it = keyList.iterator();
                }
                final String key = it.next();
                final ConfigurationSection stat = statsConfig.getConfigurationSection(key);
                final Statistic type = Statistic.valueOf(stat.getString("type"));
                final double multiply = stat.getDouble("multiply", 1.0);
                final double divide = stat.getDouble("divide", 1.0);
                final DecimalFormat format = new DecimalFormat(stat.getString("format", "#,###"));
                final String order = stat.getString("order", "desc");

                final String entityTypeString = stat.getString("entityType", "");
                final String materialString = stat.getString("material", "");

                class Elem {
                    OfflinePlayer player;
                    double value;
                    Elem(final OfflinePlayer player, final double value) {
                        this.player = player;
                        this.value = value;
                    }
                }
                final List<Elem> list = new ArrayList<Elem>();

                List<OfflinePlayer> players = Arrays.asList(ss.getServer().getOfflinePlayers());
                try {
                    for(final OfflinePlayer player : players) {
                        double value = 0.0d;
                        if(!"".equals(entityTypeString)) {
                            final EntityType entityType = EntityType.valueOf(entityTypeString);
                            value = player.getStatistic(type, entityType);
                        } else if(!"".equals(materialString)) {
                            final Material material = Material.valueOf(materialString);
                            value = player.getStatistic(type, material);
                        } else {
                            value = player.getStatistic(type);
                        }
                        list.add(new Elem(player, value));
                    }
                } catch(Exception ex) {
                    ex.printStackTrace();
                    return;
                }

                // Sort and make table
                list.sort((lhs, rhs) -> {
                    if(lhs.value == rhs.value) return 0;
                    if("asc".equals(order)) {
                        return lhs.value < rhs.value ? -1 : 1;
                    }
                    return lhs.value > rhs.value ? -1 : 1;
                });

                String table = ChatColor.BLUE + key;
                int i = 0;
                for(final Elem elem : list) {
                    elem.value *= multiply;
                    elem.value /= divide;
                    table += "\n"
                            + ChatColor.RED + String.format("%d. ", ++i)
                            + ChatColor.RESET + elem.player.getName()
                            + ChatColor.GRAY + " ("
                            + ChatColor.YELLOW + format.format(elem.value)
                            + ChatColor.GRAY + ")";
                }

                // Broadcast
                ss.getServer().broadcastMessage(table);
            }
        }, interval, interval);
    }

    public final void disable() {
        if(taskId != -1) {
            ss.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public final String getName() {
        return NAME;
    }

}
