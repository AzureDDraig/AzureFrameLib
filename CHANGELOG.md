# AzureFrameLib Changelog

## 1.1.0

**Faster**
- The Race Creator and other pick-lists no longer freeze when a modpack has thousands of models, textures or sounds.
- Mods can now ask AzureFrameLib "where is this model / animation / texture?" and get an instant answer from memory, instead of searching the hard drive every frame. This fixes big frame drops when rendering transformed players.
- Asset scanning now happens in the background when the game starts, and files that haven't changed since last time are not re-read.

**More reliable**
- File names with capital letters, spaces or odd characters (like "Anime Girl.png" or "MyDragon.geo.json") now load. They are renamed to a safe lowercase name, and the log tells you once what they were renamed to.
- If two files end up with the same safe name, the first one is kept and the log names both files.
- One broken model or animation file can no longer stop all the other models from loading. Broken files get a plain warning in the log and are skipped. Files with small mistakes (like a hidden byte-order mark or a trailing comma) are cleaned up automatically.
- Lists of discovered models/textures/animations are now safe to read from any thread and can't crash during F3+T.
- Mods can now be told when the asset list changes (startup, F3+T, new bundles added).

**Animations**
- Fixed animations not playing in many mods: models now always connect their bones to the animation system, even when the mod skips that step itself.
- Animations can be asked for by their short name ("idle") as well as the full name ("animation.dragon.idle").
- New `/azureframelib animations [filter]` command shows which animation file each model uses and which animation names are in it.

**Hitboxes**
- Bones named "hitbox" (configurable) are no longer drawn on models. Mods can read the hitbox size in blocks.

**New commands (client)**
- `/azureframelib stats` - how many assets were found.
- `/azureframelib selftest` - quick speed check.
- `/azureframelib reload` - same as F3+T.

**New config options** (`config/AzureFrameLib/azureframelib.json`)
- `hitboxBoneNames`, `hideHitboxBones`, `backgroundIndexing`, `addShortAnimationNames`, `fixAnimationBones`, `logAnimationLinks`.

No existing features were removed; mods built for 1.0.0 keep working.
