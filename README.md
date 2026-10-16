# OSRS Armory (RuneLite plugin)

Share your character on **[osrsarmory.com](https://osrsarmory.com)**: equipped gear and bonuses,
a picture of your character, skills, quests, achievement diaries and your character summary.
Anyone can then look you up by name.

## What it sends

To `https://osrsarmory.com` (configurable under the plugin's settings):

- your RuneScape name
- worn item IDs and names, summed equipment bonuses and weight (when your gear changes)
- a rendered picture of your character (after a gear change, once you stand still)
- skill levels and XP, quest states, quest points, achievement diary tiers, the number of completed
  combat tasks, and the values shown on the in-game Character Summary panel (at most once a minute,
  only when something changed)

Nothing else is read or sent. Disable the plugin to stop sharing.

## Development

Requires JDK 11+. Run `./gradlew run` (or `run-plugin.bat` on Windows) to start RuneLite in
developer mode with the plugin loaded.

The website and API live in [bkayosrs/osrs-armory](https://github.com/bkayosrs/osrs-armory).
