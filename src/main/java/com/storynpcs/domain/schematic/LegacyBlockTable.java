package com.storynpcs.domain.schematic;

import java.util.Map;
import java.util.Optional;

/**
 * Numeric legacy-id resolution for the MCEdit {@code .schematic} format
 * (issue #149). Pre-1.13 worlds encode blocks as {@code id:data} pairs; this
 * table maps the structural set the bundled builds use to modern block-state
 * strings. Orientation-bearing blocks (stairs, slabs, logs) resolve their
 * facing/type bits; colored families resolve the data nibble as the color.
 *
 * <p>Coverage is deliberately bounded — ids outside the table resolve empty
 * and the reader records them as diagnostics rather than guessing wrong.
 */
final class LegacyBlockTable {

    private static final Map<Integer, String> ID_ONLY = new java.util.HashMap<>();
    private static final Map<Long, String> ID_DATA = new java.util.HashMap<>();

    private static final String[] COLORS = {
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"
    };
    private static final String[] WOODS = {
            "oak", "spruce", "birch", "jungle", "acacia", "dark_oak"
    };
    private static final String[] STAIR_FACING = { "east", "west", "south", "north" };
    /** Legacy horizontal facing index (data&3) — EnumFacing.Plane.HORIZONTAL order. */
    private static final String[] HORIZONTAL4 = { "south", "west", "north", "east" };
    /** Legacy 4-direction attachment (ladder/chest/furnace): 2=N,3=S,4=W,5=E. */
    private static final String[] WALL4 = { null, null, "north", "south", "west", "east" };
    /** Legacy 6-direction facing (pistons/droppers/observers). */
    private static final String[] DIR6 = { "down", "up", "north", "south", "west", "east" };
    /** Legacy rail shape index → modern rail shape. */
    private static final String[] RAIL_SHAPES = {
            "north_south", "east_west", "ascending_east", "ascending_west",
            "ascending_north", "ascending_south", "south_east", "south_west",
            "north_west", "north_east"
    };
    private static final String[] SLAB_TYPES = {
            "smooth_stone", "sandstone", "oak", "cobblestone", "brick", "stone_brick",
            "nether_brick", "quartz"
    };
    /** Seamless double slabs (data bit 3) flatten to the corresponding full block. */
    private static final String[] SEAMLESS_BLOCKS = {
            "smooth_stone", "smooth_sandstone", "oak_planks", "cobblestone",
            "bricks", "stone_bricks", "nether_bricks", "smooth_quartz"
    };
    private static final String[] INFESTED = {
            "infested_stone", "infested_cobblestone", "infested_stone_bricks",
            "infested_mossy_stone_bricks", "infested_cracked_stone_bricks",
            "infested_chiseled_stone_bricks"
    };

    static {
        // Air + terrain.
        id(0, "minecraft:air");
        id(1, "minecraft:stone");
        idData(1, 1, "minecraft:granite");
        idData(1, 2, "minecraft:polished_granite");
        idData(1, 3, "minecraft:diorite");
        idData(1, 4, "minecraft:polished_diorite");
        idData(1, 5, "minecraft:andesite");
        idData(1, 6, "minecraft:polished_andesite");
        id(2, "minecraft:grass_block");
        id(3, "minecraft:dirt");
        idData(3, 1, "minecraft:coarse_dirt");
        idData(3, 2, "minecraft:podzol");
        id(4, "minecraft:cobblestone");
        id(7, "minecraft:bedrock");
        id(8, "minecraft:water");
        id(9, "minecraft:water");
        id(10, "minecraft:lava");
        id(11, "minecraft:lava");
        id(12, "minecraft:sand");
        idData(12, 1, "minecraft:red_sand");
        id(13, "minecraft:gravel");
        id(14, "minecraft:gold_ore");
        id(15, "minecraft:iron_ore");
        id(16, "minecraft:coal_ore");
        id(19, "minecraft:sponge");
        id(20, "minecraft:glass");
        id(21, "minecraft:lapis_ore");
        id(22, "minecraft:lapis_block");
        id(23, "minecraft:dispenser"); // facing decodes below
        id(24, "minecraft:sandstone");
        idData(24, 1, "minecraft:chiseled_sandstone");
        idData(24, 2, "minecraft:cut_sandstone");
        idData(24, 3, "minecraft:smooth_sandstone");
        id(25, "minecraft:note_block");
        id(26, "minecraft:white_bed[part=foot]"); // facing/part decode below
        id(27, "minecraft:powered_rail");
        id(28, "minecraft:detector_rail");
        id(29, "minecraft:sticky_piston");
        id(30, "minecraft:cobweb");
        id(31, "minecraft:short_grass");
        idData(31, 0, "minecraft:dead_bush");
        idData(31, 2, "minecraft:fern");
        id(32, "minecraft:dead_bush");
        id(33, "minecraft:piston");
        id(37, "minecraft:dandelion");
        id(38, "minecraft:poppy");
        idData(38, 1, "minecraft:blue_orchid");
        idData(38, 2, "minecraft:allium");
        idData(38, 3, "minecraft:azure_bluet");
        idData(38, 4, "minecraft:red_tulip");
        idData(38, 5, "minecraft:orange_tulip");
        idData(38, 6, "minecraft:white_tulip");
        idData(38, 7, "minecraft:pink_tulip");
        idData(38, 8, "minecraft:oxeye_daisy");
        id(39, "minecraft:brown_mushroom");
        id(40, "minecraft:red_mushroom");
        id(41, "minecraft:gold_block");
        id(42, "minecraft:iron_block");
        id(45, "minecraft:bricks");
        id(46, "minecraft:tnt");
        id(47, "minecraft:bookshelf");
        id(48, "minecraft:mossy_cobblestone");
        id(49, "minecraft:obsidian");
        id(50, "minecraft:torch"); // data 1-4 decode to wall torches below
        id(52, "minecraft:spawner");
        id(54, "minecraft:chest");
        id(56, "minecraft:diamond_ore");
        id(57, "minecraft:diamond_block");
        id(58, "minecraft:crafting_table");
        id(59, "minecraft:wheat");
        id(60, "minecraft:farmland");
        id(61, "minecraft:furnace");
        id(62, "minecraft:furnace[lit=true]");
        id(63, "minecraft:oak_sign");
        id(64, "minecraft:oak_door"); // halves decode below
        id(65, "minecraft:ladder");
        id(66, "minecraft:rail"); // shape decodes below
        id(68, "minecraft:oak_wall_sign");
        id(69, "minecraft:lever");
        id(70, "minecraft:stone_pressure_plate");
        id(71, "minecraft:iron_door");
        id(72, "minecraft:oak_pressure_plate");
        id(73, "minecraft:redstone_ore");
        id(74, "minecraft:redstone_ore");
        id(75, "minecraft:redstone_torch"); // wall forms decode below
        id(76, "minecraft:redstone_torch[lit=true]");
        id(77, "minecraft:stone_button");
        id(78, "minecraft:snow");
        id(79, "minecraft:ice");
        id(80, "minecraft:snow_block");
        id(81, "minecraft:cactus");
        id(82, "minecraft:clay");
        id(83, "minecraft:sugar_cane");
        id(84, "minecraft:jukebox");
        id(86, "minecraft:carved_pumpkin");
        id(87, "minecraft:netherrack");
        id(88, "minecraft:soul_sand");
        id(89, "minecraft:glowstone");
        id(90, "minecraft:air"); // nether portal — never persisted into a build
        id(91, "minecraft:jack_o_lantern");
        id(92, "minecraft:cake");
        id(93, "minecraft:repeater");
        id(94, "minecraft:repeater[powered=true]");
        id(96, "minecraft:oak_trapdoor"); // facing/open/half decode below
        id(97, "minecraft:infested_stone"); // silverfish variants decode below
        id(98, "minecraft:stone_bricks");
        idData(98, 1, "minecraft:mossy_stone_bricks");
        idData(98, 2, "minecraft:cracked_stone_bricks");
        idData(98, 3, "minecraft:chiseled_stone_bricks");
        id(99, "minecraft:brown_mushroom_block");
        id(100, "minecraft:red_mushroom_block");
        id(101, "minecraft:iron_bars");
        id(102, "minecraft:glass_pane");
        id(103, "minecraft:melon");
        id(104, "minecraft:pumpkin_stem");
        id(105, "minecraft:melon_stem");
        id(106, "minecraft:vine");
        id(107, "minecraft:oak_fence_gate");
        id(110, "minecraft:mycelium");
        id(111, "minecraft:lily_pad");
        id(112, "minecraft:nether_bricks");
        id(113, "minecraft:nether_brick_fence");
        id(116, "minecraft:enchanting_table");
        id(117, "minecraft:brewing_stand");
        id(118, "minecraft:cauldron");
        id(119, "minecraft:air"); // end portal
        id(120, "minecraft:end_portal_frame");
        id(121, "minecraft:end_stone");
        id(122, "minecraft:dragon_egg");
        id(123, "minecraft:redstone_lamp");
        id(124, "minecraft:redstone_lamp[lit=true]");
        id(129, "minecraft:emerald_ore");
        id(130, "minecraft:ender_chest");
        id(131, "minecraft:tripwire_hook");
        id(133, "minecraft:emerald_block");
        id(137, "minecraft:command_block");
        id(139, "minecraft:cobblestone_wall");
        idData(139, 1, "minecraft:mossy_cobblestone_wall");
        id(140, "minecraft:flower_pot");
        id(141, "minecraft:carrots");
        id(142, "minecraft:potatoes");
        id(143, "minecraft:oak_button");
        id(144, "minecraft:skeleton_skull");
        id(145, "minecraft:anvil");
        id(146, "minecraft:trapped_chest");
        id(147, "minecraft:light_weighted_pressure_plate");
        id(148, "minecraft:heavy_weighted_pressure_plate");
        id(149, "minecraft:comparator");
        id(150, "minecraft:comparator[powered=true]");
        id(151, "minecraft:daylight_detector");
        id(152, "minecraft:redstone_block");
        id(153, "minecraft:nether_quartz_ore");
        id(154, "minecraft:hopper"); // facing decodes below
        id(157, "minecraft:activator_rail");
        id(158, "minecraft:dropper");
        id(165, "minecraft:slime_block");
        id(166, "minecraft:barrier");
        id(167, "minecraft:iron_trapdoor");
        id(169, "minecraft:sea_lantern");
        id(170, "minecraft:hay_block");
        id(172, "minecraft:terracotta");
        id(173, "minecraft:coal_block");
        id(174, "minecraft:packed_ice");
        id(176, "minecraft:white_banner");
        id(177, "minecraft:white_wall_banner");
        id(179, "minecraft:red_sandstone");
        idData(179, 1, "minecraft:chiseled_red_sandstone");
        idData(179, 2, "minecraft:cut_red_sandstone");
        id(198, "minecraft:end_rod");
        id(199, "minecraft:chorus_plant");
        id(200, "minecraft:chorus_flower");
        id(201, "minecraft:purpur_block");
        id(202, "minecraft:purpur_pillar[axis=y]");
        id(203, "minecraft:purpur_stairs");
        id(204, "minecraft:purpur_slab[type=double]");
        id(205, "minecraft:purpur_slab");
        id(206, "minecraft:end_stone_bricks");
        id(208, "minecraft:dirt_path"); // grass path
        id(213, "minecraft:magma_block");
        id(214, "minecraft:nether_wart_block");
        id(215, "minecraft:red_nether_bricks");
        id(216, "minecraft:bone_block[axis=y]");
        id(217, "minecraft:structure_void");
        id(218, "minecraft:observer");
        id(219, "minecraft:white_shulker_box");
        id(235, "minecraft:white_glazed_terracotta");
        id(255, "minecraft:structure_block");

        // Colored families (data nibble = color).
        colorFamily(35, "minecraft:%s_wool");
        colorFamily(95, "minecraft:%s_stained_glass");
        colorFamily(159, "minecraft:%s_terracotta");
        colorFamily(160, "minecraft:%s_stained_glass_pane");
        colorFamily(171, "minecraft:%s_carpet");
        colorFamily(251, "minecraft:%s_concrete");
        colorFamily(252, "minecraft:%s_concrete_powder");

        // Wood families (data nibble = species).
        woodFamily(5, "minecraft:%s_planks");
        idData(161, 0, "minecraft:acacia_leaves");
        idData(161, 1, "minecraft:dark_oak_leaves");
        id(183, "minecraft:spruce_fence_gate");
        id(184, "minecraft:birch_fence_gate");
        id(185, "minecraft:jungle_fence_gate");
        id(186, "minecraft:dark_oak_fence_gate");
        id(187, "minecraft:acacia_fence_gate");
        id(188, "minecraft:spruce_fence");
        id(189, "minecraft:birch_fence");
        id(190, "minecraft:jungle_fence");
        id(191, "minecraft:dark_oak_fence");
        id(192, "minecraft:acacia_fence");
        id(85, "minecraft:oak_fence");
        id(18, "minecraft:oak_leaves");
        idData(18, 1, "minecraft:spruce_leaves");
        idData(18, 2, "minecraft:birch_leaves");
        idData(18, 3, "minecraft:jungle_leaves");

        // Shulker box colors 219-234.
        String[] shulker = { "white", "orange", "magenta", "light_blue", "yellow", "lime",
                "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green",
                "red", "black" };
        for (int i = 0; i < 16; i++) {
            id(219 + i, "minecraft:" + shulker[i] + "_shulker_box");
        }
        // Glazed terracotta 235-250.
        for (int i = 0; i < 16; i++) {
            id(235 + i, "minecraft:" + shulker[i] + "_glazed_terracotta");
        }
        // Beds 26 handled above; 220-234 beds skipped (banner family uses 176).

        // Saplings + more terrain ids.
        woodFamily(6, "minecraft:%s_sapling");
        id(36, "minecraft:air"); // moving piston extension
        id(55, "minecraft:redstone_wire");
        id(115, "minecraft:nether_wart");
        id(168, "minecraft:prismarine");
        idData(168, 1, "minecraft:prismarine_bricks");
        idData(168, 2, "minecraft:dark_prismarine");

        // Misc late ids.
        id(209, "minecraft:end_gateway");
        id(210, "minecraft:repeating_command_block");
        id(211, "minecraft:chain_command_block");
        id(212, "minecraft:frosted_ice");
        id(253, "minecraft:air"); // structure gap filler
    }

    private static void id(int id, String name) {
        ID_ONLY.put(id, name);
    }

    private static void idData(int id, int data, String name) {
        ID_DATA.put(((long) id << 8) | (data & 0xFF), name);
    }

    private static void colorFamily(int id, String pattern) {
        for (int d = 0; d < 16; d++) {
            idData(id, d, String.format(pattern, COLORS[d]));
        }
    }

    private static void woodFamily(int id, String pattern) {
        if (pattern == null) return;
        for (int d = 0; d < 6; d++) {
            idData(id, d, String.format(pattern, WOODS[d]));
        }
    }

    /**
     * Resolves one legacy {@code id:data} cell to a modern block-state string.
     * Orientation-bearing families decode their data bits; unknown ids return
     * empty so the reader can count and report them.
     */
    static Optional<String> resolve(int id, int data) {
        int kind = data & 0x7;
        // Stairs: low two bits face, bit 4 = upside down.
        String stairBase = switch (id) {
            case 53 -> "oak_stairs";
            case 67 -> "cobblestone_stairs";
            case 108 -> "brick_stairs";
            case 109 -> "stone_brick_stairs";
            case 114 -> "nether_brick_stairs";
            case 128 -> "sandstone_stairs";
            case 134 -> "spruce_stairs";
            case 135 -> "birch_stairs";
            case 136 -> "jungle_stairs";
            case 156 -> "quartz_stairs";
            case 163 -> "acacia_stairs";
            case 164 -> "dark_oak_stairs";
            case 180 -> "red_sandstone_stairs";
            case 203 -> "purpur_stairs";
            default -> null;
        };
        if (stairBase != null) {
            String facing = STAIR_FACING[Math.min(data & 0x3, 3)];
            String half = (data & 0x4) != 0 ? "top" : "bottom";
            return Optional.of("minecraft:" + stairBase + "[facing=" + facing + ",half=" + half + "]");
        }
        // Slabs: low 3 bits material, bit 3 = upper half.
        if (id == 44 || id == 126 || id == 182) {
            String base = switch (id) {
                case 126 -> WOODS[Math.min(kind, 5)] + "_slab"; // wooden slab block
                case 182 -> "red_sandstone_slab";
                default -> kind == 2 ? "petrified_oak_slab"
                        : SLAB_TYPES[Math.min(kind, SLAB_TYPES.length - 1)] + "_slab";
            };
            String type = (data & 0x8) != 0 ? "[type=top]" : "[type=bottom]";
            return Optional.of("minecraft:" + base + type);
        }
        // Double slabs: wood 125 and red sandstone 181 emit their double-slab
        // state; smooth stone 43 emits its double-slab state for data 0-7 and
        // the seamless (8-15) full block for the upper half of the range.
        if (id == 43) {
            if ((data & 0x8) != 0) {
                return Optional.of("minecraft:"
                        + SEAMLESS_BLOCKS[Math.min(kind, SEAMLESS_BLOCKS.length - 1)]);
            }
            return Optional.of("minecraft:"
                    + SLAB_TYPES[Math.min(kind, SLAB_TYPES.length - 1)] + "_slab[type=double]");
        }
        if (id == 125 || id == 181) {
            String base = id == 125 ? WOODS[Math.min(kind, 5)] : "red_sandstone";
            return Optional.of("minecraft:" + base + "_slab[type=double]");
        }
        // Logs: low 2 bits species, bits 2-3 axis (0=y, 4=x, 8=z, 12=bark).
        if (id == 17 || id == 162) {
            String wood = id == 17 ? WOODS[Math.min(data & 0x3, 3)]
                    : WOODS[4 + Math.min(data & 0x1, 1)]; // 162: acacia/dark_oak only
            String axis = switch ((data >> 2) & 0x3) {
                case 1 -> "x";
                case 2 -> "z";
                case 3 -> "none";
                default -> "y";
            };
            String block = axis.equals("none") ? wood + "_wood" : wood + "_log";
            return Optional.of("minecraft:" + block + "[axis=" + (axis.equals("none") ? "y" : axis) + "]");
        }
        // Doors: bit 3 = upper half; lower half low two bits = facing
        // (horizontal index order south/west/north/east).
        String doorBase = switch (id) {
            case 64 -> "oak_door";
            case 71 -> "iron_door";
            case 193 -> "spruce_door";
            case 194 -> "birch_door";
            case 195 -> "jungle_door";
            case 196 -> "acacia_door";
            case 197 -> "dark_oak_door";
            default -> null;
        };
        if (doorBase != null) {
            if ((data & 0x8) != 0) {
                return Optional.of("minecraft:" + doorBase + "[half=upper]");
            }
            return Optional.of("minecraft:" + doorBase
                    + "[facing=" + HORIZONTAL4[data & 0x3] + ",half=lower]");
        }
        // Torches: data 1-4 mount on walls facing the indicated direction.
        if (id == 50 || id == 75 || id == 76) {
            String prefix = id == 50 ? "" : "redstone_";
            String lit = id == 76 ? "[lit=true]" : "";
            String wall = "wall_torch";
            String facing = switch (data & 0x7) {
                case 1 -> "east";
                case 2 -> "west";
                case 3 -> "south";
                case 4 -> "north";
                default -> null;
            };
            if (facing != null) {
                return Optional.of("minecraft:" + prefix + wall + "[facing=" + facing
                        + (id == 76 ? ",lit=true]" : "]"));
            }
            return Optional.of("minecraft:" + prefix + "torch" + lit);
        }
        // Rails: low nibble = shape; powered/activator/detector rails have no
        // corner shapes — clamp curves to north_south.
        String railBase = switch (id) {
            case 27 -> "powered_rail";
            case 28 -> "detector_rail";
            case 66 -> "rail";
            case 157 -> "activator_rail";
            default -> null;
        };
        if (railBase != null) {
            int shape = data & 0xF;
            if (shape >= RAIL_SHAPES.length) shape = 0;
            if (id != 66 && shape > 5) shape = 0; // corners invalid on powered rails
            return Optional.of("minecraft:" + railBase + "[shape=" + RAIL_SHAPES[shape] + "]");
        }
        // Four-direction wall-attached blocks: data 2-5 = N/S/W/E face.
        String wallBase = switch (id) {
            case 54 -> "chest";
            case 61 -> "furnace";
            case 62 -> "furnace";
            case 65 -> "ladder";
            case 130 -> "ender_chest";
            case 146 -> "trapped_chest";
            default -> null;
        };
        if (wallBase != null) {
            String facing = WALL4[Math.min(data & 0x7, 5)];
            if (facing == null) facing = "north";
            String lit = id == 62 ? ",lit=true" : "";
            return Optional.of("minecraft:" + wallBase + "[facing=" + facing + lit + "]");
        }
        // Six-direction blocks: data&7 = facing index (down/up/N/S/W/E).
        String dir6Base = switch (id) {
            case 23 -> "dispenser";
            case 29 -> "sticky_piston";
            case 33 -> "piston";
            case 158 -> "dropper";
            case 218 -> "observer";
            default -> null;
        };
        if (dir6Base != null) {
            String facing = DIR6[Math.min(data & 0x7, 5)];
            return Optional.of("minecraft:" + dir6Base + "[facing=" + facing + "]");
        }
        // Hopper: data&7 — 0=down, 2-5 walls; 1/6/7 invalid → down.
        if (id == 154) {
            String facing = switch (data & 0x7) {
                case 2 -> "north";
                case 3 -> "south";
                case 4 -> "west";
                case 5 -> "east";
                default -> "down";
            };
            return Optional.of("minecraft:hopper[facing=" + facing + "]");
        }
        // Trapdoors: low two bits = hinge edge (legacy order S/N/E/W),
        // bit 2 = open, bit 3 = top half.
        if (id == 96 || id == 167) {
            String base = id == 96 ? "oak_trapdoor" : "iron_trapdoor";
            String facing = switch (data & 0x3) {
                case 0 -> "south";
                case 1 -> "north";
                case 2 -> "east";
                default -> "west";
            };
            String open = (data & 0x4) != 0 ? "true" : "false";
            String half = (data & 0x8) != 0 ? "top" : "bottom";
            return Optional.of("minecraft:" + base + "[facing=" + facing
                    + ",half=" + half + ",open=" + open + "]");
        }
        // Fence gates: low two bits = facing (horizontal index), bit 2 = open.
        String gateBase = switch (id) {
            case 107 -> "oak_fence_gate";
            case 183 -> "spruce_fence_gate";
            case 184 -> "birch_fence_gate";
            case 185 -> "jungle_fence_gate";
            case 186 -> "dark_oak_fence_gate";
            case 187 -> "acacia_fence_gate";
            default -> null;
        };
        if (gateBase != null) {
            String open = (data & 0x4) != 0 ? "true" : "false";
            return Optional.of("minecraft:" + gateBase + "[facing="
                    + HORIZONTAL4[data & 0x3] + ",open=" + open + "]");
        }
        // End portal frame: low two bits facing, bit 2 = has eye.
        if (id == 120) {
            return Optional.of("minecraft:end_portal_frame[eye="
                    + ((data & 0x4) != 0) + ",facing=" + HORIZONTAL4[data & 0x3] + "]");
        }
        // Axis blocks (hay, bone block, purpur pillar): bits 2-3 axis.
        String axisBase = switch (id) {
            case 170 -> "hay_block";
            case 202 -> "purpur_pillar";
            case 216 -> "bone_block";
            default -> null;
        };
        if (axisBase != null) {
            String axis = switch ((data >> 2) & 0x3) {
                case 1 -> "x";
                case 2 -> "z";
                default -> "y";
            };
            return Optional.of("minecraft:" + axisBase + "[axis=" + axis + "]");
        }
        // Quartz block family: 2-4 = pillar with axis bits.
        if (id == 155) {
            return switch (kind) {
                case 0 -> Optional.of("minecraft:quartz_block");
                case 1 -> Optional.of("minecraft:chiseled_quartz_block");
                case 2 -> Optional.of("minecraft:quartz_pillar[axis=y]");
                case 3 -> Optional.of("minecraft:quartz_pillar[axis=x]");
                case 4 -> Optional.of("minecraft:quartz_pillar[axis=z]");
                default -> Optional.of("minecraft:smooth_quartz");
            };
        }
        // Monster egg: data = hidden variant.
        if (id == 97) {
            return Optional.of("minecraft:" + INFESTED[Math.min(kind, INFESTED.length - 1)]);
        }
        // Beds: bit 3 = head part; low two bits = facing (legacy order
        // 0=south? No — legacy bed facing follows horizontal index).
        if (id == 26) {
            String part = (data & 0x8) != 0 ? "head" : "foot";
            return Optional.of("minecraft:white_bed[facing=" + HORIZONTAL4[data & 0x3]
                    + ",part=" + part + "]");
        }
        // Double plants: low 3 bits = species, bit 3 = upper half.
        if (id == 175) {
            String[] plants = { "sunflower", "lilac", "tall_grass", "large_fern",
                    "rose_bush", "peony" };
            String half = (data & 0x8) != 0 ? "upper" : "lower";
            return Optional.of("minecraft:" + plants[Math.min(kind, plants.length - 1)]
                    + "[half=" + half + "]");
        }
        // Leaves: persistent/decay flags live in high bits — mask to species.
        if (id == 18) {
            return Optional.ofNullable(ID_DATA.get(((long) id << 8) | (data & 0x3)))
                    .or(() -> Optional.ofNullable(ID_ONLY.get(id)));
        }
        if (id == 161) {
            return Optional.ofNullable(ID_DATA.get(((long) id << 8) | (data & 0x1)))
                    .or(() -> Optional.ofNullable(ID_ONLY.get(id)));
        }
        String paired = ID_DATA.get(((long) id << 8) | (data & 0xFF));
        if (paired != null) {
            return Optional.of(paired);
        }
        return Optional.ofNullable(ID_ONLY.get(id));
    }

    private LegacyBlockTable() {}
}
