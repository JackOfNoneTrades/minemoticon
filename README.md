# Minemoticon

![logo](images/logo_small.png)

Emoji and font support for `1.7.10 Minecraft`. Custom packs, server packs, animated custom emojis, picker, autocomplete, multiplayer sharing, and emoji fonts.
This mod bundles the [FreeType](https://freetype.org/) library for font rendering. The default emoji font uses [Twemoji](https://github.com/twitter/twemoji).

[![hub](images/badges/github.png)](https://github.com/JackOfNoneTrades/minemoticon/releases)
[![curse](images/badges/curse.png)](https://www.curseforge.com/minecraft/mc-mods/minemoticon)
[![67](images/badges/67.png)](https://67.fentanylsolutions.org/mod/minemoticon)
[![maven](images/badges/maven.png)](https://maven.fentanylsolutions.org/#/releases/org/fentanylsolutions/minemoticon)
![forge](images/badges/forge.png)
[![cord](images/badges/cord.png)](https://discord.gg/xAWCqGrguG)

<!--
[![modrinth](images/badges/modrinth.png)](https://modrinth.com/mod/minemoticon)
[![mcmodcn](images/badges/mcmodcn.png)](https://www.mcmod.cn/class/TODO.html)
-->

![picker](images/screenshots/main.png)

### Features

* Unicode and custom emoji support. Should work in all GUIs and on all in-game surfaces.
* Support for custom emoji sync on servers which also have the mod installed.
* Emoji picker and autocomplete.
* Custom font support. Load any ttf or otf file. Fallback support.

### Commands

| Command | Permission | Description |
|---|---|---|
| `/reload_emojis` | OP | Rescan server packs and resync all clients |
| `/clear_emojis [player]` | OP | Clear all persistent custom emojis, or only one player's |

## Dependencies

* [UniMixins](https://modrinth.com/mod/unimixins) [![curse](images/icons/curse.png)](https://www.curseforge.com/minecraft/mc-mods/unimixins)  [![modrinth](images/icons/modrinth.png)](https://modrinth.com/mod/unimixins/versions) [![git](images/icons/git.png)](https://github.com/LegacyModdingMC/UniMixins/releases)
* [GTNHLib](https://modrinth.com/mod/gtnhlib)   [![curse](images/icons/curse.png)](https://www.curseforge.com/minecraft/mc-mods/gtnhlib)  [![modrinth](images/icons/modrinth.png)](https://modrinth.com/mod/gtnhlib) [![git](images/icons/git.png)](https://github.com/GTNewHorizons/GTNHLib/releases)
* [FentLib](https://www.curseforge.com/minecraft/mc-mods/fentlib) [![curse](images/icons/curse.png)](https://www.curseforge.com/minecraft/mc-mods/fentlib) [![modrinth](images/icons/modrinth.png)](https://modrinth.com/mod/fentlib) [![67](images/icons/67.png)](https://67.fentanylsolutions.org/mod/fentlib) [![git](images/icons/git.png)](https://github.com/JackOfNoneTrades/FentLib)

## Building

`./gradlew build`

Native FreeType support is built automatically as part of `build`, leveraging the zig compiler.

If you cloned without submodules, initialize FreeType first:
```sh
git submodule update --init --checkout
```

FreeType and Zig versions are configured in `dependencies.gradle`.
After changing `freetypeVersion`, run:
```sh
./gradlew syncFreetypeSubmodule
```

To update the bundled emoji data and font:
```sh
./update-emoji-data.sh
```

## Credits

* [Twemoji](https://github.com/twitter/twemoji) for default emoji assets.
* [iamcal/emoji-data](https://github.com/iamcal/emoji-data) for emoji metadata.
* [FreeType](https://freetype.org/).
* [GT:NH buildscript](https://github.com/GTNewHorizons/ExampleMod1.7.10).

## License

`LGPLv3`.

## Buy me a coffee

* [ko-fi.com](https://ko-fi.com/jackisasubtlejoke)
* Monero: `893tQ56jWt7czBsqAGPq8J5BDnYVCg2tvKpvwTcMY1LS79iDabopdxoUzNLEZtRTH4ewAcKLJ4DM4V41fvrJGHgeKArxwmJ`

<br>

![license](images/license_small.png)
