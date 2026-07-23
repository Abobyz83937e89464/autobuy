package com.example;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "autobuy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private boolean isActive = false;
    private long maxBudget = 0;
    private long currentBalance = 0;

    private BotState currentState = BotState.IDLE;
    private int waitTicks = 0;

    // Переменные для алгоритма скальпинга
    private int targetSlotId = -1;
    private long medianPrice = 0;
    private int observeAttempts = 0;

    enum BotState {
        IDLE, CHECK_BALANCE, OPEN_AH, SCAN_AH, OBSERVE, BUY_ITEM, SELL_ITEM, REST
    }

    @Override
    public void onInitialize() {
        LOGGER.info("AutoBuy mod initialized.");

        // 1. Инициализация и настройка (Перехват чата)
        // Используем ALLOW_CHAT, чтобы возвращать false и не давать команде уйти на сервер
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (message.startsWith(".startbot")) {
                isActive = !isActive;
                sendClientMessage("Автобай " + (isActive ? "§aВКЛЮЧЕН" : "§cВЫКЛЮЧЕН"));
                if (isActive) {
                    setState(BotState.CHECK_BALANCE);
                } else {
                    setState(BotState.IDLE);
                }
                return false; // Блокируем отправку на сервер
            }

            if (message.startsWith(".botmax")) {
                try {
                    String[] parts = message.split(" ");
                    if (parts.length > 1) {
                        maxBudget = Long.parseLong(parts[1]);
                        sendClientMessage("Максимальный бюджет установлен: §e" + maxBudget);
                    }
                } catch (NumberFormatException e) {
                    sendClientMessage("§cОшибка: введите число. Пример: .botmax 250000");
                }
                return false; // Блокируем отправку на сервер
            }
            return true; // Разрешаем отправку остальных сообщений
        });

        // 2. Сканирование баланса (Парсинг входящих сообщений)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!isActive || currentState != BotState.CHECK_BALANCE) return;
            String text = message.getString();
            
            // TODO: Подставить точное слово, которое пишет FunTime при /money
            if (text.contains("Баланс:") || text.toLowerCase().contains("balance")) {
                try {
                    // Извлекаем только цифры из сообщения
                    String nums = text.replaceAll("[^0-9]", "");
                    currentBalance = Long.parseLong(nums);
                    sendClientMessage("Баланс обновлен: §e" + currentBalance);
                    
                    if (currentBalance <= 0 || (maxBudget > 0 && currentBalance < maxBudget * 0.1)) {
                        sendClientMessage("§cМало денег. Ухожу в слип.");
                        setState(BotState.REST);
                        setWait(6000); // 5 минут отдыха (20 tps * 300)
                    } else {
                        setState(BotState.OPEN_AH);
                        setWait(40); // Ждем 2 сек перед открытием аукциона
                    }
                } catch (Exception e) {
                    sendClientMessage("§cОшибка парсинга баланса.");
                }
            }
        });

        // 3. Основной цикл машины состояний
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!isActive || client.player == null) return;

            // Обработка пауз (рандомизация таймингов)
            if (waitTicks > 0) {
                waitTicks--;
                return;
            }

            switch (currentState) {
                case IDLE:
                    break;

                case CHECK_BALANCE:
                    client.getNetworkHandler().sendCommand("money");
                    setWait(100); // Таймаут, если сервер не ответил
                    break;

                case OPEN_AH:
                    // Открываем категорию незерита
                    client.getNetworkHandler().sendCommand("ah category netherite");
                    setState(BotState.SCAN_AH);
                    setWait(40); // Ждем пока GUI прогрузится
                    break;

                case SCAN_AH:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        sendClientMessage("Анализ рынка...");
                        
                        // TODO: Здесь будет парсинг NBT/Lore слотов
                        // 1. Пройтись по screen.getScreenHandler().slots
                        // 2. Собрать цены из Lore предметов (item.getTooltip())
                        // 3. Вычислить медиану (ММЦ)
                        // 4. Найти самый дешевый слот

                        // Пока симулируем успешное нахождение лота Л1:
                        targetSlotId = 11; // Допустим, 11 слот
                        observeAttempts = 0;
                        setState(BotState.OBSERVE);
                        setWait(200); // Шаг Б: Пауза 10 секунд (20 tps * 10)
                    } else {
                        sendClientMessage("§cGUI аукциона не открылось. Повтор.");
                        setState(BotState.OPEN_AH);
                        setWait(60);
                    }
                    break;

                case OBSERVE:
                    if (client.currentScreen instanceof HandledScreen<?> screen) {
                        Slot targetSlot = screen.getScreenHandler().slots.get(targetSlotId);
                        
                        // Проверяем, купили ли лот Л1 за эти 10 секунд
                        if (!targetSlot.hasStack()) {
                            sendClientMessage("§aЛ1 исчез (куплен)! Спрос подтвержден. Ищу Л2.");
                            // TODO: Найти следующий слот ниже ММЦ и обновить targetSlotId
                            setState(BotState.BUY_ITEM);
                        } else {
                            observeAttempts++;
                            if (observeAttempts < 3) {
                                sendClientMessage("Л1 на месте. Жду еще 10 сек...");
                                // TODO: Переключиться на следующий лот для проверки
                                setWait(200); 
                            } else {
                                sendClientMessage("§eСпроса нет. Никто ничего не купил. Перекур.");
                                setState(BotState.REST);
                                setWait(2400 + (int)(Math.random() * 2400)); // Рандом 2-4 мин
                            }
                        }
                    } else {
                        setState(BotState.OPEN_AH); // Если закрылось — открываем заново
                    }
                    break;

                case BUY_ITEM:
                    sendClientMessage("§aПокупаю лот!");
                    if (client.interactionManager != null && client.currentScreen instanceof HandledScreen<?> screen) {
                        // Эмуляция клика по слоту. Формат: (syncId, slotId, button, actionType, player)
                        client.interactionManager.clickSlot(
                            screen.getScreenHandler().syncId,
                            targetSlotId,
                            0, // Левый клик
                            net.minecraft.screen.slot.SlotActionType.PICKUP,
                            client.player
                        );
                    }
                    setState(BotState.SELL_ITEM);
                    setWait(40); // Ждем 2 сек на обработку покупки сервером
                    client.setScreen(null); // Закрываем GUI
                    break;

                case SELL_ITEM:
                    // Берем предмет (должен упасть в инвентарь)
                    long sellPrice = (long) (medianPrice * 1.05); // Цена ММЦ + 5%
                    sendClientMessage("§aВыставляю товар за: §e" + sellPrice);
                    
                    // Убеждаемся, что предмет в руке (опционально: переложить в хотбар пакетом)
                    client.getNetworkHandler().sendCommand("ah sell " + sellPrice);
                    
                    setState(BotState.CHECK_BALANCE); // Цикл замыкается
                    setWait(60);
                    break;

                case REST:
                    // Состояние отдыха
                    setState(BotState.CHECK_BALANCE);
                    break;
            }
        });
    }

    private void setState(BotState state) {
        this.currentState = state;
    }

    private void setWait(int ticks) {
        // Добавляем микро-рандомизацию к задержке (анти-детект)
        this.waitTicks = ticks + (int)(Math.random() * 10);
    }

    private void sendClientMessage(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal("§8[§6AutoBuy§8] §f" + msg), false);
        }
    }
}
