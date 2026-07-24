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
        IDLE, CHECK_BALANCE, OPEN_AH, SCAN_PAGE, NEXT_PAGE, EVALUATE,
        PROCESS_TARGET, MARKET_GUI, FIND_AND_BUY, BUY_ITEM, SELL_TO_MARKET, SELL_TO_AH
    }

    private final Map<String, List<Long>> priceSamples = new HashMap<>();
    private final Map<String, Long> marketPrices = new HashMap<>(); // средняя цена на ауке
    private final Map<String, Integer> itemCounts = new HashMap<>();

    private static class Target {
        String name;
        long maxPrice;         // аукционная цена, по которой покупаем
        long marketUnitPrice;  // цена за штуку с маркета (для руды/блоков)
        boolean isMarketItem;  // true = продаём через /market
    }
    private final List<Target> targets = new ArrayList<>();
    private int currentTargetIndex = 0;
    private Target currentTarget = null;

    private int buySlotId = -1;
    private String buyItemName = "";
    private long buyPrice = 0;

    private int nextPageSlotId = -1;
    private boolean huntingMode = false;

    // Для обработки маркета
    private int marketAttempts = 0;
    private static final int MAX_MARKET_ATTEMPTS = 2;
    private int marketGuiWaitTicks = 0;

    private static final int MIN_LOTS_FOR_PURCHASE = 3;

    // Чёрный список низкоуровневой брони, инструментов, удочек
    private static final Set<String> BLACKLIST_KEYWORDS = Set.of(
        "кожан", "железн", "золот", "каменн", "деревянн", "цепн", "кольчуг",
        "удочк", "fishing rod", "bow"
    );

    // Ключевые слова для товаров, проверяемых через /market
    private static final Set<String> MARKET_KEYWORDS = Set.of(
        "лазурит", "lapis", "алмаз", "diamond", "изумруд", "emerald",
        "золото", "gold", "железо", "iron", "медь", "copper",
        "редстоун", "redstone", "уголь", "coal", "кварц", "quartz",
        "эндер-кристалл", "ender crystal", "эндер-сундук", "ender chest",
        "блок", "block", "кристалл", "crystal", "руда", "ore"
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

        // Обработчик чата (только баланс)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!isActive) return;
            String text = message.getString();
            if (currentState == BotState.CHECK_BALANCE &&
                (text.toLowerCase().contains("balance") || text.toLowerCase().contains("баланс"))) {
                MinecraftClient.getInstance().execute(() -> parseBalance(text));
            }
        });

        // Основной такт
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
                    setWait(60);
                    break;

                case OPEN_AH:
                    priceSamples.clear();
                    marketPrices.clear();
                    itemCounts.clear();
                    targets.clear();
                    currentTargetIndex = 0;
                    currentTarget = null;
                    huntingMode = false;

                    client.getNetworkHandler().sendCommand("ah");
                    setState(BotState.SCAN_PAGE);
                    setWait(15);
                    break;

                case SCAN_PAGE:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        if (huntingMode) {
                            boolean found = searchForCurrentTarget(screen);
                            if (found) {
                                setState(BotState.BUY_ITEM);
                            } else {
                                nextPageSlotId = findNextPageSlot(screen);
                                if (nextPageSlotId != -1) {
                                    setState(BotState.NEXT_PAGE);
                                } else {
                                    advanceTarget();
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
                        setWait(15);
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
                            setWait(4);
                        } else {
                            client.setScreen(null);
                            setState(BotState.OPEN_AH);
                        }
                    } else {
                        setState(BotState.OPEN_AH);
                    }
                    break;

                case EVALUATE:
                    evaluateTargets();
                    if (!targets.isEmpty()) {
                        sendMsg("Найдено выгодных лотов: " + targets.size(), Formatting.AQUA);
                        for (Target t : targets) {
                            sendMsg(" - " + t.name + " цена " + t.maxPrice, Formatting.GRAY);
                        }
                        currentTargetIndex = 0;
                        client.setScreen(null);
                        setState(BotState.PROCESS_TARGET);
                        setWait(10);
                    } else {
                        sendMsg("Нет целей, начинаю новый проход.", Formatting.GRAY);
                        client.setScreen(null);
                        setState(BotState.OPEN_AH);
                        setWait(20);
                    }
                    break;

                case PROCESS_TARGET:
                    if (currentTargetIndex >= targets.size()) {
                        sendMsg("Все цели обработаны, открываю аукцион заново.", Formatting.GRAY);
                        setState(BotState.OPEN_AH);
                        setWait(10);
                    } else {
                        currentTarget = targets.get(currentTargetIndex);
                        if (currentTarget.isMarketItem) {
                            marketAttempts = 0;
                            client.getNetworkHandler().sendCommand("market search " + currentTarget.name);
                            setState(BotState.MARKET_GUI);
                            marketGuiWaitTicks = 10; // ускорено до 0.5 сек (10 тиков)
                            setWait(marketGuiWaitTicks);
                        } else {
                            huntingMode = true;
                            client.getNetworkHandler().sendCommand("ah");
                            setState(BotState.FIND_AND_BUY);
                            setWait(15);
                        }
                    }
                    break;

                case MARKET_GUI:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        long unitPrice = scanMarketGui(screen);
                        if (unitPrice > 0) {
                            currentTarget.marketUnitPrice = unitPrice;
                            sendMsg("Маркет цена за шт: " + unitPrice, Formatting.AQUA);
                            if (unitPrice > currentTarget.maxPrice) {
                                sendMsg("Маркет дороже аукциона, покупаем!", Formatting.GREEN);
                                client.setScreen(null);
                                huntingMode = true;
                                client.getNetworkHandler().sendCommand("ah");
                                setState(BotState.FIND_AND_BUY);
                                setWait(15);
                            } else {
                                sendMsg("Маркет цена не выше аукциона, пропускаем.", Formatting.RED);
                                client.setScreen(null);
                                advanceTarget();
                            }
                        } else {
                            sendMsg("Не удалось извлечь цену из GUI маркета.", Formatting.RED);
                            client.setScreen(null);
                            advanceTarget();
                        }
                    } else {
                        if (marketGuiWaitTicks <= 0) {
                            if (marketAttempts < MAX_MARKET_ATTEMPTS - 1) {
                                marketAttempts++;
                                sendMsg("Повторная попытка /market search для " + currentTarget.name, Formatting.YELLOW);
                                client.getNetworkHandler().sendCommand("market search " + currentTarget.name);
                                marketGuiWaitTicks = 10;
                                setWait(marketGuiWaitTicks);
                            } else {
                                sendMsg("Не удалось открыть GUI маркета, пропускаю.", Formatting.RED);
                                advanceTarget();
                            }
                        } else {
                            marketGuiWaitTicks -= waitTicks;
                            setWait(1);
                        }
                    }
                    break;

                case FIND_AND_BUY:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        boolean found = searchForCurrentTarget(screen);
                        if (found) {
                            setState(BotState.BUY_ITEM);
                        } else {
                            nextPageSlotId = findNextPageSlot(screen);
                            if (nextPageSlotId != -1) {
                                client.interactionManager.clickSlot(
                                    screen.getScreenHandler().syncId,
                                    nextPageSlotId,
                                    0,
                                    net.minecraft.screen.slot.SlotActionType.PICKUP,
                                    client.player
                                );
                                setWait(4);
                            } else {
                                sendMsg("Цель не найдена на ауке: " + currentTarget.name, Formatting.RED);
                                advanceTarget();
                            }
                        }
                    } else {
                        sendMsg("Аукцион не открыт для цели, пробую /ah", Formatting.RED);
                        client.getNetworkHandler().sendCommand("ah");
                        setWait(15);
                    }
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
                        if (currentTarget != null && currentTarget.isMarketItem) {
                            setState(BotState.SELL_TO_MARKET);
                        } else {
                            setState(BotState.SELL_TO_AH);
                        }
                        setWait(10);
                    } else {
                        advanceTarget();
                    }
                    break;

                case SELL_TO_MARKET:
                    if (currentTarget != null && currentTarget.marketUnitPrice > 0) {
                        client.getNetworkHandler().sendCommand("market sell " + currentTarget.marketUnitPrice);
                        sendMsg("Продаю на маркете " + currentTarget.name + " по " + currentTarget.marketUnitPrice, Formatting.GREEN);
                    }
                    advanceTarget();
                    break;

                case SELL_TO_AH:
                    // Новая прогрессивная наценка от цены покупки
                    long sellPrice;
                    if (buyPrice < 10_000) {
                        sellPrice = (long)(buyPrice * 1.7);
                    } else if (buyPrice < 50_000) {
                        sellPrice = (long)(buyPrice * 1.33);
                    } else if (buyPrice < 150_000) {
                        sellPrice = (long)(buyPrice * 1.2);
                    } else {
                        sellPrice = (long)(buyPrice * 1.15);
                    }
                    if (sellPrice <= 0) sellPrice = 50000; // страховка
                    client.getNetworkHandler().sendCommand("ah sell " + sellPrice);
                    sendMsg("Выставляю " + buyItemName + " за " + sellPrice, Formatting.GREEN);
                    advanceTarget();
                    break;
            }
        });
    }

    // ===================== Обработчики чата =====================
    private void parseBalance(String text) {
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
                    setWait(10);
                }
            }
        } catch (Exception e) {
            sendMsg("Ошибка парсинга баланса.", Formatting.RED);
        }
    }

    // ===================== Управление целями =====================
    private void advanceTarget() {
        currentTargetIndex++;
        buySlotId = -1;
        buyItemName = "";
        buyPrice = 0;
        currentTarget = null;
        huntingMode = false;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.currentScreen != null) client.setScreen(null);
        setState(BotState.PROCESS_TARGET);
        setWait(5);
    }

    // ===================== Сканирование и оценка =====================
    private void scanPageForStats(HandledScreen<?> screen) {
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String name = stack.getName().getString();
            long price = extractPrice(stack);
            if (price <= 0 || !isAllowedToBuy(name)) continue;

            priceSamples.computeIfAbsent(name, k -> new ArrayList<>()).add(price);
        }

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

    private long scanMarketGui(HandledScreen<?> screen) {
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            long price = extractMarketUnitPrice(stack);
            if (price > 0) return price;
        }
        return -1;
    }

    private long extractMarketUnitPrice(ItemStack stack) {
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
            if (text.contains("минимальная цена") ||
                text.contains("цена за шт") ||
                text.contains("unit price")) {
                String nums = line.getString().replaceAll("[^0-9]", "");
                if (!nums.isEmpty()) {
                    try {
                        return Long.parseLong(nums);
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        return -1;
    }

    private void evaluateTargets() {
        targets.clear();

        for (var entry : marketPrices.entrySet()) {
            String name = entry.getKey();
            long marketAvg = entry.getValue();
            int count = itemCounts.getOrDefault(name, 0);

            if (!isAllowedToBuy(name)) continue;

            boolean isMarketItem = isMarketItem(name);
            boolean isTotem = name.toLowerCase().contains("тотем") || name.toLowerCase().contains("totem");

            List<Long> prices = priceSamples.get(name);
            if (prices == null) continue;

            for (long auctionPrice : prices) {
                if (auctionPrice > currentBalance) continue;

                if (isTotem) {
                    if (auctionPrice >= 50000 && auctionPrice <= 100000) {
                        Target t = new Target();
                        t.name = name;
                        t.maxPrice = auctionPrice;
                        t.isMarketItem = false;
                        targets.add(t);
                        break;
                    }
                } else if (count >= MIN_LOTS_FOR_PURCHASE) {
                    double discount = 1.0 - (double) auctionPrice / marketAvg;
                    if (discount >= 0.25) {
                        Target t = new Target();
                        t.name = name;
                        t.maxPrice = auctionPrice;
                        t.isMarketItem = isMarketItem;
                        targets.add(t);
                        break;
                    }
                }
            }
        }

        targets.sort((a, b) -> {
            if (a.isMarketItem != b.isMarketItem) return a.isMarketItem ? -1 : 1;
            double discA = 1.0 - (double)a.maxPrice / marketPrices.getOrDefault(a.name, a.maxPrice);
            double discB = 1.0 - (double)b.maxPrice / marketPrices.getOrDefault(b.name, b.maxPrice);
            return Double.compare(discB, discA);
        });
    }

    private boolean searchForCurrentTarget(HandledScreen<?> screen) {
        if (currentTarget == null) return false;
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            if (stack.getName().getString().equals(currentTarget.name)) {
                long price = extractPrice(stack);
                if (price > 0 && price <= currentTarget.maxPrice && price <= currentBalance) {
                    buySlotId = i;
                    buyItemName = currentTarget.name;
                    buyPrice = price;
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAllowedToBuy(String name) {
        String lower = name.toLowerCase();
        for (String kw : BLACKLIST_KEYWORDS) {
            if (lower.contains(kw)) return false;
        }
        return true;
    }

    private boolean isMarketItem(String name) {
        String lower = name.toLowerCase();
        for (String kw : MARKET_KEYWORDS) {
            if (lower.contains(kw)) return true;
        }
        return false;
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
        this.waitTicks = Math.max(1, ticks + (int)(Math.random() * 3));
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
