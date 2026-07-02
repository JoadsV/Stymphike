package com.stymphike;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.ProgressPieComponent;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;

public class StymphikeSceneOverlay extends Overlay
{
	private final Client client;
	private final StymphikePlugin plugin;
	private final StymphikeConfig config;
	private final ModelOutlineRenderer modelOutlineRenderer;

	@Inject
	private StymphikeSceneOverlay(Client client, StymphikePlugin plugin, StymphikeConfig config,
		ModelOutlineRenderer modelOutlineRenderer)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		this.modelOutlineRenderer = modelOutlineRenderer;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Baited trees are drawn from the tile you baited, so they show even when the
		// tree object is not tracked.
		if (config.highlightBaited())
		{
			for (WorldPoint wp : plugin.getBaitedTiles())
			{
				final GameObject tree = findTreeAt(wp);
				final LocalPoint lp = tree != null
					? tree.getLocalLocation()
					: LocalPoint.fromWorld(client, wp);
				drawCircle(graphics, lp, config.baitedColor());
			}
		}

		// All un-baited trees.
		if (config.highlightAllTrees())
		{
			for (GameObject tree : plugin.getTrees())
			{
				if (!plugin.getBaitedTiles().contains(tree.getWorldLocation()))
				{
					drawCircle(graphics, tree.getLocalLocation(), config.treeColor());
				}
			}
		}

		// The bird itself, so it's easy to spot and spear the moment it appears.
		if (config.highlightStymphike())
		{
			for (NPC stymphike : plugin.getStymphikes())
			{
				modelOutlineRenderer.drawOutline(stymphike, 2, config.stymphikeColor(), 4);
			}
		}

		return null;
	}

	private GameObject findTreeAt(WorldPoint wp)
	{
		for (GameObject tree : plugin.getTrees())
		{
			if (wp.equals(tree.getWorldLocation()))
			{
				return tree;
			}
		}
		return null;
	}

	/** A small filled pie marker at the tree's centre, like the Hunter plugin's traps. */
	private void drawCircle(Graphics2D graphics, LocalPoint lp, Color color)
	{
		if (lp == null)
		{
			return;
		}
		final Point canvas = Perspective.localToCanvas(client, lp, client.getPlane());
		if (canvas == null)
		{
			return;
		}
		final ProgressPieComponent pie = new ProgressPieComponent();
		pie.setPosition(canvas);
		pie.setProgress(1);
		pie.setFill(new Color(color.getRed(), color.getGreen(), color.getBlue(), 100));
		pie.setBorderColor(color);
		pie.render(graphics);
	}
}
