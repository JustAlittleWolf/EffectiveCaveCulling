package me.wolfii.effectivecaveculling.config;

import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;

public final class CurrentWorld {
	private CurrentWorld() {
	}

	public static Optional<String> key() {
		return key(Minecraft.getInstance());
	}

	/**
	 * Singleplayer uses the world name. Multiplayer uses the address the client connected with,
	 * dropping {@link SharedConstants#DEFAULT_MINECRAFT_PORT} the same way the server list does.
	 */
	public static Optional<String> key(Minecraft minecraft) {
		if (minecraft.level == null) {
			return Optional.empty();
		}
		if (minecraft.isLocalServer()) {
			IntegratedServer server = minecraft.getSingleplayerServer();
			if (server == null) {
				return Optional.empty();
			}
			String levelName = server.getWorldData().getLevelName();
			if (levelName == null || levelName.isBlank()) {
				return Optional.empty();
			}
			return Optional.of(WorldKeys.singleplayer(levelName));
		}
		ServerData server = minecraft.getCurrentServer();
		if (server == null || server.ip == null || server.ip.isBlank()) {
			return Optional.empty();
		}
		return Optional.of(WorldKeys.serverAddress(server.ip, SharedConstants.DEFAULT_MINECRAFT_PORT));
	}
}
