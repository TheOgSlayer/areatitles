# AreaTitles

Paper 1.21+ plugin: show a custom title/subtitle when a player enters a region.

1. Stand at one corner: `/areatitle pos1`; stand at the opposite corner: `/areatitle pos2`
2. `/areatitle create <name> [fullheight]`
3. `/areatitle settitle <name> <text>` and `/areatitle setsubtitle <name> <text>`
4. `/areatitle settimes <name> <fadeIn> <stay> <fadeOut>` (seconds)
5. `/areatitle preview <name>` to see it

Colours: `&` codes and hex (`&#00a2ff`). `%player%` inserts the player's name.
Permission: `areatitle.admin` (op by default). Overlapping regions: the smallest one wins.
