package com.stymphike;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.ProgressPieComponent;

public class StymphikeSceneOverlay extends Overlay
{
	private final Client client;
	private final StymphikePlugin plugin;
	private final StymphikeConfig config;

	@Inject
	private StymphikeSceneOverlay(Client client, StymphikePlugin plugin, StymphikeConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		// Baited trees are drawn from the tile you baited, so they show even when the
		// tree object is not tracked (e.g. it morphs to another ID once baited).
		if (config.highlightBaited())
		{
			for (WorldPoint wp : plugin.getBaitedTiles())
			{
				final GameObject tree = findTreeAt(wp);
				if (tree != null)
				{
					drawTree(graphics, tree, config.baitedColor());
				}
				else
				{
					drawTile(graphics, wp, config.baitedColor());
				}
			}
		}

		// All un-baited trees.
		if (config.highlightAllTrees())
		{
			for (GameObject tree : plugin.getTrees())
			{
				if (!plugin.getBaitedTiles().contains(tree.getWorldLocation()))
				{
					drawTree(graphics, tree, config.treeColor());
				}
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

	/** Draws a baited tile that has no tracked GameObject (rare fallback). */
	private void drawTile(Graphics2D graphics, WorldPoint wp, Color color)
	{
		final LocalPoint lp = LocalPoint.fromWorld(client, wp);
		if (lp == null)
		{
			return;
		}
		if (config.renderStyle() == StymphikeConfig.RenderStyle.CIRCLE)
		{
			drawCircle(graphics, lp, color);
		}
		else
		{
			drawTilePoly(graphics, lp, color);
		}
	}

	private void drawTree(Graphics2D graphics, GameObject tree, Color color)
	{
		switch (config.renderStyle())
		{
			case OUTLINE:
			{
				final Shape hull = tree.getConvexHull();
				if (hull != null)
				{
					OverlayUtil.renderPolygon(graphics, hull, color);
				}
				break;
			}
			case CLICKBOX:
			{
				final Shape clickbox = tree.getClickbox();
				if (clickbox != null)
				{
					final Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 30);
					OverlayUtil.renderPolygon(graphics, clickbox, color, fill, new BasicStroke(2));
				}
				break;
			}
			case TILE:
			{
				drawTilePoly(graphics, tree.getLocalLocation(), color);
				break;
			}
			case CIRCLE:
			default:
			{
				drawCircle(graphics, tree.getLocalLocation(), color);
				break;
			}
		}
	}

	/** A small filled pie marker at the tile centre, like the Hunter plugin's traps. */
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

	private void drawTilePoly(Graphics2D graphics, LocalPoint lp, Color color)
	{
		if (lp == null)
		{
			return;
		}
		final Polygon poly = Perspective.getCanvasTilePoly(client, lp);
		if (poly != null)
		{
			final Color fill = new Color(color.getRed(), color.getGreen(), color.getBlue(), 30);
			OverlayUtil.renderPolygon(graphics, poly, color, fill, new BasicStroke(2));
		}
	}
}
