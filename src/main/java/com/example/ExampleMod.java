package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.item.ItemStack;
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

    // Состояния бота
    enum BotState {
        IDLE, CHECK_BALANCE, OPEN_AH, REFRESH_AH, SCAN_AH, BUY_ITEM, SELL_ITEM
    }

    // Для хранения рыночных цен (имя предмета -> средняя цена)
    private final Map<String, Long> marketPrices = new HashMap<>();

    // Текущая цель для покупки
    private int buySlotId = -1;
    private String buyItemName = "";
    private long buyPrice = 0;

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

        // Обработчик чата для баланса
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

        // Основной тик-цикл
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null) return;

            // Переключение бота клавишей U
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
                    client.getNetworkHandler().sendCommand("ah");
                    setState(BotState.REFRESH_AH);
                    setWait(30); // ждем открытия GUI
                    break;

                case REFRESH_AH:
                    if (client.currentScreen instanceof HandledScreen) {
                        clickRefreshButton(client.currentScreen);
                        setState(BotState.SCAN_AH);
                        setWait(10); // даем время на обновление
                    } else {
                        sendMsg("Аукцион закрыт, пробую открыть снова.", Formatting.RED);
                        setState(BotState.OPEN_AH);
                        setWait(40);
                    }
                    break;

                case SCAN_AH:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        scanAuction(screen);
                        if (buySlotId != -1) {
                            setState(BotState.BUY_ITEM);
                        } else {
                            sendMsg("Нет выгодных предложений, обновляю...", Formatting.GRAY);
                            setState(BotState.REFRESH_AH);
                            setWait(6); // 0.3 сек
                        }
                    } else {
                        setState(BotState.OPEN_AH);
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
                        setState(BotState.SELL_ITEM);
                        setWait(20);
                    } else {
                        setState(BotState.OPEN_AH);
                    }
                    break;

                case SELL_ITEM:
                    long marketPrice = marketPrices.getOrDefault(buyItemName, 0L);
                    if (marketPrice <= 0) marketPrice = buyPrice; // если рынок не известен, продаём хотя бы с наценкой
                    long sellPrice = (long)(marketPrice * 1.25);
                    if (sellPrice <= 0) sellPrice = 50000;
                    client.getNetworkHandler().sendCommand("ah sell " + sellPrice);
                    sendMsg("Выставляю " + buyItemName + " за " + sellPrice, Formatting.GREEN);
                    buySlotId = -1;
                    buyItemName = "";
                    buyPrice = 0;
                    setState(BotState.REFRESH_AH);
                    setWait(20);
                    break;
            }
        });
    }

    /**
     * Находит и нажимает кнопку "Обновить" в GUI аукциона.
     */
    private void clickRefreshButton(Screen screen) {
        for (var child : screen.children()) {
            if (child instanceof ClickableWidget widget) {
                String text = widget.getMessage().getString().toLowerCase();
                if (text.contains("обновить") || text.contains("refresh")) {
                    widget.onPress();
                    return;
                }
            }
        }
        // Если кнопка не найдена — просто ничего не делаем (иногда аукцион обновляется автоматически)
    }

    /**
     * Сканирует аукцион, обновляет рыночные цены и ищет самый выгодный лот для покупки.
     */
    private void scanAuction(HandledScreen<?> screen) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        // Сбор информации со всех слотов
        Map<String, List<Long>> itemPrices = new HashMap<>();
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
            if (price <= 0) continue; // не можем определить цену

            itemPrices.computeIfAbsent(name, k -> new ArrayList<>()).add(price);

            // Поиск самого выгодного (цена на 25% ниже средней рыночной для этого предмета)
            // Рыночная цена будет рассчитана после сбора всех данных, поэтому первое действие — сбор,
            // а потом уже поиск лучшего. Поэтому здесь просто накапливаем.
        }

        // Обновляем средние рыночные цены по всем предметам
        for (Map.Entry<String, List<Long>> entry : itemPrices.entrySet()) {
            String name = entry.getKey();
            List<Long> prices = entry.getValue();
            long avg = (long) prices.stream().mapToLong(Long::longValue).average().orElse(0);
            marketPrices.put(name, avg);
        }

        // Теперь ищем лучший лот для покупки, сравнивая с обновлённой рыночной ценой
        bestSlot = null;
        bestPrice = Long.MAX_VALUE;
        bestDiscount = 0;
        for (int i = 0; i < screen.getScreenHandler().slots.size(); i++) {
            Slot slot = screen.getScreenHandler().slots.get(i);
            if (!slot.hasStack()) continue;
            ItemStack stack = slot.getStack();
            String name = stack.getName().getString();
            long price = extractPrice(stack);
            if (price <= 0) continue;

            long market = marketPrices.getOrDefault(name, price);
            if (market <= 0) continue;
            double discount = 1.0 - (double) price / market; // >0, если цена ниже рынка
            if (discount >= 0.25 && price < bestPrice) { // минимум 25% скидки и самая дешёвая из подходящих
                bestPrice = price;
                bestSlot = slot;
                bestName = name;
            }
        }

        if (bestSlot != null) {
            buySlotId = bestSlot.id;
            buyItemName = bestName;
            buyPrice = bestPrice;
            sendMsg("Найден " + bestName + " за " + bestPrice + " (рынок " + marketPrices.get(bestName) + ")", Formatting.AQUA);
        } else {
            buySlotId = -1;
        }
    }

    /**
     * Извлекает цену из предмета аукциона (из строки лора, содержащей "Цена:" или "цена:").
     */
    private long extractPrice(ItemStack stack) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return -1;
        List<Text> lore = stack.getTooltip(client.player, 
                net.minecraft.item.tooltip.TooltipContext.Default.BASIC);
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
