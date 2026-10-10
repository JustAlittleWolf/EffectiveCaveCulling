package me.wolfii.effectivecaveculling.config;

import me.wolfii.effectivecaveculling.Cavefill;
import me.wolfii.effectivecaveculling.CavefillController;

import dev.isxander.yacl3.config.v2.api.ConfigClassHandler;
import dev.isxander.yacl3.config.v2.api.SerialEntry;
import dev.isxander.yacl3.config.v2.api.serializer.GsonConfigSerializerBuilder;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public final class CavefillConfig {
	public static final ConfigClassHandler<CavefillConfig> HANDLER = ConfigClassHandler.createBuilder(CavefillConfig.class)
		.id(Identifier.fromNamespaceAndPath(Cavefill.MOD_ID, "config"))
		.serializer(config -> GsonConfigSerializerBuilder.create(config)
			.setPath(FabricLoader.getInstance().getConfigDir().resolve(Cavefill.MOD_ID + ".json5"))
			.setJson5(true)
			.build())
		.build();

	@SerialEntry(comment = "Master switch. Applies immediately, including while a world is open. Whitelisted worlds still have to opt in.")
	public boolean enabled = true;

	@SerialEntry(comment = "Worlds where the mod is allowed to run. Always a whitelist: an empty set disables every world and server. Singleplayer keys look like world/\"World Name\". Server keys are host, or host:port when the port is not the default Minecraft port. Duplicates are ignored.")
	public Set<String> whitelist = new LinkedHashSet<>();

	public CavefillConfig() {
	}

	public static void load() {
		HANDLER.load();
	}

	public static boolean enabled() {
		return HANDLER.instance().enabled;
	}

	public static void setEnabled(boolean enabled) {
		CavefillConfig config = HANDLER.instance();
		if (config.enabled == enabled) {
			return;
		}
		config.enabled = enabled;
		HANDLER.save();
		CavefillController.onConfigChanged();
	}

	public static Set<String> whitelist() {
		CavefillConfig config = HANDLER.instance();
		if (config.whitelist == null) {
			config.whitelist = new LinkedHashSet<>();
		}
		return config.whitelist;
	}

	public static List<String> whitelistView() {
		return new ArrayList<>(whitelist());
	}

	public static void setWhitelist(List<String> values) {
		LinkedHashSet<String> next = WorldKeys.normalize(values);
		Set<String> current = whitelist();
		if (current.equals(next)) {
			return;
		}
		current.clear();
		current.addAll(next);
		HANDLER.save();
		CavefillController.onConfigChanged();
	}

	public static boolean allows(Minecraft minecraft) {
		if (!enabled() || minecraft.player == null || minecraft.level == null || minecraft.level.isDebug()) {
			return false;
		}
		return CurrentWorld.key(minecraft).filter(whitelist()::contains).isPresent();
	}
}
