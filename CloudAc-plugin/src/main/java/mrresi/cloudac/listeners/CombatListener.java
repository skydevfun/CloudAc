package mrresi.cloudac.listeners;

import mrresi.cloudac.CloudAC;
import mrresi.cloudac.ml.AntiCheatAI;
import mrresi.cloudac.utils.RayTraceUtils;
import mrresi.cloudac.utils.SimpleCollisionBox;
import mrresi.cloudac.utils.LanguageManager;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.FluidCollisionMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CombatListener implements Listener {

    private static final String PREFIX = "§x§5§F§E§2§E§2C§x§5§B§D§6§E§1l§x§5§6§C§9§E§0o§x§5§2§B§D§D§Fu§x§4§D§B§1§D§Dd§x§4§9§A§4§D§CA§x§4§4§9§8§D§BC §8┃ §r";
    private static final String DIVIDER = " §8│ ";
    private static final double MAX_RAY_DISTANCE = 6.0;

    private final CloudAC plugin;
    private final AntiCheatAI ai;

    private final Map<UUID, Integer> lastAlertLevel = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> hitboxVL = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> reachVL = new ConcurrentHashMap<>();

    public CombatListener(CloudAC plugin, AntiCheatAI ai) {
        this.plugin = plugin;
        this.ai = ai;
    }

    private static class HitScanResult {
        final boolean hit;
        final double distance;

        HitScanResult(boolean hit, double distance) {
            this.hit = hit;
            this.distance = distance;
        }
    }

    @EventHandler
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player)) return;
        if (!(event.getEntity() instanceof Player)) return;

        Player attacker = (Player) event.getDamager();
        Player target = (Player) event.getEntity();

        ai.recordHit(attacker, target);

        if (attacker.isOp() || attacker.hasPermission("cloudac.bypass")) return;

        if (plugin.getCheckManager().getKillAuraCheck().handleAttack(attacker)) {
            event.setCancelled(true);
            return;
        }

        double roughDist = attacker.getLocation().distance(target.getLocation());
        if (roughDist > MAX_RAY_DISTANCE) {
            event.setCancelled(true);
            return;
        }

        UUID uuid = attacker.getUniqueId();
        ClientVersion version = getClientVersion(attacker);
        List<Vector> lookDirs = buildLookDirs(attacker, version);
        List<Location> eyePositions = buildEyePositions(attacker);

        int ping = plugin.getPacketListener().getPlayerPing(uuid);
        int pingTicks = ping / 50;
        int buffer = plugin.getConfig().getInt("checks.hitbox.ping-buffer-ticks", 2);
        if (!plugin.getConfig().contains("checks.hitbox.ping-buffer-ticks")) {
            buffer = plugin.getConfig().getInt("checks.reach.ping-buffer-ticks", 2);
        }
        int tickWindow = Math.min(pingTicks + buffer, 20);

        Integer attackTick = plugin.getPacketListener().getAttackTick(uuid);
        if (attackTick == null) attackTick = plugin.getPacketListener().getCurrentServerTick();

        Map<Integer, Map<UUID, BoundingBox>> snapshots = plugin.getPacketListener().getServerSnapshots();

        boolean hitboxEnabled = plugin.getConfig().getBoolean("checks.hitbox.enabled", true);
        boolean reachEnabled = plugin.getConfig().getBoolean("checks.reach.enabled", true);
        if (hitboxEnabled || reachEnabled) {
            double hitboxExpand = version.isOlderThan(ClientVersion.V_1_9) ? 0.1 : 0.0;

            boolean hitRealBox = false;
            double bestReach = Double.MAX_VALUE;

            SimpleCollisionBox currentBox = new SimpleCollisionBox(target.getBoundingBox());
            if (hitboxExpand > 0) currentBox.expand(hitboxExpand);

            HitScanResult currentResult = scanBox(eyePositions, lookDirs, currentBox);
            if (currentResult.hit) {
                hitRealBox = true;
                bestReach = currentResult.distance;
            }

            for (int offset = -1; offset <= tickWindow; offset++) {
                int tick = attackTick - offset;
                Map<UUID, BoundingBox> snap = snapshots.get(tick);
                if (snap == null) continue;

                BoundingBox histBoxRaw = snap.get(target.getUniqueId());
                if (histBoxRaw == null) continue;

                SimpleCollisionBox histBox = new SimpleCollisionBox(histBoxRaw);
                if (hitboxExpand > 0) histBox.expand(hitboxExpand);

                HitScanResult histResult = scanBox(eyePositions, lookDirs, histBox);
                if (histResult.hit && histResult.distance < bestReach) {
                    hitRealBox = true;
                    bestReach = histResult.distance;
                }
            }

            if (hitboxEnabled) {
                int vlOnMiss = plugin.getConfig().getInt("checks.hitbox.vl-on-miss", 2);
                int vlDecay = plugin.getConfig().getInt("checks.hitbox.vl-decay-on-hit", 1);
                boolean cancelDamage = plugin.getConfig().getBoolean("checks.hitbox.cancel-damage", true);
                int cancelVl = plugin.getConfig().getInt("checks.hitbox.cancel-vl", 3);
                int alertVl = plugin.getConfig().getInt("checks.hitbox.alert-vl", 5);

                if (!hitRealBox) {
                    int vl = hitboxVL.getOrDefault(uuid, 0) + vlOnMiss;
                    hitboxVL.put(uuid, vl);

                    if (cancelDamage && vl >= cancelVl) {
                        event.setCancelled(true);
                    }

                    if (vl >= alertVl) {
                        broadcastCombatAlert(attacker, "Hitbox", vl, 0, ping);
                        plugin.getCheckManager().getAlertManager().checkPunishment(attacker, "hitbox", vl);
                    }
                    return;
                }

                int currentVl = hitboxVL.getOrDefault(uuid, 0);
                if (currentVl > 0) {
                    hitboxVL.put(uuid, Math.max(0, currentVl - vlDecay));
                }
            }

            if (reachEnabled && hitRealBox) {
                double maxReach = plugin.getConfig().getDouble("checks.reach.max-reach", 3.1);
                int vlDecay = plugin.getConfig().getInt("checks.reach.vl-decay-on-hit", 1);
                boolean cancelDamage = plugin.getConfig().getBoolean("checks.reach.cancel-damage", true);
                int cancelVl = plugin.getConfig().getInt("checks.reach.cancel-vl", 3);
                int alertVl = plugin.getConfig().getInt("checks.reach.alert-vl", 5);

                if (bestReach > maxReach) {
                    int vl = reachVL.getOrDefault(uuid, 0) + 1;
                    reachVL.put(uuid, vl);

                    if (cancelDamage && vl >= cancelVl) {
                        event.setCancelled(true);
                    }

                    if (vl >= alertVl) {
                        broadcastCombatAlert(attacker, "Reach", vl, bestReach, ping);
                        plugin.getCheckManager().getAlertManager().checkPunishment(attacker, "reach", vl);
                    }
                    return;
                }

                int currentVl = reachVL.getOrDefault(uuid, 0);
                if (currentVl > 0) {
                    reachVL.put(uuid, Math.max(0, currentVl - vlDecay));
                }
            }
        }

        boolean wallhitEnabled = plugin.getConfig().getBoolean("checks.wallhit.enabled", true);
        if (wallhitEnabled && isHittingThroughBlocks(attacker, target, snapshots, attackTick, tickWindow)) {
            boolean wallhitCancel = plugin.getConfig().getBoolean("checks.wallhit.cancel-damage", true);
            if (wallhitCancel) {
                event.setCancelled(true);
            }
            return;
        }

        double damageMultiplier = ai.getDamageMultiplier(attacker.getUniqueId());
        if (damageMultiplier < 1.0) {
            event.setDamage(event.getDamage() * damageMultiplier);
        }
        final double dmgMult = damageMultiplier;

        double[] rawFeatures = ai.getDataCollector().extractFeatures(attacker.getUniqueId());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            AntiCheatAI.CheckResult result = ai.analyzePlayer(attacker, rawFeatures);
            Bukkit.getScheduler().runTask(plugin, () -> {
                broadcastAiAlert(attacker, result, dmgMult);
                plugin.getCheckManager().getAlertManager().checkAiPunishment(attacker, result.getScore());
            });
        });
    }

    @EventHandler
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        ai.getDataCollector().recordRawClick(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        ai.clearPlayerData(uuid);
        lastAlertLevel.remove(uuid);
        hitboxVL.remove(uuid);
        reachVL.remove(uuid);
        plugin.getPacketListener().cleanup(uuid);
    }

    private HitScanResult scanBox(List<Location> eyes, List<Vector> lookDirs, SimpleCollisionBox box) {
        double bestDist = Double.MAX_VALUE;
        boolean anyHit = false;

        for (Location eye : eyes) {
            Vector eyeVec = eye.toVector();

            if (RayTraceUtils.isVecInside(box, eyeVec)) {
                return new HitScanResult(true, 0.0);
            }

            for (Vector dir : lookDirs) {
                Vector end = eyeVec.clone().add(dir.clone().multiply(MAX_RAY_DISTANCE));
                Vector hitPos = RayTraceUtils.calculateIntercept(box, eyeVec, end);
                if (hitPos != null) {
                    anyHit = true;
                    double dist = eyeVec.distance(hitPos);
                    if (dist < bestDist) {
                        bestDist = dist;
                    }
                }
            }
        }

        return new HitScanResult(anyHit, bestDist);
    }

    private boolean isHittingThroughBlocks(Player attacker, Player target,
                                           Map<Integer, Map<UUID, BoundingBox>> snapshots, int attackTick, int tickWindow) {
        Location baseEye = attacker.getEyeLocation();

        double[] attPos = plugin.getPacketListener().getAttackPosition(attacker.getUniqueId());
        if (attPos != null) {
            baseEye = new Location(attacker.getWorld(), attPos[0], attPos[1] + attacker.getEyeHeight(), attPos[2]);
        }

        if (hasLineOfSight(baseEye, target.getBoundingBox().clone(), target.getWorld())) {
            return false;
        }

        for (int offset = -1; offset <= tickWindow; offset++) {
            int tick = attackTick - offset;
            Map<UUID, BoundingBox> snap = snapshots.get(tick);
            if (snap == null) continue;

            BoundingBox histBoxRaw = snap.get(target.getUniqueId());
            if (histBoxRaw == null) continue;

            if (hasLineOfSight(baseEye, histBoxRaw.clone(), target.getWorld())) {
                return false;
            }
        }

        return true;
    }

    private boolean hasLineOfSight(Location eye, BoundingBox box, org.bukkit.World world) {
        double minX = box.getMinX();
        double minY = box.getMinY();
        double minZ = box.getMinZ();
        double maxX = box.getMaxX();
        double maxY = box.getMaxY();
        double maxZ = box.getMaxZ();
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        double cz = (minZ + maxZ) / 2;

        Location[] points = {
            new Location(world, cx, cy, cz),
            new Location(world, minX, minY, minZ),
            new Location(world, minX, minY, maxZ),
            new Location(world, minX, maxY, minZ),
            new Location(world, minX, maxY, maxZ),
            new Location(world, maxX, minY, minZ),
            new Location(world, maxX, minY, maxZ),
            new Location(world, maxX, maxY, minZ),
            new Location(world, maxX, maxY, maxZ),
            new Location(world, cx, maxY, cz),
            new Location(world, cx, minY, cz),
            new Location(world, minX, cy, cz),
            new Location(world, maxX, cy, cz),
            new Location(world, cx, cy, minZ),
            new Location(world, cx, cy, maxZ)
        };

        Vector eyeVec = eye.toVector();
        for (Location point : points) {
            double dist = eyeVec.distance(point.toVector());
            if (dist < 0.1) return true;
            Vector direction = point.toVector().subtract(eyeVec).normalize();
            RayTraceResult blockHit = world.rayTraceBlocks(eye, direction, dist, FluidCollisionMode.NEVER, true);
            if (blockHit == null || blockHit.getHitBlock() == null) {
                Vector right = new Vector(-direction.getZ(), 0, direction.getX());
                if (right.lengthSquared() > 0) right.normalize().multiply(0.05);
                else right = new Vector(0.05, 0, 0);

                Vector up = new Vector(0, 0.05, 0);

                Vector pVec = point.toVector();
                Vector dirLeft = pVec.clone().add(right).subtract(eyeVec).normalize();
                Vector dirRight = pVec.clone().subtract(right).subtract(eyeVec).normalize();
                Vector dirUp = pVec.clone().add(up).subtract(eyeVec).normalize();
                Vector dirDown = pVec.clone().subtract(up).subtract(eyeVec).normalize();

                boolean leftPass = world.rayTraceBlocks(eye, dirLeft, dist, FluidCollisionMode.NEVER, true) == null;
                boolean rightPass = world.rayTraceBlocks(eye, dirRight, dist, FluidCollisionMode.NEVER, true) == null;
                boolean upPass = world.rayTraceBlocks(eye, dirUp, dist, FluidCollisionMode.NEVER, true) == null;
                boolean downPass = world.rayTraceBlocks(eye, dirDown, dist, FluidCollisionMode.NEVER, true) == null;

                if ((leftPass || rightPass) && (upPass || downPass)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ClientVersion getClientVersion(Player attacker) {
        ClientVersion version = PacketEvents.getAPI().getPlayerManager().getClientVersion(attacker);
        if (version == null) {
            version = ClientVersion.getById(
                PacketEvents.getAPI().getServerManager().getVersion().getProtocolVersion());
        }
        return version;
    }

    private List<Vector> buildLookDirs(Player attacker, ClientVersion version) {
        List<Vector> dirs = new ArrayList<>();
        UUID uuid = attacker.getUniqueId();

        float bYaw = attacker.getLocation().getYaw();
        float bPitch = attacker.getLocation().getPitch();
        addAllMathVariants(dirs, version, bYaw, bPitch);

        float[] attRot = plugin.getPacketListener().getAttackRotation(uuid);
        if (attRot != null) {
            addAllMathVariants(dirs, version, attRot[0], attRot[1]);
        }

        List<float[]> rotHist = plugin.getPacketListener().getAttackRotationHistory(uuid);
        if (rotHist != null && rotHist.size() >= 2) {
            float[] prev = rotHist.get(rotHist.size() - 2);
            addAllMathVariants(dirs, version, prev[0], prev[1]);

            if (attRot != null) {
                addAllMathVariants(dirs, version, prev[0], attRot[1]);
            }
        }

        return dirs;
    }

    private void addAllMathVariants(List<Vector> dirs, ClientVersion version, float yaw, float pitch) {
        dirs.add(RayTraceUtils.getLookGrim(version, yaw, pitch, true));
        dirs.add(RayTraceUtils.getLookGrim(version, yaw, pitch, false));
        dirs.add(RayTraceUtils.getLookVanilla(yaw, pitch));
    }

    private List<Location> buildEyePositions(Player attacker) {
        List<Location> eyes = new ArrayList<>();

        eyes.add(attacker.getEyeLocation());

        double[] attPos = plugin.getPacketListener().getAttackPosition(attacker.getUniqueId());
        if (attPos != null) {
            double[] eyeHeights = {attacker.getEyeHeight(), 1.62, 1.54, 1.27};
            for (double h : eyeHeights) {
                eyes.add(new Location(attacker.getWorld(), attPos[0], attPos[1] + h, attPos[2]));
            }
        }

        return eyes;
    }

    private void broadcastCombatAlert(Player player, String type, int vl, double reach, int ping) {
        String msg;
        if ("Reach".equals(type)) {
            msg = PREFIX
                + "§f" + player.getName()
                + DIVIDER + LanguageManager.getMessage("alerts.cheat_prefix") + LanguageManager.getMessage("checks.reach")
                + DIVIDER + LanguageManager.getMessage("alerts.dist_prefix") + "§c" + String.format("%.2f", reach)
                + DIVIDER + LanguageManager.getMessage("alerts.ping_prefix") + "§e" + ping
                + DIVIDER + LanguageManager.getMessage("alerts.vl_prefix") + "§c" + vl;
        } else {
            msg = PREFIX
                + "§f" + player.getName()
                + DIVIDER + LanguageManager.getMessage("alerts.cheat_prefix") + LanguageManager.getMessage("checks.hitbox")
                + DIVIDER + LanguageManager.getMessage("alerts.ping_prefix") + "§e" + ping
                + DIVIDER + LanguageManager.getMessage("alerts.vl_prefix") + "§c" + vl;
        }
        sendToStaff(msg);
    }

    private void broadcastAiAlert(Player player, AntiCheatAI.CheckResult result, double dmgMult) {
        double pct = result.getScore() * 100.0;

        if (pct < 65.0) {
            lastAlertLevel.remove(player.getUniqueId());
            return;
        }

        int currentLevel = (int) (pct / 5) * 5;
        int lastLevel = lastAlertLevel.getOrDefault(player.getUniqueId(), 0);

        if (currentLevel <= lastLevel) return;
        lastAlertLevel.put(player.getUniqueId(), currentLevel);

        String scoreColor;
        String checkLabel;
        if (pct >= 90) {
            scoreColor = "§4";
            checkLabel = LanguageManager.getMessage("checks.killaura_aimbot");
        } else if (pct >= 80) {
            scoreColor = "§c";
            checkLabel = LanguageManager.getMessage("checks.aimassist_killaura");
        } else if (pct >= 70) {
            scoreColor = "§e";
            checkLabel = LanguageManager.getMessage("checks.aimassist");
        } else {
            scoreColor = "§e";
            checkLabel = LanguageManager.getMessage("checks.ai_suspicion");
        }

        String msg = PREFIX
            + "§f" + player.getName()
            + DIVIDER + LanguageManager.getMessage("alerts.confidence_prefix") + scoreColor + String.format("%.1f%%", pct)
            + DIVIDER + checkLabel;

        sendToStaff(msg);
    }

    private void sendToStaff(String msg) {
        Bukkit.broadcast(msg, "cloudac.alerts");
    }
}
