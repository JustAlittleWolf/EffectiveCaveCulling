package me.wolfii.effectivecaveculling;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldKeysTest {
	@Test
	void singleplayerWrapsTheLevelNameInQuotes() {
		assertEquals("world/\"New World\"", WorldKeys.singleplayer("New World"));
	}

	@Test
	void singleplayerEscapesQuotesAndBackslashes() {
		assertEquals("world/\"Say \\\"hi\\\" \\\\ path\"", WorldKeys.singleplayer("Say \"hi\" \\ path"));
	}

	@Test
	void defaultPortIsOmitted() {
		assertEquals("play.example.com", WorldKeys.server("play.example.com", 25565, 25565));
		assertEquals("play.example.com", WorldKeys.serverAddress("play.example.com:25565", 25565));
		assertEquals("play.example.com", WorldKeys.serverAddress("play.example.com", 25565));
	}

	@Test
	void otherPortsStayVisible() {
		assertEquals("localhost:25566", WorldKeys.server("localhost", 25566, 25565));
		assertEquals("localhost:25566", WorldKeys.serverAddress("localhost:25566", 25565));
	}

	@Test
	void ipv6KeepsBrackets() {
		assertEquals("[::1]", WorldKeys.server("::1", 25565, 25565));
		assertEquals("[::1]:25566", WorldKeys.serverAddress("[::1]:25566", 25565));
	}

	@Test
	void normalizeIsASet() {
		assertEquals(List.of("world/\"New World\"", "localhost"), List.copyOf(WorldKeys.normalize(List.of(
			" world/\"New World\" ",
			"",
			"localhost",
			"localhost",
			"   "
		))));
		assertTrue(WorldKeys.contains(List.of(" localhost "), "localhost"));
		assertFalse(WorldKeys.contains(List.of("localhost"), "other"));
	}
}
