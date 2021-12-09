package com.hjjg200.spigotSuite;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.ResultSet;
import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Set;
import java.time.temporal.ChronoUnit;
import java.time.LocalDateTime;
import java.lang.Runnable;

import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import com.hjjg200.spigotSuite.util.Resource;

public final class LoginBuff implements Module, Listener {

    private final static String NAME = LoginBuff.class.getSimpleName();
    private final SpigotSuite ss;
    private final File dbFile;
    private final String jdbcAddr;

    private float resetHour;
    private int effectMinutes;
    private HashMap<PotionEffectType, Integer> effectsMap;

    public LoginBuff(final SpigotSuite ss) {
        this.ss = ss;
        this.dbFile = Resource.get(ss, NAME, "data.db");
        this.jdbcAddr = "jdbc:sqlite:" + this.dbFile.toString();
    }

    public final String getName() {
        return NAME;
    }

    private final void ensureDatabase() throws SQLException {
        if(Files.exists(dbFile.toPath())) return;

        // If database does not exist
        Connection conn = null;
        conn = DriverManager.getConnection(jdbcAddr);
        Statement stmt = conn.createStatement();
        stmt.setQueryTimeout(30);

        stmt.executeUpdate(
            "CREATE TABLE `buff_info` (\n"
          + "    `uuid` VARCHAR(36) NOT NULL UNIQUE,\n"
          + "    `buff_start` DATETIME NOT NULL,\n"
          + "    UNIQUE(`uuid`)\n"
          + ");"
        );

        conn.close();
    }

    public final void enable() throws Exception {
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);

        effectMinutes = config.getInt("duration");
        assert effectMinutes > 0 : "Buff duration must be positive";

        resetHour = (float)config.getDouble("resetHour");
        assert resetHour >= 0.0 : "Buff reset hour must be positive";
        assert resetHour < 24.0 : "Buff reset hour must be below 24.0";

        final ConfigurationSection effects = config.getConfigurationSection("effects");
        final Set<String> keySet = effects.getKeys(false);
        if(keySet.size() == 0) {
            throw new Module.DisabledException();
        }

        effectsMap = new HashMap<PotionEffectType, Integer>();
        for(final String name : keySet) {
            final int amp = effects.getInt(name);
            final PotionEffectType typ = PotionEffectType.getByName(name);

            assert typ != null : "Potion effect not found";
            effectsMap.put(typ, amp);
        }

        //
        ensureDatabase();

        // Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void disable() {
    }

    private final LocalDateTime getLastReset() {
        final int hrs = (int)resetHour;
        final int mins = (int)((resetHour-(float)hrs) * 60.0);

        final LocalDateTime now = LocalDateTime.now();
        LocalDateTime lastReset = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), hrs, mins);

        if(now.compareTo(lastReset) < 0) {
            // If last reset is in the future
            // minus 1 day
            lastReset = lastReset.minusDays(1);
        }
        return lastReset;
    }

    private final void applyEffects(Player p) {

        if(p.isDead()) return;

        final String uuid = p.getUniqueId().toString();

        boolean giveNew = true;
        int durationTicks = effectMinutes * 60 * 20;

        final LocalDateTime now = LocalDateTime.now();

        Connection conn;
        Statement stmt;
        checkStart: try {
            conn = DriverManager.getConnection(jdbcAddr);
            stmt = conn.createStatement();
            stmt.setQueryTimeout(30);

            final ResultSet rs1 = stmt.executeQuery(
                "SELECT `buff_start`\n"
              + "FROM `buff_info`\n"
              + "WHERE `uuid` = \""+uuid+"\";"
            );

            if(false == rs1.next()) {
                // Put row
                stmt.executeUpdate(
                    "INSERT INTO `buff_info`\n"
                  + "VALUES (\""+uuid+"\", \""+LocalDateTime.now().toString()+"\");"
                );
                break checkStart;
            }

            // sqlite3 does not provide date type
            final LocalDateTime start = LocalDateTime.parse(rs1.getString("buff_start"));
            final LocalDateTime end = start.plus(effectMinutes, ChronoUnit.MINUTES);
            final LocalDateTime lastReset = getLastReset();

            if(now.compareTo(lastReset) < 0 || start.compareTo(lastReset) > 0) {
                if(end.compareTo(now) < 0) {
                    conn.close();
                    return;
                }

                // Give remaining time
                giveNew = false;
                durationTicks = 20 * (int)now.until(end, ChronoUnit.SECONDS);
            } else {
                // Give new buff
            }

        } catch(SQLException e) {
            e.printStackTrace();
            return;
        }

        if(giveNew) {
            try {
                stmt.executeUpdate(
                    "UPDATE `buff_info`\n"
                  + "SET `buff_start` = \""+now.toString()+"\"\n"
                  + "WHERE `uuid` = \""+uuid+"\";"
                );
            } catch(SQLException e) {
                e.printStackTrace();
                return;
            }
        }

        try {
            if(conn != null) conn.close();
        } catch(SQLException e) {
            e.printStackTrace();
            return;
        }

        for(PotionEffectType typ : effectsMap.keySet()) {
            final int amp = effectsMap.get(typ) - 1;
            final PotionEffect pe = new PotionEffect(typ, durationTicks, amp, false);
            pe.apply(p);
        }

    }

    @EventHandler
    public final void onPlayerRespawn(PlayerRespawnEvent e) {
        ss.getServer().getScheduler().scheduleSyncDelayedTask(ss, new Runnable() {
            @Override
            public void run() {
                applyEffects(e.getPlayer());
            }
        }, 1L);
    }

    @EventHandler
    public final void onPlayerJoin(PlayerJoinEvent e) {
        applyEffects(e.getPlayer());
    }

}

