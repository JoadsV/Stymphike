package com.stymphike;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Scene;
import net.runelite.api.Skill;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.events.StatChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@PluginDescriptor(
	name = "Stymphike Hunter",
	description = "Notifies you when you catch a stymphike and marks the trees you have baited.",
	tags = {"hunter", "stymphike", "vampyrium", "bird", "bait"}
)
public class StymphikePlugin extends Plugin
{
	// ==========================================================================
	// TODO: These are placeholders for brand-new content. Capture the real
	// values in-game (see the README notes) and paste them here. Nothing else
	// in the plugin needs to change once these are correct.
	// ==========================================================================

	/** The object name shown on the tree's right-click menu ("Check Stymphike tree"). */
	static final String TREE_NAME = "Stymphike tree";

	/**
	 * Object IDs of the stymphike tree (captured via dev-tools). Matching by ID is
	 * more reliable than by name for new content. Baiting keeps the same ID (only the
	 * menu option changes), so these cover both baited and un-baited states.
	 */
	static final Set<Integer> TREE_IDS = Set.of(40782, 40783, 40785);

	/** Object IDs of the hiding bush (captured via dev-tools). */
	static final Set<Integer> BUSH_IDS = Set.of(40781);

	/** Menu option used to bait the tree. Adjust if the real option differs. */
	static final String BAIT_OPTION = "Bait";

	/** NPC IDs of the stymphike bird (captured via dev-tools). */
	static final Set<Integer> STYMPHIKE_NPC_IDS = Set.of(15747);

	/** NPC name of the stymphike bird (fallback match, case-insensitive substring). */
	static final String STYMPHIKE_NPC = "stymphike";

	/** Tiles within which a bird is considered "at" a baited tree (for association). */
	static final int BIRD_ASSOC_DISTANCE = 2;

	/** Tiles from a tree's base tile at which the player is close enough to have baited it. */
	static final int BAIT_REACH = 2;

	/** Ticks a pending bait waits for the player to reach the tree before being dropped. */
	static final int PENDING_BAIT_TIMEOUT = 30;

	/** Ticks a registered catch waits for the bird's (death-animation-delayed) despawn. */
	static final int CATCH_DESPAWN_TIMEOUT = 10;

	/**
	 * Minimum Hunter XP gained in one drop to count as a catch. Peeking gives 125,
	 * a catch gives ~1,350 (scaled by damage), so anything above the peek amount is
	 * a catch. 200 sits safely between the two.
	 */
	static final int CATCH_XP_THRESHOLD = 200;

	/** The object name of the hiding bush ("Hide-in Bush" / "Exit Bush"). */
	static final String BUSH_NAME = "Bush";

	/** Menu verb to enter the bush. Menu splits as option="Hide-in", target="Bush". */
	static final String HIDE_OPTION = "Hide-in";

	/** Menu verb to leave the bush. */
	static final String EXIT_OPTION = "Exit";

	// ==========================================================================

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private StymphikeSceneOverlay overlay;

	@Inject
	private Notifier notifier;

	@Inject
	private StymphikeConfig config;

	/** Last seen Hunter XP, used to compute per-drop gains. -1 until initialised. */
	private int lastHunterXp = -1;

	/** Every stymphike tree currently rendered in the scene. */
	@Getter
	private final Set<GameObject> trees = new HashSet<>();

	/** World tiles of trees the player has baited. */
	@Getter
	private final Set<WorldPoint> baitedTiles = new HashSet<>();

	/** Stymphike birds currently in the scene. */
	private final Set<NPC> stymphikes = new HashSet<>();

	/** Bird index -> the baited tile it settled on, so we can clear it on despawn. */
	private final Map<Integer, WorldPoint> birdBaitTile = new HashMap<>();

	/** True after a catch until the caught bird's despawn is seen (or it times out). */
	private boolean pendingCatch;

	/** Ticks left for a pending catch to be matched to a bird despawn. */
	private int pendingCatchTicks;

	/** Whether the player is currently hidden in a bush. */
	@Getter
	private boolean hidden;

	/** Tile the player hid on; used to detect when they walk out of the bush. */
	private WorldPoint hidingTile;

	/** Player location on the previous tick, used to know when they have settled. */
	private WorldPoint lastLocation;

	/** Bush tile the player is walking to hide in; committed once they stand on it. */
	private WorldPoint pendingHideTile;

	/** Tree tile the player is walking to bait; committed on arrival, else times out. */
	private WorldPoint pendingBaitTile;

	/** Ticks left to fulfil a pending bait before it is abandoned. */
	private int pendingBaitTicks;

	@Provides
	StymphikeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(StymphikeConfig.class);
	}

	@Inject
	private StymphikeStatusOverlay statusOverlay;

	@Override
	protected void startUp()
	{
		overlayManager.add(overlay);
		overlayManager.add(statusOverlay);
		// Objects already loaded when the plugin is enabled don't fire spawn events,
		// so scan the current scene once on the client thread.
		clientThread.invokeLater(this::rebuildTrees);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(statusOverlay);
		trees.clear();
		baitedTiles.clear();
		stymphikes.clear();
		birdBaitTile.clear();
		hidden = false;
		hidingTile = null;
		pendingHideTile = null;
		pendingBaitTile = null;
		pendingCatch = false;
		lastHunterXp = -1;
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Scene is rebuilt on load; stored GameObjects become stale.
		if (event.getGameState() == GameState.LOADING)
		{
			trees.clear();
			stymphikes.clear();
			birdBaitTile.clear();
		}

		if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			setHidden(false);
			pendingHideTile = null;
			pendingBaitTile = null;
			// Re-seed the XP baseline on next login so we don't misread the login
			// StatChanged (or a different account's XP) as a catch.
			lastHunterXp = -1;
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// Drop a pending catch that never got matched to a despawn (safety net).
		if (pendingCatch && --pendingCatchTicks <= 0)
		{
			pendingCatch = false;
		}

		if (client.getLocalPlayer() == null)
		{
			return;
		}

		final WorldPoint now = client.getLocalPlayer().getWorldLocation();

		// Commit a pending hide only once the player is actually standing on the bush.
		if (pendingHideTile != null && pendingHideTile.equals(now))
		{
			hidingTile = now;
			pendingHideTile = null;
			setHidden(true);
		}

		// Once hidden, stepping off the bush tile means the player is exposed again.
		if (hidden && hidingTile != null && !hidingTile.equals(now))
		{
			setHidden(false);
		}

		// Commit a pending bait once the player has reached and stopped at the tree.
		if (pendingBaitTile != null)
		{
			if (now.equals(lastLocation) && now.distanceTo(pendingBaitTile) <= BAIT_REACH)
			{
				baitedTiles.add(pendingBaitTile);
				log.debug("Baited stymphike tree at {}", pendingBaitTile);
				pendingBaitTile = null;
			}
			else if (--pendingBaitTicks <= 0)
			{
				// Player never reached the tree (walked off / cancelled) — drop it.
				pendingBaitTile = null;
			}
		}

		lastLocation = now;

		// Associate any bird sitting on a baited tree with that tile, so we can clear
		// the mark (and tell catch from flee) when the bird later despawns — even if
		// it flies away first.
		if (!baitedTiles.isEmpty())
		{
			for (NPC bird : stymphikes)
			{
				final WorldPoint birdLoc = bird.getWorldLocation();
				if (birdLoc == null || birdBaitTile.containsKey(bird.getIndex()))
				{
					continue;
				}
				for (WorldPoint tile : baitedTiles)
				{
					if (birdLoc.distanceTo(tile) <= BIRD_ASSOC_DISTANCE)
					{
						birdBaitTile.put(bird.getIndex(), tile);
						break;
					}
				}
			}
		}
	}

	private void setHidden(boolean value)
	{
		if (hidden == value)
		{
			return;
		}
		hidden = value;
		if (!value)
		{
			hidingTile = null;
			if (config.notifyWhenExposed())
			{
				notifier.notify("You are no longer hidden — stymphikes will not approach.");
			}
		}
	}

	@Subscribe
	public void onGameObjectSpawned(GameObjectSpawned event)
	{
		final GameObject obj = event.getGameObject();
		if (isStymphikeTree(obj))
		{
			trees.add(obj);
		}
	}

	@Subscribe
	public void onGameObjectDespawned(GameObjectDespawned event)
	{
		trees.remove(event.getGameObject());
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		final MenuAction type = event.getMenuAction();
		final boolean isObjectAction =
			type == MenuAction.GAME_OBJECT_FIRST_OPTION
			|| type == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| type == MenuAction.GAME_OBJECT_THIRD_OPTION
			|| type == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| type == MenuAction.GAME_OBJECT_FIFTH_OPTION;

		if (!isObjectAction)
		{
			return;
		}

		final String option = event.getMenuOption();
		final String target = net.runelite.client.util.Text.removeTags(event.getMenuTarget());
		// For object menu actions this is the object ID.
		final int objectId = event.getId();

		final boolean isTree = TREE_IDS.contains(objectId) || TREE_NAME.equalsIgnoreCase(target);
		final boolean isBush = BUSH_IDS.contains(objectId) || BUSH_NAME.equalsIgnoreCase(target);

		// --- Bait a stymphike tree ---
		// Only a pending target here; it is committed once the player actually reaches
		// the tree (see onGameTick), not on the click while still walking over.
		if (config.highlightBaited() && isTree && BAIT_OPTION.equalsIgnoreCase(option))
		{
			pendingBaitTile = WorldPoint.fromScene(
				client,
				event.getParam0(),
				event.getParam1(),
				client.getPlane());
			pendingBaitTicks = PENDING_BAIT_TIMEOUT;
			return;
		}

		// --- Hide in / exit the bush ---
		if (isBush)
		{
			if (HIDE_OPTION.equalsIgnoreCase(option))
			{
				// Committed only once the player stands on the bush tile (onGameTick),
				// not on the click while still walking there.
				pendingHideTile = WorldPoint.fromScene(
					client,
					event.getParam0(),
					event.getParam1(),
					client.getPlane());
			}
			else if (EXIT_OPTION.equalsIgnoreCase(option))
			{
				pendingHideTile = null;
				setHidden(false);
			}
		}
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		if (isStymphikeNpc(event.getNpc()))
		{
			stymphikes.add(event.getNpc());
		}
	}

	@Subscribe
	public void onNpcDespawned(NpcDespawned event)
	{
		final NPC npc = event.getNpc();
		if (!stymphikes.remove(npc))
		{
			return;
		}

		// Was this bird sitting on one of our baited trees? If not, it just wandered
		// out of range — ignore it.
		final WorldPoint tile = birdBaitTile.remove(npc.getIndex());
		if (tile == null)
		{
			return;
		}

		// The bait is gone either way; clear the mark.
		baitedTiles.remove(tile);

		if (pendingCatch)
		{
			// This despawn is the caught bird's death (which lags the catch XP by a
			// few ticks of death animation) — not a flee.
			pendingCatch = false;
		}
		else if (config.notifyOnFlee())
		{
			announce("A stymphike fled with your bait!");
		}
	}

	/** Posts a game chat message and fires a notification (for when tabbed out). */
	private void announce(String message)
	{
		client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null);
		notifier.notify(message);
	}

	private boolean isStymphikeNpc(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}
		if (STYMPHIKE_NPC_IDS.contains(npc.getId()))
		{
			return true;
		}
		final String name = npc.getName();
		return name != null && name.toLowerCase().contains(STYMPHIKE_NPC);
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		if (event.getSkill() != Skill.HUNTER)
		{
			return;
		}

		final int xp = event.getXp();

		// First reading just seeds the baseline — don't treat login as a catch.
		if (lastHunterXp < 0)
		{
			lastHunterXp = xp;
			return;
		}

		final int gained = xp - lastHunterXp;
		lastHunterXp = xp;

		if (gained >= CATCH_XP_THRESHOLD)
		{
			registerCatch();
		}
	}

	/** Common handling for a confirmed catch. */
	private void registerCatch()
	{
		// Flag the catch so the caught bird's later despawn is read as a catch, not a
		// flee. The baited tile is cleared when the bird despawns (see onNpcDespawned).
		pendingCatch = true;
		pendingCatchTicks = CATCH_DESPAWN_TIMEOUT;

		if (config.notifyOnCatch())
		{
			announce("You caught a stymphike!");
		}
	}

	private boolean isStymphikeTree(GameObject obj)
	{
		if (obj == null)
		{
			return false;
		}
		if (TREE_IDS.contains(obj.getId()))
		{
			return true;
		}
		// Fall back to a name match in case the tree has an untracked variant ID.
		final var comp = client.getObjectDefinition(obj.getId());
		return comp != null && TREE_NAME.equalsIgnoreCase(comp.getName());
	}

	/** Scans the whole loaded scene for stymphike trees and repopulates the set. */
	private void rebuildTrees()
	{
		trees.clear();
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final Scene scene = client.getScene();
		final Tile[][][] tiles = scene.getTiles();
		final int z = client.getPlane();
		for (int x = 0; x < Constants.SCENE_SIZE; x++)
		{
			for (int y = 0; y < Constants.SCENE_SIZE; y++)
			{
				final Tile tile = tiles[z][x][y];
				if (tile == null)
				{
					continue;
				}
				for (GameObject go : tile.getGameObjects())
				{
					if (isStymphikeTree(go))
					{
						trees.add(go);
					}
				}
			}
		}
		log.debug("Stymphike scene scan found {} tree(s)", trees.size());
	}
}
