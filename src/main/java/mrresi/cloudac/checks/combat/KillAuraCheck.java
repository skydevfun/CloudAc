package mrresi.cloudac.checks.combat;

import mrresi.cloudac.CloudAC;
import mrresi.cloudac.checks.AlertManager;
import mrresi.cloudac.checks.Check;
import mrresi.cloudac.utils.LanguageManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KillAuraCheck extends Check {

    private static final long MIN_USE_ATTACK_GAP_MS = 100;

    private final Map<UUID, Long> lastAttackTimes = new ConcurrentHashMap<>();

    public KillAuraCheck(CloudAC plugin, AlertManager alertManager) {
        super("killaura", "§cKillAura", plugin, alertManager, 3, 30);
    }

    public boolean handleAttack(Player attacker) {
        if (shouldSkip(attacker)) {
            return false;
        }
        if (attacker.isHandRaised()) {
            ItemStack mainHand = attacker.getInventory().getItemInMainHand();
            ItemStack offHand = attacker.getInventory().getItemInOffHand();
            boolean mainIsBlocking = isBlockingItem(mainHand);
            boolean offIsBlocking = isBlockingItem(offHand);
            if (mainIsBlocking || offIsBlocking) {
                String itemName = mainIsBlocking ? formatItemName(mainHand.getType()) : formatItemName(offHand.getType());
                flag(attacker, 5, "§7" + itemName + LanguageManager.getMessage("items.attack_suffix"));
                return true;
            }
        }
        long now = System.currentTimeMillis();
        lastAttackTimes.put(attacker.getUniqueId(), now);
        return false;
    }

    private boolean isBlockingItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        Material type = item.getType();
        if (type.isEdible()) return true;
        switch (type) {
            case BOW:
            case CROSSBOW:
            case TRIDENT:
            case SHIELD:
            case POTION:
            case MILK_BUCKET:
                return true;
            default:
                return false;
        }
    }

    private String formatItemName(Material type) {
        if (type.isEdible()) return LanguageManager.getMessage("items.food");
        switch (type) {
            case BOW: return LanguageManager.getMessage("items.bow");
            case CROSSBOW: return LanguageManager.getMessage("items.crossbow");
            case TRIDENT: return LanguageManager.getMessage("items.trident");
            case SHIELD: return LanguageManager.getMessage("items.shield");
            case POTION: return LanguageManager.getMessage("items.potion");
            case MILK_BUCKET: return LanguageManager.getMessage("items.milk");
            default: return type.name();
        }
    }

    private boolean shouldSkip(Player player) {
        return player.isFlying() || player.getAllowFlight() || player.isDead();
    }
}
