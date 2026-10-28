package com.example.areatitle;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public class AreaTitlesPlugin extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {

    private static final TextColor BLUE = TextColor.color(0x00a2ff);
    private static final NamedTextColor WHITE = NamedTextColor.WHITE;
    private static final NamedTextColor RED = NamedTextColor.RED;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,32}");
    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final List<String> SUBS = List.of(
            "pos1", "pos2", "create", "delete", "list", "settitle", "setsubtitle", "settimes", "info", "preview");

    private static final class Region {
        String name;
        String world;
        int minX, minY, minZ, maxX, maxY, maxZ;
        String title = "";
        String subtitle = "";
        double fadeIn = 0.5, stay = 3.0, fadeOut = 1.0;

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }

        boolean contains(Location l) {
            return l.getWorld() != null && l.getWorld().getName().equals(world)
                    && l.getBlockX() >= minX && l.getBlockX() <= maxX
                    && l.getBlockY() >= minY && l.getBlockY() <= maxY
                    && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
        }
    }

    private final Map<String, Region> regions = new LinkedHashMap<>();
    private final Map<UUID, String> current = new HashMap<>();
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private File file;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        file = new File(getDataFolder(), "regions.yml");
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("areatitle").setExecutor(this);
        getCommand("areatitle").setTabCompleter(this);
    }

    // ---------------------------------------------------------------- storage

    private void load() {
        regions.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("regions");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(name);
            if (s == null) continue;
            Region r = new Region();
            r.name = name;
            r.world = s.getString("world", "world");
            r.minX = s.getInt("min-x");
            r.minY = s.getInt("min-y");
            r.minZ = s.getInt("min-z");
            r.maxX = s.getInt("max-x");
            r.maxY = s.getInt("max-y");
            r.maxZ = s.getInt("max-z");
            r.title = s.getString("title", "");
            r.subtitle = s.getString("subtitle", "");
            r.fadeIn = s.getDouble("fade-in", 0.5);
            r.stay = s.getDouble("stay", 3.0);
            r.fadeOut = s.getDouble("fade-out", 1.0);
            regions.put(name.toLowerCase(), r);
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Region r : regions.values()) {
            String b = "regions." + r.name;
            yml.set(b + ".world", r.world);
            yml.set(b + ".min-x", r.minX);
            yml.set(b + ".min-y", r.minY);
            yml.set(b + ".min-z", r.minZ);
            yml.set(b + ".max-x", r.maxX);
            yml.set(b + ".max-y", r.maxY);
            yml.set(b + ".max-z", r.maxZ);
            yml.set(b + ".title", r.title);
            yml.set(b + ".subtitle", r.subtitle);
            yml.set(b + ".fade-in", r.fadeIn);
            yml.set(b + ".stay", r.stay);
            yml.set(b + ".fade-out", r.fadeOut);
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            getLogger().warning("Could not save regions.yml: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- detection

    /** The smallest region containing the location, or null. */
    private Region find(Location l) {
        Region best = null;
        for (Region r : regions.values()) {
            if (r.contains(l) && (best == null || r.volume() < best.volume())) best = r;
        }
        return best;
    }

    private void show(Player p, Region r) {
        Component title = LEGACY.deserialize(r.title.replace("%player%", p.getName()));
        Component sub = LEGACY.deserialize(r.subtitle.replace("%player%", p.getName()));
        Title.Times times = Title.Times.times(
                Duration.ofMillis((long) (r.fadeIn * 1000)),
                Duration.ofMillis((long) (r.stay * 1000)),
                Duration.ofMillis((long) (r.fadeOut * 1000)));
        p.showTitle(Title.title(title, sub, times));
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (from.getWorld() == to.getWorld() && from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) return;

        Player p = e.getPlayer();
        Region r = find(to);
        String name = r == null ? null : r.name;
        String prev = current.get(p.getUniqueId());
        if (Objects.equals(prev, name)) return;

        if (name == null) current.remove(p.getUniqueId());
        else current.put(p.getUniqueId(), name);
        if (r != null) show(p, r);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        // Remember the region silently so logging in inside one doesn't trigger a title.
        Region r = find(e.getPlayer().getLocation());
        if (r != null) current.put(e.getPlayer().getUniqueId(), r.name);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        current.remove(id);
        pos1.remove(id);
        pos2.remove(id);
    }

    // ---------------------------------------------------------------- commands

    private void ok(CommandSender s, String t) {
        s.sendMessage(Component.text(t, WHITE));
    }

    private void err(CommandSender s, String t) {
        s.sendMessage(Component.text(t, RED));
    }

    private void usage(CommandSender s) {
        err(s, "Usage:");
        String[] lines = {
                "/areatitle pos1  and  /areatitle pos2  (stand at the two corners)",
                "/areatitle create <name> [fullheight]",
                "/areatitle delete <name>",
                "/areatitle list",
                "/areatitle settitle <name> <text>",
                "/areatitle setsubtitle <name> <text|none>",
                "/areatitle settimes <name> <fadeIn> <stay> <fadeOut>  (seconds)",
                "/areatitle info <name>",
                "/areatitle preview <name>",
                "Colours: & codes and hex (&#00a2ff). %player% = player's name."
        };
        for (String l : lines) s.sendMessage(Component.text(l, BLUE));
    }

    private static String join(String[] a, int from) {
        return String.join(" ", Arrays.copyOfRange(a, from, a.length));
    }

    private Region get(CommandSender s, String name) {
        Region r = regions.get(name.toLowerCase());
        if (r == null) err(s, "No region named '" + name + "'. Use /areatitle list.");
        return r;
    }

    private static double parse(String s) {
        return Double.parseDouble(s);
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("areatitle.admin")) {
            err(s, "You don't have permission.");
            return true;
        }
        if (a.length == 0) {
            usage(s);
            return true;
        }
        String sub = a[0].toLowerCase();
        switch (sub) {
            case "pos1", "pos2" -> {
                if (!(s instanceof Player p)) {
                    err(s, "Players only.");
                    return true;
                }
                Location l = p.getLocation();
                Location b = new Location(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
                (sub.equals("pos1") ? pos1 : pos2).put(p.getUniqueId(), b);
                ok(s, sub + " set to " + b.getBlockX() + ", " + b.getBlockY() + ", " + b.getBlockZ());
            }
            case "create" -> {
                if (!(s instanceof Player p)) {
                    err(s, "Players only.");
                    return true;
                }
                if (a.length < 2) {
                    err(s, "Usage: /areatitle create <name> [fullheight]");
                    return true;
                }
                if (!ID.matcher(a[1]).matches()) {
                    err(s, "The name may only contain letters, numbers, _ and - (max 32).");
                    return true;
                }
                if (regions.containsKey(a[1].toLowerCase())) {
                    err(s, "A region with that name already exists.");
                    return true;
                }
                Location l1 = pos1.get(p.getUniqueId());
                Location l2 = pos2.get(p.getUniqueId());
                if (l1 == null || l2 == null) {
                    err(s, "Set both corners first with /areatitle pos1 and /areatitle pos2.");
                    return true;
                }
                if (!Objects.equals(l1.getWorld(), l2.getWorld())) {
                    err(s, "Both corners must be in the same world.");
                    return true;
                }
                World w = l1.getWorld();
                Region r = new Region();
                r.name = a[1];
                r.world = w.getName();
                r.minX = Math.min(l1.getBlockX(), l2.getBlockX());
                r.maxX = Math.max(l1.getBlockX(), l2.getBlockX());
                r.minZ = Math.min(l1.getBlockZ(), l2.getBlockZ());
                r.maxZ = Math.max(l1.getBlockZ(), l2.getBlockZ());
                if (a.length >= 3 && a[2].equalsIgnoreCase("fullheight")) {
                    r.minY = w.getMinHeight();
                    r.maxY = w.getMaxHeight() - 1;
                } else {
                    r.minY = Math.min(l1.getBlockY(), l2.getBlockY());
                    r.maxY = Math.max(l1.getBlockY(), l2.getBlockY());
                }
                r.title = a[1];
                r.fadeIn = getConfig().getDouble("default-times.fade-in", 0.5);
                r.stay = getConfig().getDouble("default-times.stay", 3.0);
                r.fadeOut = getConfig().getDouble("default-times.fade-out", 1.0);
                regions.put(r.name.toLowerCase(), r);
                save();
                ok(s, "Created region '" + r.name + "' (" + r.volume() + " blocks). Now set its text with /areatitle settitle "
                        + r.name + " <text>.");
            }
            case "delete" -> {
                if (a.length < 2) {
                    err(s, "Usage: /areatitle delete <name>");
                    return true;
                }
                Region r = get(s, a[1]);
                if (r == null) return true;
                regions.remove(r.name.toLowerCase());
                save();
                ok(s, "Deleted region '" + r.name + "'.");
            }
            case "list" -> {
                if (regions.isEmpty()) {
                    ok(s, "No regions yet.");
                    return true;
                }
                for (Region r : regions.values()) {
                    s.sendMessage(Component.text(r.name, BLUE)
                            .append(Component.text("  " + r.world + "  \"" + r.title + "\"", WHITE)));
                }
            }
            case "settitle", "setsubtitle" -> {
                if (a.length < 3) {
                    err(s, "Usage: /areatitle " + sub + " <name> <text>");
                    return true;
                }
                Region r = get(s, a[1]);
                if (r == null) return true;
                String text = join(a, 2);
                if (sub.equals("settitle")) {
                    r.title = text;
                } else {
                    r.subtitle = text.equalsIgnoreCase("none") ? "" : text;
                }
                save();
                ok(s, "Updated '" + r.name + "'. Use /areatitle preview " + r.name + " to see it.");
            }
            case "settimes" -> {
                if (a.length < 5) {
                    err(s, "Usage: /areatitle settimes <name> <fadeIn> <stay> <fadeOut> (seconds)");
                    return true;
                }
                Region r = get(s, a[1]);
                if (r == null) return true;
                try {
                    double fi = parse(a[2]), st = parse(a[3]), fo = parse(a[4]);
                    if (fi < 0 || st < 0 || fo < 0 || fi > 60 || st > 60 || fo > 60) {
                        err(s, "Each time must be between 0 and 60 seconds.");
                        return true;
                    }
                    r.fadeIn = fi;
                    r.stay = st;
                    r.fadeOut = fo;
                } catch (NumberFormatException ex) {
                    err(s, "Times must be numbers, e.g. 0.5 3 1");
                    return true;
                }
                save();
                ok(s, "Times updated for '" + r.name + "'.");
            }
            case "info" -> {
                if (a.length < 2) {
                    err(s, "Usage: /areatitle info <name>");
                    return true;
                }
                Region r = get(s, a[1]);
                if (r == null) return true;
                ok(s, r.name + " in " + r.world + ": " + r.minX + "," + r.minY + "," + r.minZ
                        + " to " + r.maxX + "," + r.maxY + "," + r.maxZ);
                ok(s, "Title: " + r.title);
                ok(s, "Subtitle: " + (r.subtitle.isEmpty() ? "(none)" : r.subtitle));
                ok(s, "Times (s): in " + r.fadeIn + ", stay " + r.stay + ", out " + r.fadeOut);
            }
            case "preview" -> {
                if (!(s instanceof Player p)) {
                    err(s, "Players only.");
                    return true;
                }
                if (a.length < 2) {
                    err(s, "Usage: /areatitle preview <name>");
                    return true;
                }
                Region r = get(s, a[1]);
                if (r == null) return true;
                show(p, r);
            }
            default -> usage(s);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        if (!s.hasPermission("areatitle.admin")) return List.of();
        if (a.length == 1) {
            String t = a[0].toLowerCase();
            return SUBS.stream().filter(x -> x.startsWith(t)).toList();
        }
        if (a.length == 2 && List.of("delete", "settitle", "setsubtitle", "settimes", "info", "preview")
                .contains(a[0].toLowerCase())) {
            String t = a[1].toLowerCase();
            return regions.values().stream().map(r -> r.name).filter(x -> x.toLowerCase().startsWith(t)).toList();
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("create")) return List.of("fullheight");
        return List.of();
    }
}
