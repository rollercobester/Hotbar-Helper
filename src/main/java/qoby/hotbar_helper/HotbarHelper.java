package qoby.hotbar_helper;

import net.fabricmc.api.ModInitializer;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HotbarHelper implements ModInitializer {
	public static final String MOD_ID = "hotbar-helper";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static HotbarHelperConfig config;

	/** Pending refills from server-side (e.g. drop mixin). Client polls this. */
	private static final Queue<PendingRefillRequest> pendingFromServer = new ConcurrentLinkedQueue<>();

	@Override
	public void onInitialize() {
		LOGGER.info("Hotbar Helper initialized");
	}

	public static HotbarHelperConfig getConfig() {
		return config;
	}

	public static void setConfig(HotbarHelperConfig config) {
		HotbarHelper.config = config;
	}

	/** Called by mixin (server thread) - schedules refill for client to process. */
	public static void scheduleRefill(int slot, Item itemType, HotbarRefillCause cause, HotbarHelperConfig config) {
		pendingFromServer.add(new PendingRefillRequest(slot, itemType, cause, config));
	}

	/** Polls and removes one pending refill from server, or null. */
	public static PendingRefillRequest pollPendingRefill() {
		return pendingFromServer.poll();
	}

	public record PendingRefillRequest(int slot, Item itemType, HotbarRefillCause cause, HotbarHelperConfig config) {
	}
}