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
                } else {
                    sendMsg("Авто-шахтёр деактивирован", Formatting.RED);
                    stopMovement(client);
                    target = null;
                    pickupTicks = 0;
                    escaping = false;
                }
            }

            if (!active) return;

            try {
                // Побег из бедроковой ловушки
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

                // Добыча алмаза
                if (dist <= REACH_DISTANCE) {
                    faceTarget(client, targetCenter);
                    if (isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(true);
                    } else {
                        client.options.attackKey.setPressed(false);
                    }
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

                    // Подъём
                    if (deltaY > 0) {
                        Direction dir = Direction.fromHorizontalDegrees((double) client.player.getYaw());
                        BlockPos frontFeet = playerFeet.add(dir.getVector());
                        BlockPos frontHead = frontFeet.up();
                        BlockPos frontAbove = frontHead.up();

                        boolean feetSolid = isSolid(client.world, frontFeet);
                        boolean headSolid = isSolid(client.world, frontHead);
                        boolean aboveSolid = isSolid(client.world, frontAbove);

                        if (aboveSolid) {
                            safeMine(client, frontAbove);
                        } else if (headSolid) {
                            safeMine(client, frontHead);
                        } else if (feetSolid) {
                            if (isAir(client.world, frontFeet.up(2)) && canBreak(client.world, frontFeet)) {
                                client.options.jumpKey.setPressed(true);
                                client.options.forwardKey.setPressed(true);
                                client.options.attackKey.setPressed(false);
                            } else {
                                safeMine(client, frontFeet);
                            }
                        } else {
                            client.options.forwardKey.setPressed(true);
                            client.options.jumpKey.setPressed(false);
                            client.options.attackKey.setPressed(false);
                        }
                        return;
                    }

                    // Спуск
                    if (deltaY < 0) {
                        BlockPos below = playerFeet.down();
                        if (isSolid(client.world, below)) {
                            safeMine(client, below);
                            return;
                        }
                    }

                    // Горизонтальное движение с улучшенным обнаружением препятствий
                    BlockPos obstacle = findBestObstacle(client, targetCenter);
                    if (obstacle != null) {
                        // Препятствие найдено, ломаем его (бот повернётся к нему автоматически в safeMine)
                        // Если препятствие нельзя сломать (бедрок), safeMine ничего не делает – тогда бот будет стоять.
                        // Чтобы не застревать, можно добавить обход, но пока оставим.
                        safeMine(client, obstacle);
                    } else {
                        // Путь свободен – идём прямо к цели
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

    // ======================== ДВИЖЕНИЕ И ДОБЫЧА ========================
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

    private void safeMine(MinecraftClient client, BlockPos targetBlock) {
        if (canBreak(client.world, targetBlock)) {
            faceTarget(client, Vec3d.ofCenter(targetBlock));
            client.options.attackKey.setPressed(true);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        } else {
            // Блок нельзя сломать (бедрок). Стоим на месте, возможно, нужно обходить.
            // Пока ничего не делаем, чтобы не было ненужных движений.
            client.options.attackKey.setPressed(false);
            client.options.forwardKey.setPressed(false);
            client.options.jumpKey.setPressed(false);
        }
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

    /**
     * Точечные фильтры: игнорирует алмазы, которые невозможно/опасно добывать.
     */
    private boolean isValidDiamondTarget(World world, BlockPos pos) {
        // Над лавой? (проверяем 3 блока вниз)
        for (int dy = 1; dy <= 3; dy++) {
            if (world.getBlockState(pos.down(dy)).isOf(Blocks.LAVA)) {
                return false;
            }
        }
        // Под бедроком?
        if (world.getBlockState(pos.up()).isOf(Blocks.BEDROCK)) {
            return false;
        }
        // На уровне бедрока (соседи по горизонтали)?
        if (isBedrock(world, pos.north()) || isBedrock(world, pos.south()) ||
            isBedrock(world, pos.east()) || isBedrock(world, pos.west())) {
            return false;
        }
        return true;
    }

    private boolean isAir(World world, BlockPos pos) {
        return world.getBlockState(pos).isAir();
    }

    /** Блок твёрдый, но не бедрок и не алмаз */
    private boolean isSolid(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !isDiamond(world, pos) && !state.isOf(Blocks.BEDROCK);
    }

    /** Можно ли сломать блок (не воздух, не бедрок) */
    private boolean canBreak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.isOf(Blocks.BEDROCK);
    }

    /**
     * Ищет ближайшее препятствие на пути к цели с учётом боковых блоков (ширина игрока).
     * Возвращает позицию препятствия или null, если путь свободен.
     */
    private BlockPos findBestObstacle(MinecraftClient client, Vec3d targetCenter) {
        Vec3d eyePos = client.player.getEyePos();
        Vec3d dir = targetCenter.subtract(eyePos).normalize();
        double maxDist = eyePos.distanceTo(targetCenter) - 0.5;
        double step = 0.3;
        // Вектор вправо для проверки боковых точек
        Vec3d right = dir.crossProduct(new Vec3d(0, 1, 0)).normalize();
        double halfWidth = 0.3; // половина ширины игрока

        Vec3d currentPos = eyePos;
        BlockPos lastHead = null;

        for (double d = 0; d < maxDist; d += step) {
            currentPos = eyePos.add(dir.multiply(d));
            // Проверяем три точки на каждом уровне: центр, влево, вправо
            Vec3d[] offsets = {
                currentPos,
                currentPos.add(right.multiply(halfWidth)),
                currentPos.add(right.multiply(-halfWidth))
            };

            for (Vec3d point : offsets) {
                BlockPos headPos = new BlockPos((int)Math.floor(point.x), (int)Math.floor(point.y), (int)Math.floor(point.z));
                BlockPos feetPos = headPos.down();

                // Проверяем голову и ноги
                if (!headPos.equals(lastHead)) { // небольшая оптимизация
                    if (isSolid(client.world, headPos)) return headPos;
                    if (isSolid(client.world, feetPos)) return feetPos;
                }
            }
            lastHead = new BlockPos((int)Math.floor(currentPos.x), (int)Math.floor(currentPos.y), (int)Math.floor(currentPos.z));
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
