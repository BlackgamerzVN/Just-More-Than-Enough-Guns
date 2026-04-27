package com.blackgamerz.jmteg.jegcompat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class GunDataManager {

    private static final Map<ResourceLocation, JsonObject> gunConfigCache = new HashMap<>();

    /**
     * Loads (and caches) the gun config JSON for a given item id.
     * Adjust the resource path for your file locations!
     */
    public static JsonObject getConfig(ResourceLocation itemId) {
        if (gunConfigCache.containsKey(itemId)) return gunConfigCache.get(itemId);

        // Example: "/assets/jeg/guns/assault_rifle.json" for jeg:assault_rifle
        String filePath = "/assets/" + itemId.getNamespace() + "/guns/" + itemId.getPath() + ".json";
        try (InputStream is = GunDataManager.class.getResourceAsStream(filePath)) {
            if (is == null) return null;
            JsonObject obj = JsonParser.parseReader(new InputStreamReader(is, StandardCharsets.UTF_8)).getAsJsonObject();
            gunConfigCache.put(itemId, obj);
            return obj;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String getAmmoTypeFromJson(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        // Load JSON config for itemId (e.g. jeg:assault_rifle loads "assault_rifle.json")
        JsonObject gunConfig = GunDataManager.getConfig(itemId);
        if (gunConfig == null) return null;
        if (gunConfig.has("projectile")) {
            JsonObject proj = gunConfig.getAsJsonObject("projectile");
            if (proj.has("item")) return proj.get("item").toString();
        }
        if (gunConfig.has("reloads")) {
            JsonObject reloads = gunConfig.getAsJsonObject("reloads");
            if (reloads.has("reloadItem")) return reloads.get("reloadItem").toString();
        }
        return null;
    }
}
