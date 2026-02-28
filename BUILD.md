# Build per Version

This mod uses **build-per-version**: each JAR targets a specific Minecraft version. Build once per supported version and release separate JARs.

## Quick Start

1. Edit `gradle.properties`:
   - `minecraft_version` – target MC version to build against
   - `minecraft_version_range` – human-readable range for JAR name (e.g. `1.21-1.21.1`, `1.21.2`)
   - `minecraft_depends` – version range in `fabric.mod.json` (e.g. `>=1.21 <1.21.2`)
   - `fabric_api_version` – match the Fabric API for that MC version ([fabricmc.net/develop](https://fabricmc.net/develop))

2. Run `./gradlew genSources` (first time or when changing MC version)

3. Run `./gradlew build`

4. Output: `build/libs/hotbar-helper-1.0.0-1.21-1.21.1.jar` (based on `minecraft_version_range`)

## Building for Multiple Versions

Example for 1.21 and 1.21.2:

**Build 1 – 1.21/1.21.1**
```properties
# gradle.properties
minecraft_version=1.21
minecraft_version_range=1.21-1.21.1
minecraft_depends=>=1.21 <1.21.2
fabric_api_version=0.102.0+1.21
```
→ `./gradlew genSources build` → `hotbar-helper-1.0.0-1.21-1.21.1.jar`

**Build 2 – 1.21.2**
```properties
minecraft_version=1.21.2
minecraft_version_range=1.21.2
minecraft_depends=>=1.21.2 <1.21.3
fabric_api_version=0.xx.x+1.21.2
```
→ `./gradlew genSources build` → `hotbar-helper-1.0.0-1.21.2.jar`

Repeat for each version. The JAR name includes the MC version so you can keep multiple builds side by side.

## Version-Specific Changes

If a version needs code changes (e.g. API differences), update the source before building for that version. The codebase is currently tailored for 1.21–1.21.1; adapt as needed for 1.21.2+.
