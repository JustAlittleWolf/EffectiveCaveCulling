package me.wolfii.effectivecaveculling.config;

import com.google.common.net.HostAndPort;
import java.util.Collection;
import java.util.LinkedHashSet;

/**
 * Whitelist keys. Singleplayer worlds are {@code world/"World Name"}.
 * Servers are {@code host} when the port is Minecraft's default, and {@code host:port} otherwise.
 */
public final class WorldKeys {
	private WorldKeys() {
	}

	public static String singleplayer(String levelName) {
		return "world/\"" + escape(levelName) + "\"";
	}

	public static String server(String host, int port, int defaultPort) {
		String shown = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
		if (port == defaultPort) {
			return shown;
		}
		return shown + ":" + port;
	}

	public static String serverAddress(String address, int defaultPort) {
		String trimmed = address.trim();
		try {
			HostAndPort parsed = HostAndPort.fromString(trimmed).withDefaultPort(defaultPort);
			if (parsed.getHost().isEmpty()) {
				return trimmed;
			}
			return server(parsed.getHost(), parsed.getPort(), defaultPort);
		} catch (IllegalArgumentException ignored) {
			return trimmed;
		}
	}

	public static LinkedHashSet<String> normalize(Iterable<String> values) {
		LinkedHashSet<String> next = new LinkedHashSet<>();
		if (values == null) {
			return next;
		}
		for (String value : values) {
			if (value == null) {
				continue;
			}
			String trimmed = value.trim();
			if (!trimmed.isEmpty()) {
				next.add(trimmed);
			}
		}
		return next;
	}

	public static boolean contains(Collection<String> values, String key) {
		if (values == null || key == null) {
			return false;
		}
		for (String value : values) {
			if (value != null && value.trim().equals(key)) {
				return true;
			}
		}
		return false;
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}
}
