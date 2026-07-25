package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "autominer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean initialized = false;

    private boolean active = false;
    private BlockPos target = null;
    private int pickupTicks = 0;

    private boolean escaping = false;
    private int escapeTimer = 0;
    private int escapeSlot = -1;

    private boolean avoidingBedrock = false;
    private int avoidTicks = 0;
    private Direction avoidDirection = null;
    private BlockPos avoidOriginalTarget = null;

    // Защита от дёргания: запоминаем, какой блок мы сейчас копаем, и не переключаемся, пока он не исчез
    private BlockPos currentMineTarget = null;
    private int mineStuckTicks = 0; // счётчик, чтобы не залипнуть навсегда

    private static final double REACH_DISTANCE = 2.5;
    private static final int SCAN_RADIUS = 50;

    @Override
    public void onInitialize() {
        initLogic();
    }

    @Override
    public void onInitializeClient() {
        initLogic();
    }

    private synchronized void initLogic() {
        if (initialized) return;
        initialized = true;

        LOGGER.info("[AutoMiner] Мод успешно инициализирован!");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autominer.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories.misc"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            if (toggleKey.wasPressed()) {
                active = !active;
                if (active) {
                    sendMsg("Авто-шахтёр активирован", Formatting.GREEN);
                    target = null;
                    pickupTicks = 0;
                    escaping = false;
                    avoidingBedrock = false;
                    currentMineTarget = null;
                } else {
                    sendMsg("Авто-шахтёр деактивирован", Formatting.RED);
                    stopMovement(client);
                    target = null;
                    pickupTicks = 0;
                    escaping = false;
                    avoidingBedrock = false;
                    currentMineTarget = null;
                }
            }

            if (!active) return;

            try {
                if (escaping) {
                    handleEscape(client);
                    return;
                }

                if (client.player.age % 40 == 0 && isTrapped(client)) {
                    startEscape(client);
                    return;
                }

                if (pickupTicks > 0) {
                    pickupTicks--;
                    Vec3d targetCenter = Vec3d.ofCenter(target);
                    double dist = client.player.getEyePos().distanceTo(targetCenter);
                    if (dist > 0.5) {
                        faceTarget(client, targetCenter);
                        client.options.forwardKey.setPressed(true);
                    } else {
                        client.options.forwardKey.setPressed(false);
                    }
                    client.options.attackKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    if (pickupTicks == 0) {
                        target = null;
                    }
                    return;
                }

                if (target == null) {
                    target = findNearestDiamond(client);
                    if (target == null) {
                        stopMovement(client);
                        return;
                    }
                    sendMsg("Найден алмаз: " + target.getX() + ", " + target.getY() + ", " + target.getZ(), Formatting.AQUA);
                    avoidingBedrock = false;
                    currentMineTarget = null;
                }

                Vec3d eyePos = client.player.getEyePos();
                Vec3d targetCenter = Vec3d.ofCenter(target);
                double dist = eyePos.distanceTo(targetCenter);

                if (dist <= REACH_DISTANCE) {
                    faceTarget(client, targetCenter);
                    client.options.attackKey.setPressed(isDiamond(client.world, target));
                    client.options.forwardKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    client.options.leftKey.setPressed(false);
                    client.options.rightKey.setPressed(false);

                    if (!isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(false);
                        sendMsg("Алмаз добыт! Подбираю...", Formatting.GREEN);
                        pickupTicks = 20;
                        currentMineTarget = null;
                    }
                    return;
                }

                if (avoidingBedrock) {
                    handleBedrockAvoidance(client);
                    return;
                }

                // Прямолинейное движение с защитой от дёргания
                navigateStraight(client);

            } catch (Exception e) {
                sendMsg("Ошибка: " + e.getMessage(), Formatting.RED);
                target = null;
                pickupTicks = 0;
                currentMineTarget = null;
                stopMovement(client);
            }
        });

        // Рендеринг трассера и обводки (без изменений)
        WorldRenderEvents.LAST.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || !active || target == null) return;

            Vec3d eyePos = client.player.getEyePos();
            Vec3d targetCenter = Vec3d.ofCenter(target);
            Vec3d camPos = context.camera().getPos();

            MatrixStack matrices = context.matrixStack();
            matrices.push();
            matrices.translate(-camPos.x, -camPos.y, -camPos.z);

            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);

            buffer.vertex((float)(eyePos.x - camPos.x), (float)(eyePos.y - camPos.y), (float)(eyePos.z - camPos.z)).color(1.0f, 1.0f, 0.0f, 1.0f);
            buffer.vertex((float)(targetCenter.x - camPos.x), (float)(targetCenter.y - camPos.y), (float)(targetCenter.z - camPos.z)).color(1.0f, 1.0f, 0.0f, 1.0f);

            float minX = (float)(target.getX() - camPos.x);
            float minY = (float)(target.getY() - camPos.y);
            float minZ = (float)(target.getZ() - camPos.z);
            float maxX = minX + 1.0f;
            float maxY = minY + 1.0f;
            float maxZ = minZ + 1.0f;

            // Нижняя грань
            buffer.vertex(minX, minY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, minY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, minY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, minY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, minY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, minY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, minY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, minY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);

            // Верхняя грань
            buffer.vertex(minX, maxY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, maxY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, maxY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, maxY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, maxY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, maxY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, maxY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, maxY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);

            // Вертикали
            buffer.vertex(minX, minY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, maxY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, minY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, maxY, minZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, minY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(maxX, maxY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, minY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);
            buffer.vertex(minX, maxY, maxZ).color(1.0f, 1.0f, 1.0f, 1.0f);

            BufferRenderer.drawWithGlobalProgram(buffer.end());
            matrices.pop();
        });
    }

    // ======================== ПОБЕГ ИЗ БЕДРОКОВОЙ ЛОВУШКИ ========================
    private boolean isTrapped(MinecraftClient client) {
        BlockPos head = client.player.getBlockPos().up();
        return isBedrock(client.world, head.north()) && isBedrock(client.world, head.south()) &&
               isBedrock(client.world, head.east()) && isBedrock(client.world, head.west());
    }

    private boolean isBedrock(World world, BlockPos pos) {
        return world.getBlockState(pos).isOf(Blocks.BEDROCK);
    }

    private void startEscape(MinecraftClient client) {
        PlayerInventory inv = client.player.getInventory();
        escapeSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && !isTool(stack)) { escapeSlot = i; break; }
        }
        if (escapeSlot != -1) {
            escaping = true; escapeTimer = 0;
            sendMsg("Обнаружена бедроковая ловушка! Пытаюсь выбраться...", Formatting.YELLOW);
        } else sendMsg("Нет блоков для побега из ловушки!", Formatting.RED);
    }

    private void handleEscape(MinecraftClient client) {
        if (escapeSlot == -1) { escaping = false; return; }
        client.player.getInventory().selectedSlot = escapeSlot;
        client.player.setPitch(90.0f);
        escapeTimer++;
        if (escapeTimer <= 10) {
            client.options.useKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.attackKey.setPressed(false);
        } else if (escapeTimer <= 15) {
            client.options.useKey.setPressed(false);
            client.options.jumpKey.setPressed(true);
        } else {
            client.options.jumpKey.setPressed(false);
            client.options.useKey.setPressed(false);
            escaping = false; escapeTimer = 0;
            sendMsg("Побег завершён.", Formatting.GREEN);
        }
    }

    private boolean isTool(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        return name.contains("pickaxe") || name.contains("shovel") ||
               name.contains("axe") || name.contains("hoe");
    }

    // ======================== ПРЯМОЛИНЕЙНОЕ ДВИЖЕНИЕ (БЕЗ ДЁРГАНЬЯ) ========================
    private void navigateStraight(MinecraftClient client) {
        BlockPos playerFeet = client.player.getBlockPos();
        Direction forward = Direction.fromHorizontalDegrees(client.player.getYaw());

        // Если мы уже копаем какой-то блок и он ещё существует, продолжаем его копать и не переключаемся
        if (currentMineTarget != null) {
            if (isSolidOrBedrock(client.world, currentMineTarget) && !isBedrock(client.world, currentMineTarget)) {
                // Блок всё ещё на месте, продолжаем
                safeMine(client, currentMineTarget);
                mineStuckTicks++;
                if (mineStuckTicks > 40) { // больше 2 секунд — сбрасываем, возможно застряли
                    currentMineTarget = null;
                    mineStuckTicks = 0;
                }
                return;
            } else {
                // Блок исчез, сбрасываем
                currentMineTarget = null;
                mineStuckTicks = 0;
            }
        }

        // Основная логика выбора цели для копания или движения
        int deltaY = target.getY() - playerFeet.getY();
        BlockPos frontFeet = playerFeet.add(forward.getVector());
        BlockPos frontHead = frontFeet.up();
        BlockPos frontAbove = frontFeet.up(2);

        // Подъём
        if (deltaY > 0) {
            BlockPos aboveFeet = playerFeet.up();
            BlockPos aboveHead = playerFeet.up(2);
            if (isSolidOrBedrock(client.world, aboveFeet)) {
                currentMineTarget = aboveFeet;
                safeMine(client, aboveFeet);
                return;
            }
            if (isSolidOrBedrock(client.world, aboveHead)) {
                currentMineTarget = aboveHead;
                safeMine(client, aboveHead);
                return;
            }

            if (isSolidOrBedrock(client.world, frontAbove)) {
                if (isBedrock(client.world, frontAbove)) { startBedrockAvoidance(client, forward); return; }
                currentMineTarget = frontAbove;
                safeMine(client, frontAbove);
                return;
            }
            if (isSolidOrBedrock(client.world, frontHead)) {
                if (isBedrock(client.world, frontHead)) { startBedrockAvoidance(client, forward); return; }
                currentMineTarget = frontHead;
                safeMine(client, frontHead);
                return;
            }

            if (isSolidOrBedrock(client.world, frontFeet)) {
                if (isBedrock(client.world, frontFeet)) { startBedrockAvoidance(client, forward); return; }
                // Ступенька, идём и прыгаем
                client.options.forwardKey.setPressed(true);
                client.options.jumpKey.setPressed(client.player.isOnGround());
                client.options.attackKey.setPressed(false);
                return;
            } else {
                // Свободный путь вперёд
                client.options.forwardKey.setPressed(true);
                client.options.jumpKey.setPressed(false);
                client.options.attackKey.setPressed(false);
                return;
            }
        }

        // Спуск
        if (deltaY < 0) {
            BlockPos below = playerFeet.down();
            if (isSolidOrBedrock(client.world, below)) {
                if (isBedrock(client.world, below)) { startBedrockAvoidance(client, forward); return; }
                currentMineTarget = below;
                safeMine(client, below);
                return;
            }
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            return;
        }

        // Горизонталь
        boolean feetBlocked = isSolidOrBedrock(client.world, frontFeet);
        boolean headBlocked = isSolidOrBedrock(client.world, frontHead);

        if ((feetBlocked && isBedrock(client.world, frontFeet)) ||
            (headBlocked && isBedrock(client.world, frontHead))) {
            startBedrockAvoidance(client, forward);
            return;
        }

        if (feetBlocked) {
            currentMineTarget = frontFeet;
            safeMine(client, frontFeet);
            client.options.forwardKey.setPressed(true);
            return;
        }
        if (headBlocked) {
            currentMineTarget = frontHead;
            safeMine(client, frontHead);
            client.options.forwardKey.setPressed(true);
            return;
        }

        // Свободный путь
        faceTarget(client, Vec3d.ofCenter(target)); // плавно смотрим на алмаз
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    // ======================== ОБХОД БЕДРОКА (с защитой от зацикливания) ========================
    private void startBedrockAvoidance(MinecraftClient client, Direction blockedDir) {
        Direction rightDir = blockedDir.rotateYClockwise();
        Direction leftDir = blockedDir.rotateYCounterclockwise();
        BlockPos playerFeet = client.player.getBlockPos();
        if (isPassable(client.world, playerFeet.add(rightDir.getVector())) &&
            isPassable(client.world, playerFeet.add(rightDir.getVector()).up())) {
            avoidDirection = rightDir;
        } else if (isPassable(client.world, playerFeet.add(leftDir.getVector())) &&
                   isPassable(client.world, playerFeet.add(leftDir.getVector()).up())) {
            avoidDirection = leftDir;
        } else {
            avoidingBedrock = false;
            stopMovement(client);
            target = null;
            currentMineTarget = null;
            sendMsg("Нет пути для обхода бедрока, ищу другой алмаз.", Formatting.RED);
            return;
        }

        avoidingBedrock = true;
        avoidTicks = 0;
        avoidOriginalTarget = target;
        currentMineTarget = null;
        sendMsg("Обхожу бедрок...", Formatting.YELLOW);
    }

    private void handleBedrockAvoidance(MinecraftClient client) {
        avoidTicks++;
        if (avoidTicks <= 3) {
            faceDirection(client, avoidDirection);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            if (avoidDirection == Direction.fromHorizontalDegrees(client.player.getYaw() + 90))
                client.options.rightKey.setPressed(true);
            else if (avoidDirection == Direction.fromHorizontalDegrees(client.player.getYaw() - 90))
                client.options.leftKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        } else if (avoidTicks <= 6) {
            faceTarget(client, Vec3d.ofCenter(avoidOriginalTarget));
            client.options.forwardKey.setPressed(true);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        } else {
            avoidingBedrock = false;
            avoidTicks = 0;
            stopMovement(client);
        }
    }

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (НИЧЕГО НЕ УРЕЗАНО) ========================
    private void safeMine(MinecraftClient client, BlockPos pos) {
        if (!canBreak(client.world, pos)) return;
        faceBlock(client, pos);
        client.options.attackKey.setPressed(true);
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void safeMineVertical(MinecraftClient client, BlockPos pos) {
        if (!canBreak(client.world, pos)) return;
        Vec3d eyePos = client.player.getEyePos();
        Vec3d target = Vec3d.ofCenter(pos);
        Vec3d dir = target.subtract(eyePos).normalize();
        double horizontalDist = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        if (horizontalDist < 0.1) {
            client.player.setPitch((float) Math.toDegrees(-Math.asin(dir.y)));
        } else {
            faceTarget(client, target);
        }
        client.options.attackKey.setPressed(true);
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private boolean canBreak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.isOf(Blocks.BEDROCK);
    }

    private boolean isSolid(World world, BlockPos pos) {
        return isSolidOrBedrock(world, pos) && !isBedrock(world, pos);
    }

    private BlockPos findBestObstacle(MinecraftClient client, Vec3d targetCenter) {
        return null;
    }

    private void faceBlock(MinecraftClient client, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        faceTarget(client, center);
    }

    private void faceDirection(MinecraftClient client, Direction dir) {
        float yaw = switch (dir) {
            case NORTH -> 180;
            case SOUTH -> 0;
            case WEST -> 90;
            case EAST -> -90;
            default -> client.player.getYaw();
        };
        client.player.setYaw(yaw);
        client.player.setPitch(0);
    }

    private void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    private void faceTarget(MinecraftClient client, Vec3d target) {
        Vec3d eye = client.player.getEyePos();
        Vec3d dir = target.subtract(eye).normalize();
        client.player.setYaw((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
        client.player.setPitch((float) Math.toDegrees(-Math.asin(dir.y)));
    }

    private BlockPos findNearestDiamond(MinecraftClient client) {
        World world = client.world;
        BlockPos playerPos = client.player.getBlockPos();
        BlockPos nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
            for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (isDiamond(world, pos) && isValidDiamondTarget(world, pos)) {
                        double dist = playerPos.getSquaredDistance(pos);
                        if (dist < nearestDist) { nearestDist = dist; nearest = pos; }
                    }
                }
            }
        }
        return nearest;
    }

    private boolean isDiamond(World world, BlockPos pos) {
        return world.getBlockState(pos).isOf(Blocks.DIAMOND_ORE) ||
               world.getBlockState(pos).isOf(Blocks.DEEPSLATE_DIAMOND_ORE);
    }

    private boolean isValidDiamondTarget(World world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (world.getBlockState(pos.offset(direction)).isOf(Blocks.LAVA)) return false;
        }
        if (world.getBlockState(pos.up()).isOf(Blocks.BEDROCK)) return false;
        if (isBedrock(world, pos.north()) || isBedrock(world, pos.south()) ||
            isBedrock(world, pos.east()) || isBedrock(world, pos.west())) return false;
        return true;
    }

    private boolean isSolidOrBedrock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !isDiamond(world, pos);
    }

    private boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || isDiamond(world, pos);
    }

    private void sendMsg(String msg, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.execute(() -> client.player.sendMessage(
                    Text.literal("[AutoMiner] ").formatted(Formatting.GOLD)
                            .append(Text.literal(msg).formatted(color)), false));
        }
    }
}
