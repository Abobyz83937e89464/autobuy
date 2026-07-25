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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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

                    if (!isDiamond(client.world, target)) {
                        client.options.attackKey.setPressed(false);
                        sendMsg("Алмаз добыт! Подбираю...", Formatting.GREEN);
                        pickupTicks = 20;
                    }
                    return;
                }

                // === ПРЯМОЛИНЕЙНОЕ ДВИЖЕНИЕ К АЛМАЗУ ===
                // Всегда смотрим на цель
                faceTarget(client, targetCenter);

                // Получаем луч от глаз в направлении взгляда
                Vec3d lookVec = client.player.getRotationVec(1.0F);
                // Ищем первый блок на пути, который не является воздухом/алмазом/бедроком (ломаемый)
                BlockPos obstacle = findFirstBreakableBlock(client, eyePos, lookVec, dist);

                // Проверяем, есть ли неломаемое препятствие (бедрок) прямо перед нами
                BlockPos bedrockInFront = findBedrockInFront(client, eyePos, lookVec, 2.0);

                // Если бедрок впереди, пробуем обойти
                if (bedrockInFront != null && obstacle == null) {
                    // Бедрок, но нет ломаемого блока – пытаемся сместиться в сторону
                    Vec3d right = lookVec.crossProduct(new Vec3d(0, 1, 0)).normalize();
                    // Проверяем, свободно ли справа или слева
                    BlockPos rightPos = client.player.getBlockPos().add(Direction.fromHorizontalDegrees(client.player.getYaw() + 90).getVector());
                    BlockPos leftPos = client.player.getBlockPos().add(Direction.fromHorizontalDegrees(client.player.getYaw() - 90).getVector());
                    if (isPassable(client.world, rightPos) && isPassable(client.world, rightPos.up())) {
                        // Смещаемся вправо
                        client.options.leftKey.setPressed(false);
                        client.options.rightKey.setPressed(true);
                        client.options.forwardKey.setPressed(false);
                        client.options.attackKey.setPressed(false);
                        client.options.jumpKey.setPressed(false);
                    } else if (isPassable(client.world, leftPos) && isPassable(client.world, leftPos.up())) {
                        client.options.rightKey.setPressed(false);
                        client.options.leftKey.setPressed(true);
                    } else {
                        // Не можем обойти, стоим на месте
                        stopMovement(client);
                    }
                    return;
                }

                // Обычное движение: копаем препятствие или идём
                if (obstacle != null && canBreak(client.world, obstacle)) {
                    // Ломаем препятствие, одновременно идём вперёд
                    client.options.attackKey.setPressed(true);
                    client.options.forwardKey.setPressed(true);
                    client.options.jumpKey.setPressed(false);
                } else if (obstacle != null && !canBreak(client.world, obstacle)) {
                    // Неломаемое, но не бедрок (например, коренная порода, но у нас проверка isSolid исключает бедрок, так что сюда не должно попасть)
                    // На всякий случай стоим
                    stopMovement(client);
                } else {
                    // Путь свободен – идём вперёд
                    client.options.attackKey.setPressed(false);
                    client.options.forwardKey.setPressed(true);
                    // Прыгаем только если нужно подняться на блок
                    BlockPos playerFeet = client.player.getBlockPos();
                    int deltaY = target.getY() - playerFeet.getY();
                    if (deltaY > 0) {
                        Direction dir = Direction.fromHorizontalDegrees((double) client.player.getYaw());
                        BlockPos frontFeet = playerFeet.add(dir.getVector());
                        if (isSolid(client.world, frontFeet) && isAir(client.world, frontFeet.up())) {
                            client.options.jumpKey.setPressed(true);
                        } else {
                            client.options.jumpKey.setPressed(false);
                        }
                    } else if (deltaY < 0) {
                        // Если цель ниже, пытаемся копать под собой
                        BlockPos below = playerFeet.down();
                        if (isSolid(client.world, below)) {
                            client.options.attackKey.setPressed(true);
                            client.options.forwardKey.setPressed(false);
                            client.options.jumpKey.setPressed(false);
                        }
                    } else {
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

    // ======================== НАВИГАЦИОННЫЕ ПОМОЩНИКИ ========================
    private void stopMovement(MinecraftClient client) {
        client.options.forwardKey.setPressed(false);
        client.options.attackKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
        client.options.leftKey.setPressed(false);
        client.options.rightKey.setPressed(false);
    }

    private void faceTarget(MinecraftClient client, Vec3d target) {
        Vec3d eyePos = client.player.getEyePos();
        Vec3d dir = target.subtract(eyePos).normalize();
        double yaw = Math.toDegrees(Math.atan2(-dir.x, dir.z));
        double pitch = Math.toDegrees(-Math.asin(dir.y));
        client.player.setYaw((float)yaw);
        client.player.setPitch((float)pitch);
    }

    /** Поиск первого ломаемого блока (не воздух/алмаз/бедрок) на пути луча */
    private BlockPos findFirstBreakableBlock(MinecraftClient client, Vec3d start, Vec3d direction, double maxDistance) {
        Vec3d end = start.add(direction.multiply(maxDistance));
        // Используем raycast для получения точного пересечения
        BlockHitResult hit = client.world.raycast(new RaycastContext(
                start, end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                client.player
        ));
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = hit.getBlockPos();
            if (isSolid(client.world, pos)) { // isSolid исключает воздух, алмаз, бедрок
                return pos;
            }
        }
        // Если точный рейкаст ничего не дал, проверяем вручную с шагом
        double step = 0.2;
        Vec3d current = start;
        BlockPos last = null;
        for (double d = 0; d < maxDistance; d += step) {
            current = start.add(direction.multiply(d));
            BlockPos pos = new BlockPos((int)Math.floor(current.x), (int)Math.floor(current.y), (int)Math.floor(current.z));
            if (!pos.equals(last)) {
                last = pos;
                if (isSolid(client.world, pos)) return pos;
            }
        }
        return null;
    }

    /** Проверяет, есть ли бедрок прямо перед игроком (на уровне ног/головы) в пределах distance */
    private BlockPos findBedrockInFront(MinecraftClient client, Vec3d start, Vec3d direction, double distance) {
        double step = 0.2;
        Vec3d current = start;
        BlockPos last = null;
        for (double d = 0; d < distance; d += step) {
            current = start.add(direction.multiply(d));
            BlockPos pos = new BlockPos((int)Math.floor(current.x), (int)Math.floor(current.y), (int)Math.floor(current.z));
            if (!pos.equals(last)) {
                last = pos;
                if (client.world.getBlockState(pos).isOf(Blocks.BEDROCK)) return pos;
            }
        }
        return null;
    }

    /** Можно ли пройти сквозь блок (воздух, алмаз, жидкость – не твёрдый) */
    private boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || isDiamond(world, pos) || state.isOf(Blocks.LAVA) || state.isOf(Blocks.WATER);
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

    /** Точечные фильтры */
    private boolean isValidDiamondTarget(World world, BlockPos pos) {
        for (int dy = 1; dy <= 3; dy++) {
            if (world.getBlockState(pos.down(dy)).isOf(Blocks.LAVA)) return false;
        }
        if (world.getBlockState(pos.up()).isOf(Blocks.BEDROCK)) return false;
        if (isBedrock(world, pos.north()) || isBedrock(world, pos.south()) ||
            isBedrock(world, pos.east()) || isBedrock(world, pos.west())) return false;
        return true;
    }

    private boolean isAir(World world, BlockPos pos) {
        return world.getBlockState(pos).isAir();
    }

    /** Твёрдый блок, который нужно ломать (не воздух, не алмаз, не бедрок) */
    private boolean isSolid(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !isDiamond(world, pos) && !state.isOf(Blocks.BEDROCK);
    }

    private boolean canBreak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.isOf(Blocks.BEDROCK);
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
