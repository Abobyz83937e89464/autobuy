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
                } else {
                    sendMsg("Авто-шахтёр деактивирован", Formatting.RED);
                    stopMovement(client);
                    target = null;
                    pickupTicks = 0;
                    escaping = false;
                    avoidingBedrock = false;
                }
            }

            if (!active) return;

            try {
                // Побег из бедроковой ловушки имеет высший приоритет
                if (escaping) {
                    handleEscape(client);
                    return;
                }

                // Проверка на ловушку каждые 2 секунды
                if (client.player.age % 40 == 0 && isTrapped(client)) {
                    startEscape(client);
                    return;
                }

                // Подбор предметов после добычи
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

                // Поиск алмаза (с игнорированием опасных)
                if (target == null) {
                    target = findNearestDiamond(client);
                    if (target == null) {
                        stopMovement(client);
                        return;
                    }
                    sendMsg("Найден алмаз: " + target.getX() + ", " + target.getY() + ", " + target.getZ(), Formatting.AQUA);
                    avoidingBedrock = false;
                }

                Vec3d eyePos = client.player.getEyePos();
                Vec3d targetCenter = Vec3d.ofCenter(target);
                double dist = eyePos.distanceTo(targetCenter);

                // Добыча алмаза, если мы рядом
                if (dist <= REACH_DISTANCE) {
                    faceTarget(client, targetCenter);
                    if (isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(true);
                    } else {
                        client.options.attackKey.setPressed(false);
                    }
                    client.options.forwardKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    client.options.leftKey.setPressed(false);
                    client.options.rightKey.setPressed(false);

                    if (!isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(false);
                        sendMsg("Алмаз добыт! Подбираю...", Formatting.GREEN);
                        pickupTicks = 20;
                    }
                    return;
                }

                // Если выполняется обход бедрока
                if (avoidingBedrock) {
                    handleBedrockAvoidance(client);
                    return;
                }

                // Прямолинейное движение к алмазу с копанием туннеля 1x2
                navigateStraight(client);

            } catch (Exception e) {
                sendMsg("Ошибка: " + e.getMessage(), Formatting.RED);
                target = null;
                pickupTicks = 0;
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
        return isBedrock(client.world, head.north()) &&
               isBedrock(client.world, head.south()) &&
               isBedrock(client.world, head.east()) &&
               isBedrock(client.world, head.west());
    }

    private boolean isBedrock(World world, BlockPos pos) {
        return world.getBlockState(pos).isOf(Blocks.BEDROCK);
    }

    private void startEscape(MinecraftClient client) {
        PlayerInventory inv = client.player.getInventory();
        escapeSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getStack(i);
            if (!stack.isEmpty() && !isTool(stack)) {
                escapeSlot = i;
                break;
            }
        }
        if (escapeSlot != -1) {
            escaping = true;
            escapeTimer = 0;
            sendMsg("Обнаружена бедроковая ловушка! Пытаюсь выбраться...", Formatting.YELLOW);
        } else {
            sendMsg("Нет блоков для побега из ловушки!", Formatting.RED);
        }
    }

    private void handleEscape(MinecraftClient client) {
        if (escapeSlot == -1) {
            escaping = false;
            return;
        }

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
            escaping = false;
            escapeTimer = 0;
            sendMsg("Побег завершён.", Formatting.GREEN);
        }
    }

    private boolean isTool(ItemStack stack) {
        String name = stack.getItem().toString().toLowerCase();
        return name.contains("pickaxe") || name.contains("shovel") ||
               name.contains("axe") || name.contains("hoe");
    }

    // ======================== ПРЯМОЛИНЕЙНОЕ ДВИЖЕНИЕ С КОПАНИЕМ 1x2 ========================
    private void navigateStraight(MinecraftClient client) {
        BlockPos playerFeet = client.player.getBlockPos();
        Vec3d targetCenter = Vec3d.ofCenter(target);

        // Смотрим на цель
        faceTarget(client, targetCenter);
        Direction forward = Direction.fromHorizontalDegrees(client.player.getYaw());
        int deltaY = target.getY() - playerFeet.getY();

        BlockPos frontFeet = playerFeet.add(forward.getVector());
        BlockPos frontHead = frontFeet.up();
        boolean feetSolid = isSolidOrBedrock(client.world, frontFeet);
        boolean headSolid = isSolidOrBedrock(client.world, frontHead);

        // Подъём: цель выше
        if (deltaY > 0) {
            // Проверяем блоки прямо над игроком
            BlockPos aboveFeet = playerFeet.up();       // на уровне головы
            BlockPos aboveHead = playerFeet.up(2);      // над головой
            if (isSolidOrBedrock(client.world, aboveFeet)) {
                safeMine(client, aboveFeet);
                return;
            }
            if (isSolidOrBedrock(client.world, aboveHead)) {
                safeMine(client, aboveHead);
                return;
            }

            // Блоки перед игроком
            if (feetSolid || headSolid) {
                if (feetSolid) {
                    if (isBedrock(client.world, frontFeet)) startBedrockAvoidance(client, forward);
                    else safeMine(client, frontFeet);
                } else {
                    if (isBedrock(client.world, frontHead)) startBedrockAvoidance(client, forward);
                    else safeMine(client, frontHead);
                }
                return;
            }

            // Путь свободен – идём и прыгаем (только если на земле)
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(client.player.isOnGround());
            client.options.attackKey.setPressed(false);
            return;
        }

        // Спуск: цель ниже
        if (deltaY < 0) {
            BlockPos below = playerFeet.down();
            if (isSolidOrBedrock(client.world, below)) {
                if (isBedrock(client.world, below)) startBedrockAvoidance(client, forward);
                else safeMine(client, below);
                return;
            }
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            return;
        }

        // Тот же уровень – туннель 1x2
        if (feetSolid || headSolid) {
            if ((feetSolid && isBedrock(client.world, frontFeet)) ||
                (headSolid && isBedrock(client.world, frontHead))) {
                startBedrockAvoidance(client, forward);
                return;
            }
            if (feetSolid) safeMine(client, frontFeet);
            else safeMine(client, frontHead);
            // Одновременно идём и копаем
            client.options.forwardKey.setPressed(true);
            return;
        }

        // Путь свободен
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    // ======================== ОБХОД БЕДРОКА ========================
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
            stopMovement(client);
            return;
        }

        avoidingBedrock = true;
        avoidTicks = 0;
        avoidOriginalTarget = target;
        sendMsg("Обхожу бедрок...", Formatting.YELLOW);
    }

    private void handleBedrockAvoidance(MinecraftClient client) {
        avoidTicks++;
        if (avoidTicks <= 3) {
            faceDirection(client, avoidDirection);
            client.options.leftKey.setPressed(false);
            client.options.rightKey.setPressed(false);
            if (avoidDirection == Direction.fromHorizontalDegrees(client.player.getYaw() + 90)) {
                client.options.rightKey.setPressed(true);
            } else if (avoidDirection == Direction.fromHorizontalDegrees(client.player.getYaw() - 90)) {
                client.options.leftKey.setPressed(true);
            }
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

    private boolean canBreak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.isOf(Blocks.BEDROCK);
    }

    // Методы, которые были в старых версиях, теперь оставлены для совместимости
    private boolean isSolid(World world, BlockPos pos) {
        return isSolidOrBedrock(world, pos) && !isBedrock(world, pos);
    }

    private BlockPos findBestObstacle(MinecraftClient client, Vec3d targetCenter) {
        // Больше не используется, но метод сохранён
        return null;
    }

    private void faceBlock(MinecraftClient client, BlockPos pos) {
        Vec3d center = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
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
                        if (dist < nearestDist) {
                            nearestDist = dist;
                            nearest = pos;
                        }
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
        for (int dy = 1; dy <= 3; dy++) {
            if (world.getBlockState(pos.down(dy)).isOf(Blocks.LAVA)) return false;
        }
        if (isBedrock(world, pos.up())) return false;
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
