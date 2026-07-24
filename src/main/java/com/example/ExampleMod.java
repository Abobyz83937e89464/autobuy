package com.example;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ClientModInitializer {
    public static final String MOD_ID = "autobuy";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    // Переменная для зарегистрированного бинда
    private static KeyBinding toggleKey;

    private boolean isActive = false;  
    private long currentBalance = 0;  

    private BotState currentState = BotState.IDLE;  
    private int waitTicks = 0;  

    private int targetSlotId = -1;  
    private long medianPrice = 0;  
    private int observeAttempts = 0;  

    enum BotState {  
        IDLE, CHECK_BALANCE, OPEN_AH, SCAN_AH, OBSERVE, BUY_ITEM, SELL_ITEM, REST  
    }  

    @Override  
    public void onInitializeClient() {  
        LOGGER.info("AutoBuy mod initialized.");  

        // Регистрируем кнопку U в стандартной категории "Разное" (KeyBinding.MISC_CATEGORY)
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autobuy.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KeyBinding.MISC_CATEGORY
        ));

        // Отслеживание сообщений о балансе
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {  
            if (!isActive || currentState != BotState.CHECK_BALANCE) return;  
            String text = message.getString();  

            if (text.contains("Баланс:") || text.toLowerCase().contains("balance")) {  
                MinecraftClient.getInstance().execute(() -> {  
                    try {  
                        String nums = text.replaceAll("[^0-9]", "");  
                        if (!nums.isEmpty()) {  
                            currentBalance = Long.parseLong(nums);  
                            sendMsg("Баланс обновлен: " + currentBalance, Formatting.YELLOW);  
                            if (currentBalance <= 0) {  
                                sendMsg("Мало денег. Ухожу в слип на 5 мин.", Formatting.RED);  
                                setState(BotState.REST);  
                                setWait(6000);  
                            } else {  
                                setState(BotState.OPEN_AH);  
                                setWait(40);  
                            }  
                        }  
                    } catch (Exception e) {  
                        sendMsg("Ошибка парсинга баланса.", Formatting.RED);  
                    }  
                });  
            }  
        });  

        // Главный тик-цикл бота
        ClientTickEvents.END_CLIENT_TICK.register(client -> {  
            if (client.player == null) return;  

            // Обработка нажатия кнопки U (срабатывает ровно 1 раз за клик)
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
                    client.getNetworkHandler().sendCommand("money");  
                    setWait(100);  
                    break;  

                case OPEN_AH:  
                    client.getNetworkHandler().sendCommand("ah category netherite");  
                    setState(BotState.SCAN_AH);  
                    setWait(40);  
                    break;  

                case SCAN_AH:  
                    if (client.currentScreen instanceof HandledScreen<?>) {  
                        sendMsg("Анализ рынка...", Formatting.AQUA);  
                        targetSlotId = 11;  
                        medianPrice = 10000;  
                        observeAttempts = 0;  
                        setState(BotState.OBSERVE);  
                        setWait(200);  
                    } else {  
                        sendMsg("GUI аукциона не открылось. Пробую снова.", Formatting.RED);  
                        setState(BotState.OPEN_AH);  
                        setWait(60);  
                    }  
                    break;  

                case OBSERVE:  
                    if (client.currentScreen instanceof HandledScreen<?> screen) {  
                        if (targetSlotId >= 0 && targetSlotId < screen.getScreenHandler().slots.size()) {  
                            Slot targetSlot = screen.getScreenHandler().slots.get(targetSlotId);  
                            if (!targetSlot.hasStack()) {  
                                sendMsg("Лот исчез! Спрос подтвержден. Идем покупать.", Formatting.GREEN);  
                                setState(BotState.BUY_ITEM);  
                            } else {  
                                observeAttempts++;  
                                if (observeAttempts < 3) {  
                                    sendMsg("Лот еще на месте. Жду еще 10 сек...", Formatting.YELLOW);  
                                    setWait(200);  
                                } else {  
                                    sendMsg("Спроса нет. Никто не берет. Перекур.", Formatting.GOLD);  
                                    setState(BotState.REST);  
                                    setWait(2400 + (int)(Math.random() * 1200));  
                                }  
                            }  
                        }  
                    } else {  
                        setState(BotState.OPEN_AH);  
                    }  
                    break;  

                case BUY_ITEM:  
                    sendMsg("Кликаю по лоту (покупка)!", Formatting.GREEN);  
                    if (client.interactionManager != null && client.currentScreen instanceof HandledScreen<?> screen) {  
                        client.interactionManager.clickSlot(  
                            screen.getScreenHandler().syncId,  
                            targetSlotId,  
                            0,  
                            net.minecraft.screen.slot.SlotActionType.PICKUP,  
                            client.player  
                        );  
                    }  
                    setState(BotState.SELL_ITEM);  
                    setWait(40);  
                    client.setScreen(null);  
                    break;  

                case SELL_ITEM:  
                    long sellPrice = (long)(medianPrice * 1.05);  
                    if (sellPrice <= 0) sellPrice = 50000;  
                    sendMsg("Выставляю купленный товар за: " + sellPrice, Formatting.GREEN);  
                    client.getNetworkHandler().sendCommand("ah sell " + sellPrice);  
                    setState(BotState.CHECK_BALANCE);  
                    setWait(60);  
                    break;  

                case REST:  
                    setState(BotState.CHECK_BALANCE);  
                    break;  
            }  
        });  
    }  

    private void setState(BotState state) {  
        this.currentState = state;  
    }  

    private void setWait(int ticks) {  
        this.waitTicks = ticks + (int)(Math.random() * 10);  
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
