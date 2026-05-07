package com.blackgamerz.jmteg.client.ui;

import com.blackgamerz.jmteg.Main;
import com.blackgamerz.jmteg.network.JmtegNetwork;
import com.blackgamerz.jmteg.network.payload.C2SRecruitDoctrineRequestPayload;
import com.blackgamerz.jmteg.network.payload.C2SRecruitDoctrineSetPayload;
import com.blackgamerz.jmteg.network.payload.S2CRecruitDoctrineSyncPayload;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrine;
import com.blackgamerz.jmteg.recruitcompat.RecruitDoctrineHolder;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Injects doctrine controls into Recruits inventory screen using reflection only.
 */
@Mod.EventBusSubscriber(modid = Main.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class RecruitDoctrineScreenIntegration {
    private static final Logger LOGGER = LogManager.getLogger("JMTEG-RecruitDoctrineUI");

    private static final String RECRUIT_INVENTORY_SCREEN_CLASS = "com.talhanation.recruits.client.gui.RecruitInventoryScreen";

    private static final Map<UUID, SyncedDoctrineState> SYNCED_STATE = new ConcurrentHashMap<>();
    private static final Map<Screen, UiState> SCREEN_STATES = Collections.synchronizedMap(new WeakHashMap<>());

    private RecruitDoctrineScreenIntegration() {}

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (screen == null || !isRecruitInventoryScreen(screen)) {
            return;
        }

        UUID recruitUuid = extractRecruitUuid(screen);
        if (recruitUuid == null) {
            return;
        }

        UiState state = new UiState(recruitUuid);
        SyncedDoctrineState synced = SYNCED_STATE.get(recruitUuid);
        if (synced != null) {
            state.applySync(synced);
        }
        SCREEN_STATES.put(screen, state);

        int left = Math.max(6, screen.width / 2 - 88);
        int top = Math.max(6, screen.height / 2 - 84);

        Button modeButton = Button.builder(Component.literal("Doctrine: Inherit"), b -> {
            state.override = !state.override;
            if (state.override && state.selectedDoctrine == null) {
                state.selectedDoctrine = state.effectiveDoctrine != null
                        ? state.effectiveDoctrine
                        : RecruitDoctrine.values()[0];
            }
            sendDoctrineUpdate(state);
            refreshButtons(state);
        }).bounds(left, top, 176, 20).build();

        Button doctrineButton = Button.builder(Component.literal("Doctrine: -"), b -> {
            if (!state.override) {
                return;
            }
            RecruitDoctrine[] values = RecruitDoctrine.values();
            if (state.selectedDoctrine == null) {
                state.selectedDoctrine = values[0];
            } else {
                state.selectedDoctrine = values[(state.selectedDoctrine.ordinal() + 1) % values.length];
            }
            sendDoctrineUpdate(state);
            refreshButtons(state);
        }).bounds(left, top + 22, 176, 20).build();

        Button effectiveLabel = Button.builder(Component.literal("Effective: None (None)"), b -> {
        }).bounds(left, top + 44, 176, 20).build();
        effectiveLabel.active = false;

        state.modeButton = modeButton;
        state.doctrineButton = doctrineButton;
        state.effectiveLabel = effectiveLabel;

        refreshButtons(state);

        event.addListener(modeButton);
        event.addListener(doctrineButton);
        event.addListener(effectiveLabel);

        JmtegNetwork.sendToServer(new C2SRecruitDoctrineRequestPayload(recruitUuid));
    }

    public static void handleSync(S2CRecruitDoctrineSyncPayload payload) {
        if (payload == null || payload.recruitUuid() == null) {
            return;
        }

        SyncedDoctrineState synced = new SyncedDoctrineState(
                payload.personalDoctrine(),
                payload.effectiveDoctrine(),
                payload.source());
        SYNCED_STATE.put(payload.recruitUuid(), synced);

        synchronized (SCREEN_STATES) {
            for (UiState state : SCREEN_STATES.values()) {
                if (state == null || !payload.recruitUuid().equals(state.recruitUuid)) {
                    continue;
                }
                state.applySync(synced);
                refreshButtons(state);
            }
        }
    }

    private static void sendDoctrineUpdate(UiState state) {
        if (state.override) {
            RecruitDoctrine doctrine = state.selectedDoctrine != null ? state.selectedDoctrine : RecruitDoctrine.values()[0];
            JmtegNetwork.sendToServer(new C2SRecruitDoctrineSetPayload(state.recruitUuid, doctrine.name(), false));
            return;
        }
        JmtegNetwork.sendToServer(new C2SRecruitDoctrineSetPayload(state.recruitUuid, null, true));
    }

    private static void refreshButtons(UiState state) {
        if (state.modeButton == null || state.doctrineButton == null || state.effectiveLabel == null) {
            return;
        }

        String modeText = state.override ? "Doctrine: Override" : "Doctrine: Inherit";
        state.modeButton.setMessage(Component.literal(modeText));

        RecruitDoctrine shown = state.override
                ? (state.selectedDoctrine != null ? state.selectedDoctrine : RecruitDoctrine.values()[0])
                : state.personalDoctrine;
        String doctrineText = shown != null ? shown.displayName : "-";
        state.doctrineButton.setMessage(Component.literal("Doctrine: " + doctrineText));
        state.doctrineButton.active = state.override;

        String effectiveText = state.effectiveDoctrine != null ? state.effectiveDoctrine.displayName : "None";
        state.effectiveLabel.setMessage(Component.literal("Effective: " + effectiveText + " (" + sourceLabel(state.source) + ")"));
    }

    private static String sourceLabel(RecruitDoctrineHolder.DoctrineSource source) {
        if (source == null) {
            return "None";
        }
        return switch (source) {
            case PERSONAL -> "Personal";
            case COMMANDER -> "Commander";
            case NONE -> "None";
        };
    }

    private static boolean isRecruitInventoryScreen(Screen screen) {
        try {
            return RECRUIT_INVENTORY_SCREEN_CLASS.equals(screen.getClass().getName());
        } catch (Throwable t) {
            return false;
        }
    }

    private static UUID extractRecruitUuid(Screen screen) {
        try {
            Object menu = null;
            Method getMenu = findNoArgMethod(screen.getClass(), "getMenu");
            if (getMenu != null) {
                menu = getMenu.invoke(screen);
            }
            if (menu == null) {
                Field menuField = findField(screen.getClass(), "menu");
                if (menuField != null) {
                    menu = menuField.get(screen);
                }
            }
            if (menu == null) {
                return null;
            }

            Method getRecruit = findNoArgMethod(menu.getClass(), "getRecruit");
            if (getRecruit == null) {
                return null;
            }

            Object recruit = getRecruit.invoke(menu);
            if (recruit == null) {
                return null;
            }

            Method getUuid = findNoArgMethod(recruit.getClass(), "getUUID");
            if (getUuid == null) {
                return null;
            }
            Object uuidObj = getUuid.invoke(recruit);
            if (uuidObj instanceof UUID uuid) {
                return uuid;
            }
        } catch (Throwable t) {
            LOGGER.debug("Failed to resolve recruit from Recruits inventory screen", t);
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(name);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private record SyncedDoctrineState(
            RecruitDoctrine personal,
            RecruitDoctrine effective,
            RecruitDoctrineHolder.DoctrineSource source) {
    }

    private static final class UiState {
        private final UUID recruitUuid;
        private boolean override;
        private RecruitDoctrine selectedDoctrine;
        private RecruitDoctrine personalDoctrine;
        private RecruitDoctrine effectiveDoctrine;
        private RecruitDoctrineHolder.DoctrineSource source = RecruitDoctrineHolder.DoctrineSource.NONE;
        private Button modeButton;
        private Button doctrineButton;
        private Button effectiveLabel;

        private UiState(UUID recruitUuid) {
            this.recruitUuid = recruitUuid;
        }

        private void applySync(SyncedDoctrineState state) {
            this.personalDoctrine = state.personal();
            this.effectiveDoctrine = state.effective();
            this.source = state.source() != null ? state.source() : RecruitDoctrineHolder.DoctrineSource.NONE;
            this.override = this.personalDoctrine != null;
            this.selectedDoctrine = this.personalDoctrine != null
                    ? this.personalDoctrine
                    : (this.selectedDoctrine != null ? this.selectedDoctrine : this.effectiveDoctrine);
        }
    }
}
