package uz.jigar.superauth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.*;

public final class SuperAuth extends JavaPlugin implements Listener {

    private final Set<UUID> authenticated = new HashSet<>();
    private final Map<UUID, Long> joinTime = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>(); // IP -> Session

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);

        // Timer: Kirishni eslatib turish va Vaqt o'tsa KICK qilish
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                int maxTime = getConfig().getInt("settings.login-timeout-seconds", 60) * 1000;

                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!authenticated.contains(p.getUniqueId())) {
                        Long joined = joinTime.get(p.getUniqueId());
                        if (joined != null && (now - joined) > maxTime) {
                            p.kickPlayer(msg("messages.timeout-kick"));
                            continue;
                        }

                        // Title & Chat Reminder
                        boolean registered = isRegistered(p.getName());
                        if (registered) {
                            p.sendTitle(color("&e&lKIRISH"), color("&f/login <parol>"), 0, 40, 10);
                            p.sendMessage(msg("messages.please-login"));
                        } else {
                            p.sendTitle(color("&a&lRO'YXATDAN O'TISH"), color("&f/register <parol> <parol>"), 0, 40, 10);
                            p.sendMessage(msg("messages.please-register"));
                        }
                    }
                }
            }
        }.runTaskTimer(this, 40L, 100L); // Har 5 soniyada eslatadi

        getLogger().info("SuperAuth 1.0.0 muvaffaqiyatli ishga tushdi!");
    }

    @Override
    public void onDisable() {
        authenticated.clear();
        joinTime.clear();
    }

    // ==========================================
    // PLAYER EVENTS & RESTRICTIONS
    // ==========================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "";
        
        // Session Auto-login tekshiruvi
        if (sessions.containsKey(ip)) {
            Session s = sessions.get(ip);
            long timeout = getConfig().getInt("settings.session-timeout-minutes", 10) * 60_000L;
            if (s.playerName.equalsIgnoreCase(p.getName()) && (System.currentTimeMillis() - s.timestamp) < timeout) {
                authenticated.add(p.getUniqueId());
                p.sendMessage(msg("messages.session-restored"));
                return;
            }
        }

        joinTime.put(p.getUniqueId(), System.currentTimeMillis());
        p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 999999, 1, false, false));
        p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 999999, 5, false, false));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (authenticated.contains(p.getUniqueId())) {
            String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "";
            if (!ip.isEmpty()) {
                sessions.put(ip, new Session(p.getName(), System.currentTimeMillis()));
            }
        }
        authenticated.remove(p.getUniqueId());
        joinTime.remove(p.getUniqueId());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!authenticated.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
                event.setTo(event.getFrom()); // Harakatni cheklash
            }
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        if (!authenticated.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(msg("messages.please-login"));
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!authenticated.contains(event.getPlayer().getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            if (!msg.startsWith("/login") && !msg.startsWith("/l ") &&
                !msg.startsWith("/register") && !msg.startsWith("/reg ")) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(msg("messages.please-login"));
            }
        }
    }

    @EventHandler public void onBreak(BlockBreakEvent e) { if (!authenticated.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onPlace(BlockPlaceEvent e) { if (!authenticated.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDrop(PlayerDropItemEvent e) { if (!authenticated.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onPickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && !authenticated.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onDamage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && !authenticated.contains(p.getUniqueId())) e.setCancelled(true); }
    @EventHandler public void onAttack(EntityDamageByEntityEvent e) { if (e.getDamager() instanceof Player p && !authenticated.contains(p.getUniqueId())) e.setCancelled(true); }

    // ==========================================
    // COMMAND HANDLING
    // ==========================================

    @Override
    public boolean onCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Bu buyruq faqat o'yinchilar uchun!");
            return true;
        }

        String cmd = command.getName().toLowerCase();

        // /register <pass> <pass2>
        if (cmd.equals("register") || cmd.equals("reg")) {
            if (authenticated.contains(p.getUniqueId())) {
                p.sendMessage(msg("messages.already-logged-in"));
                return true;
            }
            if (isRegistered(p.getName())) {
                p.sendMessage(msg("messages.please-login"));
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(color("&cIshlatish: /register <parol> <parolni_takrorlang>"));
                return true;
            }
            if (!args[0].equals(args[1])) {
                p.sendMessage(msg("messages.passwords-dont-match"));
                return true;
            }
            int minLen = getConfig().getInt("settings.min-password-length", 4);
            if (args[0].length() < minLen) {
                p.sendMessage(msg("messages.password-too-short"));
                return true;
            }

            // Parolni shifrlab saqlash
            savePassword(p.getName(), args[0]);
            lo