package org.embeddedt.modernfix.common.mixin.perf.dynamic_resources;

import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.embeddedt.modernfix.annotation.ClientOnlyMixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.List;

@ClientOnlyMixin
@Mixin(BlockStateModelLoader.class)
public interface BlockStateModelLoaderMixin {
    @Invoker("loadBlockStateDefinitionStack")
    static BlockStateModelLoader.LoadedModels mfix$invokeLoadBlockStateDefinitionStack(Identifier identifier, StateDefinition<Block, BlockState> stateDefinition, List<BlockStateModelLoader.LoadedBlockModelDefinition> list) {
        throw new RuntimeException("Invoker didn't work?");
    }
}
