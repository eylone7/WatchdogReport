package com.example.watchdogpaper;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.logging.Level;

public class WatchdogPaper extends JavaPlugin implements Listener {

    private Connection dbConnection;
    private final Set<String> validReasons = new HashSet<>(Arrays.asList(
            "Chat Abuse", "Cheating (Hacks)", "Bad Name", "Bad Skin", "Other",
            "Bug Abuse", "Pet Name Abuse", "Boosting / Exploits"
    ));
    private final Map<Player, String> reportSelections = new HashMap<>();

    // Punishment types
    private enum PunishmentType {
        BAN, TEMPBAN, IPBAN, TEMPIPBAN, MUTE, TEMPMUTE, WARN, TEMPWARN, NOTE, KICK
    }

    @Override
    public void onEnable() {
        connectToDatabase();
        if (dbConnection == null) {
            getLogger().severe("Failed to connect to the database. Check your credentials!");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        createTables();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("report").setExecutor(new ReportCommand());
        getCommand("watchdogreport-accept").setExecutor(new AcceptCommand());
        getCommand("watchdoglist").setExecutor(new ListCommand());

        // Register punishment commands
        getCommand("ban").setExecutor(new BanCommand());
        getCommand("tempban").setExecutor(new TempBanCommand());
        getCommand("mute").setExecutor(new MuteCommand());
        getCommand("tempmute").setExecutor(new TempMuteCommand());
        getCommand("warn").setExecutor(new WarnCommand());
        getCommand("kick").setExecutor(new KickCommand());
        getCommand("unban").setExecutor(new UnBanCommand());
        getCommand("unmute").setExecutor(new UnMuteCommand());
        getCommand("history").setExecutor(new HistoryCommand());

        // Start announcement task
        startAnnouncementTask();

        getLogger().info("WatchdogReport has been enabled!");
    }

    @Override
    public void onDisable() {
        if (dbConnection != null) {
            try {
                dbConnection.close();
            } catch (SQLException e) {
                getLogger().log(Level.WARNING, "Error closing database connection", e);
            }
        }
        getLogger().info("WatchdogReport has been disabled!");
    }

    private void connectToDatabase() {
        try {
            String url = "jdbc:mysql://host/db_831273?user=username&password=pass&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
            dbConnection = DriverManager.getConnection(url);
            getLogger().info("Successfully connected to MySQL database.");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to connect to MySQL", e);
            dbConnection = null;
        }
    }

    private void createTables() {
        createReportsTable();
        createPunishmentsTable();
    }

    private void createReportsTable() {
        PreparedStatement stmt = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS reports (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "reporter VARCHAR(16) NOT NULL, " +
                            "reported VARCHAR(16) NOT NULL, " +
                            "reason TEXT NOT NULL, " +
                            "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                            "status VARCHAR(20) DEFAULT 'pending'" +
                            ")"
            );
            stmt.executeUpdate();
            getLogger().info("Reports table created/verified.");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to create reports table", e);
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
    }

    private void createPunishmentsTable() {
        PreparedStatement stmt = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS punishments (" +
                            "id INT AUTO_INCREMENT PRIMARY KEY, " +
                            "player_name VARCHAR(16) NOT NULL, " +
                            "player_uuid VARCHAR(36), " +
                            "player_ip VARCHAR(45), " +
                            "punishment_type VARCHAR(20) NOT NULL, " +
                            "reason TEXT NOT NULL, " +
                            "operator VARCHAR(16) NOT NULL, " +
                            "duration BIGINT, " + // in milliseconds, NULL for permanent
                            "start_time BIGINT NOT NULL, " +
                            "end_time BIGINT, " + // NULL for permanent
                            "active BOOLEAN DEFAULT TRUE, " +
                            "silent BOOLEAN DEFAULT FALSE" +
                            ")"
            );
            stmt.executeUpdate();
            getLogger().info("Punishments table created/verified.");
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to create punishments table", e);
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
    }

    private void sendMessage(CommandSender sender, String message) {
        sender.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    private void broadcastMessage(String message) {
        Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', message));
    }

    // =====================
    // Time Formatting
    // =====================
    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return String.format("%d day(s) %d hour(s) %d minute(s) and %d second(s)",
                    days, hours % 24, minutes % 60, seconds % 60);
        } else if (hours > 0) {
            return String.format("%d hour(s) %d minute(s) and %d second(s)",
                    hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return String.format("%d minute(s) and %d second(s)", minutes, seconds % 60);
        } else {
            return String.format("%d second(s)", seconds);
        }
    }

    private long parseDuration(String durationStr) {
        try {
            if (durationStr.startsWith("#")) {
                // Handle time layouts (simplified)
                return parseTimeLayout(durationStr.substring(1));
            }

            long total = 0;
            String[] parts = durationStr.split("(?<=[smhd])");
            for (String part : parts) {
                if (part.length() < 2) continue;

                char unit = part.charAt(part.length() - 1);
                long value = Long.parseLong(part.substring(0, part.length() - 1));

                switch (unit) {
                    case 's': total += value * 1000; break;
                    case 'm': total += value * 60 * 1000; break;
                    case 'h': total += value * 60 * 60 * 1000; break;
                    case 'd': total += value * 24 * 60 * 60 * 1000; break;
                }
            }
            return total;
        } catch (Exception e) {
            return -1;
        }
    }

    private long parseTimeLayout(String layout) {
        // Simplified time layout parser
        long total = 0;
        if (layout.contains("d")) {
            String[] daysPart = layout.split("d");
            if (daysPart.length > 0) {
                total += Long.parseLong(daysPart[0]) * 24 * 60 * 60 * 1000;
            }
        }
        // Add more parsing logic as needed
        return total;
    }

    // =====================
    // Punishment Management
    // =====================
    private boolean addPunishment(String playerName, String playerUUID, String playerIP,
                                  PunishmentType type, String reason, String operator,
                                  long duration, boolean silent) {
        PreparedStatement stmt = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "INSERT INTO punishments (player_name, player_uuid, player_ip, punishment_type, " +
                            "reason, operator, duration, start_time, end_time, silent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            );

            long startTime = System.currentTimeMillis();
            long endTime = (duration > 0) ? startTime + duration : 0;

            stmt.setString(1, playerName);
            stmt.setString(2, playerUUID);
            stmt.setString(3, playerIP);
            stmt.setString(4, type.name());
            stmt.setString(5, reason);
            stmt.setString(6, operator);
            if (duration > 0) {
                stmt.setLong(7, duration);
                stmt.setLong(8, startTime);
                stmt.setLong(9, endTime);
            } else {
                stmt.setNull(7, Types.BIGINT);
                stmt.setLong(8, startTime);
                stmt.setNull(9, Types.BIGINT);
            }
            stmt.setBoolean(10, silent);

            return stmt.executeUpdate() > 0;
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to add punishment", e);
            return false;
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
    }

    private boolean isPlayerBanned(String playerName) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "SELECT * FROM punishments WHERE player_name = ? AND punishment_type IN ('BAN', 'TEMPBAN') AND active = TRUE AND (end_time IS NULL OR end_time > ?)"
            );
            stmt.setString(1, playerName);
            stmt.setLong(2, System.currentTimeMillis());
            rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to check ban status", e);
            return false;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing result set", e);
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
    }

    private boolean isPlayerMuted(String playerName) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "SELECT * FROM punishments WHERE player_name = ? AND punishment_type IN ('MUTE', 'TEMPMUTE') AND active = TRUE AND (end_time IS NULL OR end_time > ?)"
            );
            stmt.setString(1, playerName);
            stmt.setLong(2, System.currentTimeMillis());
            rs = stmt.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to check mute status", e);
            return false;
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing result set", e);
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
    }

    private int getRecentBansCount(long sinceTime) {
        PreparedStatement stmt = null;
        ResultSet rs = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "SELECT COUNT(*) as count FROM punishments WHERE punishment_type IN ('BAN', 'TEMPBAN') AND start_time > ?"
            );
            stmt.setLong(1, sinceTime);
            rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getInt("count");
            }
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to get recent bans count", e);
        } finally {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing result set", e);
                }
            }
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
        return 0;
    }

    // =====================
    // Announcement System
    // =====================
    private void startAnnouncementTask() {
        Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                broadcastAnnouncement();
            }
        }, 36000L, 36000L); // 30 minutes = 36000 ticks (20 ticks/second * 60 seconds * 30)
    }

    private void broadcastAnnouncement() {
        long sevenDaysAgo = System.currentTimeMillis() - (7L * 24 * 60 * 60 * 1000);
        int totalBans = getRecentBansCount(sevenDaysAgo);

        broadcastMessage("&f");
        broadcastMessage("&4[WATCHDOG ANNOUNCEMENT]");
        broadcastMessage("&fWatchdog has banned &c&l" + totalBans + " &fplayers in the last 7 days.");
        broadcastMessage("&fStaff have banned an additional &c&l" + (totalBans / 2) + " &fin the last 7 days."); // Example calculation
        broadcastMessage("&fBlacklisted modifications are a bannable offense!");
        broadcastMessage("&f");
    }

    // =====================
    // Event Handlers
    // =====================
    @EventHandler
    public void onPlayerLogin(PlayerLoginEvent event) {
        Player player = event.getPlayer();
        if (isPlayerBanned(player.getName())) {
            event.setResult(PlayerLoginEvent.Result.KICK_BANNED);
            event.setKickMessage(ChatColor.translateAlternateColorCodes('&',
                    "&cYou are permanently banned from this server!\n" +
                            "&7\n" +
                            "&7Reason: &fViolation of server rules.\n" +
                            "&7Find out more: &b&nhttps://www.farepixel.fun/appeal\n" +
                            "&7\n" +
                            "&7Ban ID: &f#0001\n" +
                            "&7Sharing your Ban ID may affect the processing of your appeal"
            ));
        }
    }

    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (isPlayerMuted(player.getName())) {
            event.setCancelled(true);
            sendMessage(player,
                    "&cYou are permanently muted from this server!\n" +
                            "&7\n" +
                            "&7Reason: &fChat violation.\n" +
                            "&7Find out more: &b&nhttps://www.farepixel.fun/appeal"
            );
        }
    }

    // =====================
    // Report Menu (GUI)
    // =====================
    private void openReportMenu(Player player, String reportedName) {
        Inventory menu = Bukkit.createInventory(null, 54, "Report Menu");

        // Player head (top center)
        ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
        skullMeta.setOwner(reportedName);
        skullMeta.setDisplayName(ChatColor.YELLOW + "/reporing " + reportedName);
        skullMeta.setLore(Arrays.asList(ChatColor.GRAY + "Select a reason below"));
        head.setItemMeta(skullMeta);
        menu.setItem(4, head);

        // Report reason items
        menu.setItem(20, createMenuItem(Material.BOOK_AND_QUILL, ChatColor.GREEN + "Chat Abuse", (short) 0));
        menu.setItem(21, createMenuItem(Material.DIAMOND_SWORD, ChatColor.GREEN + "Cheating (Hacks)", (short) 0));
        menu.setItem(22, createMenuItem(Material.PAPER, ChatColor.GREEN + "Bad Name", (short) 0));
        menu.setItem(23, createMenuItem(Material.BANNER, ChatColor.GREEN + "Bad Skin", (short) 0));
        menu.setItem(24, createMenuItem(Material.COMPASS, ChatColor.GREEN + "Other", (short) 0));

        // Second row
        menu.setItem(29, createMenuItem(Material.LEATHER, ChatColor.GREEN + "Bug Abuse", (short) 0));
        menu.setItem(30, createMenuItem(Material.MONSTER_EGG, ChatColor.GREEN + "Bad Pet name", (short) 97));
        menu.setItem(31, createMenuItem(Material.TNT, ChatColor.GREEN + "Boosting / Exploits", (short) 0));
        menu.setItem(48, createMenuItem(Material.BOOK, ChatColor.GREEN + "Report Info", (short) 0));
        menu.setItem(49, createCloseItem());

        player.openInventory(menu);
        reportSelections.put(player, reportedName);
    }

    private ItemStack createMenuItem(Material material, String name, short durability) {
        ItemStack item = new ItemStack(material, 1, durability);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList("" + ChatColor.RED + "Abuse may result in punishment!"));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCloseItem() {
        ItemStack close = new ItemStack(Material.BARRIER);
        ItemMeta meta = close.getItemMeta();
        meta.setDisplayName(ChatColor.RED + "Close " + ChatColor.GRAY + "(#0166)");
        meta.setLore(Arrays.asList(ChatColor.GRAY + ""));
        close.setItemMeta(meta);
        return close;
    }

    // =====================
    // Confirmation Menu
    // =====================
    private void openConfirmationMenu(Player player, String reportedName, String reason) {
        Inventory confirmMenu = Bukkit.createInventory(null, 9 * 3, "Confirm Report");

        ItemStack confirm = new ItemStack(Material.STAINED_CLAY, 1, (short) 13);
        ItemMeta confirmMeta = confirm.getItemMeta();
        confirmMeta.setDisplayName(ChatColor.GREEN + "Submit Report");
        confirm.setItemMeta(confirmMeta);
        confirmMenu.setItem(11, confirm);

        ItemStack cancel = new ItemStack(Material.STAINED_CLAY, 1, (short) 14);
        ItemMeta cancelMeta = cancel.getItemMeta();
        cancelMeta.setDisplayName(ChatColor.RED + "Cancel Report");
        cancel.setItemMeta(cancelMeta);
        confirmMenu.setItem(15, cancel);

        // Player head center
        ItemStack head = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta headMeta = (SkullMeta) head.getItemMeta();
        headMeta.setOwner(reportedName);
        headMeta.setDisplayName(ChatColor.YELLOW + "/Reported " + reportedName);
        headMeta.setLore(Arrays.asList(ChatColor.GRAY + "Reason: " + ChatColor.GREEN + reason));
        head.setItemMeta(headMeta);
        confirmMenu.setItem(13, head);

        player.openInventory(confirmMenu);
        reportSelections.put(player, reportedName + ":" + reason);
    }

    // =====================
    // Click Handling
    // =====================
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        Inventory clickedInventory = event.getClickedInventory();
        if (clickedInventory == null) return;

        String title = event.getView().getTitle();

        if (title.equals("Report Menu")) {
            event.setCancelled(true);
            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;

            String reason = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            if (validReasons.contains(reason)) {
                String reportedName = reportSelections.get(player);
                openConfirmationMenu(player, reportedName, reason);
            } else if (item.getType() == Material.BARRIER) {
                player.closeInventory();
            }
        } else if (title.equals("Confirm Report")) {
            event.setCancelled(true);
            ItemStack item = event.getCurrentItem();
            if (item == null || item.getType() == Material.AIR) return;

            String selection = reportSelections.get(player);
            if (selection == null) {
                player.closeInventory();
                return;
            }
            String[] parts = selection.split(":");
            if (parts.length < 2) {
                player.closeInventory();
                return;
            }

            String reportedName = parts[0];
            String reason = parts[1];

            if (item.getType() == Material.STAINED_CLAY) {
                short durability = item.getDurability();
                if (durability == 13) {
                    submitReport(player, reportedName, reason);
                    player.closeInventory();
                } else if (durability == 14) {
                    sendMessage(player, "&f[WATCHDOG] &cReport cancelled.");
                    player.closeInventory();
                }
            }
        }
    }

    // =====================
    // Report Database Handling - FIXED VERSION
    // =====================
    private void submitReport(Player player, String reportedName, String reason) {
        // Check database connection first
        if (dbConnection == null) {
            sendMessage(player, "&cDatabase connection is not available. Please contact an administrator.");
            getLogger().severe("Database connection is null when trying to submit report!");
            return;
        }

        PreparedStatement stmt = null;
        try {
            stmt = dbConnection.prepareStatement(
                    "INSERT INTO reports (reporter, reported, reason, timestamp, status) VALUES (?, ?, ?, NOW(), 'pending')"
            );
            stmt.setString(1, player.getName());
            stmt.setString(2, reportedName);
            stmt.setString(3, reason);
            int affectedRows = stmt.executeUpdate();

            if (affectedRows > 0) {
                // Success - send messages
                String message = "&f[WATCHDOG] &e" + player.getName() + " &ahas reported &e" + reportedName + " &afor &e[" + reason + "]";
                for (Player p : getServer().getOnlinePlayers()) {
                    if (p.hasPermission("farepixel.admin") || p.hasPermission("watchdog.admin")) {
                        sendMessage(p, message);
                    }
                }
                sendMessage(player, "&f[WATCHDOG] &aReport submitted! Make sure to open a report at &b&nhttps://farepixel.net/report&r");
                sendMessage(player, "&f[WATCHDOG] &c&oWarning! Abuse of this command is punishable.");

                getLogger().info("Report submitted: " + player.getName() + " reported " + reportedName + " for " + reason);
            } else {
                sendMessage(player, "&cFailed to save report to database.");
                getLogger().warning("No rows affected when inserting report for " + reportedName);
            }

        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Database insert error for report", e);
            sendMessage(player, "&cDatabase error! Report not saved. Error: " + e.getMessage());
        } finally {
            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing statement", e);
                }
            }
        }
    }

    // =====================
    // Commands
    // =====================
    private class ReportCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sendMessage(sender, "&cThis command can only be used by players!");
                return true;
            }
            Player player = (Player) sender;

            if (args.length != 1) {
                sendMessage(player, "&cUse: /report <username>");
                return true;
            }

            String reportedName = args[0];
            if (reportedName.equalsIgnoreCase(player.getName())) {
                sendMessage(player, "&f[WATCHDOG] &cYou can't report yourself!");
                return true;
            }

            // Check if database is connected
            if (dbConnection == null) {
                sendMessage(player, "&cDatabase connection is not available. Please try again later.");
                getLogger().warning("Database connection is null when player tried to report!");
                return true;
            }

            openReportMenu(player, reportedName);
            return true;
        }
    }

    private class AcceptCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("farepixel.mod")) {
                sendMessage(sender, "&cYou're not the rank of this command! You need to be admin or higher!");
                return true;
            }

            if (args.length < 2) {
                sendMessage(sender, "&cUse: /watchdogreport-accept <reporter> <reported>");
                return true;
            }

            String reporter = args[0];
            String reported = args[1];

            PreparedStatement stmt = null;
            try {
                stmt = dbConnection.prepareStatement(
                        "UPDATE reports SET status = 'accepted' WHERE reporter = ? AND reported = ? AND status = 'pending'");
                stmt.setString(1, reporter);
                stmt.setString(2, reported);
                int rows = stmt.executeUpdate();
                if (rows == 0) {
                    sendMessage(sender, "&cNo pending report found for " + reporter + " against " + reported);
                    return true;
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Database update error", e);
                sendMessage(sender, "&cDatabase error!");
                return true;
            } finally {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Error closing statement", e);
                    }
                }
            }

            for (Player p : getServer().getOnlinePlayers()) {
                if (p.getName().equalsIgnoreCase(reporter)) {
                    sendMessage(p, "&f[WATCHDOG] &aYour report against " + reported + " has been addressed! Thanks for your help!");
                    break;
                }
            }

            sendMessage(sender, "&f[WATCHDOG] &aAccepted " + reporter + "'s report against " + reported + ".");
            return true;
        }
    }

    private class ListCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("farepixel.admin")) {
                sendMessage(sender, "&cYou don't have permission to view reports!");
                return true;
            }

            if (dbConnection == null) {
                sendMessage(sender, "&c[WATCHDOG] Database connection is not available!");
                return true;
            }

            PreparedStatement stmt = null;
            ResultSet rs = null;
            try {
                stmt = dbConnection.prepareStatement(
                        "SELECT id, reporter, reported, reason, timestamp FROM reports WHERE status = 'pending' ORDER BY timestamp DESC LIMIT 20");
                rs = stmt.executeQuery();
                if (!rs.next()) {
                    sendMessage(sender, "&f[WATCHDOG] No pending reports.");
                    return true;
                }

                sendMessage(sender, "&f[WATCHDOG] Pending reports (latest first):");
                do {
                    int id = rs.getInt("id");
                    String reporter = rs.getString("reporter");
                    String reported = rs.getString("reported");
                    String reason = rs.getString("reason");
                    Timestamp timestamp = rs.getTimestamp("timestamp");
                    String line = "&eID: " + id + " &7- &e" + reporter + " &areported &e" + reported + " &afor &e[" + reason + "] &aat &e" + timestamp;
                    sendMessage(sender, line);
                } while (rs.next());
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Database query error", e);
                sendMessage(sender, "&cDatabase error!");
                return true;
            } finally {
                if (rs != null) {
                    try {
                        rs.close();
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Error closing result set", e);
                    }
                }
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Error closing statement", e);
                    }
                }
            }
            return true;
        }
    }

    private class BanCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.ban")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 2) {
                sendMessage(sender, "&cUsage: &7&o/ban (-s) [Name] [Reason/@Layout]");
                return true;
            }

            boolean silent = args[0].equalsIgnoreCase("-s");
            int startIndex = silent ? 1 : 0;

            if (args.length < startIndex + 2) {
                sendMessage(sender, "&cUsage: &7&o/ban (-s) [Name] [Reason/@Layout]");
                return true;
            }

            String playerName = args[startIndex];
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();

            if (isPlayerBanned(playerName)) {
                sendMessage(sender, "&c" + playerName + " is already been banned!");
                return true;
            }

            if (addPunishment(playerName, null, null, PunishmentType.BAN, reason,
                    sender.getName(), 0, silent)) {
                sendMessage(sender, "&c" + playerName + " was successfully banned!");

                if (!silent) {
                    String message = "&c&l&n" + playerName + " &cgot banned by &l" + sender.getName() + " &cFor " + reason + " permanently";
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                }

                // Kick player if online
                Player target = getServer().getPlayer(playerName);
                if (target != null) {
                    target.kickPlayer(ChatColor.translateAlternateColorCodes('&',
                            "&cYou are permanently banned from this server!\n" +
                                    "&7\n" +
                                    "&7Reason: &f" + reason + "\n" +
                                    "&7Find out more: &b&nhttps://www.farepixel.fun/appeal\n" +
                                    "&7\n" +
                                    "&7Ban ID: &f#0001\n" +
                                    "&7Sharing your Ban ID may affect the processing of your appeal"
                    ));
                }
            } else {
                sendMessage(sender, "&cFailed to ban " + playerName);
            }

            return true;
        }
    }

    private class TempBanCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.tempban")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 3) {
                sendMessage(sender, "&cUsage: /tempban (-s) [Name] [Xmo/Xd/Xh/Xm/Xs/#TimeLayout] [Reason/@Layout]");
                return true;
            }

            boolean silent = args[0].equalsIgnoreCase("-s");
            int startIndex = silent ? 1 : 0;

            if (args.length < startIndex + 3) {
                sendMessage(sender, "&cUsage: /tempban (-s) [Name] [Xmo/Xd/Xh/Xm/Xs/#TimeLayout] [Reason/@Layout]");
                return true;
            }

            String playerName = args[startIndex];
            String durationStr = args[startIndex + 1];
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = startIndex + 2; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();

            long duration = parseDuration(durationStr);
            if (duration <= 0) {
                sendMessage(sender, "&cInvalid duration format!");
                return true;
            }

            if (addPunishment(playerName, null, null, PunishmentType.TEMPBAN, reason,
                    sender.getName(), duration, silent)) {
                String formattedDuration = formatDuration(duration);
                sendMessage(sender, "&c" + playerName + " was successfully temp-banned for " + formattedDuration + "!");

                if (!silent) {
                    String message = "&c&l&n" + playerName + " &cgot banned by &l" + sender.getName() +
                            " &cFor " + reason + " For &f" + formattedDuration;
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                }

                // Kick player if online
                Player target = getServer().getPlayer(playerName);
                if (target != null) {
                    target.kickPlayer(ChatColor.translateAlternateColorCodes('&',
                            "&c You are temporarily banned for &f" + formattedDuration + " &cfrom this server!\n" +
                                    "&7\n" +
                                    "&7Reason: &f" + reason + "\n" +
                                    "&7Find out more: &b&nhttps://www.farepixel.fun/appeal\n" +
                                    "&7\n" +
                                    "&7Ban ID: &f#0001\n" +
                                    "&7Sharing your Ban ID may affect the processing of your appeal"
                    ));
                }
            } else {
                sendMessage(sender, "&cFailed to temp-ban " + playerName);
            }

            return true;
        }
    }

    private class MuteCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.mute")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 2) {
                sendMessage(sender, "&cUsage: &7&o/mute (-s) [Name] [Reason/@Layout]");
                return true;
            }

            boolean silent = args[0].equalsIgnoreCase("-s");
            int startIndex = silent ? 1 : 0;

            if (args.length < startIndex + 2) {
                sendMessage(sender, "&cUsage: &7&o/mute (-s) [Name] [Reason/@Layout]");
                return true;
            }

            String playerName = args[startIndex];
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();

            if (isPlayerMuted(playerName)) {
                sendMessage(sender, "&c&n" + playerName + " &chas been already been muted!");
                return true;
            }

            if (addPunishment(playerName, null, null, PunishmentType.MUTE, reason,
                    sender.getName(), 0, silent)) {
                sendMessage(sender, "&c" + playerName + " was successfully muted!");

                if (!silent) {
                    String message = "&c&l&n" + playerName + " &cgot muted permanently by &l" + sender.getName() + " &cFor " + reason;
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                }
            } else {
                sendMessage(sender, "&cFailed to mute " + playerName);
            }

            return true;
        }
    }

    private class TempMuteCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.tempmute")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 3) {
                sendMessage(sender, "&cUsage: /tempmute (-s) [Name] [Xmo/Xd/Xh/Xm/Xs/#TimeLayout] [Reason/@Layout]");
                return true;
            }

            boolean silent = args[0].equalsIgnoreCase("-s");
            int startIndex = silent ? 1 : 0;

            if (args.length < startIndex + 3) {
                sendMessage(sender, "&cUsage: /tempmute (-s) [Name] [Xmo/Xd/Xh/Xm/Xs/#TimeLayout] [Reason/@Layout]");
                return true;
            }

            String playerName = args[startIndex];
            String durationStr = args[startIndex + 1];
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = startIndex + 2; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();

            long duration = parseDuration(durationStr);
            if (duration <= 0) {
                sendMessage(sender, "&cInvalid duration format!");
                return true;
            }

            if (addPunishment(playerName, null, null, PunishmentType.TEMPMUTE, reason,
                    sender.getName(), duration, silent)) {
                String formattedDuration = formatDuration(duration);
                sendMessage(sender, "&c" + playerName + " was successfully temp-muted for " + formattedDuration + "!");

                if (!silent) {
                    String message = "&c&l&n" + playerName + " &cgot muted by &l" + sender.getName() +
                            " &cFor " + reason + " For &f" + formattedDuration;
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                }
            } else {
                sendMessage(sender, "&cFailed to temp-mute " + playerName);
            }

            return true;
        }
    }

    private class WarnCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.warn")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 2) {
                sendMessage(sender, "&cUsage: /warn (-s) [Name] [Reason/@Layout]");
                return true;
            }

            boolean silent = args[0].equalsIgnoreCase("-s");
            int startIndex = silent ? 1 : 0;

            if (args.length < startIndex + 2) {
                sendMessage(sender, "&cUsage: /warn (-s) [Name] [Reason/@Layout]");
                return true;
            }

            String playerName = args[startIndex];
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();

            if (addPunishment(playerName, null, null, PunishmentType.WARN, reason,
                    sender.getName(), 0, silent)) {
                sendMessage(sender, "&c" + playerName + " was successfully warned!");

                if (!silent) {
                    String message = "&c&l&n" + playerName + " &cgot warned by &l" + sender.getName() + " &cFor the reason " + reason;
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                }

                // Notify warned player if online
                Player target = getServer().getPlayer(playerName);
                if (target != null) {
                    sendMessage(target,
                            "&cYou received a warning from this server!\n" +
                                    "&7\n" +
                                    "&7Reason: &f" + reason + "\n" +
                                    "&7Find out more: &b&nhttps://www.farepixel.fun/appeal"
                    );
                }
            } else {
                sendMessage(sender, "&cFailed to warn " + playerName);
            }

            return true;
        }
    }

    private class KickCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.kick")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 2) {
                sendMessage(sender, "&cUsage &8» &7&o/kick (-s) [Name] [Reason/@Layout]");
                return true;
            }

            boolean silent = args[0].equalsIgnoreCase("-s");
            int startIndex = silent ? 1 : 0;

            if (args.length < startIndex + 2) {
                sendMessage(sender, "&cUsage &8» &7&o/kick (-s) [Name] [Reason/@Layout]");
                return true;
            }

            String playerName = args[startIndex];
            StringBuilder reasonBuilder = new StringBuilder();
            for (int i = startIndex + 1; i < args.length; i++) {
                reasonBuilder.append(args[i]).append(" ");
            }
            String reason = reasonBuilder.toString().trim();

            Player target = getServer().getPlayer(playerName);
            if (target == null) {
                sendMessage(sender, "&c&o" + playerName + " &7is not online!");
                return true;
            }

            if (addPunishment(playerName, null, null, PunishmentType.KICK, reason,
                    sender.getName(), 0, silent)) {
                sendMessage(sender, "&c&o" + playerName + " &7was successfully kicked!");

                target.kickPlayer(ChatColor.translateAlternateColorCodes('&',
                        "&cYou got kicked from this server!\n" +
                                "&7\n" +
                                "&7Reason: &l" + reason + "\n" +
                                "&7"
                ));
            } else {
                sendMessage(sender, "&cFailed to kick " + playerName);
            }

            return true;
        }
    }

    private class UnBanCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.unban")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 1) {
                sendMessage(sender, "&cUsage: /unban [Name/IP]");
                return true;
            }

            String target = args[0];
            PreparedStatement stmt = null;
            try {
                stmt = dbConnection.prepareStatement(
                        "UPDATE punishments SET active = FALSE WHERE player_name = ? AND punishment_type IN ('BAN', 'TEMPBAN') AND active = TRUE"
                );
                stmt.setString(1, target);
                int rows = stmt.executeUpdate();

                if (rows > 0) {
                    sendMessage(sender, "&a" + target + " was successfully unbanned!");
                    String message = "&e&o" + sender.getName() + " &7unbanned &c&o" + target;
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                } else {
                    sendMessage(sender, "&c&n" + target + " &cis not banned!");
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to unban player", e);
                sendMessage(sender, "&cFailed to unban " + target);
            } finally {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Error closing statement", e);
                    }
                }
            }

            return true;
        }
    }

    private class UnMuteCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.unmute")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 1) {
                sendMessage(sender, "&cUsage: /unmute [Name]");
                return true;
            }

            String target = args[0];
            PreparedStatement stmt = null;
            try {
                stmt = dbConnection.prepareStatement(
                        "UPDATE punishments SET active = FALSE WHERE player_name = ? AND punishment_type IN ('MUTE', 'TEMPMUTE') AND active = TRUE"
                );
                stmt.setString(1, target);
                int rows = stmt.executeUpdate();

                if (rows > 0) {
                    sendMessage(sender, "&c&n" + target + " &7was successfully unmuted!");
                    String message = "&e&o" + sender.getName() + " &7unmuted &c&o" + target;
                    for (Player p : getServer().getOnlinePlayers()) {
                        if (p.hasPermission("watchdog.staff")) {
                            sendMessage(p, message);
                        }
                    }
                } else {
                    sendMessage(sender, "&c&n" + target + " is not muted!");
                }
            } catch (SQLException e) {
                getLogger().log(Level.SEVERE, "Failed to unmute player", e);
                sendMessage(sender, "&cFailed to unmute " + target);
            } finally {
                if (stmt != null) {
                    try {
                        stmt.close();
                    } catch (SQLException e) {
                        getLogger().log(Level.WARNING, "Error closing statement", e);
                    }
                }
            }

            return true;
        }
    }

    private class HistoryCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!sender.hasPermission("watchdog.history")) {
                sendMessage(sender, "&cYou don't have permission for that!");
                return true;
            }

            if (args.length < 1) {
                sendMessage(sender, "&cUsage &8» &7&o/history [Name/IP] <Page>");
                return true;
            }

            String target = args[0];
            int page = 1;
            if (args.length > 1) {
                try {
                    page = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    sendMessage(sender, "&cInvalid page number!");
                    return true;
                }
            }

            // Implementation for history viewing
            sendMessage(sender, "&7History feature will be implemented in next update");
            return true;
        }
    }
}