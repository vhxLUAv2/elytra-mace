# Elytra Mace Pilot

Client-side Fabric mod for Minecraft Java 1.21.11.

## Controls

- Press R to toggle automation.
- Press V to cycle between Mace, Crystal, and Anchor modes.

## Required loadout

- Mace mode: Wear an Elytra, keep a mace in the hotbar, keep a chestplate in the inventory for the Elytra swap, and keep a Totem of Undying in the inventory for the fall fail-safe. Fireworks in the offhand are optional.
- Crystal mode: Keep Obsidian and End Crystals in the hotbar.
- Anchor mode: Keep Respawn Anchors and Glowstone in the hotbar. Anchor mode does not detonate anchors in the Nether.

## Combo sequence

The mod searches for the nearest visible non-teammate player within 48 blocks and smoothly aims at that player. While diving, it swaps the Elytra with an available chestplate when the target is within 5 blocks and the recorded fall height and downward velocity meet the configured thresholds. It selects the hotbar mace, waits until Elytra flight has ended, and attacks only when the target is under the crosshair and within 3 blocks of the crosshair hit position.

A successful hit is detected from the target's hurt state or health/absorption change. The mod then restores the Elytra, waits for a Wind Burst launch to reach its apex, requests a normal vanilla re-glide, and uses an offhand firework if one is available. If a dive misses and the player is falling more than 3 blocks, it attempts to move a Totem of Undying to the offhand.

Mace smash attacks in Java Edition require at least 1.5 blocks of fall height and cannot activate while the player is grounded, has Slow Falling, or is still Elytra-gliding. The smash can trigger regardless of the normal attack cooldown, so this mod does not delay the strike for a full cooldown.

Crystal and Anchor modes acquire the nearest visible non-teammate player within 7 blocks. Actual block and crystal interactions remain limited to 4.5 blocks. Crystal mode uses a nearby valid Obsidian/Bedrock base or attempts to place Obsidian beside the target, then places and attacks an End Crystal. Anchor mode places an Anchor beside a target, charges it once, and detonates it outside the Nether. Both modes use the vanilla client interaction manager and wait for the world state/entity update before advancing; they do not send speculative placement or detonation sequences.

Crystal and Anchor modes automatically move a Totem of Undying from the inventory into the offhand when the offhand is empty or no longer holds a Totem. Mace mode retains its existing emergency Totem behavior so an offhand Firework can still be used for re-gliding.

Before detonating, the modes use Minecraft's client-side received-damage estimate and skip the explosion when the estimate could be lethal to the local player. This is a conservative guard, not a server-side guarantee. The current version does not include local crystal deletion, packet spoofing, automatic shield-block placement, Surround, or anti-pearl hole filling.

## Build

Requires JDK 21. Run gradle build from this directory. The output JAR is in build/libs.

## Server compatibility

This mod uses vanilla client interaction, inventory, and glide actions. It does not spoof movement, conceal automation, or bypass anti-cheat. Use it only in single-player or on servers where this kind of automation is permitted. Servers may reject or restrict automated actions.