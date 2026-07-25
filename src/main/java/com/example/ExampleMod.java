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
    private boolean mining = false;

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
                    mining = false;
                } else {
                    sendMsg("Авто-шахтёр деактивирован", Formatting.RED);
                    stopMovement(client);
                    target = null;
                    mining = false;
                }
            }

            if (!active) return;

            try {
                if (target == null) {
                    target = findNearestDiamond(client);
                    if (target == null) {
                        sendMsg("Алмазы не найдены в радиусе " + SCAN_RADIUS + " блоков", Formatting.YELLOW);
                        active = false;
                        return;
                    }
                    sendMsg("Найден алмаз: " + target.getX() + ", " + target.getY() + ", " + target.getZ(), Formatting.AQUA);
                }

                Vec3d eyePos = client.player.getEyePos();
                Vec3d targetCenter = Vec3d.ofCenter(target);
                double dist = eyePos.distanceTo(targetCenter);

                if (dist <= REACH_DISTANCE) {
                    faceTarget(client, targetCenter);
                    client.options.attackKey.setPressed(true);
                    mining = true;

                    if (!isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(false);
                        sendMsg("Алмаз добыт! Отключаюсь.", Formatting.GREEN);
                        active = false;
                        target = null;
                        mining = false;
                    }
                } else {
                    if (mining) {
                        client.options.attackKey.setPressed(false);
                        mining = false;
                    }
                    faceTarget(client, targetCenter);
                    client.options.forwardKey.setPressed(true);

                    Vec3d lookDir = client.player.getRotationVec(1.0F);
                    Vec3d hitPos = eyePos.add(lookDir.multiply(0.5, 0.5, 0.5));
                    BlockPos frontBlock = new BlockPos((int)Math.floor(hitPos.x), (int)Math.floor(hitPos.y), (int)Math.floor(hitPos.z));
                    if (!client.world.getBlockState(frontBlock).isAir() &&
                        !client.world.getBlockState(frontBlock).isOf(Blocks.DIAMOND_ORE) &&
                        !client.world.getBlockState(frontBlock).isOf(Blocks.DEEPSLATE_DIAMOND_ORE)) {
                        client.options.attackKey.setPressed(true);
                    } else {
                        client.options.attackKey.setPressed(false);
                    }
                }
            } catch (Exception e) {
                sendMsg("Ошибка: " + e.getMessage(), Formatting.RED);
                active = false;
                stopMovement(client);
                target = null;
                mining = false;
            }
        });

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

            // Линия от глаз до цели
            buffer.vertex((float)(eyePos.x - camPos.x), (float)(eyePos.y - camPos.y), (float)(eyePos.z - camPos.z)).color(1.0f, 1.0f, 0.0f, 1.0f);
            buffer.vertex((float)(targetCenter.x - camPos.x), (float)(targetCenter.y - camPos.y), (float)(targetCenter.z - camPos.z)).color(1.0f, 1.0f, 0.0f, 1.0f);

            // Обводка
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
