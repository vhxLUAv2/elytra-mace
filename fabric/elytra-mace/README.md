# Elytra Mace Pilot

Client-side Fabric mod for Minecraft Java 1.21.11.

## Controls

- Press R to toggle automation.
- Press V to cycle between Mace, Crystal, and Anchor modes.

Crystal and Anchor are currently selectable only; their automation behavior is not implemented yet.

## Required loadout

- Wear an Elytra and begin gliding manually.
- Keep a mace in the hotbar.
- Keep a chestplate in the inventory for the Elytra swap.
- Keep a Totem of Undying in the inventory for the fall fail-safe.
- Fireworks in the offhand are optional and are used after a successful re-glide.

## Combo sequence

The mod searches for the nearest visible non-teammate player within 48 blocks and smoothly aims at that player. While diving, it swaps the Elytra with an available chestplate when the target is within 5 blocks and the recorded fall height and downward velocity meet the configured thresholds. It selects the hotbar mace, waits until Elytra flight has ended, and attacks only when the target is under the crosshair and within 3 blocks of the crosshair hit position.

A successful hit is detected from the target's hurt state or health/absorption change. The mod then restores the Elytra, waits for a Wind Burst launch to reach its apex, requests a normal vanilla re-glide, and uses an offhand firework if one is available. If a dive misses and the player is falling more than 3 blocks, it attempts to move a Totem of Undying to the offhand.

Mace smash attacks in Java Edition require at least 1.5 blocks of fall height and cannot activate while the player is grounded, has Slow Falling, or is still Elytra-gliding. The smash can trigger regardless of the normal attack cooldown, so this mod does not delay the strike for a full cooldown.

## Build

Requires JDK 21. Run gradle build from this directory. The output JAR is in build/libs.

## Server compatibility

This mod uses vanilla client interaction, inventory, and glide actions. It does not spoof movement, conceal automation, or bypass anti-cheat. Use it only in single-player or on servers where this kind of automation is permitted. Servers may reject or restrict automated actions.