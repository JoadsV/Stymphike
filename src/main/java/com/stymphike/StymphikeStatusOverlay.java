package com.stymphike;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

public class StymphikeStatusOverlay extends OverlayPanel
{
	private final StymphikePlugin plugin;
	private final StymphikeConfig config;

	@Inject
	private StymphikeStatusOverlay(StymphikePlugin plugin, StymphikeConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showHidingStatus())
		{
			return null;
		}

		// Only relevant while stymphike trees are loaded in the scene (i.e. you are
		// in the hunting area) — hide the box everywhere else.
		if (plugin.getTrees().isEmpty())
		{
			return null;
		}

		final boolean hidden = plugin.isHidden();
		final boolean hasBait = !plugin.getBaitedTiles().isEmpty();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Stymphike")
			.build());

		// Exposed only matters while a baited tree is waiting; otherwise you're idle.
		final String statusText;
		final Color statusColor;
		if (hidden)
		{
			statusText = "Hidden";
			statusColor = config.hiddenColor();
		}
		else if (hasBait)
		{
			statusText = "Exposed";
			statusColor = config.exposedColor();
		}
		else
		{
			statusText = "Idle";
			statusColor = Color.LIGHT_GRAY;
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Status:")
			.right(statusText)
			.rightColor(statusColor)
			.build());

		// Only nag about hiding when there is a baited tree to wait for.
		if (!hidden && hasBait)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.right("Hide in a bush!")
				.rightColor(config.exposedColor())
				.build());
		}

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Baited trees:")
			.right(Integer.toString(plugin.getBaitedTiles().size()))
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Trees tracked:")
			.right(Integer.toString(plugin.getTrees().size()))
			.build());

		return super.render(graphics);
	}
}
