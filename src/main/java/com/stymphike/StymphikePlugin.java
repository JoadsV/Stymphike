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
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameObjectDespawned;
import net.runelite.api.events.GameObjectSpawned;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.GameState;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcDespawned;
import net.runelite.api.events.NpcSpawned;
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
	// IDs below were captured in-game via the dev tools; no gameval constants
	// exist yet for this content. Update here if a game update changes them.
	// ==========================================================================

	/** The object name shown on the tree's right-click menu ("Check Stymphike tree"). */
	static final String TREE_NAME = "Stymphike tree";

	/**
	 * Object IDs of the stymphike tree variants (one per tree in the area). The
	 * trees are varbit-driven multilocs: baiting keeps the ID but swaps the active
	 * form, which is how the menu changes between "Bait" and "Check".
	 */
	static final Set<Integer> TREE_IDS = Set.of(40782, 40783, 40784, 40785, 40786);

	/** Menu action present on a tree's active form only while it is baited. */
	static final String BAITED_ACTION = "Check";

	/** NPC IDs of the stymphike bird. */
	static final Set<Integer> STYMPHIKE_NPC_IDS = Set.of(15747);

	/** NPC name of the stymphike bird (fallback match, case-insensitive substring). */
	static final String STYMPHIKE_NPC = "stymphike";

	/**
	 * Varbits observed (via the dev tools var inspector) to flip 0->1 on hiding in
	 * a bush and back on exit. The tree varbits form a per-object block (15440,
	 * 15442, ...), so 15444 may be specific to one bush while 16289 is the global
	 * hidden flag — either being set means the player is hidden.
	 */
	static final int HIDDEN_VARBIT = 15444;

	/** Companion hidden varbit; see HIDDEN_VARBIT. */
	static final int HIDDEN_VARBIT_ALT = 16289;

	/** Tiles within which a bird is considered "at" a baited tree (for association). */
	static final int BIRD_ASSOC_DISTANCE = 2;

	/** Ticks a bird's death waits to be matched to its (animation-delayed) despawn. */
	static final int CATCH_DESPAWN_TIMEOUT = 10;

	/**
	 * If the bird despawns while its baited tile is further than this from the
	 * player, it merely left our render distance (we walked away) — not an outcome.
	 */
	static final int DESPAWN_VIEW_DISTANCE = 15;

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
	private StymphikeStatusOverlay statusOverlay;

	@Inject
	private Notifier notifier;

	@Inject
	private StymphikeConfig config;

	/** Every stymphike tree currently rendered in the scene. */
	@Getter
	private final Set<GameObject> trees = new HashSet<>();

	/**
	 * Centre tiles of trees that are currently baited. Recomputed every tick from
	 * each tree's varbit-driven active form, so it is always the game's own state.
	 */
	@Getter
	private final Set<WorldPoint> baitedTiles = new HashSet<>();

	/** Stymphike birds currently in the scene. */
	@Getter
	private final Set<NPC> stymphikes = new HashSet<>();

	/** Bird index -> the baited tile it settled on, so we can clear it on despawn. */
	private final Map<Integer, WorldPoint> birdBaitTile = new HashMap<>();

	/** True after the bird dies until its despawn is seen (or it times out). */
	private boolean pendingCatch;

	/** Ticks left for a pending catch to be matched to a bird despawn. */
	private int pendingCatchTicks;

	/** Whether the player is currently hidden in a bush (read from HIDDEN_VARBIT). */
	@Getter
	private boolean hidden;

	@Provides
	StymphikeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(StymphikeConfig.class);
	}

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
		pendingCatch = false;
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
			birdBaitTile.clear();
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

		// The game tracks both states itself — read them rather than inferring from
		// clicks and movement.
		setHidden(client.getVarbitValue(HIDDEN_VARBIT) == 1
			|| client.getVarbitValue(HIDDEN_VARBIT_ALT) == 1);
		refreshBaitedTiles();

		// Associate any bird sitting on a baited tree with that tile, so we can tell
		// catch from flee when the bird later despawns — even if it flies away first.
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

	/** Rebuilds the baited set from each tracked tree's current varbit-driven form. */
	private void refreshBaitedTiles()
	{
		baitedTiles.clear();
		for (GameObject tree : trees)
		{
			if (isTreeBaited(tree))
			{
				baitedTiles.add(tree.getWorldLocation());
			}
		}
	}

	/** A tree is baited when its active form carries the "Check" menu action. */
	private boolean isTreeBaited(GameObject tree)
	{
		ObjectComposition comp = client.getObjectDefinition(tree.getId());
		if (comp == null)
		{
			return false;
		}
		if (comp.getImpostorIds() != null)
		{
			comp = comp.getImpostor();
			if (comp == null)
			{
				return false;
			}
		}
		final String[] actions = comp.getActions();
		if (actions == null)
		{
			return false;
		}
		for (String action : actions)
		{
			if (BAITED_ACTION.equalsIgnoreCase(action))
			{
				return true;
			}
		}
		return false;
	}

	private void setHidden(boolean value)
	{
		if (hidden == value)
		{
			return;
		}
		hidden = value;
		if (!value && config.notifyWhenExposed())
		{
			notifier.notify("You are no longer hidden — stymphikes will not approach.");
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
	public void onNpcSpawned(NpcSpawned event)
	{
		if (isStymphikeNpc(event.getNpc()))
		{
			stymphikes.add(event.getNpc());
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		// The bird dying (rather than flying off) is what distinguishes a catch from
		// a flee — a failed spear also pays XP, so XP alone can't tell them apart.
		if (stymphikes.contains(event.getActor()))
		{
			pendingCatch = true;
			pendingCatchTicks = CATCH_DESPAWN_TIMEOUT;
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

		// The bird only left our render distance because the player walked away —
		// the outcome is unknown, so say nothing.
		if (client.getLocalPlayer() == null
			|| client.getLocalPlayer().getWorldLocation().distanceTo(tile) > DESPAWN_VIEW_DISTANCE)
		{
			return;
		}

		if (pendingCatch || npc.isDead())
		{
			// The bird died: a successful catch (the despawn lags the death by a few
			// ticks of death animation).
			pendingCatch = false;

			if (config.notifyOnCatch())
			{
				announce("You caught a stymphike!");
			}
		}
		else if (config.notifyOnFlee())
		{
			// Despawned alive: it flew off with the bait.
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
		final ObjectComposition comp = client.getObjectDefinition(obj.getId());
		return comp != null && TREE_NAME.equalsIgnoreCase(comp.getName());
	}

	/** Scans the whole loaded scene for stymphike trees to repopulate the set. */
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
