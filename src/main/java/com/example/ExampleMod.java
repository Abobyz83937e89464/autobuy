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

import java.util.*;

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

    // Планировщик
    private List<BlockPos> plannedRoute = new ArrayList<>();
    private int routeIndex = 0;
    private BlockPos currentMineTarget = null;
    private int mineStuckTicks = 0;

    private static final double REACH_DISTANCE = 2.5;
    private static final int SCAN_RADIUS = 50;
    private static final int PATHFIND_RADIUS = 50; // теперь совпадает с радиусом поиска алмазов

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
                    plannedRoute.clear();
                    currentMineTarget = null;
                } else {
                    sendMsg("Авто-шахтёр деактивирован", Formatting.RED);
                    stopMovement(client);
                    target = null;
                    pickupTicks = 0;
                    escaping = false;
                    avoidingBedrock = false;
                    plannedRoute.clear();
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
                        plannedRoute.clear();
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
                    plannedRoute = planRoute(client, client.player.getBlockPos(), target);
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
                        plannedRoute.clear();
                    }
                    return;
                }

                if (avoidingBedrock) {
                    handleBedrockAvoidance(client);
                    return;
                }

                // Приоритет – маршрут, иначе прямое движение (улучшенное)
                if (!plannedRoute.isEmpty()) {
                    followRoute(client);
                } else {
                    navigateStraight(client);
                }

            } catch (Exception e) {
                sendMsg("Ошибка: " + e.getMessage(), Formatting.RED);
                target = null;
                pickupTicks = 0;
                plannedRoute.clear();
                currentMineTarget = null;
                stopMovement(client);
            }
        });

        // Полный рендеринг трассера и обводки
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

    private boolean isLava(World world, BlockPos pos) {
        return world.getBlockState(pos).isOf(Blocks.LAVA);
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

    // ======================== ПЛАНИРОВЩИК МАРШРУТА (BFS, проходит сквозь копаемые блоки) ========================
    private List<BlockPos> planRoute(MinecraftClient client, BlockPos start, BlockPos goal) {
        World world = client.world;
        if (world == null) return new ArrayList<>();

        Queue<BlockPos> queue = new LinkedList<>();
        Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();

        queue.add(start);
        visited.add(start);
        cameFrom.put(start, null);

        boolean found = false;
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            if (current.equals(goal)) {
                found = true;
                break;
            }

            for (Direction dir : Direction.values()) {
                BlockPos neighbor = current.offset(dir);
                if (Math.abs(neighbor.getX() - start.getX()) > PATHFIND_RADIUS ||
                    Math.abs(neighbor.getY() - start.getY()) > PATHFIND_RADIUS ||
                    Math.abs(neighbor.getZ() - start.getZ()) > PATHFIND_RADIUS) {
                    continue;
                }

                if (!visited.contains(neighbor) && canStandAt(world, neighbor)) {
                    visited.add(neighbor);
                    cameFrom.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }

        if (!found) return new ArrayList<>();

        List<BlockPos> route = new ArrayList<>();
        BlockPos current = goal;
        while (current != null && !current.equals(start)) {
            route.add(current);
            current = cameFrom.get(current);
        }
        Collections.reverse(route);
        return route;
    }

    // Блок проходим для планировщика, если это не лава и не бедрок (остальное выкопаем)
    private boolean canStandAt(World world, BlockPos pos) {
        return isNotLavaOrBedrock(world, pos) && isNotLavaOrBedrock(world, pos.up());
    }

    private boolean isNotLavaOrBedrock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isOf(Blocks.LAVA) && !state.isOf(Blocks.BEDROCK);
    }

    // Проходим ли без копания (воздух или алмаз)
    private boolean isPassable(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return state.isAir() || isDiamond(world, pos) || state.isOf(Blocks.WATER);
    }

    // ======================== СЛЕДОВАНИЕ ПО МАРШРУТУ ========================
    private void followRoute(MinecraftClient client) {
        // Убираем пройденные точки
        while (routeIndex < plannedRoute.size() && reached(client.player.getBlockPos(), plannedRoute.get(routeIndex))) {
            routeIndex++;
        }

        if (routeIndex >= plannedRoute.size()) {
            plannedRoute.clear();
            return;
        }

        BlockPos nextStep = plannedRoute.get(routeIndex);

        // Если блок непроходим (не воздух/алмаз) – копаем
        if (!isPassable(client.world, nextStep) || !isPassable(client.world, nextStep.up())) {
            currentMineTarget = nextStep;
            safeMine(client, currentMineTarget);
            mineStuckTicks++;
            if (mineStuckTicks > 60) {
                plannedRoute.clear();
                mineStuckTicks = 0;
            }
            return;
        } else {
            mineStuckTicks = 0;
        }

        faceTarget(client, Vec3d.ofCenter(nextStep));
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(shouldJump(client, nextStep));
        client.options.attackKey.setPressed(false);
    }

    private boolean shouldJump(MinecraftClient client, BlockPos targetPos) {
        BlockPos playerFeet = client.player.getBlockPos();
        return targetPos.getY() > playerFeet.getY() && client.player.isOnGround();
    }

    private boolean reached(BlockPos playerFeet, BlockPos targetPos) {
        return playerFeet.getSquaredDistance(targetPos) < 1.5;
    }

    // ======================== ЗАПАСНОЕ ПРЯМОЕ ДВИЖЕНИЕ (ИСПРАВЛЕНО: подъём без лишнего копания) ========================
    private void navigateStraight(MinecraftClient client) {
        BlockPos playerFeet = client.player.getBlockPos();
        Direction forward = Direction.fromHorizontalDegrees(client.player.getYaw());

        // Если уже есть цель копания и она ещё существует – продолжаем
        if (currentMineTarget != null) {
            if (isSolidOrLavaOrBedrock(client.world, currentMineTarget) && canBreak(client.world, currentMineTarget)) {
                safeMine(client, currentMineTarget);
                mineStuckTicks++;
                if (mineStuckTicks > 40) {
                    currentMineTarget = null;
                    mineStuckTicks = 0;
                }
                return;
            } else {
                currentMineTarget = null;
                mineStuckTicks = 0;
            }
        }

        int deltaY = target.getY() - playerFeet.getY();
        BlockPos frontFeet = playerFeet.add(forward.getVector());
        BlockPos frontHead = frontFeet.up();
        BlockPos frontAbove = frontFeet.up(2);
        BlockPos aboveFeet = playerFeet.up();   // строго над головой
        BlockPos aboveHead = playerFeet.up(2);

        if (deltaY > 0) {
            // 1. Проверяем, достаточно ли высоты над головой (2 блока). Если нет – копаем потолок.
            if (isSolidOrLavaOrBedrock(client.world, aboveFeet) && !isLava(client.world, aboveFeet) && !isBedrock(client.world, aboveFeet)) {
                currentMineTarget = aboveFeet;
                safeMine(client, aboveFeet);
                return;
            }
            if (isSolidOrLavaOrBedrock(client.world, aboveHead) && !isLava(client.world, aboveHead) && !isBedrock(client.world, aboveHead)) {
                currentMineTarget = aboveHead;
                safeMine(client, aboveHead);
                return;
            }
            // Если потолок – лава/бедрок, пытаемся обойти
            if (isLava(client.world, aboveFeet) || isBedrock(client.world, aboveFeet)) {
                startBedrockAvoidance(client, forward, aboveFeet);
                return;
            }
            if (isLava(client.world, aboveHead) || isBedrock(client.world, aboveHead)) {
                startBedrockAvoidance(client, forward, aboveHead);
                return;
            }

            // 2. Ступеньки впереди: верхний, средний, нижний
            if (isSolidOrLavaOrBedrock(client.world, frontAbove)) {
                if (isLava(client.world, frontAbove) || isBedrock(client.world, frontAbove)) {
                    startBedrockAvoidance(client, forward, frontAbove);
                    return;
                }
                currentMineTarget = frontAbove;
                safeMine(client, frontAbove);
                return;
            }
            if (isSolidOrLavaOrBedrock(client.world, frontHead)) {
                if (isLava(client.world, frontHead) || isBedrock(client.world, frontHead)) {
                    startBedrockAvoidance(client, forward, frontHead);
                    return;
                }
                currentMineTarget = frontHead;
                safeMine(client, frontHead);
                return;
            }
            if (isSolidOrLavaOrBedrock(client.world, frontFeet)) {
                if (isLava(client.world, frontFeet) || isBedrock(client.world, frontFeet)) {
                    startBedrockAvoidance(client, forward, frontFeet);
                    return;
                }
                // Используем как ступеньку: идём и прыгаем
                client.options.forwardKey.setPressed(true);
                client.options.jumpKey.setPressed(client.player.isOnGround());
                client.options.attackKey.setPressed(false);
                return;
            }

            // 3. Никаких препятствий – просто идём вперёд
            client.options.forwardKey.setPressed(true);
            client.options.jumpKey.setPressed(false);
            client.options.attackKey.setPressed(false);
            return;
        }

        if (deltaY < 0) {
            BlockPos below = playerFeet.down();
            if (isSolidOrLavaOrBedrock(client.world, below)) {
                if (isLava(client.world, below) || isBedrock(client.world, below)) {
                    startBedrockAvoidance(client, forward, below);
                    return;
                }
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
        boolean feetBlocked = isSolidOrLavaOrBedrock(client.world, frontFeet);
        boolean headBlocked = isSolidOrLavaOrBedrock(client.world, frontHead);

        if ((feetBlocked && (isLava(client.world, frontFeet) || isBedrock(client.world, frontFeet))) ||
            (headBlocked && (isLava(client.world, frontHead) || isBedrock(client.world, frontHead)))) {
            startBedrockAvoidance(client, forward, feetBlocked ? frontFeet : frontHead);
            return;
        }

        if (feetBlocked && canBreak(client.world, frontFeet)) {
            currentMineTarget = frontFeet;
            safeMine(client, frontFeet);
            client.options.forwardKey.setPressed(true);
            return;
        }
        if (headBlocked && canBreak(client.world, frontHead)) {
            currentMineTarget = frontHead;
            safeMine(client, frontHead);
            client.options.forwardKey.setPressed(true);
            return;
        }

        faceTarget(client, Vec3d.ofCenter(target));
        client.options.forwardKey.setPressed(true);
        client.options.jumpKey.setPressed(false);
        client.options.attackKey.setPressed(false);
    }

    // ======================== ОБХОД БЕДРОКА/ЛАВЫ (сохранено) ========================
    private void startBedrockAvoidance(MinecraftClient client, Direction blockedDir, BlockPos obstacle) {
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
            plannedRoute.clear();
            sendMsg("Нет пути для обхода, ищу другой алмаз.", Formatting.RED);
            return;
        }

        avoidingBedrock = true;
        avoidTicks = 0;
        avoidOriginalTarget = target;
        String reason = isLava(client.world, obstacle) ? "лаву" : "бедрок";
        sendMsg("Обхожу " + reason + "...", Formatting.YELLOW);
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

    // ======================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ (НИЧЕГО НЕ УДАЛЕНО) ========================
    private void safeMine(MinecraftClient client, BlockPos pos) {
        if (!canBreak(client.world, pos)) return;
        faceBlock(client, pos);
        client.options.attackKey.setPressed(true);
        client.options.forwardKey.setPressed(false);
        client.options.jumpKey.setPressed(false);
    }

    private boolean canBreak(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.isOf(Blocks.BEDROCK) && !state.isOf(Blocks.LAVA);
    }

    private boolean isSolidOrLavaOrBedrock(World world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !isDiamond(world, pos);
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

    // ======================== ПОИСК АЛМАЗОВ И ФИЛЬТРЫ ========================
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

    // Все точечные фильтры
    private boolean isValidDiamondTarget(World world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (world.getBlockState(pos.offset(direction)).isOf(Blocks.LAVA)) return false;
        }
        if (world.getBlockState(pos.up()).isOf(Blocks.BEDROCK)) return false;
        if (isBedrock(world, pos.north()) || isBedrock(world, pos.south()) ||
            isBedrock(world, pos.east()) || isBedrock(world, pos.west())) return false;
        return true;
    }

    // Совместимость со старыми версиями (не удалены)
    private boolean isSolid(World world, BlockPos pos) {
        return isSolidOrLavaOrBedrock(world, pos) && !isBedrock(world, pos);
    }

    private BlockPos findBestObstacle(MinecraftClient client, Vec3d targetCenter) {
        return null;
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
