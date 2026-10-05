package com.storynpcs.domain.schematic;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Legacy id:data resolution for issue #149 — the table is the only translation
 * layer between MCEdit containers and modern block states, so every family
 * decode (orientation bits, halves, colors, axes) is pinned here. Wrong ids are
 * worse than unmapped ones: a bad entry silently places the wrong block.
 */
class LegacyBlockTableTest {

    @Test
    void commonStructuralIdsResolve() {
        assertThat(LegacyBlockTable.resolve(1, 0)).contains("minecraft:stone");
        assertThat(LegacyBlockTable.resolve(1, 1)).contains("minecraft:granite");
        assertThat(LegacyBlockTable.resolve(4, 0)).contains("minecraft:cobblestone");
        assertThat(LegacyBlockTable.resolve(45, 0)).contains("minecraft:bricks");
        assertThat(LegacyBlockTable.resolve(20, 0)).contains("minecraft:glass");
        assertThat(LegacyBlockTable.resolve(89, 0)).contains("minecraft:glowstone");
        assertThat(LegacyBlockTable.resolve(121, 0)).contains("minecraft:end_stone");
        assertThat(LegacyBlockTable.resolve(169, 0)).contains("minecraft:sea_lantern");
    }

    @Test
    void sandstoneStoneBrickAndQuartzFamiliesResolve() {
        // Regression: these arrays existed but were never wired — a legacy
        // build full of sandstone would silently drop to air.
        assertThat(LegacyBlockTable.resolve(24, 0)).contains("minecraft:sandstone");
        assertThat(LegacyBlockTable.resolve(24, 1)).contains("minecraft:chiseled_sandstone");
        assertThat(LegacyBlockTable.resolve(24, 2)).contains("minecraft:cut_sandstone");
        assertThat(LegacyBlockTable.resolve(98, 0)).contains("minecraft:stone_bricks");
        assertThat(LegacyBlockTable.resolve(98, 1)).contains("minecraft:mossy_stone_bricks");
        assertThat(LegacyBlockTable.resolve(98, 2)).contains("minecraft:cracked_stone_bricks");
        assertThat(LegacyBlockTable.resolve(155, 0)).contains("minecraft:quartz_block");
        assertThat(LegacyBlockTable.resolve(155, 3)).contains("minecraft:quartz_pillar[axis=x]");
        assertThat(LegacyBlockTable.resolve(155, 4)).contains("minecraft:quartz_pillar[axis=z]");
        assertThat(LegacyBlockTable.resolve(168, 1)).contains("minecraft:prismarine_bricks");
    }

    @Test
    void wrongIdNamesAreCorrect() {
        // Regression: 118 is cauldron — brewing stand is 117. A swapped id
        // would silently substitute blocks.
        assertThat(LegacyBlockTable.resolve(118, 0)).contains("minecraft:cauldron");
        assertThat(LegacyBlockTable.resolve(117, 0)).contains("minecraft:brewing_stand");
        // Regression: 1.13 renamed quartz_ore to nether_quartz_ore — a name the
        // modern parser rejects is worse than an unmapped id.
        assertThat(LegacyBlockTable.resolve(153, 0)).contains("minecraft:nether_quartz_ore");
    }

    @Test
    void doorsResolveBothHalves() {
        // Lower half: facing from low bits (horizontal index order).
        assertThat(LegacyBlockTable.resolve(64, 0))
                .contains("minecraft:oak_door[facing=south,half=lower]");
        assertThat(LegacyBlockTable.resolve(64, 2))
                .contains("minecraft:oak_door[facing=north,half=lower]");
        // Upper half: bit 3 — must not emit a second lower half.
        assertThat(LegacyBlockTable.resolve(64, 9))
                .contains("minecraft:oak_door[half=upper]");
        assertThat(LegacyBlockTable.resolve(193, 1))
                .contains("minecraft:spruce_door[facing=west,half=lower]");
        assertThat(LegacyBlockTable.resolve(193, 8))
                .contains("minecraft:spruce_door[half=upper]");
        assertThat(LegacyBlockTable.resolve(71, 0))
                .contains("minecraft:iron_door[facing=south,half=lower]");
    }

    @Test
    void torchesDecodeWallMounting() {
        assertThat(LegacyBlockTable.resolve(50, 5)).contains("minecraft:torch");
        assertThat(LegacyBlockTable.resolve(50, 1))
                .contains("minecraft:wall_torch[facing=east]");
        assertThat(LegacyBlockTable.resolve(50, 4))
                .contains("minecraft:wall_torch[facing=north]");
        assertThat(LegacyBlockTable.resolve(75, 2))
                .contains("minecraft:redstone_wall_torch[facing=west]");
        assertThat(LegacyBlockTable.resolve(76, 5))
                .contains("minecraft:redstone_torch[lit=true]");
    }

    @Test
    void railsDecodeShapeAndClampPoweredCorners() {
        assertThat(LegacyBlockTable.resolve(66, 0)).contains("minecraft:rail[shape=north_south]");
        assertThat(LegacyBlockTable.resolve(66, 3)).contains("minecraft:rail[shape=ascending_west]");
        assertThat(LegacyBlockTable.resolve(66, 7)).contains("minecraft:rail[shape=south_west]");
        // Powered rails have no corner shapes — clamp rather than emit invalid.
        assertThat(LegacyBlockTable.resolve(27, 7))
                .contains("minecraft:powered_rail[shape=north_south]");
        assertThat(LegacyBlockTable.resolve(28, 1))
                .contains("minecraft:detector_rail[shape=east_west]");
        assertThat(LegacyBlockTable.resolve(157, 0))
                .contains("minecraft:activator_rail[shape=north_south]");
    }

    @Test
    void facingBlocksDecode() {
        assertThat(LegacyBlockTable.resolve(54, 3)).contains("minecraft:chest[facing=south]");
        assertThat(LegacyBlockTable.resolve(61, 4)).contains("minecraft:furnace[facing=west]");
        assertThat(LegacyBlockTable.resolve(62, 5))
                .contains("minecraft:furnace[facing=east,lit=true]");
        assertThat(LegacyBlockTable.resolve(65, 2)).contains("minecraft:ladder[facing=north]");
        assertThat(LegacyBlockTable.resolve(65, 0)).contains("minecraft:ladder[facing=north]");
        assertThat(LegacyBlockTable.resolve(146, 5))
                .contains("minecraft:trapped_chest[facing=east]");
        assertThat(LegacyBlockTable.resolve(130, 4))
                .contains("minecraft:ender_chest[facing=west]");
    }

    @Test
    void sixDirectionBlocksDecode() {
        assertThat(LegacyBlockTable.resolve(23, 1)).contains("minecraft:dispenser[facing=up]");
        assertThat(LegacyBlockTable.resolve(33, 0)).contains("minecraft:piston[facing=down]");
        assertThat(LegacyBlockTable.resolve(29, 3)).contains("minecraft:sticky_piston[facing=south]");
        assertThat(LegacyBlockTable.resolve(158, 4)).contains("minecraft:dropper[facing=west]");
        assertThat(LegacyBlockTable.resolve(218, 2)).contains("minecraft:observer[facing=north]");
        assertThat(LegacyBlockTable.resolve(154, 0)).contains("minecraft:hopper[facing=down]");
        assertThat(LegacyBlockTable.resolve(154, 5)).contains("minecraft:hopper[facing=east]");
    }

    @Test
    void trapdoorsAndGatesDecode() {
        assertThat(LegacyBlockTable.resolve(96, 0))
                .contains("minecraft:oak_trapdoor[facing=south,half=bottom,open=false]");
        assertThat(LegacyBlockTable.resolve(96, 12)) // top + open
                .contains("minecraft:oak_trapdoor[facing=south,half=top,open=true]");
        assertThat(LegacyBlockTable.resolve(167, 1))
                .contains("minecraft:iron_trapdoor[facing=north,half=bottom,open=false]");
        assertThat(LegacyBlockTable.resolve(107, 2))
                .contains("minecraft:oak_fence_gate[facing=north,open=false]");
        assertThat(LegacyBlockTable.resolve(183, 5))
                .contains("minecraft:spruce_fence_gate[facing=west,open=true]");
        assertThat(LegacyBlockTable.resolve(120, 7))
                .contains("minecraft:end_portal_frame[eye=true,facing=east]");
    }

    @Test
    void axisBlocksDecode() {
        assertThat(LegacyBlockTable.resolve(17, 0)).contains("minecraft:oak_log[axis=y]");
        assertThat(LegacyBlockTable.resolve(17, 5)).contains("minecraft:spruce_log[axis=x]");
        assertThat(LegacyBlockTable.resolve(17, 10)).contains("minecraft:birch_log[axis=z]");
        assertThat(LegacyBlockTable.resolve(17, 12)).contains("minecraft:oak_wood[axis=y]");
        assertThat(LegacyBlockTable.resolve(162, 4)).contains("minecraft:acacia_log[axis=x]");
        assertThat(LegacyBlockTable.resolve(162, 1)).contains("minecraft:dark_oak_log[axis=y]");
        assertThat(LegacyBlockTable.resolve(170, 4)).contains("minecraft:hay_block[axis=x]");
        assertThat(LegacyBlockTable.resolve(216, 8)).contains("minecraft:bone_block[axis=z]");
        assertThat(LegacyBlockTable.resolve(202, 4)).contains("minecraft:purpur_pillar[axis=x]");
    }

    @Test
    void leavesMaskDecayAndPersistentFlags() {
        // High bits (persistent=4, decay=8) must not downgrade the species.
        assertThat(LegacyBlockTable.resolve(18, 13)) // spruce + 4 + 8
                .contains("minecraft:spruce_leaves");
        assertThat(LegacyBlockTable.resolve(18, 15)) // jungle + flags
                .contains("minecraft:jungle_leaves");
        assertThat(LegacyBlockTable.resolve(161, 13)) // dark_oak + flags
                .contains("minecraft:dark_oak_leaves");
    }

    @Test
    void slabsAndDoublesResolve() {
        assertThat(LegacyBlockTable.resolve(44, 2)).contains("minecraft:petrified_oak_slab[type=bottom]");
        assertThat(LegacyBlockTable.resolve(44, 12)) // brick slab, upper
                .contains("minecraft:brick_slab[type=top]");
        assertThat(LegacyBlockTable.resolve(125, 1)).contains("minecraft:spruce_slab[type=double]");
        // Seamless double slabs flatten to their full-block equivalent.
        assertThat(LegacyBlockTable.resolve(43, 8)).contains("minecraft:smooth_stone");
        assertThat(LegacyBlockTable.resolve(43, 15)).contains("minecraft:smooth_quartz");
        assertThat(LegacyBlockTable.resolve(181, 0)).contains("minecraft:red_sandstone_slab[type=double]");
        assertThat(LegacyBlockTable.resolve(182, 8)).contains("minecraft:red_sandstone_slab[type=top]");
    }

    @Test
    void miscStructuralIdsResolve() {
        assertThat(LegacyBlockTable.resolve(52, 0)).contains("minecraft:spawner");
        assertThat(LegacyBlockTable.resolve(97, 2)).contains("minecraft:infested_stone_bricks");
        assertThat(LegacyBlockTable.resolve(101, 0)).contains("minecraft:iron_bars");
        assertThat(LegacyBlockTable.resolve(113, 0)).contains("minecraft:nether_brick_fence");
        assertThat(LegacyBlockTable.resolve(139, 1)).contains("minecraft:mossy_cobblestone_wall");
        assertThat(LegacyBlockTable.resolve(26, 8)).contains("minecraft:white_bed[facing=south,part=head]");
        assertThat(LegacyBlockTable.resolve(175, 1)).contains("minecraft:lilac[half=lower]");
        assertThat(LegacyBlockTable.resolve(175, 9)).contains("minecraft:lilac[half=upper]");
    }

    @Test
    void fireBeaconAndPistonHeadResolve() {
        // 51 fire is faithful where it can survive (the first neighbor update
        // pops it to air where it cannot); 138 beacon is inert; 34 piston head
        // is an extension detail that must never persist — deliberate air like
        // 36/90/119/253.
        assertThat(LegacyBlockTable.resolve(51, 0)).contains("minecraft:fire");
        assertThat(LegacyBlockTable.resolve(138, 0)).contains("minecraft:beacon");
        assertThat(LegacyBlockTable.resolve(34, 0)).contains("minecraft:air");
    }

    @Test
    void unmappedIdsStayUnmapped() {
        // The contract: unknown ids return empty so the reader reports them —
        // never a guess.
        assertThat(LegacyBlockTable.resolve(9999, 0)).isEmpty();
        assertThat(LegacyBlockTable.resolve(254, 0)).isEmpty();
    }
}
