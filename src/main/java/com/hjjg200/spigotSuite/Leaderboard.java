package com.hjjg200.spigotSuite;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.Iterator;
import java.util.Collections;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.text.DecimalFormat;
import java.lang.IllegalArgumentException;

import org.bukkit.OfflinePlayer;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.Statistic;
import org.bukkit.Material;

public final class Leaderboard implements Module {

    private final static String NAME = Leaderboard.class.getSimpleName();
    private final static long TICK_MINUTE = 20L * 60L;
    private final static String TYPE = "type";
    private final static String ENTITY_TYPE = "entityType";
    private final static String MATERIAL = "material";

    private final SpigotSuite ss;
    private int taskId = -1;

    public Leaderboard(final SpigotSuite ss) {
        this.ss = ss;
    }

    // TODO: timeout tasks that take too long
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
        final Runnable asyncTask = new Runnable() {
            Iterator<String> it = null;
            private final <T extends Enum<T>> List<T> enumList(final ConfigurationSection stat, final Class<T> clazz) {

                String enumType;
                if(clazz == EntityType.class) {
                    enumType = ENTITY_TYPE;
                } else if(clazz == Material.class) {
                    enumType = MATERIAL;
                } else {
                    return null;
                }

                if(!stat.contains(enumType))
                    return null;

                List<T> list;
                if(stat.isList(enumType)) {
                    list = new ArrayList<T>();
                    final List<String> stringList = stat.getStringList(enumType);
                    for(final String name : stringList) {
                        list.add(T.valueOf(clazz, name));
                    }
                    if(list.size() == 0) {
                        // https://stackoverflow.com/questions/43020075/java-util-arrays-aslist-when-used-with-removeif-throws-unsupportedoperationexcep
                        // Wrap it with arraylist so that remove is
                        // supported
                        list = new ArrayList<>(Arrays.asList(clazz.getEnumConstants()));
                        list.removeIf(e -> e.name().startsWith("LEGACY_"));
                    }
                } else {
                    list = new ArrayList<T>();
                    list.add(T.valueOf(clazz, stat.getString(enumType)));
                }

                return list;

            }

            public void run() {
                if(it == null || it.hasNext() == false) {
                    if(shuffle) {
                        Collections.shuffle(keyList);
                    }
                    it = keyList.iterator();
                }
                final String key = it.next();
                final ConfigurationSection stat = statsConfig.getConfigurationSection(key);
                final List<Statistic> types = new ArrayList<Statistic>();
                if(stat.isList(TYPE)) {
                    List<String> list = stat.getStringList(TYPE);
                    for(final String each : list) {
                        types.add(Statistic.valueOf(each));
                    }
                } else {
                    types.add(Statistic.valueOf(stat.getString(TYPE)));
                }
                final double multiply = stat.getDouble("multiply", 1.0);
                final double divide = stat.getDouble("divide", 1.0);
                final DecimalFormat format = new DecimalFormat(stat.getString("format", "#,###"));
                final String order = stat.getString("order", "desc");

                // EntityType and Material
                final List<EntityType> entityTypes = enumList(stat, EntityType.class);
                final List<Material> materials = enumList(stat, Material.class);
                final Map<String, Double> map = new HashMap<String, Double>();
                List<OfflinePlayer> players = Arrays.asList(ss.getServer().getOfflinePlayers());

                int increment = 0;
                for(final Statistic type : types) {
                    if(entityTypes != null) {
                        for(final EntityType entityType : entityTypes) {
                            try {
                                for(final OfflinePlayer player : players) {
                                    map.put(player.getName(), player.getStatistic(type, entityType)
                                            + map.getOrDefault(player.getName(), 0.0d));
                                    increment++;
                                }
                            } catch(IllegalArgumentException ex) {
                                continue;
                            } catch(Exception ex) {
                                ex.printStackTrace();
                                return;
                            }
                        }
                    } else if(materials != null) {
                        for(final Material material : materials) {
                            try {
                                for(final OfflinePlayer player : players) {
                                    map.put(player.getName(), player.getStatistic(type, material)
                                            + map.getOrDefault(player.getName(), 0.0d));
                                    increment++;
                                }
                            } catch(IllegalArgumentException ex) {
                                continue;
                            } catch(Exception ex) {
                                ex.printStackTrace();
                                return;
                            }
                        }
                    } else {
                        try {
                            for(final OfflinePlayer player : players) {
                                map.put(player.getName(), player.getStatistic(type)
                                        + map.getOrDefault(player.getName(), 0.0d));
                                increment++;
                            }
                        } catch(IllegalArgumentException ex) {
                            continue;
                        } catch(Exception ex) {
                            ex.printStackTrace();
                            return;
                        }
                    }
                }

                // Sort and make table
                class Elem {
                    final String name;
                    double value;
                    Elem(final String name, final double value) {
                        this.name = name;
                        this.value = value;
                    }
                }
                final List<Elem> list = new ArrayList<Elem>();
                for(final String name : map.keySet()) {
                    list.add(new Elem(name, map.get(name)));
                }
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
                            + ChatColor.RESET + elem.name
                            + ChatColor.GRAY + " ("
                            + ChatColor.YELLOW + format.format(elem.value)
                            + ChatColor.GRAY + ")";
                }

                // Broadcast
                ss.getServer().broadcastMessage(table);
            }
        };

        final Runnable syncTask = new Runnable() {
            public void run() {
                CompletableFuture.runAsync(asyncTask);
            }
        };

        taskId = ss.getServer().getScheduler().scheduleSyncRepeatingTask(ss, syncTask, interval, interval);

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
