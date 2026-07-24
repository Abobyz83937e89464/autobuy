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
        IDLE, CHECK_BALANCE, OPEN_AH, SCAN_PAGE, NEXT_PAGE, CLOSE_AH, BUY_ITEM, SELL_ITEM
    }

    // Хранилище данных текущего прохода
    private final Map<String, List<Long>> priceSamples = new HashMap<>(); // все цены предмета
    private final Map<String, Long> marketPrices = new HashMap<>(); // средняя цена
    private final Map<String, Integer> itemCounts = new HashMap<>(); // количество лотов предмета

    private int buySlotId = -1;
    private String buyItemName = "";
    private long buyPrice = 0;

    private int nextPageSlotId = -1; // слот кнопки "Далее"

    private static final int MIN_LOTS_FOR_PURCHASE = 3; // меньше лотов — не покупаем (кроме тотемов)

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

        // Парсинг баланса
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
                    setWait(100);
                    break;

                case OPEN_AH:
                    // Сброс статистики нового прохода
                    priceSamples.clear();
                    marketPrices.clear();
                    itemCounts.clear();
                    buySlotId = -1;
                    nextPageSlotId = -1;

                    client.getNetworkHandler().sendCommand("ah");
                    setState(BotState.SCAN_PAGE);
                    setWait(30); // ожидание открытия GUI
                    break;

                case SCAN_PAGE:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        // Сканируем страницу и сразу ищем, что купить
                        boolean bought = scanAndEvaluate(screen);
                        if (bought) {
                            // нашли цель для покупки, переходим
                            setState(BotState.BUY_ITEM);
                        } else {
                            // Ищем кнопку "Следующая страница"
                            nextPageSlotId = findNextPageSlot(screen);
                            if (nextPageSlotId != -1) {
                                setState(BotState.NEXT_PAGE);
                            } else {
                                // страниц больше нет, закрываем аук
                                setState(BotState.CLOSE_AH);
                            }
                        }
                    } else {
                        // GUI закрылся (возможно, ещё не открылся), пробуем снова
                        sendMsg("Аукцион не открыт, пробую /ah снова", Formatting.RED);
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
                            sendMsg("Переход на следующую страницу...", Formatting.GRAY);
                            setState(BotState.SCAN_PAGE);
                            setWait(20); // подгрузка страницы
                        } else {
                            // что-то не так, уходим
                            setState(BotState.CLOSE_AH);
                        }
                    } else {
                        setState(BotState.OPEN_AH);
                    }
                    break;

                case CLOSE_AH:
                    client.setScreen(null);
                    sendMsg("Закрываю аукцион, открываю заново.", Formatting.GRAY);
                    setState(BotState.OPEN_AH);
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
                    // После продажи продолжаем текущий проход (снова откроем ах)
                    setState(BotState.OPEN_AH);
                    setWait(20);
                    break;
            }
        });
    }

    /**
     * Сканирует текущую страницу, обновляет статистику и принимает решение о покупке.
     * @return true, если найден лот для немедленной покупки
     */
    private boolean scanAndEvaluate(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return false;

        Map<String, List<Long>> pagePrices = new HashMap<>();

        // Сбор данных со страницы
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String name = stack.getName().getString();
            long price = extractPrice(stack);
            if (price <= 0) continue;

            pagePrices.computeIfAbsent(name, k -> new ArrayList<>()).add(price);
        }

        // Обновление общей статистики
        for (var entry : pagePrices.entrySet()) {
            String name = entry.getKey();
            List<Long> newPrices = entry.getValue();

            priceSamples.computeIfAbsent(name, k -> new ArrayList<>()).addAll(newPrices);
            itemCounts.put(name, priceSamples.get(name).size());

            // Пересчёт средней рыночной
            List<Long> allPrices = priceSamples.get(name);
            long avg = (long) allPrices.stream().mapToLong(Long::longValue).average().orElse(0);
            marketPrices.put(name, avg);
        }

        // Поиск цели для покупки
        Slot bestSlot = null;
        String bestName = "";
        long bestPrice = Long.MAX_VALUE;
        double bestDiscount = 0;

        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String name = stack.getName().getString();
            long price = extractPrice(stack);
            if (price <= 0) continue;

            boolean isTotem = name.toLowerCase().contains("тотем") || name.toLowerCase().contains("totem");

            // Спецправило для тотемов в диапазоне 50-100к
            if (isTotem && price >= 50000 && price <= 100000) {
                buySlotId = i;
                buyItemName = name;
                buyPrice = price;
                sendMsg("Найден тотем по спеццене: " + price, Formatting.AQUA);
                return true;
            }

            // Обычные правила покупки: достаточно лотов и цена ниже рынка на 25%
            long market = marketPrices.getOrDefault(name, price);
            int count = itemCounts.getOrDefault(name, 0);
            if (count >= MIN_LOTS_FOR_PURCHASE && market > 0) {
                double discount = 1.0 - (double) price / market;
                if (discount >= 0.25 && price < bestPrice) {
                    bestPrice = price;
                    bestSlot = slot;
                    bestName = name;
                    bestDiscount = discount;
                }
            }
        }

        if (bestSlot != null) {
            buySlotId = bestSlot.id;
            buyItemName = bestName;
            buyPrice = bestPrice;
            sendMsg("Выгодное предложение: " + bestName + " за " + bestPrice +
                    " (рынок " + marketPrices.get(bestName) + ", скидка " +
                    String.format("%.0f%%", bestDiscount * 100) + ")", Formatting.AQUA);
            return true;
        }

        return false;
    }

    /**
     * Ищет в слотах предмет, символизирующий кнопку "Следующая страница".
     * @return id слота или -1
     */
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
