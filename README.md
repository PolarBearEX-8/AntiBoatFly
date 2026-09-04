# AntiBoatFly

AntiBoatFly is a Paper/Folia plugin that detects non-freefall and spoofed movement on boats, minecarts, and rideable animals.

## Detection layers

- Checks authoritative server-side vehicle motion with `VehicleMoveEvent`.
- Checks the rider's motion with `PlayerMoveEvent`.
- Compares rider and vehicle displacement to detect motion desynchronization.
- Uses PacketEvents to validate `VEHICLE_MOVE` position, speed, and `onGround` data before Bukkit applies it.
- Detects `PLAYER_POSITION` and `PLAYER_POSITION_AND_ROTATION` packets sent while controlling a vehicle.
- Ignores delayed `VEHICLE_MOVE` packets when no recent authoritative server mount exists.
- Supports boats, minecarts, horses, donkeys, mules, llamas, camels, pigs, striders, and other Bukkit `Vehicle` entities.
- Ignores supported vehicles touching solid blocks, liquids, or bubble columns.
- Allows configurable transition grace and legitimate animal jump grace.
- Adds violations for rising, hovering, upward acceleration, motion mismatches, and packet spoofing.
- Decays violations when movement returns to legitimate freefall.
- Cancels clear BoatFly packets immediately by default; animal mounts retain jump grace.
- Simulates the next airborne boat position from authoritative velocity, gravity, and drag.
- Cancels `VEHICLE_MOVE` immediately when it falls outside the simulation envelope.
- Enforces hard per-packet boat displacement limits from the first packet after mounting.
- Exempts server-confirmed water/surface contact from the upward hard limit so
  legitimate buoyancy and collision impulses do not create violations.
- Keeps a region-thread snapshot of nearby collision surfaces, allowing legitimate
  ground/water bounce packets to rebase the simulation before Bukkit reports contact.
- Covers the boat's reachable horizontal packet area so one-block step-down
  landings are recognised even when the destination block is not directly below.
- Scales simulation steps and hard limits to the elapsed packet time so batched
  Bedrock/Geyser vehicle movement is validated over the correct number of ticks.
- Sends an immediate authoritative vehicle correction, zeros the rejected boat motion,
  rolls it back to its last supported location, and then applies the configured action.
- Uses packet simulation as the only BoatFly authority to avoid duplicate VL.
- Rejects upward advantage while allowing faster legitimate downward corrections.

## Requirements

- Java 21
- Paper or Folia 1.21.4+
- PacketEvents 2.13.0+

## Build

```text
./gradlew build
```

The output is written to `build/libs/AntiBoatFly-1.0.0.jar`.

After every successful `build`, Gradle also copies the plugin JAR to
`C:/Leap/SV/plugins` using the `deployPlugin` task.

PacketEvents is a provided dependency and is not bundled into the plugin JAR.

## Target modes

- `target-mode: all` checks Java and Bedrock players.
- `target-mode: geyser` checks only players confirmed through the optional
  Geyser or Floodgate runtime API.

## Commands and permissions

- `/antiboatfly reload` reloads the configuration.
- `/antiboatfly status` shows the tracked vehicle count.
- `antiboatfly.admin` grants access to administration commands.
- `antiboatfly.alerts` receives detection alerts.
- `antiboatfly.bypass` bypasses all checks.

Detection is silent for the suspect. Only players with `antiboatfly.alerts`
and the server console receive violation details.
