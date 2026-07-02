# Changelog

## 2026-07-03

- Hiding status and baited-tree markers are now read directly from the game's own
  state (varbits), so they always match reality — including after world hops,
  teleports, or bait taken while you were away.
- Catches are detected by the bird dying: a failed spear (under 10 damage) is now
  correctly reported as the stymphike fleeing, not as a catch.
- All five stymphike tree variants are recognised.
- The stymphike bird is outlined when it appears (toggleable).
- Trees are marked with a circle at their centre, like Hunter trap markers.
- The status box only shows inside the hunting area, and only reminds you to hide
  while a baited tree is waiting.
- Catch and flee results are announced in the chatbox as well as via notification.

## 2026-07-02

- Initial release: catch notifications, flee alerts, baited-tree marking, and a
  hidden/exposed status indicator for stymphike hunting in Vampyrium.
