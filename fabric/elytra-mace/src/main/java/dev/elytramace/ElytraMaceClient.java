package dev.elytramace;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class ElytraMaceClient implements ClientModInitializer {
    private static final double TARGET_RANGE = 48.0;
    private static final double SWAP_RANGE = 5.0;
    private static final double ATTACK_REACH = 3.0;
    private static final double MIN_FALL_HEIGHT = 1.5;
    private static final double MIN_DIVE_SPEED = -0.35;
    private static final int DETACH_TIMEOUT_TICKS = 16;
    private static final int PRE_STRIKE_TIMEOUT_TICKS = 16;
    private static final int ATTACK_WINDOW_TICKS = 28;
    private static final double MAX_TARGET_LEAD_TICKS = 8.0;
    private static final float MAX_ATTACK_ROTATION_STEP = 32.0f;
    private static final int CHEST_SLOT_ID = 6;
    private static final int OFFHAND_SLOT_ID = 45;
    private static final float MAX_ROTATION_STEP = 32.0f;

    private static KeyBinding toggleKey;
    private static KeyBinding modeKey;
    private static boolean enabled;
    private static CombatMode combatMode = CombatMode.MACE;
    private static Phase phase = Phase.GLIDING;
    private static PlayerEntity target;
    private static int storedElytraSlot = -1;
    private static int previousHotbarSlot = -1;
    private static int phaseTicks;
    private static float targetHealthBeforeHit;
    private static int targetHurtTimeBeforeHit;
    private static boolean waitForWindBurstApex;
    private static int glideRequestAttempts;
    private static int ticksSinceGlideRequest;
    private static boolean fireworkUsed;
    private static boolean recoveryMessageSent;

    private enum CombatMode {
        MACE("Mace"),
        CRYSTAL("Crystal"),
        ANCHOR("Anchor");

        private final String displayName;

        CombatMode(String displayName) {
            this.displayName = displayName;
        }

        private CombatMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private enum Phase {
        GLIDING,
        DETACHING,
        ATTACKING,
        VERIFYING_HIT,
        WAITING_FOR_LAUNCH,
        RECOVERING
    }

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.elytramace.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                KeyBinding.Category.MISC
        ));
        modeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.elytramace.mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyBinding.Category.MISC
        ));
        ClientTickEvents.END_CLIENT_TICK.register(ElytraMaceClient::tick);
    }

    private static void tick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            enabled = !enabled;
            if (client.player != null) {
                client.player.sendMessage(Text.literal(enabled ? "Elytra Mace Pilot enabled" : "Elytra Mace Pilot disabled"), true);
            }
        }

        ClientPlayerEntity player = client.player;
        while (modeKey.wasPressed()) {
            CombatModeAutomation.reset(player);
            combatMode = combatMode.next();
            if (player != null) {
                player.sendMessage(Text.literal("Combat mode: " + combatMode.displayName), true);
            }
        }

        if (!enabled) {
            CombatModeAutomation.reset(player);
            if (player != null && client.currentScreen == null) {
                if (storedElytraSlot >= 0) {
                    restoreElytra(client, player);
                }
                restoreHotbar(player);
                clearCycle();
            }
            return;
        }

        if (player == null || client.world == null || client.interactionManager == null) {
            CombatModeAutomation.reset(player);
            clearCycle();
            return;
        }

        if (client.currentScreen != null) {
            return;
        }

        if (!player.isAlive() || player.isSpectator()) {
            beginRecovery(client, player, false);
            return;
        }

        if (phase == Phase.GLIDING && combatMode != CombatMode.MACE) {
            if (combatMode == CombatMode.CRYSTAL) {
                CombatModeAutomation.tickCrystal(client, player);
            } else {
                CombatModeAutomation.tickAnchor(client, player);
            }
            return;
        }

        switch (phase) {
            case GLIDING -> tickGliding(client, player);
            case DETACHING -> tickDetaching(client, player);
            case ATTACKING -> tickAttacking(client, player);
            case VERIFYING_HIT -> tickVerifyingHit(client, player);
            case WAITING_FOR_LAUNCH -> tickWaitingForLaunch(client, player);
            case RECOVERING -> tickRecovering(client, player);
        }
    }

    private static void tickGliding(MinecraftClient client, ClientPlayerEntity player) {
        if (!player.isGliding()) {
            return;
        }

        target = findTarget(client, player);
        if (target == null) {
            return;
        }

        aimAt(player, target, MAX_ROTATION_STEP, !target.isOnGround() || !player.isGliding());

        if (!player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            return;
        }

        if (player.getVelocity().y > MIN_DIVE_SPEED || player.fallDistance < MIN_FALL_HEIGHT) {
            return;
        }

        if (player.squaredDistanceTo(target) > SWAP_RANGE * SWAP_RANGE) {
            return;
        }

        int maceSlot = findMaceHotbarSlot(player);
        int chestplateSlot = findChestplateSlot(player);
        if (maceSlot < 0 || chestplateSlot < 0 || player.hasStatusEffect(StatusEffects.SLOW_FALLING)) {
            return;
        }

        previousHotbarSlot = player.getInventory().getSelectedSlot();
        storedElytraSlot = chestplateSlot;
        if (!swapInventorySlot(client, player, chestplateSlot, CHEST_SLOT_ID)) {
            storedElytraSlot = -1;
            previousHotbarSlot = -1;
            return;
        }

        player.getInventory().setSelectedSlot(maceSlot);
        phase = Phase.DETACHING;
        phaseTicks = 0;
    }

    private static void tickDetaching(MinecraftClient client, ClientPlayerEntity player) {
        target = resolveTarget(client, player, target);
        if (target == null || player.isOnGround()) {
            beginRecovery(client, player, false);
            return;
        }

        aimAt(player, target, MAX_ROTATION_STEP, !target.isOnGround() || !player.isGliding());
        phaseTicks++;

        if (!player.isGliding() && player.fallDistance >= MIN_FALL_HEIGHT) {
            phase = Phase.ATTACKING;
            phaseTicks = 0;
            return;
        }

        if (phaseTicks > DETACH_TIMEOUT_TICKS) {
            beginRecovery(client, player, false);
        }
    }

    private static void tickAttacking(MinecraftClient client, ClientPlayerEntity player) {
        target = resolveTarget(client, player, target);
        if (target == null || player.isOnGround()) {
            beginRecovery(client, player, false);
            return;
        }

        aimAt(player, target, MAX_ATTACK_ROTATION_STEP, true);
        int maceSlot = findMaceHotbarSlot(player);
        if (maceSlot < 0) {
            beginRecovery(client, player, false);
            return;
        }
        player.getInventory().setSelectedSlot(maceSlot);

        if (player.isGliding() || player.fallDistance < MIN_FALL_HEIGHT) {
            phaseTicks++;
            if (phaseTicks > PRE_STRIKE_TIMEOUT_TICKS) {
                beginRecovery(client, player, false);
            }
            return;
        }

        if (client.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() == target
                && player.getEyePos().squaredDistanceTo(hit.getPos()) <= ATTACK_REACH * ATTACK_REACH) {
            targetHealthBeforeHit = target.getHealth() + target.getAbsorptionAmount();
            targetHurtTimeBeforeHit = target.hurtTime;
            client.interactionManager.attackEntity(player, target);
            phase = Phase.VERIFYING_HIT;
            phaseTicks = 0;
            return;
        }

        phaseTicks++;
        if (phaseTicks > ATTACK_WINDOW_TICKS || player.fallDistance > 18.0) {
            beginRecovery(client, player, false);
        }
    }

    private static void tickVerifyingHit(MinecraftClient client, ClientPlayerEntity player) {
        phaseTicks++;
        if (target != null) {
            float currentTotal = target.getHealth() + target.getAbsorptionAmount();
            boolean healthChanged = currentTotal < targetHealthBeforeHit - 0.01f;
            boolean hurtStateChanged = target.hurtTime > targetHurtTimeBeforeHit;
            if (healthChanged || hurtStateChanged) {
                phase = Phase.WAITING_FOR_LAUNCH;
                phaseTicks = 0;
                return;
            }
        }

        if (phaseTicks > 6) {
            beginRecovery(client, player, false);
        }
    }

    private static void tickWaitingForLaunch(MinecraftClient client, ClientPlayerEntity player) {
        phaseTicks++;
        if (player.getVelocity().y > 0.15 || phaseTicks >= 2) {
            restoreElytra(client, player);
            waitForWindBurstApex = player.getVelocity().y > 0.15;
            phase = Phase.RECOVERING;
            phaseTicks = 0;
            glideRequestAttempts = 0;
            ticksSinceGlideRequest = 0;
            fireworkUsed = false;
            target = null;
        }
    }

    private static void tickRecovering(MinecraftClient client, ClientPlayerEntity player) {
        phaseTicks++;
        restoreElytra(client, player);
        if (player.getVelocity().y > 0.15) {
            waitForWindBurstApex = true;
        }

        if (player.isOnGround()) {
            finishCycle(player);
            return;
        }

        if (!player.isGliding() && player.fallDistance >= 3.0) {
            equipTotem(client, player);
        }

        boolean wearingElytra = player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        boolean hasReachedApex = !waitForWindBurstApex || player.getVelocity().y <= 0.0;
        if (!player.isGliding() && wearingElytra && hasReachedApex
                && glideRequestAttempts < 2
                && (glideRequestAttempts == 0 || ticksSinceGlideRequest >= 6)) {
            if (client.getNetworkHandler() != null) {
                client.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(
                        player,
                        ClientCommandC2SPacket.Mode.START_FALL_FLYING
                ));
                glideRequestAttempts++;
                ticksSinceGlideRequest = 0;
            }
        }

        if (player.isGliding()) {
            if (!fireworkUsed && player.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.FIREWORK_ROCKET)) {
                client.interactionManager.interactItem(player, Hand.OFF_HAND);
                fireworkUsed = true;
            }
            finishCycle(player);
            return;
        }

        ticksSinceGlideRequest++;
        if (phaseTicks > 24) {
            if (!recoveryMessageSent) {
                player.sendMessage(Text.literal("Mace Pilot recovery: Elytra restored; glide or clutch manually if needed."), true);
                recoveryMessageSent = true;
            }
            if (player.fallDistance > 3.0) {
                equipTotem(client, player);
            }
            if (glideRequestAttempts >= 2) {
                finishCycle(player);
            }
        }
    }

    private static PlayerEntity findTarget(MinecraftClient client, ClientPlayerEntity player) {
        PlayerEntity closest = null;
        double closestDistance = TARGET_RANGE * TARGET_RANGE;

        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (candidate == player || !candidate.isAlive() || candidate.isSpectator()) {
                continue;
            }
            if (player.isTeammate(candidate) || !player.canSee(candidate)) {
                continue;
            }

            double distance = player.squaredDistanceTo(candidate);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = candidate;
            }
        }
        return closest;
    }

    private static PlayerEntity resolveTarget(MinecraftClient client, ClientPlayerEntity player, PlayerEntity current) {
        if (current != null && current.isAlive() && !current.isSpectator()
                && !player.isTeammate(current) && player.canSee(current)
                && player.squaredDistanceTo(current) <= TARGET_RANGE * TARGET_RANGE) {
            return current;
        }
        return findTarget(client, player);
    }

    private static void aimAt(ClientPlayerEntity player, PlayerEntity entity, float rotationStep, boolean adjustPitch) {
        Vec3d relativePosition = entity.getBoundingBox().getCenter().subtract(player.getEyePos());
        Vec3d relativeVelocity = entity.getVelocity().subtract(player.getVelocity());
        double distanceSquared = relativePosition.lengthSquared();
        if (distanceSquared < 1.0e-8) {
            return;
        }

        double a = relativeVelocity.lengthSquared();
        double b = 2.0 * relativePosition.dotProduct(relativeVelocity);
        double c = distanceSquared - ATTACK_REACH * ATTACK_REACH;
        double leadTicks = 0.0;

        if (c > 0.0 && a > 1.0e-8) {
            double discriminant = b * b - 4.0 * a * c;
            if (discriminant >= 0.0) {
                double root = Math.sqrt(discriminant);
                double interceptTicks = (-b - root) / (2.0 * a);
                if (interceptTicks < 0.0) {
                    interceptTicks = (-b + root) / (2.0 * a);
                }
                if (interceptTicks >= 0.0) {
                    leadTicks = MathHelper.clamp(interceptTicks, 0.0, MAX_TARGET_LEAD_TICKS);
                }
            }
        }

        Vec3d direction = relativePosition.add(relativeVelocity.multiply(leadTicks));
        double horizontalLength = Math.sqrt(direction.x * direction.x + direction.z * direction.z);

        float desiredYaw = (float) (Math.toDegrees(Math.atan2(direction.z, direction.x)) - 90.0);
        float desiredPitch = (float) -Math.toDegrees(Math.atan2(direction.y, horizontalLength));
        float yawDifference = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
        float pitchDifference = desiredPitch - player.getPitch();

        player.setYaw(player.getYaw() + MathHelper.clamp(yawDifference, -rotationStep, rotationStep));
        if (adjustPitch) {
            player.setPitch(MathHelper.clamp(
                    player.getPitch() + MathHelper.clamp(pitchDifference, -rotationStep, rotationStep),
                    -90.0f,
                    90.0f
            ));
        }
    }

    private static int findMaceHotbarSlot(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.MACE)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findChestplateSlot(ClientPlayerEntity player) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(Items.NETHERITE_CHESTPLATE)
                    || stack.isOf(Items.DIAMOND_CHESTPLATE)
                    || stack.isOf(Items.IRON_CHESTPLATE)
                    || stack.isOf(Items.CHAINMAIL_CHESTPLATE)
                    || stack.isOf(Items.GOLDEN_CHESTPLATE)
                    || stack.isOf(Items.LEATHER_CHESTPLATE)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findItemSlot(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean swapInventorySlot(MinecraftClient client, ClientPlayerEntity player, int inventorySlot, int destinationSlot) {
        if (client.currentScreen != null || inventorySlot < 0 || inventorySlot >= 36) {
            return false;
        }

        ScreenHandler handler = player.currentScreenHandler;
        int sourceSlot = inventorySlot < 9 ? inventorySlot + 36 : inventorySlot;
        client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        client.interactionManager.clickSlot(handler.syncId, destinationSlot, 0, SlotActionType.PICKUP, player);
        client.interactionManager.clickSlot(handler.syncId, sourceSlot, 0, SlotActionType.PICKUP, player);
        return true;
    }

    private static boolean restoreElytra(MinecraftClient client, ClientPlayerEntity player) {
        if (player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            storedElytraSlot = -1;
            return true;
        }

        int elytraSlot = -1;
        if (storedElytraSlot >= 0 && storedElytraSlot < 36
                && player.getInventory().getStack(storedElytraSlot).isOf(Items.ELYTRA)) {
            elytraSlot = storedElytraSlot;
        } else {
            elytraSlot = findItemSlot(player, Items.ELYTRA);
        }

        if (elytraSlot < 0 || !swapInventorySlot(client, player, elytraSlot, CHEST_SLOT_ID)) {
            return false;
        }
        storedElytraSlot = -1;
        return true;
    }

    private static void equipTotem(MinecraftClient client, ClientPlayerEntity player) {
        if (player.getEquippedStack(EquipmentSlot.OFFHAND).isOf(Items.TOTEM_OF_UNDYING)) {
            return;
        }
        int totemSlot = findItemSlot(player, Items.TOTEM_OF_UNDYING);
        if (totemSlot >= 0) {
            swapInventorySlot(client, player, totemSlot, OFFHAND_SLOT_ID);
        }
    }

    private static void beginRecovery(MinecraftClient client, ClientPlayerEntity player, boolean waitForApex) {
        restoreElytra(client, player);
        target = null;
        phase = Phase.RECOVERING;
        phaseTicks = 0;
        waitForWindBurstApex = waitForApex;
        glideRequestAttempts = 0;
        ticksSinceGlideRequest = 0;
        fireworkUsed = false;
        recoveryMessageSent = false;
    }

    private static void restoreHotbar(ClientPlayerEntity player) {
        if (previousHotbarSlot >= 0 && previousHotbarSlot < 9) {
            player.getInventory().setSelectedSlot(previousHotbarSlot);
        }
        previousHotbarSlot = -1;
    }

    private static void finishCycle(ClientPlayerEntity player) {
        restoreHotbar(player);
        target = null;
        storedElytraSlot = -1;
        phase = Phase.GLIDING;
        phaseTicks = 0;
        waitForWindBurstApex = false;
        glideRequestAttempts = 0;
        ticksSinceGlideRequest = 0;
        fireworkUsed = false;
        recoveryMessageSent = false;
    }

    private static void clearCycle() {
        target = null;
        storedElytraSlot = -1;
        previousHotbarSlot = -1;
        phase = Phase.GLIDING;
        phaseTicks = 0;
        targetHealthBeforeHit = 0.0f;
        targetHurtTimeBeforeHit = 0;
        waitForWindBurstApex = false;
        glideRequestAttempts = 0;
        ticksSinceGlideRequest = 0;
        fireworkUsed = false;
        recoveryMessageSent = false;
    }
}