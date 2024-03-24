<div align="center">

# Refmapper

Kotlin mixins support

Originally made for `LavaHack`

# Building

To build refmapper you need to have [JDK 17](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html) installed

</div>

1. Download refmapper source code by clicking `Code -> Download ZIP` on [the main page](https://github.com/kisman2000/refmapper)
2. Extract the source code somewhere and open cmd (on Windows) or Terminal
3. Execute `gradlew build` (if you're on Windows) or `chmod +x ./gradlew && ./gradlew build` (if you're on Linux) and wait until the building process finishes
4. After that you should have a file called `refmapper-VERSION.jar` inside `<refmapper src>/build/libs` folder
5. Use it anywhere you need

<div align="center">

# Downloading

You can download stable prebuilt JARs from [the releases page](https://github.com/kisman2000/refmapper/releases)

# Usage via CLI

To use CLI you need to have `tiny` mappings and `named` minecraft jar

</div>

### Getting `tiny` mappings

<div align="center">

## Fabric

To use [yarn2tiny](https://github.com/kisman2000/yarn2tiny) you need to have `yarn` mappings

</div>

1. Figure out yarn mappings version you need
2. Replace `YARN_VERSION` in link from next step to your yarn mappings version
3. Download archive from `https://maven.fabricmc.net/net/fabricmc/yarn/YARN_VERSION/yarn-YARN_VERSION-tiny.gz`
4. Extract the archive somewhere
5. Generate `tiny` mappings with [yarn2tiny](https://github.com/kisman2000/yarn2tiny)

<div align="center">

## Forge

To use [srg2tiny](https://github.com/kisman2000/srg2tiny) you need to have `srg`/`fields`/`methods` mappings

</div>

1. Figure out for what minecraft version you need mappings
2. Download MCP for your version from [here](http://www.modcoderpack.com/)
3. Extract `conf` folder from `mcpXXX.zip`
4. Use `conf/joined.srg` as `srg` mappings 
5. Use `conf/fields.csv` as `fields` mappings 
6. Use `conf/methods.csv` as `methods` mappings 
7. Generate `tiny` mappings with [srg2tiny](https://github.com/kisman2000/srg2tiny)

### Getting `named` minecraft jar

<div align="center">

## Fabric

</div>

1. Setup empty fabric mod project
2. Execute `gradlew getSources` (if you're on Windows) or `chmod +x ./gradlew && ./gradlew getSources` (if you're on Linux) and wait until the process finishes
3. Open each folder in `<project src>/.gradle/loom-cache/minecraftMaven/` until you no longer see more folders
4. Move `XXX.jar` file(not `XXX-sources.jar`) to somewhere

<div align="center">

## Forge

</div>

1. Setup empty forge mod project
2. Execute `gradlew build` (if you're on Windows) or `chmod +x ./gradlew && ./gradlew build` (if you're on Linux) and wait until the process finishes
3. Go to `<userfolder>/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/X.XX.X-XX.XX.X.XXXX_mapped_stable_XX-X.XX/`
4. Move `forge-X.XX.X-XX.XX.X.XXXX_mapped_stable_XX-X.XX.jar` to somewhere

### Making mod executable

1. Execute `java -jar refmapper-VERSION.jar "raw mod" "refmapped mod" "tiny mappings" "named minecraft jar"`
2. Move `"refmapped mod"` into `.minecraft/mods` folder
3. Enjoy!

<div align="center">

# Usage via gradle

To use refmapper you need to have project of fabric/forge mod

</div>

1. Make sure to check up if you have mixins written in kotlin or refmapper is useless
2. Download `refmapper-VERSION.jar` and put it into `<project src>`
3. Create `<project src>/refmapper` folder
4. Get `tiny` mappings, rename it to `tiny` and put into `<project src>/refmapper`

<div align="center">

### Fabric

</div>

5. Put the following code into `<project src>/build.gradle.kts`

```kotlin
task<Exec>("refmapper") {
    commandLine(
        "java",
        "-jar",
        "refmapper-VERSION.jar",
        "build/libs/MOD_NAME-MOD_VERSION.jar",
        "build/libs/MOD_NAME-MOD_VERSION-refmap.jar",
        "refmapper/tiny", 
        ".gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-project-root/MINECRAFT_VERSION1-net.fabricmc.yarn.MINECRAFT_VERSION2.YARN_MAPPINGS-YARN_VERSION/minecraft-merged-project-root-MINECRAFT_VERSION1-net.fabricmc.yarn.MINECRAFT_VERSION2.YARN_MAPPINGS-YARN_VERSION.jar"
    )
}
```

6. Replace `MOD_NAME` to `${base.archivesName.get()}`
7. Replace `MOD_VERSION` to `$version`

if you're using `build.gradle.kts` from kotlin mod template

8. Replace `MINECRAFT_VERSION1` to `${project.extra["minecraft_version"] as String}`
9. Replace `MINECRAFT_VERSION2` to `${(project.extra["minecraft_version"] as String).replace('.', '_')}`
10. Replace `YARN_MAPPINGS` to `${project.extra["yarn_mappings"] as String}`
11. Replace `YARN_VERSION` to `v2`

if you're using custom `build.gradle.kts`

8. Replace `MINECRAFT_VERSION1` to minecraft version in `X.XX.X` format 
9. Replace `MINECRAFT_VERSION2` to minecraft version in `X_XX_X` format 
10. Replace `YARN_MAPPINGS` to yarn mappings for your minecraft version 
11. Replace `YARN_VERSION` to yarn mappings version format(`v2` by default)

<div align="center">

### Forge(`WIP`)

</div>

5. Put the following code into `<project src>/build.gradle`

```groovy
tasks.register('refmapper', Exec) {
    commandLine(
            "java",
            "-jar",
            "refmapper-VERSION.jar",
            "build/libs/MOD_NAME-MOD_VERSION.jar",
            "build/libs/MOD_NAME-MOD_VERSION-refmap.jar",
            "refmapper/tiny",
            "USER_FOLDER/.gradle/caches/forge_gradle/minecraft_user_repo/net/minecraftforge/forge/MINECRAFT_VERSION-FORGE_VERSION_mapped_MAPPINGS_CHANNEL_MAPPINGS_VERSION/forge-MINECRAFT_VERSION-FORGE_VERSION_mapped_MAPPINGS_CHANNEL_MAPPINGS_VERSION.jar"
    )
}
```

6. Replace `MOD_NAME` to `${archivesBaseName}`
7. Replace `MOD_VERSION` to `$version`
8. Replace `USER_FOLDER` to path to your user folder(`/home/USERNAME`/`C:/Users/USERNAME` for example)

if you have following properties in `gradle.properties`

9. Replace `MINECRAFT_VERSION` to `$minecraftVersion`
10. Replace `FORGE_VERSION` to `$forgeVersion`
11. Replace `MAPPINGS_CHANNEL` to `$mappingsChannel`
12. Replace `MAPPINGS_VERSION` to `$mappingsVersion`

else

9. Figure out minecraft/forge/mappings versions and mappings channel from `minecraft { ... }` of `build.gradle`

<div align="center">

After each `gradlew build` execute `gradlew refmapper`

Built mod file is `MOD_NAME-MOD_VERSION-refmap.jar`

# We are

Handling `Inject`/`Redirect`/`ModifyArgs`/`Accessor`/`Invoker` hooks of original mixins

Handling `WrapWithCondition` hook of [MixinExtras](https://github.com/LlamaLad7/MixinExtras)

Handling `HEAD`/`TAIL`/`RETURN`/`INVOKE`/`FIELD`/`JUMP` injection points

Remapping mixins with `ClassTypeSignature` as target

Remapping shadow/overridden members

Remapping accesswinder instead of fabric loom(`WIP`)

Remapping lambdas

# Known issues

`Redirect`/`ModifyArgs` hook may not work for methods whose descriptor is different from `()V`

Remapped shadow field/method counters do not work correctly

Mixins with override methods are not currently supported(`WIP`)

Mixins from child packages are not currently supported

# Special thanks to
[**cattyn**](https://github.com/cattyngmd)/[**ferret**](https://github.com/cattyngmd/ferret) for example of this readme

</div>
