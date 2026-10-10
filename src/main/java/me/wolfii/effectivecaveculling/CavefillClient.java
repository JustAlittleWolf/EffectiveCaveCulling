package me.wolfii.effectivecaveculling;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class CavefillClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		CavefillController.init();
		ClientTickEvents.END_CLIENT_TICK.register(CavefillController::onClientTick);
	}
}
