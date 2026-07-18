# Changelog

## 2026-07-03

- Hiding status and baited-tree markers are now read directly from the game's own
  state, so they always match reality — including after world hops, teleports, or
  bait taken while you were away.
- Reliable catch and flee detection: a catch fires when a stymphike is speared; a
  flee ("A stymphike fled with your bait!") fires when a bird takes your bait and
  escapes. A failed spear (under 10 damage) is no longer mistaken for a catch.
- All five stymphike tree variants are recognised.
- The stymphike bird is outlined when it appears (toggleable).
- Trees are marked with a circle at their centre, like Hunter trap markers.
- The status box only shows inside the hunting area, and only reminds you to hide
  while a baited tree is waiting.
- Catch and flee results are announced in the chatbox as well as via notification,
  and the "no longer hidden" reminder no longer interrupts a catch.

## 2026-07-02

- Initial release: catch notifications, flee alerts, baited-tree marking, and a
  hidden/exposed status indicator for stymphike hunting in Vampyrium.
