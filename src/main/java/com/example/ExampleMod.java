package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
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
                } else {
                    sendMsg("Авто-шахтёр деактивирован", Formatting.RED);
                    stopMovement(client);
                    target = null;
                    pickupTicks = 0;
                }
            }

            if (!active) return;

            try {
                // Подбор после добычи
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

                // Поиск алмаза
                if (target == null) {
                    target = findNearestDiamond(client);
                    if (target == null) {
                        stopMovement(client);
                        return;
                    }
                    sendMsg("Найден алмаз: " + target.getX() + ", " + target.getY() + ", " + target.getZ(), Formatting.AQUA);
                }

                Vec3d eyePos = client.player.getEyePos();
                Vec3d targetCenter = Vec3d.ofCenter(target);
                double dist = eyePos.distanceTo(targetCenter);

                if (dist <= REACH_DISTANCE) {
                    // Добываем алмаз
                    faceTarget(client, targetCenter);
                    client.options.attackKey.setPressed(true);
                    client.options.forwardKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);

                    if (!isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(false);
                        sendMsg("Алмаз добыт! Подбираю...", Formatting.GREEN);
                        pickupTicks = 20;
                    }
                } else {
                    BlockPos playerFeet = client.player.getBlockPos();
                    int deltaY = target.getY() - playerFeet.getY();

                    // Подъём: алмаз выше
                    if (deltaY > 0) {
                        Direction dir = Direction.fromHorizontalDegrees((double) client.player.getYaw());
                        BlockPos frontFeet = playerFeet.add(dir.getVector());
                        BlockPos frontHead = frontFeet.up();
                        BlockPos frontAbove = frontHead.up();

                        boolean feetSolid = !isAirOrDiamondOrBedrock(client.world, frontFeet);
                        boolean headSolid = !isAirOrDiamondOrBedrock(client.world, frontHead);
                        boolean aboveSolid = !isAirOrDiamondOrBedrock(client.world, frontAbove);

                        if (aboveSolid) {
                            // Ломаем самый верхний блок
                            faceTarget(client, Vec3d.ofCenter(frontAbove));
                            client.options.attackKey.setPressed(true);
                            client.options.forwardKey.setPressed(false);
                            client.options.jumpKey.setPressed(false);
                        } else if (headSolid) {
                            // Ломаем блок на уровне головы
                            faceTarget(client, Vec3d.ofCenter(frontHead));
                            client.options.attackKey.setPressed(true);
                            client.options.forwardKey.setPressed(false);
                            client.options.jumpKey.setPressed(false);
                        } else if (feetSolid) {
                            // Можно запрыгнуть на ступеньку
                            client.options.forwardKey.setPressed(true);
                            client.options.jumpKey.setPressed(true);
                            client.options.attackKey.setPressed(false);
                        } else {
                            // Путь свободен, идём вперёд
                            client.options.forwardKey.setPressed(true);
                            client.options.jumpKey.setPressed(false);
                            client.options.attackKey.setPressed(false);
                        }
                        return;
                    }

                    // Спуск: алмаз ниже
                    if (deltaY < 0) {
                        BlockPos below = playerFeet.down();
                        if (!isAirOrDiamondOrBedrock(client.world, below)) {
                            faceTarget(client, Vec3d.ofCenter(below));
                            client.options.attackKey.setPressed(true);
                            client.options.forwardKey.setPressed(false);
                            client.options.jumpKey.setPressed(false);
                            return;
                        }
                    }

                    // Обычное движение по горизонтали или небольшой перепад
                    BlockPos obstacle = findBestObstacle(client, targetCenter);
                    if (obstacle != null) {
                        Vec3d obstacleCenter = Vec3d.ofCenter(obstacle);
                        double distToObstacle = eyePos.distanceTo(obstacleCenter);

                        if (distToObstacle > REACH_DISTANCE) {
                            faceTarget(client, obstacleCenter);
                            client.options.forwardKey.setPressed(true);
                            client.options.attackKey.setPressed(false);
                            client.options.jumpKey.setPressed(false);
                        } else {
                            Direction dir = Direction.fromHorizontalDegrees((double) client.player.getYaw());
                            BlockPos frontFeet = playerFeet.add(dir.getVector());
                            BlockPos frontHead = frontFeet.up();

                            boolean feetSolid = !isAirOrDiamondOrBedrock(client.world, frontFeet);
                            boolean headSolid = !isAirOrDiamondOrBedrock(client.world, frontHead);

                            if (feetSolid && !headSolid) {
                                // Прыгаем на одиночный блок
                                client.options.jumpKey.setPressed(true);
                                client.options.forwardKey.setPressed(true);
                                client.options.attackKey.setPressed(false);
                            } else {
                                // Ломаем препятствие
                                faceTarget(client, obstacleCenter);
                                client.options.attackKey.setPressed(true);
                                client.options.forwardKey.setPressed(false);
                                client.options.jumpKey.setPressed(false);
                            }
                        }
                    } else {
                        // Путь свободен
                        faceTarget(client, targetCenter);
                        client.options.forwardKey.setPressed(true);
                        client.options.attackKey.setPressed(false);
                        client.options.jumpKey.setPressed(false);
                    }
                }
            } catch (Exception e) {
                sendMsg("Ошибка: " + e.getMessage(), Formatting.RED);
                target = null;
                pickupTicks = 0;
                stopMovement(client);
            }
        });

        // Рендеринг (без изменений)
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

    private void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private void faceTarget(MinecraftClient client, Vec3d target) {
        Vec3d eyePos = client.player.getEyePos();
        Vec3d dir = target.subtract(eyePos).normalize();
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double pitch = Math.toDegrees(-Math.asin(dir.y));
        client.player.setYaw((float)yaw);
        client.player.setPitch((float)pitch);
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
                    if (isDiamond(world, pos)) {
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

    private boolean isAirOrDiamondOrBedrock(World world, BlockPos pos) {
        var state = world.getBlockState(pos);
        return state.isAir() || isDiamond(world, pos) || state.isOf(Blocks.BEDROCK);
    }

    /**
     * Возвращает ближайшее препятствие (включая боковые), которое мешает движению к цели.
     * Игнорирует воздух, алмазы и бедрок.
     */
    private BlockPos findBestObstacle(MinecraftClient client, Vec3d targetCenter) {
        Vec3d eyePos = client.player.getEyePos();
        Vec3d dir = targetCenter.subtract(eyePos).normalize();
        double maxDist = eyePos.distanceTo(targetCenter) - 0.5;
        double step = 0.3;
        Vec3d currentPos = eyePos;
        BlockPos lastBlock = null;

        for (double d = 0; d < maxDist; d += step) {
            currentPos = eyePos.add(dir.multiply(d));
            // Центральные позиции
            BlockPos headPos = new BlockPos((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
            BlockPos feetPos = headPos.down();

            // Собираем все позиции для проверки: центральная и соседние по горизонтали (чтобы не застревать боками)
            BlockPos[] positions = {
                headPos, feetPos,
                headPos.east(), headPos.west(), headPos.north(), headPos.south(),
                feetPos.east(), feetPos.west(), feetPos.north(), feetPos.south()
            };

            for (BlockPos pos : positions) {
                if (!pos.equals(lastBlock) && !isAirOrDiamondOrBedrock(client.world, pos)) {
                    // Препятствие найдено, возвращаем его
                    return pos;
                }
            }
            lastBlock = headPos; // отмечаем, что этот участок проверили
        }
        return null;
    }

    private void sendMsg(String msg, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.execute(() -> client.player.sendMessage(
                    Text.literal("[AutoMiner] ").formatted(Formatting.GOLD)
                            .append(Text.literal(msg).formatted(color)),
                    false
            ));
        }
    }
        }
