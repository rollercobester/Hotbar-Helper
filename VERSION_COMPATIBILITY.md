# Hotbar Helper – Version Compatibility Analysis

**Current target:** Minecraft 1.21.x (Fabric)

**Declared support:** All 1.21 versions (1.21, 1.21.1 … 1.21.11) via `fabric.mod.json`:
- `"minecraft": ">=1.21 <1.22"`
- `"fabricloader": ">=0.15.11"` (covers 1.21.0 through 1.21.11)

This document summarizes version-specific APIs used by the mod and estimates how far back support can go without major refactoring.

---

## Summary

| Version Range | Feasibility | Effort |
|---------------|-------------|--------|
| **1.21.x** | ✅ Works | None – current target |
| **1.20.6 – 1.21.0** | ⚠️ Likely | Low – API tweaks |
| **1.20.5** | ⚠️ Moderate | Medium – Data components, packet changes |
| **1.20.1 – 1.20.4** | 🔶 Significant refactor | High |
| **1.19.x and earlier** | 🔴 Major rewrite | Very high |

**Practical recommendation:** Backporting to **1.20.6** or **1.21.0** is plausible with small changes. **1.20.5** requires more work. Earlier versions would need a substantial refactor.

---

## Version-Specific APIs

### 1. `Identifier.fromNamespaceAndPath()` (HotbarToolSwitch)
- **Current:** `Identifier.fromNamespaceAndPath("hotbar_helper", "silk_touch_blocks")`
- **1.21+:** New API.
- **Pre-1.21:** Use `new Identifier("hotbar_helper", "silk_touch_blocks")`.

### 2. DataComponents / Food detection (HotbarHelperEvents)
- **Current:** `stack.getComponents().has(DataComponents.FOOD)`
- **1.20.5+:** Data component system.
- **Pre-1.20.5:** Use `item instanceof FoodProvider` or `item.getFoodComponent() != null` (exact API varies by version).

### 3. Registry API for enchantments (HotbarToolSwitch)
- **Current:** `level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)` and `enchantments.getOrThrow(Enchantments.X)`
- **1.21 / 1.20.6:** Holder-based registry lookup.
- **Pre-1.20.6:** Use `EnchantmentHelper.getEnchantmentLevel(Enchantments.XXX, stack)` directly (no `Holder`).

### 4. ServerboundContainerClickPacket (HotbarRefillClient)
- **Current:** Uses `menu.getStateId()` and `HashedStack.EMPTY`.
- **1.21+:** Container state sync (stateId) and HashedStack-based packets.
- **Pre-1.21:** Different constructor: no stateId, uses `ItemStack.EMPTY` instead of HashedStack, different slot encoding.

### 5. Fabric API Tags – ConventionalBlockTags v2
- **Current:** `net.fabricmc.fabric.api.tag.convention.v2.ConventionalBlockTags`
- **1.21+:** v2 package.
- **1.19 – 1.20:** v1 package: `net.fabricmc.fabric.api.tag.convention.v1.ConventionalBlockTags` (same tag names, different package).

### 6. Fabric events (BlockEvents, ItemEvents, AttackBlockCallback)
- **BlockEvents.USE_ITEM_ON** and **ItemEvents.USE** – available from Fabric API 0.82+ (1.20).
- **AttackBlockCallback** – available from Fabric API 0.82+ (1.20).
- **ClientTickEvents** – long-standing, widely supported.

### 7. Player.drop() mixin (PlayerDropMixin)
- **Current:** `drop(Lnet/minecraft/world/item/ItemStack;Z)Z` (Mojang mappings).
- Method exists in 1.19+ with similar semantics; older versions may use `dropItem` or different descriptors.
- Mixin `@Inject(method = "drop(...)")` would need mapping adjustments per MC version.

### 8. ItemStack.isSameItem()
- **Current:** `ItemStack.isSameItem(a, b)`
- **1.17+:** Exists (previously `areItemsEqual` in older mappings).
- Widely available.

### 9. Java version
- **Current build:** Java 21.
- **1.21 / 1.20.5+:** Java 21.
- **1.20.1 – 1.20.4:** Java 17.
- **1.19.x:** Java 17.
- Supporting pre-1.20.5 requires switching build to Java 17 (or lower) and adjusting Gradle.

### 10. Loom – splitEnvironmentSourceSets()
- Requires Loom 1.8+ (not tied to Minecraft version).
- Can be removed if a single source set is preferred for older setups.

---

## Backport Checklist

### 1.20.6 – 1.21.0
- [ ] Swap `Identifier.fromNamespaceAndPath` for `new Identifier(ns, path)` if needed.
- [ ] Confirm `ConventionalBlockTags` package (v1 vs v2) for the target Fabric API.
- [ ] Validate `ServerboundContainerClickPacket` and container sync API for exact version.
- [ ] Test Player.drop mixin with correct method mapping.

### 1.20.5
- [ ] All items for 1.20.6.
- [ ] Replace `DataComponents.FOOD` with pre–data-components food check.
- [ ] Adapt `ServerboundContainerClickPacket` to pre–1.21 format (no stateId, no HashedStack).
- [ ] Adjust EnchantmentHelper usage if Holder-based API is not available.
- [ ] Set Java target to 17 if supporting 1.20.5 on older loaders.

### 1.20.1 – 1.20.4
- [ ] All items for 1.20.5.
- [ ] Use `ConventionalBlockTags` v1.
- [ ] Use old enchantment API (no Holder).
- [ ] Use old container click packet format.
- [ ] Ensure Loom and Fabric Loader versions support 1.20.x.
- [ ] Java 17.

### 1.19.x
- [ ] All items for 1.20.1.
- [ ] Fabric API modules and event names may differ.
- [ ] Player drop mixin method mapping (Yarn/intermediary) for 1.19.
- [ ] Confirm Fabric Tag conventions for 1.19.

---

## Multi-version approach

To support multiple Minecraft versions without too much duplication:

1. **Gradle:** Use `loom.gameVersion()` or a version matrix to build per MC version.
2. **Source:** Separate version-specific modules or use `compileOnly`/`runtimeOnly` to switch implementations.
3. **Mappings:** Each MC version needs its own mappings (Mojang/Yarn).

Example layout:
```
src/main/java/          # Shared code
src/client/java/        # Shared client code
src/mainCompat/java/    # Version-specific (1.20.5 vs 1.21)
```

Or use a single source tree with conditional compilation and small compatibility helpers.
