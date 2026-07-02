package com.stymphike;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(StymphikeConfig.GROUP)
public interface StymphikeConfig extends Config
{
	String GROUP = "stymphike";

	@ConfigSection(
		name = "Notifications",
		description = "When to notify you",
		position = 0
	)
	String notificationsSection = "notifications";

	@ConfigSection(
		name = "Tree marking",
		description = "How trees are highlighted",
		position = 1
	)
	String markingSection = "marking";

	@ConfigSection(
		name = "Hiding",
		description = "Bush hiding status and reminders",
		position = 2
	)
	String hidingSection = "hiding";

	// ---- Notifications ----

	@ConfigItem(
		keyName = "notifyOnCatch",
		name = "Notify on catch",
		description = "Fires a RuneLite notification each time you successfully catch a stymphike.",
		section = notificationsSection,
		position = 0
	)
	default boolean notifyOnCatch()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyOnFlee",
		name = "Notify when it flees",
		description = "Fires a notification when a stymphike flees with your bait (you dealt under 10 damage).",
		section = notificationsSection,
		position = 1
	)
	default boolean notifyOnFlee()
	{
		return true;
	}

	// ---- Marking ----

	@ConfigItem(
		keyName = "highlightBaited",
		name = "Highlight baited trees",
		description = "Outlines trees you have baited so you can find them again.",
		section = markingSection,
		position = 0
	)
	default boolean highlightBaited()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightAllTrees",
		name = "Highlight all trees",
		description = "Outlines every stymphike tree, baited or not.",
		section = markingSection,
		position = 1
	)
	default boolean highlightAllTrees()
	{
		return false;
	}

	@ConfigItem(
		keyName = "baitedColor",
		name = "Baited tree colour",
		description = "Outline colour for trees you have baited.",
		section = markingSection,
		position = 2
	)
	default Color baitedColor()
	{
		return new Color(0, 255, 0);
	}

	@ConfigItem(
		keyName = "treeColor",
		name = "Tree colour",
		description = "Outline colour for un-baited stymphike trees.",
		section = markingSection,
		position = 3
	)
	default Color treeColor()
	{
		return new Color(255, 255, 0);
	}

	@ConfigItem(
		keyName = "renderStyle",
		name = "Marker style",
		description = "How a tree is marked: a small centre circle (like a trap marker), the tile, a full outline, or the clickbox.",
		section = markingSection,
		position = 4
	)
	default RenderStyle renderStyle()
	{
		return RenderStyle.CIRCLE;
	}

	enum RenderStyle
	{
		CIRCLE,
		TILE,
		OUTLINE,
		CLICKBOX
	}

	// ---- Hiding ----

	@ConfigItem(
		keyName = "showHidingStatus",
		name = "Show hiding status",
		description = "Displays an on-screen box telling you whether you are hidden in the bush.",
		section = hidingSection,
		position = 0
	)
	default boolean showHidingStatus()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyWhenExposed",
		name = "Notify when exposed",
		description = "Fires a notification if you leave the bush (stymphikes will not approach while you are exposed).",
		section = hidingSection,
		position = 1
	)
	default boolean notifyWhenExposed()
	{
		return false;
	}

	@ConfigItem(
		keyName = "hiddenColor",
		name = "Hidden colour",
		description = "Status colour while you are hidden.",
		section = hidingSection,
		position = 2
	)
	default Color hiddenColor()
	{
		return new Color(0, 255, 0);
	}

	@ConfigItem(
		keyName = "exposedColor",
		name = "Exposed colour",
		description = "Status colour while you are exposed.",
		section = hidingSection,
		position = 3
	)
	default Color exposedColor()
	{
		return new Color(255, 0, 0);
	}
}
