package com.example.automine;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.command.CommandSource;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/**
 * Mayank's AutoMiner v2 (client-side, Fabric 1.21.11)
 *
 *   /automine <block> [amount]          mine a block type
 *   /automine scan <block> | scan off   ESP only
 *   /automine config <opt> <true|false> opt: esp, combat, eat, safety, pickup
 *   /automine status                    show settings
 *   /automine stop
 */
public class AutoMineMod implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 32;
    private static final double REACH = 4.5;
    private static final double ATTACK_RANGE = 3.0;
    private static final int HUNGER_START = 14;
    private static final int HUNGER_STOP = 19;
    private static final int ESP_MAX = 100;
    private static final int TARGET_TIMEOUT = 600;   // ticks without progress before skipping a block
    private static final float MIN_HEALTH = 5.0f;    // stop mining at/below this (2.5 hearts)

    // settings
    private static boolean cfgEsp = true;
    private static boolean cfgCombat = true;
    private static boolean cfgEat = true;
    private static boolean cfgSafety = true;
    private static boolean cfgPickup = true;

    // mining state
    private static boolean active = false;
    private static Block targetBlock = null;
    private static int remaining = 0;
    private static int totalAmount = 0;
    private static BlockPos current = null;
    private static BlockPos lastBreakPos = null;
    private static int targetTicks = 0;
    private static final Set<BlockPos> blacklist = new HashSet<>();
    private static String status = "";

    // pickup
    private static int pickupTicks = 0;

    // ESP state
    private static Block espBlock = null;
    private static boolean scanMode = false;
    private static final List<BlockPos> espPositions = new ArrayList<>();

    // combat / eating
    private static LivingEntity fightTarget = null;
    private static boolean eating = false;

    private static int tickCount = 0;
    private static int lastNoFoodMsg = -10000;
    private static int lastFullMsg = -10000;

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(literal("automine")
                .then(literal("stop").executes(ctx -> {
                    stopAll(ctx.getSource(), "Stopped.");
                    return 1;
                }))
                .then(literal("status").executes(ctx -> {
                    ctx.getSource().sendFeedback(Text.literal("esp=" + cfgEsp + " combat=" + cfgCombat
                        + " eat=" + cfgEat + " safety=" + cfgSafety + " pickup=" + cfgPickup
                        + (active ? " | mining " + (totalAmount - remaining) + "/" + totalAmount : " | idle")));
                    return 1;
                }))
                .then(literal("config")
                    .then(argument("option", StringArgumentType.word())
                        .suggests((c, b) -> {
                            for (String s : new String[]{"esp", "combat", "eat", "safety", "pickup"}) b.suggest(s);
                            return b.buildFuture();
                        })
                        .then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
                            String opt = StringArgumentType.getString(ctx, "option").toLowerCase();
                            boolean v = BoolArgumentType.getBool(ctx, "value");
                            switch (opt) {
                                case "esp" -> cfgEsp = v;
                                case "combat" -> cfgCombat = v;
                                case "eat" -> cfgEat = v;
                                case "safety" -> cfgSafety = v;
                                case "pickup" -> cfgPickup = v;
                                default -> {
                                    ctx.getSource().sendError(Text.literal("Options: esp, combat, eat, safety, pickup"));
                                    return 0;
                                }
                            }
                            ctx.getSource().sendFeedback(Text.literal(opt + " = " + v));
                            return 1;
                        }))))
                .then(literal("scan")
                    .then(literal("off").executes(ctx -> {
                        scanMode = false;
                        if (!active) { espBlock = null; espPositions.clear(); }
                        ctx.getSource().sendFeedback(Text.literal("ESP off."));
                        return 1;
                    }))
                    .then(argument("block", StringArgumentType.word())
                        .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), b))
                        .executes(ctx -> {
                            Block b = parseBlock(ctx.getSource(), StringArgumentType.getString(ctx, "block"));
                            if (b == null) return 0;
                            espBlock = b;
                            scanMode = true;
                            tickCount = 19;
                            ctx.getSource().sendFeedback(Text.literal("Scanning for " + Registries.BLOCK.getId(b) + "..."));
                            return 1;
                        })))
                .then(argument("block", StringArgumentType.word())
                    .suggests((c, b) -> CommandSource.suggestIdentifiers(Registries.BLOCK.getIds(), b))
                    .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "block"), 1))
                    .then(argument("amount", IntegerArgumentType.integer(1, 10000))
                        .executes(ctx -> start(ctx.getSource(),
                            StringArgumentType.getString(ctx, "block"),
                            IntegerArgumentType.getInteger(ctx, "amount"))))));
        });

        ClientTickEvents.END_CLIENT_TICK.register(AutoMineMod::tick);

        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            Identifier.of("automine", "esp"),
            AutoMineMod::renderHud);
    }

    // ------------------------------------------------------------ commands

    private static Block parseBlock(FabricClientCommandSource src, String name) {
        Identifier id = Identifier.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            src.sendError(Text.literal("Unknown block: " + name));
            return null;
        }
        return Registries.BLOCK.get(id);
    }

    private static int start(FabricClientCommandSource src, String name, int amount) {
        Block b = parseBlock(src, name);
        if (b == null) return 0;
        targetBlock = b;
        espBlock = b;
        remaining = amount;
        totalAmount = amount;
        current = null;
        lastBreakPos = null;
        targetTicks = 0;
        pickupTicks = 0;
        blacklist.clear();
        fightTarget = null;
        eating = false;
        active = true;
        status = "starting";
        tickCount = 19;
        src.sendFeedback(Text.literal("Mining " + amount + "x " + Registries.BLOCK.getId(b)
            + "... (/automine stop to cancel)"));
        return 1;
    }

    private static void releaseKeys(MinecraftClient mc) {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
        mc.options.useKey.setPressed(false);
    }

    private static void stopAll(FabricClientCommandSource src, String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        active = false;
        current = null;
        fightTarget = null;
        eating = false;
        scanMode = false;
        espBlock = null;
        espPositions.clear();
        releaseKeys(mc);
        if (src != null) src.sendFeedback(Text.literal(msg));
        else if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }

    private static void finish(String msg) {
        MinecraftClient mc = MinecraftClient.getInstance();
        active = false;
        current = null;
        fightTarget = null;
        eating = false;
        if (!scanMode) { espBlock = null; espPositions.clear(); }
        releaseKeys(mc);
        if (mc.player != null) mc.player.sendMessage(Text.literal(msg), false);
    }

    // ------------------------------------------------------------ tick

    private static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            active = false;
            espPositions.clear();
            return;
        }

        tickCount++;
        if (espBlock != null && tickCount % 20 == 0) scanEsp(mc, player);

        if (!active) return;

        // pause while any menu/chat is open
        if (mc.currentScreen != null) {
            releaseKeys(mc);
            return;
        }

        // low health safety
        if (cfgSafety && fightTarget == null && player.getHealth() <= MIN_HEALTH) {
            finish("Stopped: health is low!");
            return;
        }

        // inventory full warning
        if (tickCount % 40 == 0 && player.getInventory().getEmptySlot() == -1 && tickCount - lastFullMsg > 600) {
            lastFullMsg = tickCount;
            player.sendMessage(Text.literal("Inventory is full!"), true);
        }

        if (cfgCombat && handleCombat(mc, player)) { status = "fighting"; return; }
        if (cfgEat && handleEating(mc, player)) { status = "eating"; return; }
        if (cfgPickup && handlePickup(mc, player)) { status = "collecting drops"; return; }

        // ---- mining ----
        if (current != null && mc.world.getBlockState(current).getBlock() != targetBlock) {
            current = null;
            lastBreakPos = null;
            remaining--;
            pickupTicks = 60;
            if (remaining <= 0) {
                finish("Done mining " + totalAmount + " blocks.");
                return;
            }
        }

        if (current == null) {
            current = findNearest(mc, player);
            targetTicks = 0;
            if (current == null) {
                finish("No more reachable blocks within " + SEARCH_RADIUS + " blocks.");
                return;
            }
        }

        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(current);
        aimAt(player, center);
        status = "mining";

        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            eye, center, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            mc.options.forwardKey.setPressed(true);
            return;
        }

        BlockPos breakPos = hit.getBlockPos();

        // progress tracking: breaking a new obstruction counts as progress
        if (!breakPos.equals(lastBreakPos)) {
            lastBreakPos = breakPos;
            targetTicks = 0;
        }
        targetTicks++;
        if (targetTicks > TARGET_TIMEOUT) {
            skipCurrent(mc, "stuck");
            return;
        }

        if (isUnsafe(mc, player, breakPos)) {
            skipCurrent(mc, "unsafe (lava/fall/gravel)");
            return;
        }

        double dist = eye.distanceTo(Vec3d.ofCenter(breakPos));
        if (dist <= REACH) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            equipBestTool(player, mc.world.getBlockState(breakPos));
            mc.interactionManager.updateBlockBreakingProgress(breakPos, hit.getSide());
            player.swingHand(Hand.MAIN_HAND);
        } else {
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
        }
    }

    private static void skipCurrent(MinecraftClient mc, String reason) {
        if (current != null) blacklist.add(current);
        current = null;
        lastBreakPos = null;
        targetTicks = 0;
        releaseKeys(mc);
        if (mc.player != null) mc.player.sendMessage(Text.literal("Skipped a block: " + reason), true);
    }

    /** Lava next to the block, falling blocks above you, or digging down into a hole. */
    private static boolean isUnsafe(MinecraftClient mc, ClientPlayerEntity player, BlockPos pos) {
        if (!cfgSafety) return false;
        for (Direction d : Direction.values()) {
            if (mc.world.getBlockState(pos.offset(d)).isOf(Blocks.LAVA)) return true;
        }
        BlockPos feet = player.getBlockPos();
        boolean sameColumn = pos.getX() == feet.getX() && pos.getZ() == feet.getZ();
        if (sameColumn && mc.world.getBlockState(pos.up()).getBlock() instanceof FallingBlock) return true;
        if (sameColumn && pos.getY() < feet.getY()) {
            BlockState below = mc.world.getBlockState(pos.down());
            if (below.isAir() || below.isOf(Blocks.LAVA)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ pickup

    /** After a block breaks, walk to nearby dropped items for a short time. */
    private static boolean handlePickup(MinecraftClient mc, ClientPlayerEntity player) {
        if (pickupTicks <= 0) return false;
        pickupTicks--;

        ItemEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (ItemEntity e : mc.world.getEntitiesByClass(ItemEntity.class, player.getBoundingBox().expand(8), x -> true)) {
            if (Math.abs(e.getY() - player.getY()) > 3) continue;
            double d = player.distanceTo(e);
            if (d < bestDist) { bestDist = d; best = e; }
        }
        if (best == null || bestDist < 1.0) {
            mc.options.forwardKey.setPressed(false);
            if (best == null) pickupTicks = 0;
            return false;
        }

        // don't chase items through a lava/drop: only walk if the straight path isn't an obvious hazard
        aimAt(player, best.getBoundingBox().getCenter());
        mc.options.forwardKey.setPressed(true);
        mc.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
        return true;
    }

    // ------------------------------------------------------------ combat

    private static boolean handleCombat(MinecraftClient mc, ClientPlayerEntity player) {
        if (fightTarget != null
            && (!fightTarget.isAlive() || fightTarget.isRemoved() || player.distanceTo(fightTarget) > 16)) {
            fightTarget = null;
        }

        if (player.hurtTime > 0) {
            LivingEntity attacker = player.getAttacker();
            if (attacker instanceof Monster && attacker.isAlive() && player.distanceTo(attacker) <= 16) {
                fightTarget = attacker;
            }
        }

        if (fightTarget == null) return false;

        eating = false;
        mc.options.useKey.setPressed(false);

        aimAt(player, fightTarget.getBoundingBox().getCenter());

        if (player.distanceTo(fightTarget) <= ATTACK_RANGE) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            equipBestWeapon(player);
            if (player.getAttackCooldownProgress(0.5f) >= 1.0f) {
                mc.interactionManager.attackEntity(player, fightTarget);
                player.swingHand(Hand.MAIN_HAND);
            }
        } else {
            mc.options.forwardKey.setPressed(true);
            mc.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
        }
        return true;
    }

    private static void equipBestWeapon(ClientPlayerEntity player) {
        int bestSlot = -1, bestScore = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (nearlyBroken(s)) continue;
            int score = s.isIn(ItemTags.SWORDS) ? 2 : (s.isIn(ItemTags.AXES) ? 1 : 0);
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot >= 0) player.getInventory().setSelectedSlot(bestSlot);
    }

    // ------------------------------------------------------------ eating

    private static boolean handleEating(MinecraftClient mc, ClientPlayerEntity player) {
        int food = player.getHungerManager().getFoodLevel();

        if (!eating && food <= HUNGER_START) {
            if (findFoodSlot(player) == -1) {
                if (tickCount - lastNoFoodMsg > 600) {
                    lastNoFoodMsg = tickCount;
                    player.sendMessage(Text.literal("Hungry, but no food in your hotbar!"), true);
                }
                return false;
            }
            eating = true;
        }

        if (eating) {
            int slot = findFoodSlot(player);
            if (food >= HUNGER_STOP || slot == -1) {
                eating = false;
                mc.options.useKey.setPressed(false);
                return false;
            }
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            player.getInventory().setSelectedSlot(slot);
            mc.options.useKey.setPressed(true);
            return true;
        }
        return false;
    }

    private static int findFoodSlot(ClientPlayerEntity player) {
        int best = -1, bestNutrition = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.isEmpty()) continue;
            FoodComponent fc = s.get(DataComponentTypes.FOOD);
            if (fc == null || isBadFood(s)) continue;
            if (fc.nutrition() > bestNutrition) {
                bestNutrition = fc.nutrition();
                best = i;
            }
        }
        return best;
    }

    private static boolean isBadFood(ItemStack s) {
        return s.isOf(Items.ROTTEN_FLESH) || s.isOf(Items.SPIDER_EYE) || s.isOf(Items.PUFFERFISH)
            || s.isOf(Items.POISONOUS_POTATO) || s.isOf(Items.CHORUS_FRUIT) || s.isOf(Items.CHICKEN)
            || s.isOf(Items.GOLDEN_APPLE) || s.isOf(Items.ENCHANTED_GOLDEN_APPLE);
    }

    // ------------------------------------------------------------ helpers

    private static boolean nearlyBroken(ItemStack s) {
        return s.isDamageable() && s.getMaxDamage() - s.getDamage() <= 2;
    }

    private static void aimAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        player.setYaw((float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f);
        player.setPitch((float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * 180.0 / Math.PI));
    }

    private static BlockPos findNearest(MinecraftClient mc, ClientPlayerEntity player) {
        BlockPos origin = player.getBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mc.world.getBlockState(m).getBlock() == targetBlock) {
                        double d = m.getSquaredDistance(origin);
                        if (d < bestDist && !blacklist.contains(m)) {
                            bestDist = d;
                            best = m.toImmutable();
                        }
                    }
                }
            }
        }
        return best;
    }

    private static void equipBestTool(ClientPlayerEntity player, BlockState state) {
        int bestSlot = player.getInventory().getSelectedSlot();
        float bestSpeed = nearlyBroken(player.getInventory().getStack(bestSlot))
            ? 0f : player.getInventory().getStack(bestSlot).getMiningSpeedMultiplier(state);
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (nearlyBroken(s)) continue;
            float sp = s.getMiningSpeedMultiplier(state);
            if (sp > bestSpeed) {
                bestSpeed = sp;
                bestSlot = i;
            }
        }
        player.getInventory().setSelectedSlot(bestSlot);
    }

    // ------------------------------------------------------------ ESP scan

    private static void scanEsp(MinecraftClient mc, ClientPlayerEntity player) {
        if (espBlock == null) return;
        BlockPos origin = player.getBlockPos();
        List<BlockPos> found = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int x = -SEARCH_RADIUS; x <= SEARCH_RADIUS; x++) {
            for (int y = -SEARCH_RADIUS; y <= SEARCH_RADIUS; y++) {
                for (int z = -SEARCH_RADIUS; z <= SEARCH_RADIUS; z++) {
                    m.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    if (mc.world.getBlockState(m).getBlock() == espBlock) {
                        found.add(m.toImmutable());
                    }
                }
            }
        }
        found.sort(Comparator.comparingDouble(p -> p.getSquaredDistance(origin)));
        espPositions.clear();
        for (int i = 0; i < found.size() && i < ESP_MAX; i++) espPositions.add(found.get(i));
    }

    // ------------------------------------------------------------ HUD

    private static void renderHud(DrawContext ctx, RenderTickCounter tickCounter) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        // status line
        if (active && targetBlock != null) {
            String line = "Mayank's AutoMiner v2: " + Registries.BLOCK.getId(targetBlock).getPath()
                + " " + (totalAmount - remaining) + "/" + totalAmount + " [" + status + "]";
            ctx.drawText(mc.textRenderer, Text.literal(line), 6, 6, 0xFFFFFFFF, true);
        }

        if (!cfgEsp || espPositions.isEmpty()) return;

        int w = mc.getWindow().getScaledWidth();
        int h = mc.getWindow().getScaledHeight();
        double cx = w / 2.0, cy = h / 2.0;

        Vec3d eye = player.getEyePos();
        float yaw = player.getYaw(), pitch = player.getPitch();
        Vec3d f = Vec3d.fromPolar(pitch, yaw);
        double yr = Math.toRadians(yaw);
        Vec3d r = new Vec3d(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3d u = r.crossProduct(f);
        double tanHalf = Math.tan(Math.toRadians(mc.options.getFov().getValue()) / 2.0);
        double aspect = (double) w / h;

        double nearestDist = Double.MAX_VALUE;
        double nearestX = cx, nearestY = cy;
        boolean haveNearest = false;

        for (BlockPos pos : espPositions) {
            Vec3d d = Vec3d.ofCenter(pos).subtract(eye);
            double dist = d.length();
            double z = d.dotProduct(f);
            double x = d.dotProduct(r);
            double y = d.dotProduct(u);

            double sx, sy;
            boolean inFront = z > 0.05;
            if (inFront) {
                sx = cx + (x / z) / (tanHalf * aspect) * cx;
                sy = cy - (y / z) / tanHalf * cy;
            } else {
                double len = Math.sqrt(x * x + y * y);
                double ux = len < 1e-4 ? 0 : x / len;
                double uy = len < 1e-4 ? -1 : y / len;
                sx = cx + ux * Math.max(w, h);
                sy = cy - uy * Math.max(w, h);
            }

            if (dist < nearestDist) {
                nearestDist = dist;
                nearestX = sx;
                nearestY = sy;
                haveNearest = true;
            }

            if (inFront && sx > 6 && sx < w - 6 && sy > 6 && sy < h - 6) {
                int ix = (int) sx, iy = (int) sy;
                ctx.fill(ix - 5, iy - 5, ix + 5, iy + 5, 0x6600FF00);
                ctx.fill(ix - 5, iy - 5, ix + 5, iy - 4, 0xFF00FF00);
                ctx.fill(ix - 5, iy + 4, ix + 5, iy + 5, 0xFF00FF00);
                ctx.fill(ix - 5, iy - 5, ix - 4, iy + 5, 0xFF00FF00);
                ctx.fill(ix + 4, iy - 5, ix + 5, iy + 5, 0xFF00FF00);
                ctx.drawText(mc.textRenderer, Text.literal((int) dist + "m"), ix + 8, iy - 4, 0xFF55FF55, true);
            }
        }

        if (haveNearest) {
            drawLine(ctx, w, h, cx, cy, nearestX, nearestY, 0xFFFF0000);
        }
    }

    private static void drawLine(DrawContext ctx, int w, int h, double x0, double y0, double x1, double y1, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        int steps = (int) Math.min(4000, Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps <= 0) return;
        for (int i = 0; i <= steps; i += 2) {
            int px = (int) (x0 + dx * i / steps);
            int py = (int) (y0 + dy * i / steps);
            if (px >= 0 && px < w - 1 && py >= 0 && py < h - 1) {
                ctx.fill(px, py, px + 2, py + 2, color);
            }
        }
    }
}
