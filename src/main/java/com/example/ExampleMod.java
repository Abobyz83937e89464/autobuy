package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class ExampleMod implements ModInitializer, ClientModInitializer {
    public static final String MOD_ID = "autobuy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static KeyBinding toggleKey;
    private static boolean initialized = false;

    private boolean isActive = false;
    private long currentBalance = 0;

    private BotState currentState = BotState.IDLE;
    private int waitTicks = 0;

    enum BotState {
        IDLE, CHECK_BALANCE, OPEN_AH, SCAN_PAGE, NEXT_PAGE, EVALUATE, FIND_AND_BUY, BUY_ITEM, SELL_ITEM
    }

    private final Map<String, List<Long>> priceSamples = new HashMap<>();
    private final Map<String, Long> marketPrices = new HashMap<>();
    private final Map<String, Integer> itemCounts = new HashMap<>();

    private String targetItemName = "";
    private long targetMaxPrice = 0;

    private int buySlotId = -1;
    private String buyItemName = "";
    private long buyPrice = 0;

    private int nextPageSlotId = -1;
    private boolean huntingMode = false;

    private static final int MIN_LOTS_FOR_PURCHASE = 3;

    // Чёрный список: предметы, которые никогда не покупаем
    private static final Set<String> BLACKLIST_KEYWORDS = Set.of(
        "кожан", "железн", "золот", "каменн", "деревянн", "цепн", "кольчуг",
        "удочк", "fishing rod", "bow" // лук тоже запрещён, если есть в названии
        // при желании можно добавить "каменный", "железный" и т.д., но достаточно основ
    );

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

        LOGGER.info("[AutoBuy] Мод успешно инициализирован!");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuy.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                "key.categories.misc"
        ));

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!isActive || currentState != BotState.CHECK_BALANCE) return;
            String text = message.getString();
            if (text.toLowerCase().contains("balance") || text.toLowerCase().contains("баланс")) {
                MinecraftClient.getInstance().execute(() -> {
                    try {
                        String nums = text.replaceAll("[^0-9]", "");
                        if (!nums.isEmpty()) {
                            currentBalance = Long.parseLong(nums);
                            sendMsg("Баланс обновлен: " + currentBalance, Formatting.YELLOW);
                            if (currentBalance <= 0) {
                                sendMsg("Недостаточно средств. Бот остановлен.", Formatting.RED);
                                isActive = false;
                                setState(BotState.IDLE);
                            } else {
                                setState(BotState.OPEN_AH);
                                setWait(20);
                            }
                        }
                    } catch (Exception e) {
                        sendMsg("Ошибка парсинга баланса.", Formatting.RED);
                    }
                });
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            while (toggleKey.wasPressed()) {
                isActive = !isActive;
                if (isActive) {
                    sendMsg("Бот активирован", Formatting.GREEN);
                    setState(BotState.CHECK_BALANCE);
                    waitTicks = 0;
                } else {
                    sendMsg("Бот деактивирован", Formatting.RED);
                    setState(BotState.IDLE);
                    waitTicks = 0;
                }
            }

            if (!isActive) return;

            if (waitTicks > 0) {
                waitTicks--;
                return;
            }

            switch (currentState) {
                case IDLE:
                    break;

                case CHECK_BALANCE:
                    client.getNetworkHandler().sendCommand("balance");
                    setWait(100);
                    break;

                case OPEN_AH:
                    priceSamples.clear();
                    marketPrices.clear();
                    itemCounts.clear();
                    targetItemName = "";
                    targetMaxPrice = 0;
                    huntingMode = false;

                    client.getNetworkHandler().sendCommand("ah");
                    setState(BotState.SCAN_PAGE);
                    setWait(30);
                    break;

                case SCAN_PAGE:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (huntingMode) {
                            boolean found = searchForTarget(screen);
                            if (found) {
                                setState(BotState.BUY_ITEM);
                            } else {
                                nextPageSlotId = findNextPageSlot(screen);
                                if (nextPageSlotId != -1) {
                                    setState(BotState.NEXT_PAGE);
                                } else {
                                    sendMsg("Цель потеряна, начинаю новый проход.", Formatting.RED);
                                    client.setScreen(null);
                                    setState(BotState.OPEN_AH);
                                    setWait(30);
                                }
                            }
                        } else {
                            scanPageForStats(screen);
                            nextPageSlotId = findNextPageSlot(screen);
                            if (nextPageSlotId != -1) {
                                setState(BotState.NEXT_PAGE);
                            } else {
                                setState(BotState.EVALUATE);
                            }
                        }
                    } else {
                        sendMsg("Аукцион не открыт, пробую снова /ah", Formatting.RED);
                        setState(BotState.OPEN_AH);
                        setWait(40);
                    }
                    break;

                case NEXT_PAGE:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (nextPageSlotId >= 0 && nextPageSlotId < screen.getScreenHandler().slots.size()) {
                            client.interactionManager.clickSlot(
                                screen.getScreenHandler().syncId,
                                nextPageSlotId,
                                0,
                                net.minecraft.screen.slot.SlotActionType.PICKUP,
                                client.player
                            );
                            setState(BotState.SCAN_PAGE);
                            setWait(20);
                        } else {
                            client.setScreen(null);
                            setState(BotState.OPEN_AH);
                        }
                    } else {
                        setState(BotState.OPEN_AH);
                    }
                    break;

                case EVALUATE:
                    chooseTarget();
                    if (!targetItemName.isEmpty()) {
                        sendMsg("Цель: " + targetItemName + " макс.цена: " + targetMaxPrice, Formatting.AQUA);
                        huntingMode = true;
                        client.setScreen(null);
                        setState(BotState.FIND_AND_BUY);
                        setWait(20);
                    } else {
                        sendMsg("Нет выгодных предложений, начинаю новый проход.", Formatting.GRAY);
                        client.setScreen(null);
                        setState(BotState.OPEN_AH);
                        setWait(30);
                    }
                    break;

                case FIND_AND_BUY:
                    client.getNetworkHandler().sendCommand("ah");
                    setState(BotState.SCAN_PAGE); // huntingMode уже true
                    setWait(30);
                    break;

                case BUY_ITEM:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (buySlotId >= 0 && buySlotId < screen.getScreenHandler().slots.size()) {
                            client.interactionManager.clickSlot(
                                    screen.getScreenHandler().syncId,
                                    buySlotId,
                                    0,
                                    net.minecraft.screen.slot.SlotActionType.PICKUP,
                                    client.player
                            );
                            sendMsg("Куплен " + buyItemName + " за " + buyPrice, Formatting.GREEN);
                        }
                        setState(BotState.SELL_ITEM);
                        setWait(20);
                    } else {
                        setState(BotState.OPEN_AH);
                    }
                    break;

                case SELL_ITEM:
                    long marketPrice = marketPrices.getOrDefault(buyItemName, 0L);
                    if (marketPrice <= 0) marketPrice = buyPrice;
                    long sellPrice = (long)(marketPrice * 1.25);
                    if (sellPrice <= 0) sellPrice = 50000;
                    client.getNetworkHandler().sendCommand("ah sell " + sellPrice);
                    sendMsg("Выставляю " + buyItemName + " за " + sellPrice, Formatting.GREEN);
                    buySlotId = -1;
                    buyItemName = "";
                    buyPrice = 0;
                    targetItemName = "";
                    targetMaxPrice = 0;
                    huntingMode = false;
                    setState(BotState.OPEN_AH);
                    setWait(20);
                    break;
            }
        });
    }

    private void scanPageForStats(HandledScreen<?> screen) {
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String name = stack.getName().getString();
            long price = extractPrice(stack);
            if (price <= 0) continue;

            // Фильтр: не добавляем в статистику запрещённые предметы
            if (!isAllowedToBuy(name)) continue;

            priceSamples.computeIfAbsent(name, k -> new ArrayList<>()).add(price);
        }

        // Пересчёт рынка
        marketPrices.clear();
        itemCounts.clear();
        for (var entry : priceSamples.entrySet()) {
            String name = entry.getKey();
            List<Long> prices = entry.getValue();
            itemCounts.put(name, prices.size());
            long avg = (long) prices.stream().mapToLong(Long::longValue).average().orElse(0);
            marketPrices.put(name, avg);
        }
    }

    private boolean searchForTarget(HandledScreen<?> screen) {
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String name = stack.getName().getString();
            if (name.equals(targetItemName)) {
                long price = extractPrice(stack);
                if (price > 0 && price <= targetMaxPrice) {
                    buySlotId = i;
                    buyItemName = name;
                    buyPrice = price;
                    return true;
                }
            }
        }
        return false;
    }

    private void chooseTarget() {
        String bestName = "";
        long bestPrice = Long.MAX_VALUE;

        for (var entry : marketPrices.entrySet()) {
            String name = entry.getKey();
            long market = entry.getValue();
            int count = itemCounts.getOrDefault(name, 0);

            // Дополнительная проверка на всякий случай (уже отфильтровано при сборе)
            if (!isAllowedToBuy(name)) continue;

            boolean isTotem = name.toLowerCase().contains("тотем") || name.toLowerCase().contains("totem");

            if (isTotem) {
                List<Long> prices = priceSamples.get(name);
                if (prices != null) {
                    for (long p : prices) {
                        if (p >= 50000 && p <= 100000 && p < bestPrice) {
                            bestPrice = p;
                            bestName = name;
                        }
                    }
                }
                continue;
            }

            if (count < MIN_LOTS_FOR_PURCHASE) continue;

            List<Long> prices = priceSamples.get(name);
            if (prices != null) {
                for (long p : prices) {
                    double discount = 1.0 - (double) p / market;
                    if (discount >= 0.25 && p < bestPrice) {
                        bestPrice = p;
                        bestName = name;
                    }
                }
            }
        }

        if (!bestName.isEmpty()) {
            targetItemName = bestName;
            targetMaxPrice = bestPrice;
        }
    }

    private boolean isAllowedToBuy(String name) {
        String lower = name.toLowerCase();
        // Проверка чёрного списка
        for (String keyword : BLACKLIST_KEYWORDS) {
            if (lower.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private int findNextPageSlot(HandledScreen<?> screen) {
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            String name = slot.getStack().getName().getString().toLowerCase();
            if (name.contains("next") || name.contains("далее") || name.contains("следующая")) {
                return i;
            }
        }
        return -1;
    }

    private long extractPrice(ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return -1;
        List<Text> lore = stack.getTooltip(new Item.TooltipContext() {
            public boolean isAdvanced() { return false; }
            public boolean isCreative() { return false; }
            public MapState getMapState(MapIdComponent id) { return null; }
            public float getUpdateTickRate() { return 20.0F; }
            public RegistryWrapper.WrapperLookup getRegistryLookup() {
                return MinecraftClient.getInstance().player.getWorld().getRegistryManager();
            }
        }, client.player, TooltipType.Default.BASIC);
        for (Text line : lore) {
            String text = line.getString().toLowerCase();
            if (text.contains("цена:") || text.contains("price:")) {
                String nums = text.replaceAll("[^0-9]", "");
                if (!nums.isEmpty()) {
                    try {
                        return Long.parseLong(nums);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

    private void setState(BotState state) {
        this.currentState = state;
    }

    private void setWait(int ticks) {
        this.waitTicks = ticks + (int)(Math.random() * 5);
    }

    private void sendMsg(String msg, Formatting color) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.execute(() -> client.player.sendMessage(
                    Text.literal("[AutoBuy] ").formatted(Formatting.GOLD)
                            .append(Text.literal(msg).formatted(color)),
                    false
            ));
        }
    }
}
