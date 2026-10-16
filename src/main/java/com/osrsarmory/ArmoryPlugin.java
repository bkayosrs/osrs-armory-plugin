package com.osrsarmory;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
@PluginDescriptor(
	name = "OSRS Armory",
	description = "Shares your gear, skills, quests and diaries on the Armory website",
	tags = {"armory", "equipment", "gear", "skills", "quests"}
)
public class ArmoryPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

	// Skills, quests, diaries etc. are re-read this often. Equipment changes are sent right away.
	private static final long PROFILE_INTERVAL_MS = 60_000;
	// Wait a few ticks after login so quest and varbit data has arrived from the server.
	private static final int LOGIN_SETTLE_TICKS = 5;

	// The 11 visible equipment slots. Slot indices 6, 8 and 11 are unused by the game.
	private static final EquipmentInventorySlot[] SLOTS = {
		EquipmentInventorySlot.HEAD,
		EquipmentInventorySlot.CAPE,
		EquipmentInventorySlot.AMULET,
		EquipmentInventorySlot.WEAPON,
		EquipmentInventorySlot.BODY,
		EquipmentInventorySlot.SHIELD,
		EquipmentInventorySlot.LEGS,
		EquipmentInventorySlot.GLOVES,
		EquipmentInventorySlot.BOOTS,
		EquipmentInventorySlot.RING,
		EquipmentInventorySlot.AMMO,
	};

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ArmoryConfig config;

	@Inject
	private ProfileCollector collector;

	@Inject
	private AvatarCapture avatar;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	// Latest equipment snapshot; null until the equipment container has loaded.
	private Map<String, Integer> equipment;
	private boolean equipmentDirty;
	// Latest profile (quests, diaries, ...); null until first collected after login.
	private Map<String, Object> profile;
	private long lastProfileMs;
	private int ticksLoggedIn;
	// Last payload we successfully queued, so identical updates aren't resent.
	private volatile String lastSentJson;

	@Provides
	ArmoryConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ArmoryConfig.class);
	}

	@Override
	protected void startUp()
	{
		reset();

		// Plugin enabled while already logged in: pick up the current gear.
		// Client state must be read on the client thread, not the Swing thread startUp runs on.
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				ItemContainer worn = client.getItemContainer(InventoryID.WORN);
				if (worn != null)
				{
					equipment = readEquipment(worn);
					equipmentDirty = true;
				}
			}
		});
	}

	@Override
	protected void shutDown()
	{
		reset();
	}

	private void reset()
	{
		equipment = null;
		equipmentDirty = false;
		profile = null;
		lastProfileMs = 0;
		ticksLoggedIn = 0;
		lastSentJson = null;
		avatar.reset();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		// WORN is the equipment container (formerly InventoryID.EQUIPMENT).
		if (event.getContainerId() != InventoryID.WORN)
		{
			return;
		}

		equipment = readEquipment(event.getItemContainer());
		equipmentDirty = true;
		avatar.markDirty();
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		ticksLoggedIn++;

		boolean profileDue = ticksLoggedIn >= LOGIN_SETTLE_TICKS
			&& System.currentTimeMillis() - lastProfileMs >= PROFILE_INTERVAL_MS;
		if (profileDue)
		{
			profile = collector.profile();
			lastProfileMs = System.currentTimeMillis();
		}

		// Identical payloads are skipped in trySend, so a quiet minute costs no request.
		if (equipment != null && (equipmentDirty || profileDue))
		{
			trySend();
		}

		// Character picture, captured while the "Equip Your Character" screen is open.
		String username = username();
		if (username != null)
		{
			avatar.onTick(png ->
			{
				Map<String, Object> payload = new LinkedHashMap<>();
				payload.put("username", username);
				payload.put("image", png);
				post("update-avatar", gson.toJson(payload), false);
			});
		}
	}

	// Jagex names use non-breaking spaces; normalised to regular spaces. Null before login completes.
	private String username()
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return null;
		}
		return Text.toJagexName(local.getName()).replace((char) 0xA0, ' ');
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			// Force a fresh send after logging in on another account or world.
			reset();
		}
	}

	private Map<String, Integer> readEquipment(ItemContainer container)
	{
		Map<String, Integer> result = new LinkedHashMap<>();
		for (EquipmentInventorySlot slot : SLOTS)
		{
			Item item = container.getItem(slot.getSlotIdx());
			result.put(slot.name(), item == null ? -1 : item.getId());
		}
		return result;
	}

	private void trySend()
	{
		String username = username();
		if (username == null)
		{
			return; // retry on next GameTick
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", username);
		payload.put("equipment", equipment);
		payload.put("itemNames", collector.itemNames(equipment));
		payload.put("bonuses", collector.bonuses(equipment));

		Map<String, Map<String, Integer>> skills = collector.skills();
		if (skills != null)
		{
			payload.put("skills", skills);
		}
		if (profile != null)
		{
			payload.put("profile", profile);
		}
		equipmentDirty = false;

		String json = gson.toJson(payload);
		if (json.equals(lastSentJson))
		{
			return;
		}
		lastSentJson = json;

		post(null, json, true);
	}

	/**
	 * POSTs JSON to the configured endpoint, or to a sibling route of it (e.g. "update-avatar").
	 * retryOnFailure clears the de-duplication state so the next change resends.
	 */
	private void post(String route, String json, boolean retryOnFailure)
	{
		HttpUrl endpoint = HttpUrl.parse(config.endpoint());
		HttpUrl url = endpoint == null || route == null ? endpoint : endpoint.resolve(route);
		if (url == null)
		{
			log.warn("OSRS Armory: invalid server URL '{}'", config.endpoint());
			return;
		}

		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, json))
			.build();

		// enqueue() runs on OkHttp's thread pool, so the client thread is never blocked.
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("OSRS Armory: failed to send update", e);
				if (retryOnFailure)
				{
					lastSentJson = null; // allow a retry on the next change
				}
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (response)
				{
					if (!response.isSuccessful())
					{
						log.debug("OSRS Armory: server responded {}", response.code());
						if (retryOnFailure)
						{
							lastSentJson = null;
						}
					}
				}
			}
		});
	}
}
