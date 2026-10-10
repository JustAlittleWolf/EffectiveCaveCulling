package me.wolfii.effectivecaveculling.config;

import dev.isxander.yacl3.api.ButtonOption;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class CavefillConfigScreen {
	private CavefillConfigScreen() {
	}

	public static Screen create(Screen parent) {
		ListOption<String> worlds = ListOption.<String>createBuilder()
			.name(Component.translatable("effectivecaveculling.config.whitelist"))
			.description(OptionDescription.of(Component.translatable("effectivecaveculling.config.whitelist.description")))
			.binding(List.of(), CavefillConfig::whitelistView, CavefillConfig::setWhitelist)
			.controller(StringControllerBuilder::create)
			.initial("")
			.insertEntriesAtEnd(true)
			.build();
		ButtonOption addCurrent = ButtonOption.createBuilder()
			.name(Component.translatable("effectivecaveculling.config.add_current"))
			.text(Component.translatable("effectivecaveculling.config.add_current.text"))
			.description(OptionDescription.of(Component.translatable("effectivecaveculling.config.add_current.description")))
			.action((screen, button) -> addCurrentWorld(worlds))
			.build();
		return YetAnotherConfigLib.createBuilder()
			.title(Component.translatable("effectivecaveculling.config.title"))
			.category(ConfigCategory.createBuilder()
				.name(Component.translatable("effectivecaveculling.config.category.general"))
				.option(Option.<Boolean>createBuilder()
					.name(Component.translatable("effectivecaveculling.config.enabled"))
					.description(OptionDescription.of(Component.translatable("effectivecaveculling.config.enabled.description")))
					.binding(true, CavefillConfig::enabled, CavefillConfig::setEnabled)
					.controller(TickBoxControllerBuilder::create)
					.instant(true)
					.build())
				.group(worlds)
				.option(addCurrent)
				.build())
			.save(CavefillConfig.HANDLER::save)
			.screenInit(screen -> addCurrent.setAvailable(CurrentWorld.key().isPresent()))
			.build()
			.generateScreen(parent);
	}

	private static void addCurrentWorld(ListOption<String> worlds) {
		String key = CurrentWorld.key().orElse(null);
		if (key == null || WorldKeys.contains(worlds.pendingValue(), key)) {
			return;
		}
		List<String> pending = new ArrayList<>(worlds.pendingValue());
		pending.add(key);
		worlds.requestSet(pending);
		worlds.applyValue();
	}
}
