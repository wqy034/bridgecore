package com.bridgecore;

import com.bridgecore.display.DisplayGpuBlock;
import com.bridgecore.display.DisplayGpuBlockEntity;
import com.bridgecore.display.DisplayTileBlock;
import com.bridgecore.display.DisplayTileBlockEntity;
import com.bridgecore.menu.BridgeCoreMenu;
import com.bridgecore.menu.EnergyExtractorMenu;
import com.bridgecore.menu.MomentumDistributorMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BCRegistry {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BridgeCoreMod.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BridgeCoreMod.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BridgeCoreMod.MOD_ID);
    private static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, BridgeCoreMod.MOD_ID);
    private static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BridgeCoreMod.MOD_ID);

    public static final DeferredBlock<Block> BRIDGE_CORE = BLOCKS.register("bridge_core",
            () -> new BridgeCoreBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> BRIDGE_CORE_ITEM = ITEMS.register("bridge_core",
            () -> new BlockItem(BRIDGE_CORE.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BridgeCoreBlockEntity>> BRIDGE_CORE_BE =
            BLOCK_ENTITIES.register("bridge_core",
                    () -> BlockEntityType.Builder.of(BridgeCoreBlockEntity::new, BRIDGE_CORE.get()).build(null));

    public static final DeferredBlock<Block> HUD_SCREEN = BLOCKS.register("holo_display",
            () -> new HudScreenBlock(BlockBehaviour.Properties.of()
                    .strength(2.0f)
                    .sound(SoundType.METAL)));

    public static final DeferredItem<BlockItem> HUD_SCREEN_ITEM = ITEMS.register("holo_display",
            () -> new BlockItem(HUD_SCREEN.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<HudScreenBlockEntity>> HUD_SCREEN_BE =
            BLOCK_ENTITIES.register("holo_display",
                    () -> BlockEntityType.Builder.of(HudScreenBlockEntity::new, HUD_SCREEN.get()).build(null));

    public static final DeferredBlock<Block> REDSTONE_PROXY = BLOCKS.register("redstone_proxy",
            () -> new RedstoneProxyBlock(BlockBehaviour.Properties.of()
                    .strength(2.0f)
                    .sound(SoundType.METAL)));

    public static final DeferredItem<BlockItem> REDSTONE_PROXY_ITEM = ITEMS.register("redstone_proxy",
            () -> new BlockItem(REDSTONE_PROXY.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneProxyBlockEntity>> REDSTONE_PROXY_BE =
            BLOCK_ENTITIES.register("redstone_proxy",
                    () -> BlockEntityType.Builder.of(RedstoneProxyBlockEntity::new,
                            REDSTONE_PROXY.get()).build(null));

    public static final DeferredBlock<Block> MOMENTUM_DISTRIBUTOR = BLOCKS.register("momentum_distributor",
            () -> new MomentumDistributorBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> MOMENTUM_DISTRIBUTOR_ITEM = ITEMS.register("momentum_distributor",
            () -> new BlockItem(MOMENTUM_DISTRIBUTOR.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MomentumDistributorBlockEntity>> MOMENTUM_DISTRIBUTOR_BE =
            BLOCK_ENTITIES.register("momentum_distributor",
                    () -> BlockEntityType.Builder.of(MomentumDistributorBlockEntity::new,
                            MOMENTUM_DISTRIBUTOR.get()).build(null));

    public static final DeferredBlock<Block> ENERGY_EXTRACTOR = BLOCKS.register("energy_extractor",
            () -> new EnergyExtractorBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> ENERGY_EXTRACTOR_ITEM = ITEMS.register("energy_extractor",
            () -> new BlockItem(ENERGY_EXTRACTOR.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<EnergyExtractorBlockEntity>> ENERGY_EXTRACTOR_BE =
            BLOCK_ENTITIES.register("energy_extractor",
                    () -> BlockEntityType.Builder.of(EnergyExtractorBlockEntity::new,
                            ENERGY_EXTRACTOR.get()).build(null));

    public static final DeferredItem<Item> END_ESSENCE = ITEMS.register("end_essence",
            () -> new Item(new Item.Properties()));

    // [firecontrol] phase 2 skeleton: block + BE + item, chat-only interaction for now
    public static final DeferredBlock<Block> FIRE_CONTROL_CORE = BLOCKS.register("fire_control_core",
            () -> new com.bridgecore.firecontrol.FireControlCoreBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .sound(SoundType.METAL)
                    .requiresCorrectToolForDrops()));

    public static final DeferredItem<BlockItem> FIRE_CONTROL_CORE_ITEM = ITEMS.register("fire_control_core",
            () -> new BlockItem(FIRE_CONTROL_CORE.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<com.bridgecore.firecontrol.FireControlCoreBlockEntity>> FIRE_CONTROL_CORE_BE =
            BLOCK_ENTITIES.register("fire_control_core",
                    () -> BlockEntityType.Builder.of(com.bridgecore.firecontrol.FireControlCoreBlockEntity::new,
                            FIRE_CONTROL_CORE.get()).build(null));

    // Tiled display: the GPU/controller block is the brain, the screen tiles are
    // dumb cells. Tiles keep FACING in the blockstate (six directions) so the
    // placeholder model can rotate; the pixels come from the BER.
    public static final DeferredBlock<Block> DISPLAY_GPU = BLOCKS.register("display_gpu",
            () -> new DisplayGpuBlock(BlockBehaviour.Properties.of()
                    .strength(3.5f)
                    .sound(SoundType.METAL)));

    public static final DeferredItem<BlockItem> DISPLAY_GPU_ITEM = ITEMS.register("display_gpu",
            () -> new BlockItem(DISPLAY_GPU.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisplayGpuBlockEntity>> DISPLAY_GPU_BE =
            BLOCK_ENTITIES.register("display_gpu",
                    () -> BlockEntityType.Builder.of(DisplayGpuBlockEntity::new, DISPLAY_GPU.get()).build(null));

    public static final DeferredBlock<Block> DISPLAY_TILE = BLOCKS.register("display_tile",
            () -> new DisplayTileBlock(BlockBehaviour.Properties.of()
                    .strength(2.0f)
                    .sound(SoundType.GLASS)
                    .lightLevel(state -> 12)));

    public static final DeferredItem<BlockItem> DISPLAY_TILE_ITEM = ITEMS.register("display_tile",
            () -> new BlockItem(DISPLAY_TILE.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DisplayTileBlockEntity>> DISPLAY_TILE_BE =
            BLOCK_ENTITIES.register("display_tile",
                    () -> BlockEntityType.Builder.of(DisplayTileBlockEntity::new, DISPLAY_TILE.get()).build(null));

    // Menu types: the IContainerFactory constructor (int, Inventory, FriendlyByteBuf)
    // resolves to the menus' client-side (buf) constructor; the extra data written
    // by Player.openMenu(provider, pos) is the BE's BlockPos.
    public static final DeferredHolder<MenuType<?>, MenuType<EnergyExtractorMenu>> ENERGY_EXTRACTOR_MENU =
            MENUS.register("energy_extractor", () -> IMenuTypeExtension.create(EnergyExtractorMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<MomentumDistributorMenu>> MOMENTUM_DISTRIBUTOR_MENU =
            MENUS.register("momentum_distributor", () -> IMenuTypeExtension.create(MomentumDistributorMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<BridgeCoreMenu>> BRIDGE_CORE_MENU =
            MENUS.register("bridge_core", () -> IMenuTypeExtension.create(BridgeCoreMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<com.bridgecore.menu.FireControlMenu>> FIRE_CONTROL_MENU =
            MENUS.register("fire_control", () -> IMenuTypeExtension.create(com.bridgecore.menu.FireControlMenu::new));

    // Own creative tab (the items also stay in vanilla FUNCTIONAL_BLOCKS —
    // that tab is where testers go digging; ours is one click, no digging).
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BRIDGECORE_TAB =
            TABS.register("bridgecore", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.bridgecore"))
                    .icon(() -> new ItemStack(BRIDGE_CORE_ITEM.get()))
                    .displayItems((params, output) -> {
                        output.accept(BRIDGE_CORE_ITEM.get());
                        output.accept(HUD_SCREEN_ITEM.get());
                        output.accept(REDSTONE_PROXY_ITEM.get());
                        output.accept(MOMENTUM_DISTRIBUTOR_ITEM.get());
                        output.accept(ENERGY_EXTRACTOR_ITEM.get());
                        output.accept(END_ESSENCE.get());
                        output.accept(FIRE_CONTROL_CORE_ITEM.get());
                        output.accept(DISPLAY_GPU_ITEM.get());
                        output.accept(DISPLAY_TILE_ITEM.get());
                    })
                    .build());

    private BCRegistry() {
    }

    static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        MENUS.register(modEventBus);
        TABS.register(modEventBus);
        modEventBus.addListener(BCRegistry::addCreative);
    }

    private static void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(BRIDGE_CORE_ITEM);
            event.accept(HUD_SCREEN_ITEM);
            event.accept(REDSTONE_PROXY_ITEM);
            event.accept(MOMENTUM_DISTRIBUTOR_ITEM);
            event.accept(ENERGY_EXTRACTOR_ITEM);
            event.accept(END_ESSENCE);
            event.accept(FIRE_CONTROL_CORE_ITEM);
            event.accept(DISPLAY_GPU_ITEM);
            event.accept(DISPLAY_TILE_ITEM);
        }
    }
}
