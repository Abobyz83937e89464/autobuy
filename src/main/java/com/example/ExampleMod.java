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
                // Режим подбора после добычи алмаза
                if (pickupTicks > 0) {
                    pickupTicks--;
                    // Идём вперёд к центру бывшего алмаза
                    faceTarget(client, Vec3d.ofCenter(target));
                    client.options.forwardKey.setPressed(true);
                    client.options.attackKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    if (pickupTicks == 0) {
                        target = null; // поиск следующего алмаза
                    }
                    return;
                }

                // Поиск алмаза, если цель отсутствует
                if (target == null) {
                    target = findNearestDiamond(client);
                    if (target == null) {
                        stopMovement(client);
                        return;
                    }
                    sendMsg("Найден алмаз: " + target.getX() + ", " + target.getY() + ", " + target.getZ(), Formatting.AQUA);
                }

                Vec3d targetCenter = Vec3d.ofCenter(target);
                double dist = client.player.getEyePos().distanceTo(targetCenter);

                if (dist <= REACH_DISTANCE) {
                    // Добываем алмаз
                    faceTarget(client, targetCenter);
                    client.options.attackKey.setPressed(true);
                    client.options.forwardKey.setPressed(false);
                    client.options.jumpKey.setPressed(false);
                    if (!isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(false);
                        sendMsg("Алмаз добыт! Подбираю...", Formatting.GREEN);
                        pickupTicks = 20; // 1 секунда на подбор
                    }
                } else {
                    // Прокладываем путь
                    navigateTo(client, target);
                }
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

            // Линия от глаз к цели (жёлтая)
            buffer.vertex((float)(eyePos.x - camPos.x), (float)(eyePos.y - camPos.y), (float)(eyePos.z - camPos.z)).color(1.0f, 1.0f, 0.0f, 1.0f);
            buffer.vertex((float)(targetCenter.x - camPos.x), (float)(targetCenter.y - camPos.y), (float)(targetCenter.z - camPos.z)).color(1.0f, 1.0f, 0.0f, 1.0f);

            // Обводка куба цели (белая)
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
            // Вертикальные рёбра
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

    /**
     * Интеллектуальная навигация к алмазу с учётом разницы высот.
     */
    private void navigateTo(MinecraftClient client, BlockPos targetBlock) {
        faceTarget(client, Vec3d.ofCenter(targetBlock));
        float yaw = client.player.getYaw();
        Direction dir = Direction.fromHorizontalDegrees((double) yaw);

        BlockPos playerFeet = client.player.getBlockPos(); // позиция ног
        BlockPos frontFeet = playerFeet.add(dir.getVector()); // прямо впереди на уровне пола
        BlockPos frontHead = frontFeet.up(); // блок на уровне головы
        BlockPos frontAbove = frontFeet.up(2); // блок над головой

        // Разница по высоте между алмазом и игроком
        int deltaY = targetBlock.getY() - playerFeet.getY();

        // Проверяем, является ли блок твёрдым (не воздух, не алмаз)
        boolean feetSolid = !client.world.getBlockState(frontFeet).isAir() && !isDiamond(client.world, frontFeet);
        boolean headSolid = !client.world.getBlockState(frontHead).isAir() && !isDiamond(client.world, frontHead);
        boolean aboveSolid = !client.world.getBlockState(frontAbove).isAir() && !isDiamond(client.world, frontAbove);

        // Если цель выше – приоритет копать вверх
        if (deltaY > 1) {
            // Сначала ломаем блок над головой, если есть
            if (aboveSolid) {
                faceTarget(client, Vec3d.ofCenter(frontAbove));
                client.options.attackKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                return;
            }
            // Затем ломаем блок на уровне головы
            if (headSolid) {
                faceTarget(client, Vec3d.ofCenter(frontHead));
                client.options.attackKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                return;
            }
            // Если оба свободны, идём вперёд (персонаж будет подниматься за счёт механики ступенек, если пол выше)
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(feetSolid && !headSolid); // прыгаем, если есть препятствие под ногами и нет над головой
            return;
        }

        // Если цель ниже – приоритет копать вниз
        if (deltaY < 0) {
            // Проверяем блок прямо перед ногами на уровне пола
            if (feetSolid) {
                faceTarget(client, Vec3d.ofCenter(frontFeet));
                client.options.attackKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                return;
            }
            // Если впереди свободно, идём вперёд
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            return;
        }

        // Цель примерно на той же высоте или на 1 блок выше (нужен прыжок)
        if (deltaY == 1) {
            // Если есть блок на уровне головы – убираем его, потом прыгнем
            if (headSolid) {
                faceTarget(client, Vec3d.ofCenter(frontHead));
                client.options.attackKey.setPressed(true);
                client.options.forwardKey.setPressed(false);
                client.options.jumpKey.setPressed(false);
                return;
            }
            // Если блок под ногами и нет над головой – прыгаем
            if (feetSolid && !headSolid) {
                client.options.attackKey.setPressed(false);
                client.options.forwardKey.setPressed(true);
                client.options.jumpKey.setPressed(true);
                return;
            }
            // Иначе просто идём
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            return;
        }

        // deltaY == 0 – обычное движение вперёд, убирая препятствия
        if (headSolid || feetSolid) {
            // Ломаем верхний блок, если есть, чтобы пройти
            faceTarget(client, Vec3d.ofCenter(headSolid ? frontHead : frontFeet));
            client.options.attackKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
            return;
        }
        // Свободный путь – идём
        client.options.attackKey.setPressed(false);
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(false);
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
