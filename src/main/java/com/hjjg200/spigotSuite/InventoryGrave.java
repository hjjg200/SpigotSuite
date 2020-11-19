package com.hjjg200.spigotSuite;

import java.util.List;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;

import org.bukkit.ChatColor;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Rotatable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

import org.bukkit.entity.Player;

public final class InventoryGrave implements Module, Listener {

    private final static String NAME = InventoryGrave.class.getSimpleName();
    private final static int MAX_CHEST_SLOTS = InventoryType.CHEST.getDefaultSize();
    private final static int SEARCH_RADIUS = 9;

    private final SpigotSuite ss;
    private List<Integer> keptSlots;
    private List<String> datePatterns;

    public InventoryGrave(final SpigotSuite ss) {
        this.ss = ss;
    }

    public final void enable() {
        ss.getLogger().info("Enabling " + NAME);
        // Configuration
        final ConfigurationSection config = ss.getConfig().getConfigurationSection(NAME);
        keptSlots = config.getIntegerList("keptSlots");
        assert keptSlots.size() <= MAX_CHEST_SLOTS : "Kept items must fit into a chest";
        datePatterns = config.getStringList("datePatterns");
        // Register events
        ss.getServer().getPluginManager().registerEvents(this, ss);
    }

    public final void disable() {

    }

    @EventHandler
    public void onPlayerDeath(final PlayerDeathEvent e) {
        if(e.getKeepInventory()) return;

        final Player p = e.getEntity();
        final Inventory pInv = p.getInventory();
        final Location pLoc = p.getLocation();
        final World w = pLoc.getWorld();
        final BlockFace pBf = p.getFacing();
        final List<ItemStack> drops = e.getDrops();
        final Location cLoc = getGraveLocation(pLoc);
        final Block cBlk = w.getBlockAt(cLoc);
        final Location sLoc = cLoc.clone().add(0, 1, 0);
        final Block sBlk = w.getBlockAt(sLoc);

        // Make chest
        cBlk.setType(Material.CHEST);
        final Chest c = (Chest)cBlk.getState();
        final Inventory cInv = c.getInventory();
        // * Put items
        for(int i = 0; i < pInv.getSize(); i++) {
            final ItemStack item = pInv.getItem(i);
            if(item == null) continue;
            if(keptSlots.contains(i)) {
                // Pop items out and put it in chest
                drops.remove(item);
                cInv.addItem(item);
            }
        }
        // * Set facing
        final Directional cData = (Directional)c.getBlockData();
        cData.setFacing(pBf);
        c.setBlockData(cData);
        // * Update
        c.update(true);

        // Make sign
        sBlk.setType(Material.CRIMSON_SIGN);
        final Sign s = (Sign)sBlk.getState();
        final Rotatable sData = (Rotatable)s.getBlockData();
        // * Set Facing
        sData.setRotation(pBf);
        s.setBlockData(sData);
        // * Set lines
        s.setEditable(false);
        s.setColor(DyeColor.WHITE);
        s.setLine(0, toBold(p.getDisplayName()));
        final DamageCause cause = p.getLastDamageCause().getCause();
        s.setLine(1, cause.toString());
        int i = 2;
        final LocalDateTime now = LocalDateTime.now();
        for(final String pt : datePatterns) {
            final DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pt);
            String line = "";
            try {
                line = now.format(fmt);
            } catch(Error ex) {
                line = "INVALID FORMAT";
            }
            s.setLine(i++, toBold(line));
        }
        // * Update
        s.update(true);

    }

    public final String getName() {
        return NAME;
    }

    private final static String toBold(final String str) {
        return ChatColor.BOLD.toString() + str + ChatColor.RESET.toString();
    }

    private final static int[][] getSquareOutline(final int r) {
        final int sz = ((2*r) + 1) * ((2*r) + 1);
        int[][] ret = new int[sz][2];
        int[] c = {r, 0};
        int p = 1;
        int d = 1;
        for(int i = 0; i < sz; i++) {
            ret[i] = new int[]{c[0], c[1]};
            c[p] += d;
            if(Math.abs(c[p]) == r) {
                p = p == 1 ? 0 : 1;
                d = c[p] > 0 ? -1 : 1;
            }
        }
        return ret;
    }

    private final static int[] getSearchedYs(final Location l) {
        final World w = l.getWorld();
        int y0 = (int)l.getY();
        final int maxY = w.getMaxHeight() - 2;

        y0 = Math.max(0, y0);
        y0 = Math.min(maxY, y0);

        int[] ret = new int[maxY + 1];
        int i = 0;
        for(int y = y0; y <= maxY; y++) ret[i++] = y;
        for(int y = y0 - 1; y >= 0; y--) ret[i++] = y;

        return ret;
    }

    private final static Location getGraveLocation(final Location l) {

        final World w = l.getWorld();
        for(final int y : getSearchedYs(l)) {
        for(int r = 0; r <= SEARCH_RADIUS; r++) {
            final int[][] outline = getSquareOutline(r);
            for(final int[] dxz : outline) {
                final Location cl = new Location(w, l.getX() + dxz[0], y, l.getZ() + dxz[1]);
                final Location sl = cl.clone().add(0, 1, 0);
                if(!isBlockNotSolid(cl.getBlock())) continue;
                if(!isBlockNotSolid(sl.getBlock())) continue;
                return cl;
            }
        }
        }

        return null;

    }

    private final static boolean isBlockNotSolid(final Block b) {
        return b.isEmpty() || b.isLiquid();
    }

}