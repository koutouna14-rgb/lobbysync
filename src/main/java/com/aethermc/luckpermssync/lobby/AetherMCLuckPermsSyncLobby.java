package com.aethermc.luckpermssync.lobby;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.context.ImmutableContextSet;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.group.GroupManager;
import net.luckperms.api.model.user.User;
import net.luckperms.api.model.user.UserManager;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeBuilder;
import net.luckperms.api.node.NodeType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LOBBY side. Pulls groups and player memberships from AetherMCLuckPermsSyncMain and applies them
 * to this server's LuckPerms through the LuckPerms API.
 */
public final class AetherMCLuckPermsSyncLobby extends JavaPlugin implements Listener {

    private LuckPerms lp;
    private String host;
    private int port;
    private String key;
    private int timeoutMs;
    private boolean syncUserPerms;
    private boolean deleteExtraGroups;

    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean groupsReady = false;
    private volatile boolean lastFailed = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        key = getConfig().getString("key", "");
        if (key.length() < 16 || key.startsWith("CHANGE_ME")) {
            getLogger().severe("Set the same long random 'key' as the main server in config.yml, then restart. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        RegisteredServiceProvider<LuckPerms> reg = getServer().getServicesManager().getRegistration(LuckPerms.class);
        if (reg == null) {
            getLogger().severe("LuckPerms was not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        lp = reg.getProvider();

        host = getConfig().getString("main-host", "127.0.0.1");
        port = getConfig().getInt("main-port", 25599);
        timeoutMs = getConfig().getInt("timeout-ms", 3000);
        syncUserPerms = getConfig().getBoolean("sync-user-permissions", false);
        deleteExtraGroups = getConfig().getBoolean("delete-extra-groups", true);
        long ticks = Math.max(30, getConfig().getInt("interval-seconds", 60)) * 20L;

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, this::scheduleSync, 40L, ticks);
    }

    /** Runs on the main thread: collect online players, then do the network work async. */
    private void scheduleSync() {
        List<UUID> ids = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) ids.add(p.getUniqueId());
        getServer().getScheduler().runTaskAsynchronously(this, () -> runSync(ids));
    }

    private void runSync(List<UUID> players) {
        lock.lock();
        try {
            syncGroups();
            for (UUID id : players) syncUser(id);
            if (lastFailed) {
                getLogger().info("Connection to the main server restored.");
                lastFailed = false;
            }
        } catch (Exception e) {
            if (!lastFailed) getLogger().warning("Sync failed (will keep retrying): " + e.getMessage());
            lastFailed = true;
        } finally {
            lock.unlock();
        }
    }

    /** Sync a player right before they join so their groups are ready on login. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        try {
            if (!groupsReady) {
                lock.lock();
                try {
                    if (!groupsReady) syncGroups();
                } finally {
                    lock.unlock();
                }
            }
            syncUser(e.getUniqueId());
        } catch (Exception ex) {
            getLogger().warning("Could not sync " + e.getName() + " on join: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ groups

    private void syncGroups() throws IOException {
        JsonObject root = get("/groups");
        JsonObject groups = root.getAsJsonObject("groups");
        if (groups == null || groups.size() == 0) {
            throw new IOException("main server returned no groups (refusing to wipe the lobby)");
        }

        GroupManager gm = lp.getGroupManager();
        Set<String> wanted = new HashSet<>();

        for (Map.Entry<String, JsonElement> entry : groups.entrySet()) {
            String name = entry.getKey().toLowerCase(Locale.ROOT);
            wanted.add(name);
            List<Node> nodes = decode(entry.getValue().getAsJsonArray());

            Group existing = gm.createAndLoadGroup(name).join();
            if (new HashSet<>(existing.data().toCollection()).equals(new HashSet<>(nodes))) continue;

            gm.modifyGroup(name, g -> {
                g.data().clear();
                for (Node n : nodes) g.data().add(n);
            }).join();
        }

        if (deleteExtraGroups) {
            gm.loadAllGroups().join();
            for (Group g : new ArrayList<>(gm.getLoadedGroups())) {
                String name = g.getName().toLowerCase(Locale.ROOT);
                if (name.equals("default") || wanted.contains(name)) continue;
                gm.deleteGroup(g).join();
            }
        }
        groupsReady = true;
    }

    // ------------------------------------------------------------------ users

    private void syncUser(UUID id) throws IOException {
        JsonObject root = get("/user/" + id);
        List<Node> synced = decode(root.getAsJsonArray("nodes"));

        UserManager um = lp.getUserManager();
        User current = um.loadUser(id).join();

        Set<Node> target = new LinkedHashSet<>();
        if (!syncUserPerms) {
            // keep the lobby player's non-group nodes, replace only the group memberships
            for (Node n : current.data().toCollection()) {
                if (!NodeType.INHERITANCE.matches(n)) target.add(n);
            }
            for (Node n : synced) {
                if (NodeType.INHERITANCE.matches(n)) target.add(n);
            }
        } else {
            target.addAll(synced);
        }

        if (new HashSet<>(current.data().toCollection()).equals(target)) return;

        um.modifyUser(id, user -> {
            user.data().clear();
            for (Node n : target) user.data().add(n);
        }).join();
    }

    // ------------------------------------------------------------------ http + json

    private JsonObject get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("X-Sync-Key", key);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("main server answered HTTP " + code);
        try (Reader r = new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        } finally {
            c.disconnect();
        }
    }

    private static List<Node> decode(JsonArray arr) {
        List<Node> out = new ArrayList<>();
        if (arr == null) return out;
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            NodeBuilder<?, ?> b = Node.builder(o.get("key").getAsString());
            b.value(o.get("value").getAsBoolean());
            if (o.has("expiry")) b.expiry(o.get("expiry").getAsLong());
            if (o.has("contexts")) {
                ImmutableContextSet.Builder cb = ImmutableContextSet.builder();
                for (JsonElement p : o.getAsJsonArray("contexts")) {
                    JsonArray pair = p.getAsJsonArray();
                    cb.add(pair.get(0).getAsString(), pair.get(1).getAsString());
                }
                b.context(cb.build());
            }
            out.add(b.build());
        }
        return out;
    }

    // ------------------------------------------------------------------ command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("aethermcluckpermssync.admin")) {
            sender.sendMessage("You do not have permission to use this command.");
            return true;
        }
        List<UUID> ids = new ArrayList<>();
        for (Player p : getServer().getOnlinePlayers()) ids.add(p.getUniqueId());
        sender.sendMessage("Syncing LuckPerms from the main server...");
        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            runSync(ids);
            sender.sendMessage(lastFailed ? "Sync failed - check the console." : "Sync finished.");
        });
        return true;
    }
}
