package dev.elytramace;

import java.util.List;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.explosion.ExplosionImpl;

public final class CombatModeAutomation {
    private static final double TARGET_RANGE = 7.0;
    private static final double INTERACTION_RANGE = 4.5;
    private static final int OFFHAND_SLOT_ID = 45;
    private static final int TOTEM_SWAP_TIMEOUT_TICKS = 10;
    private static final double MAX_SELF_DAMAGE_MARGIN = 1.0;
    private static final int STEP_TIMEOUT_TICKS = 40;
    private static final int SERVER_ACTION_DELAY_TICKS = 3;
    private static final int CRYSTAL_ATTACK_DELAY_TICKS = 2;
    private static final int POST_ACTION_COOLDOWN_TICKS = 4;

    private static Step step = Step.IDLE;
    private static BlockPos actionPos;
    private static EndCrystalEntity crystal;
    private static PlayerEntity activeTarget;
    private static int stepTicks;
    private static int previousSlot = -1;
    private static boolean warnedNether;
    private static boolean totemSwapPending;
    private static int totemSwapTicks;
    private static int actionCooldownTicks;

    private enum Step {
        IDLE,
        WAITING_FOR_OBSIDIAN,
        WAITING_FOR_CRYSTAL,
        WAITING_FOR_ANCHOR,
        WAITING_FOR_CHARGE,
        WAITING_FOR_DETONATION,
        WAITING_FOR_CRYSTAL_REMOVAL
    }

    private CombatModeAutomation() {
    }

    public static void tickCrystal(MinecraftClient client, ClientPlayerEntity player) {
        tickAutoTotem(client, player);
        if (step == Step.IDLE && actionCooldownTicks > 0) {
            actionCooldownTicks--;
            return;
        }
        PlayerEntity target = resolveCombatTarget(client, player);
        if (target == null) {
            reset(player);
            return;
        }

        switch (step) {
            case IDLE -> {
                EndCrystalEntity existing = findCrystal(client, player, target);
                if (existing != null) {
                    Vec3d explosionPos = existing.getEntityPos();
                    if (isSafeExplosion(client, player, target, explosionPos, 6.0f)
                            && player.getEyePos().squaredDistanceTo(explosionPos) <= INTERACTION_RANGE * INTERACTION_RANGE) {
                        crystal = existing;
                        step = Step.WAITING_FOR_CRYSTAL_REMOVAL;
                        stepTicks = 0;
                        client.interactionManager.attackEntity(player, existing);
                    }
                    return;
                }

                BlockPos base = findCrystalBase(client, player, target);
                if (base != null) {
                    if (!hasCrystalSpace(client, base) || !isSafeExplosion(
                            client, player, target, new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5), 6.0f)) {
                        return;
                    }
                    int crystalSlot = findHotbarSlot(player, Items.END_CRYSTAL);
                    if (crystalSlot < 0) {
                        return;
                    }
                    selectSlot(player, crystalSlot);
                    interactWithBlock(client, player, base, Direction.UP);
                    actionPos = base.toImmutable();
                    step = Step.WAITING_FOR_CRYSTAL;
                    stepTicks = 0;
                    return;
                }

                BlockPos placePos = findSupportedPlacePosition(client, player, target, true);
                int obsidianSlot = findHotbarSlot(player, Items.OBSIDIAN);
                if (placePos == null || obsidianSlot < 0) {
                    return;
                }

                selectSlot(player, obsidianSlot);
                interactWithBlock(client, player, placePos.down(), Direction.UP);
                actionPos = placePos.toImmutable();
                step = Step.WAITING_FOR_OBSIDIAN;
                stepTicks = 0;
            }
            case WAITING_FOR_OBSIDIAN -> {
                stepTicks++;
                if (client.world.getBlockState(actionPos).isOf(Blocks.OBSIDIAN) && stepTicks >= SERVER_ACTION_DELAY_TICKS) {
                    if (!hasCrystalSpace(client, actionPos) || !isSafeExplosion(
                            client, player, target,
                            new Vec3d(actionPos.getX() + 0.5, actionPos.getY() + 1.0, actionPos.getZ() + 0.5),
                            6.0f)) {
                        reset(player);
                        return;
                    }
                    int crystalSlot = findHotbarSlot(player, Items.END_CRYSTAL);
                    if (crystalSlot < 0) {
                        reset(player);
                        return;
                    }
                    selectSlot(player, crystalSlot);
                    interactWithBlock(client, player, actionPos, Direction.UP);
                    step = Step.WAITING_FOR_CRYSTAL;
                    stepTicks = 0;
                } else if (stepTicks > STEP_TIMEOUT_TICKS) {
                    reset(player);
                }
            }
            case WAITING_FOR_CRYSTAL -> {
                stepTicks++;
                EndCrystalEntity found = crystal != null && crystal.isAlive()
                        ? crystal
                        : findCrystalAtBase(client, player, actionPos);
                if (found != null) {
                    Vec3d explosionPos = found.getEntityPos();
                    if (!isSafeExplosion(client, player, target, explosionPos, 6.0f)) {
                        reset(player);
                        return;
                    }
                    if (player.getEyePos().squaredDistanceTo(found.getEntityPos()) <= INTERACTION_RANGE * INTERACTION_RANGE) {
                        client.interactionManager.attackEntity(player, found);
                        step = Step.WAITING_FOR_CRYSTAL_REMOVAL;
                        stepTicks = 0;
                    }
                } else if (stepTicks > STEP_TIMEOUT_TICKS) {
                    reset(player);
                }
            }
            case WAITING_FOR_CRYSTAL_REMOVAL -> {
                stepTicks++;
                if (crystal == null || !crystal.isAlive() || stepTicks > STEP_TIMEOUT_TICKS) {
                    reset(player);
                    actionCooldownTicks = POST_ACTION_COOLDOWN_TICKS;
                }
            }
            case WAITING_FOR_ANCHOR, WAITING_FOR_CHARGE, WAITING_FOR_DETONATION -> reset(player);
        }
    }

    public static void tickAnchor(MinecraftClient client, ClientPlayerEntity player) {
        tickAutoTotem(client, player);
        if (client.world.getRegistryKey().getValue().getPath().equals("the_nether")) {
            if (!warnedNether) {
                player.sendMessage(net.minecraft.text.Text.literal("Anchor mode is disabled in the Nether because anchors do not explode there."), true);
                warnedNether = true;
            }
            reset(player);
            return;
        }
        warnedNether = false;

        if (step == Step.IDLE && actionCooldownTicks > 0) {
            actionCooldownTicks--;
            return;
        }
        PlayerEntity target = resolveCombatTarget(client, player);
        if (target == null) {
            reset(player);
            return;
        }

        switch (step) {
            case IDLE -> {
                BlockPos existingAnchor = findNearbyAnchor(client, player, target);
                if (existingAnchor != null) {
                    actionPos = existingAnchor.toImmutable();
                    BlockState existingState = client.world.getBlockState(actionPos);
                    if (existingState.get(RespawnAnchorBlock.CHARGES) > RespawnAnchorBlock.NO_CHARGES) {
                        step = Step.WAITING_FOR_CHARGE;
                        stepTicks = 0;
                        return;
                    }

                    int glowstoneSlot = findHotbarSlot(player, Items.GLOWSTONE);
                    if (glowstoneSlot < 0) {
                        return;
                    }
                    selectSlot(player, glowstoneSlot);
                    interactWithBlock(client, player, actionPos, Direction.UP);
                    step = Step.WAITING_FOR_CHARGE;
                    stepTicks = 0;
                    return;
                }

                BlockPos placePos = findSupportedPlacePosition(client, player, target, false);
                int anchorSlot = findHotbarSlot(player, Items.RESPAWN_ANCHOR);
                if (placePos == null || anchorSlot < 0) {
                    return;
                }

                Vec3d explosionPos = placePos.toCenterPos();
                if (!isSafeExplosion(client, player, target, explosionPos, 5.0f)) {
                    return;
                }

                selectSlot(player, anchorSlot);
                interactWithBlock(client, player, placePos.down(), Direction.UP);
                actionPos = placePos.toImmutable();
                step = Step.WAITING_FOR_ANCHOR;
                stepTicks = 0;
            }
            case WAITING_FOR_ANCHOR -> {
                stepTicks++;
                BlockState state = client.world.getBlockState(actionPos);
                if (state.isOf(Blocks.RESPAWN_ANCHOR) && stepTicks >= SERVER_ACTION_DELAY_TICKS) {
                    int glowstoneSlot = findHotbarSlot(player, Items.GLOWSTONE);
                    if (glowstoneSlot < 0) {
                        reset(player);
                        return;
                    }
                    selectSlot(player, glowstoneSlot);
                    interactWithBlock(client, player, actionPos, Direction.UP);
                    step = Step.WAITING_FOR_CHARGE;
                    stepTicks = 0;
                } else if (stepTicks > STEP_TIMEOUT_TICKS) {
                    reset(player);
                }
            }
            case WAITING_FOR_CHARGE -> {
                stepTicks++;
                BlockState state = client.world.getBlockState(actionPos);
                if (state.isOf(Blocks.RESPAWN_ANCHOR)
                        && state.get(RespawnAnchorBlock.CHARGES) > RespawnAnchorBlock.NO_CHARGES
                        && stepTicks >= SERVER_ACTION_DELAY_TICKS) {
                    detonateAnchor(client, player, target, actionPos);
                } else if (stepTicks > STEP_TIMEOUT_TICKS) {
                    reset(player);
                }
            }
            case WAITING_FOR_DETONATION -> {
                stepTicks++;
                if (!client.world.getBlockState(actionPos).isOf(Blocks.RESPAWN_ANCHOR)
                        || stepTicks > STEP_TIMEOUT_TICKS) {
                    reset(player);
                    actionCooldownTicks = POST_ACTION_COOLDOWN_TICKS;
                }
            }
            case WAITING_FOR_CRYSTAL_REMOVAL, WAITING_FOR_OBSIDIAN, WAITING_FOR_CRYSTAL -> reset(player);
        }
    }

    private static BlockPos findNearbyAnchor(
            MinecraftClient client,
            ClientPlayerEntity player,
            PlayerEntity target
    ) {
        BlockPos center = BlockPos.ofFloored(target.getX(), target.getY(), target.getZ());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;

        for (int x = -3; x <= 3; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -3; z <= 3; z++) {
                    BlockPos candidate = center.add(x, y, z);
                    if (!client.world.getBlockState(candidate).isOf(Blocks.RESPAWN_ANCHOR)) {
                        continue;
                    }

                    double playerDistance = player.getEyePos().squaredDistanceTo(candidate.toCenterPos());
                    double targetDistance = target.squaredDistanceTo(candidate.toCenterPos());
                    if (playerDistance > INTERACTION_RANGE * INTERACTION_RANGE
                            || targetDistance > 4.0
                            || playerDistance >= bestDistance) {
                        continue;
                    }

                    best = candidate.toImmutable();
                    bestDistance = playerDistance;
                }
            }
        }
        return best;
    }

    private static void detonateAnchor(
            MinecraftClient client,
            ClientPlayerEntity player,
            PlayerEntity target,
            BlockPos anchorPos
    ) {
        Vec3d explosionPos = anchorPos.toCenterPos();
        if (!isSafeExplosion(client, player, target, explosionPos, 5.0f)) {
            reset(player);
            return;
        }

        int detonationSlot = findDetonationSlot(player);
        if (detonationSlot < 0) {
            reset(player);
            return;
        }

        selectSlot(player, detonationSlot);
        interactWithBlock(client, player, anchorPos, Direction.UP);
        step = Step.WAITING_FOR_DETONATION;
        stepTicks = 0;
    }

    public static void reset(ClientPlayerEntity player) {
        if (player != null && previousSlot >= 0 && previousSlot < 9) {
            player.getInventory().setSelectedSlot(previousSlot);
        }
        previousSlot = -1;
        step = Step.IDLE;
        actionPos = null;
        crystal = null;
        activeTarget = null;
        stepTicks = 0;
    }

    private static void tickAutoTotem(MinecraftClient client, ClientPlayerEntity player) {
        if (player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            totemSwapPending = false;
            totemSwapTicks = 0;
            return;
        }

        if (totemSwapPending) {
            totemSwapTicks++;
            if (totemSwapTicks < TOTEM_SWAP_TIMEOUT_TICKS) {
                return;
            }
            totemSwapPending = false;
            totemSwapTicks = 0;
        }

        int totemSlot = findTotemInventorySlot(player);
        if (totemSlot < 0 || client.currentScreen != null) {
            return;
        }

        if (swapInventorySlot(client, player, totemSlot, OFFHAND_SLOT_ID)) {
            totemSwapPending = true;
            totemSwapTicks = 0;
        }
    }

    private static int findTotemInventorySlot(ClientPlayerEntity player) {
        for (int slot = 0; slot < 36; slot++) {
            if (player.getInventory().getStack(slot).isOf(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean swapInventorySlot(
            MinecraftClient client,
            ClientPlayerEntity player,
            int inventorySlot,
            int destinationSlot
    ) {
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

    private static PlayerEntity resolveCombatTarget(MinecraftClient client, ClientPlayerEntity player) {
        if (step != Step.IDLE && activeTarget != null) {
            if (activeTarget.isAlive() && !activeTarget.isSpectator()
                    && !player.isTeammate(activeTarget)
                    && player.squaredDistanceTo(activeTarget) <= TARGET_RANGE * TARGET_RANGE) {
                return activeTarget;
            }
            return null;
        }

        activeTarget = findTarget(client, player);
        return activeTarget;
    }

    private static PlayerEntity findTarget(MinecraftClient client, ClientPlayerEntity player) {
        PlayerEntity closest = null;
        double closestDistance = TARGET_RANGE * TARGET_RANGE;
        for (PlayerEntity candidate : client.world.getPlayers()) {
            if (candidate == player || !candidate.isAlive() || candidate.isSpectator()
                    || player.isTeammate(candidate) || !player.canSee(candidate)) {
                continue;
            }
            double distance = player.squaredDistanceTo(candidate);
            if (distance < closestDistance) {
                closest = candidate;
                closestDistance = distance;
            }
        }
        return closest;
    }

    private static EndCrystalEntity findCrystal(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
        Box searchBox = new Box(
                target.getX() - 4.0, target.getY() - 3.0, target.getZ() - 4.0,
                target.getX() + 4.0, target.getY() + 4.0, target.getZ() + 4.0
        );
        return client.world.getEntitiesByClass(EndCrystalEntity.class, searchBox,
                entity -> entity.isAlive()
                        && player.getEyePos().squaredDistanceTo(entity.getEntityPos()) <= INTERACTION_RANGE * INTERACTION_RANGE
        ).stream().findFirst().orElse(null);
    }

    private static EndCrystalEntity findCrystalAtBase(MinecraftClient client, ClientPlayerEntity player, BlockPos base) {
        if (base == null) {
            return null;
        }
        Box searchBox = new Box(
                base.getX() - 1.5, base.getY(), base.getZ() - 1.5,
                base.getX() + 2.5, base.getY() + 4.0, base.getZ() + 2.5
        );
        return client.world.getEntitiesByClass(EndCrystalEntity.class, searchBox,
                entity -> entity.isAlive()
                        && player.getEyePos().squaredDistanceTo(entity.getEntityPos()) <= INTERACTION_RANGE * INTERACTION_RANGE
        ).stream().findFirst().orElse(null);
    }

    private static BlockPos findCrystalBase(MinecraftClient client, ClientPlayerEntity player, PlayerEntity target) {
        BlockPos center = BlockPos.ofFloored(target.getX(), target.getY(), target.getZ());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = -2; x <= 2; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -2; z <= 2; z++) {
                    BlockPos candidate = center.add(x, y, z);
                    BlockState state = client.world.getBlockState(candidate);
                    if (!state.isOf(Blocks.OBSIDIAN) && !state.isOf(Blocks.BEDROCK)) {
                        continue;
                    }
                    if (!hasCrystalSpace(client, candidate)) {
                        continue;
                    }
                    Vec3d hitPos = candidate.toCenterPos().add(0.0, 0.5, 0.0);
                    double distance = player.getEyePos().squaredDistanceTo(hitPos);
                    if (distance <= INTERACTION_RANGE * INTERACTION_RANGE && distance < bestDistance) {
                        best = candidate.toImmutable();
                        bestDistance = distance;
                    }
                }
            }
        }
        return best;
    }

    private static boolean hasCrystalSpace(MinecraftClient client, BlockPos base) {
        return (client.world.getBlockState(base).isOf(Blocks.OBSIDIAN)
                || client.world.getBlockState(base).isOf(Blocks.BEDROCK))
                && client.world.getBlockState(base.up()).isAir()
                && client.world.getBlockState(base.up(2)).isAir();
    }

    private static BlockPos findSupportedPlacePosition(
            MinecraftClient client,
            ClientPlayerEntity player,
            PlayerEntity target,
            boolean forCrystal
    ) {
        BlockPos center = BlockPos.ofFloored(target.getX(), target.getY(), target.getZ());
        int[][] offsets = {
                {1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {2, 0}, {-2, 0}, {0, 2}, {0, -2}
        };
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] offset : offsets) {
            BlockPos candidate = center.add(offset[0], 0, offset[1]);
            BlockPos support = candidate.down();
            BlockState candidateState = client.world.getBlockState(candidate);
            if (!candidateState.isReplaceable()
                    || !client.world.getBlockState(support).isSideSolidFullSquare(client.world, support, Direction.UP)
                    || !client.world.getBlockState(candidate.up()).isAir()) {
                continue;
            }
            if (forCrystal && !client.world.getBlockState(candidate.up(2)).isAir()) {
                continue;
            }
            if (target.getBoundingBox().intersects(new Box(
                    candidate.getX(), candidate.getY(), candidate.getZ(),
                    candidate.getX() + 1.0, candidate.getY() + 1.0, candidate.getZ() + 1.0
            ))) {
                continue;
            }

            Vec3d hitPos = support.toCenterPos().add(0.0, 0.5, 0.0);
            double distance = player.getEyePos().squaredDistanceTo(hitPos);
            if (distance <= INTERACTION_RANGE * INTERACTION_RANGE && distance < bestDistance) {
                best = candidate.toImmutable();
                bestDistance = distance;
            }
        }
        return best;
    }

    private static boolean isSafeExplosion(
            MinecraftClient client,
            ClientPlayerEntity player,
            PlayerEntity target,
            Vec3d explosionPos,
            float power
    ) {
        float selfDamage = ExplosionImpl.calculateReceivedDamage(explosionPos, player);
        float targetDamage = ExplosionImpl.calculateReceivedDamage(explosionPos, target);
        float selfHealth = player.getHealth() + player.getAbsorptionAmount();
        return selfDamage + MAX_SELF_DAMAGE_MARGIN < selfHealth && targetDamage > 0.0f;
    }

    private static int findHotbarSlot(ClientPlayerEntity player, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static int findDetonationSlot(ClientPlayerEntity player) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty()) {
                return slot;
            }
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isOf(Items.GLOWSTONE) && !stack.isOf(Items.RESPAWN_ANCHOR)) {
                return slot;
            }
        }
        return -1;
    }

    private static void selectSlot(ClientPlayerEntity player, int slot) {
        if (previousSlot < 0) {
            previousSlot = player.getInventory().getSelectedSlot();
        }
        player.getInventory().setSelectedSlot(slot);
    }

    private static void interactWithBlock(
            MinecraftClient client,
            ClientPlayerEntity player,
            BlockPos blockPos,
            Direction face
    ) {
        Vec3d hitPos = blockPos.toCenterPos().add(
                face.getOffsetX() * 0.5,
                face.getOffsetY() * 0.5,
                face.getOffsetZ() * 0.5
        );
        BlockHitResult hitResult = new BlockHitResult(hitPos, face, blockPos, false);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
    }
}
