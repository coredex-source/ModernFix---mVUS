package org.embeddedt.modernfix.dynresources;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.AbstractObject2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.ReferenceSets;
// import net.fabricmc.fabric.impl.client.model.loading.UnbakedModelDeserializerRegistry; // Disabled by FRAPI
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.resources.model.cuboid.CuboidModel;
import net.minecraft.client.resources.model.cuboid.ItemModelGenerator;
import net.minecraft.client.resources.model.BlockStateModelLoader;
import net.minecraft.client.resources.model.ClientItemInfoLoader;
import net.minecraft.client.resources.model.cuboid.MissingCuboidModel;
import net.minecraft.client.resources.model.ModelDiscovery;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.resources.model.ResolvedModel;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.common.mixin.perf.dynamic_resources.BlockStateDefinitionsAccessor;
import org.embeddedt.modernfix.common.mixin.perf.dynamic_resources.IdMapperAccessor;
import org.embeddedt.modernfix.common.mixin.perf.dynamic_resources.ModelDiscoveryAccessor;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

public class DynamicModelSystem {
    private static final FileToIdConverter MODEL_LISTER = FileToIdConverter.json("models");
    private static final FileToIdConverter BLOCKSTATE_LISTER = FileToIdConverter.json("blockstates");
    private static final FileToIdConverter ITEM_LISTER = FileToIdConverter.json("items");

    public static final boolean DEBUG_DYNAMIC_MODEL_LOADING = Boolean.getBoolean("modernfix.debugDynamicModelLoading");
    
    public static Map<Identifier, UnbakedModel> createDynamicUnbakedModelMap(Map<Identifier, Resource> resourceMap) {
        LoadingCache<Identifier, UnbakedModel> unbakedModelCache = CacheBuilder.newBuilder().softValues().maximumSize(1000).build(new CacheLoader<>() {
            @Override
            public UnbakedModel load(Identifier key) throws Exception {
                var resource = resourceMap.get(MODEL_LISTER.idToFile(key));
                if (resource == null) {
                    throw new IllegalArgumentException("Model " + key + " does not exist in map");
                }
                if (DEBUG_DYNAMIC_MODEL_LOADING) {
                    ModernFix.LOGGER.info("Loading unbaked model {}", key);
                }
                try (Reader reader = resource.openAsReader()) {
                    // return UnbakedModelDeserializerRegistry.deserialize(reader); // Disabled by FRAPI
                    return CuboidModel.fromStream(reader);
                }
            }
        });
        Set<Identifier> unbakedIdSet = resourceMap.keySet().stream().map(MODEL_LISTER::fileToId).collect(Collectors.toUnmodifiableSet());
        return Maps.asMap(unbakedIdSet, key -> key != null ? unbakedModelCache.getUnchecked(key) : null);
    }

    public interface SingleBlockStateEntryLoader {
        BlockStateModelLoader.LoadedModels loadEntry(Identifier identifier, List<Resource> blockstateResources);
    }

    private static Set<BlockState> getAllBlockStates() {
        return ((IdMapperAccessor<BlockState>) Block.BLOCK_STATE_REGISTRY).getReferenceMap().keySet();
    }
    public static BlockStateModelLoader.LoadedModels createDynamicBlockStateLoadedModels(Map<Identifier, List<Resource>> resourceMap, SingleBlockStateEntryLoader entryLoader) {
        LoadingCache<Identifier, BlockStateModelLoader.LoadedModels> definitionCache = CacheBuilder.newBuilder().softValues().maximumSize(1000).build(new CacheLoader<>() {
            @Override
            public BlockStateModelLoader.LoadedModels load(Identifier key) throws Exception {
                if (DEBUG_DYNAMIC_MODEL_LOADING) {
                    ModernFix.LOGGER.info("Loading blockstate definition for {}", key);
                }
                var file = BLOCKSTATE_LISTER.idToFile(key);
                var resources = resourceMap.getOrDefault(file, List.of());
                return entryLoader.loadEntry(file, resources);
            }
        });
        var staticDefinitions = BlockStateDefinitionsAccessor.getStaticDefinitions();
        var staticIdentifiers = staticDefinitions.entrySet()
                .stream()
                .flatMap(e -> e.getValue().getPossibleStates().stream().map(s -> Map.entry(s, e.getKey())))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        var blockStateSet = new DisjointSetUnion<>(getAllBlockStates(), staticIdentifiers.keySet());
        return new BlockStateModelLoader.LoadedModels(Maps.asMap(blockStateSet, state -> {
            var identifier = staticIdentifiers.get(state);
            if (identifier == null) {
                identifier = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            }
            var loadedModels = definitionCache.getUnchecked(identifier);
            return loadedModels.models().get(state);
        }));
    }

    public interface SingleClientItemEntryLoader {
        @Nullable ClientItem loadEntry(Identifier resourceFileId, Resource resource);
    }

    public static ClientItemInfoLoader.LoadedClientInfos createDynamicClientInfos(Map<Identifier, Resource> resourceMap, SingleClientItemEntryLoader entryLoader) {
        Set<Identifier> itemIdSet = resourceMap.keySet().stream().map(ITEM_LISTER::fileToId).collect(Collectors.toUnmodifiableSet());
        LoadingCache<Identifier, Object> clientItemCache = CacheBuilder.newBuilder().softValues().maximumSize(1000).build(new CacheLoader<>() {
            @Override
            public Object load(Identifier key) {
                Identifier fileId = ITEM_LISTER.idToFile(key);
                Resource resource = resourceMap.get(fileId);
                if (resource == null) {
                    return NULL_SENTINEL;
                }
                if (DEBUG_DYNAMIC_MODEL_LOADING) {
                    ModernFix.LOGGER.info("Loading client item info {}", key);
                }
                try {
                    ClientItem result = entryLoader.loadEntry(fileId, resource);
                    return result != null ? result : NULL_SENTINEL;
                } catch (RuntimeException e) {
                    ModernFix.LOGGER.warn("Failed to build dynamic client item info for {}", key, e);
                    return NULL_SENTINEL;
                }
            }
        });
        return new ClientItemInfoLoader.LoadedClientInfos(Maps.asMap(itemIdSet, key -> {
            if (key == null) {
                return null;
            }
            Object value = clientItemCache.getUnchecked(key);
            return value == NULL_SENTINEL ? null : (ClientItem) value;
        }));
    }

    public record DynamicResolver(Map<Identifier, UnbakedModel> inputModels,
                                          BlockStateModelLoader.LoadedModels loadedModels,
                                          ClientItemInfoLoader.LoadedClientInfos loadedClientInfos) {

        private ResolvedModel resolveModel(Identifier id) {
            var discovery = new ModelDiscovery(inputModels, MissingCuboidModel.missingModel());
            discovery.addSpecialModel(ItemModelGenerator.GENERATED_ITEM_MODEL_ID, new ItemModelGenerator());
            if (!id.equals(ItemModelGenerator.GENERATED_ITEM_MODEL_ID)) {
                UnbakedModel unbaked = inputModels.get(id);
                if (unbaked != null) {
                    var wrapper = discovery.createAndQueueWrapper(id, unbaked);
                    ((ModelDiscoveryAccessor)discovery).mfix$getModelWrappers().put(id, wrapper);
                } else {
                    ModernFix.LOGGER.warn("Cannot find the root model for {}", id);
                }
            }
            var resolved = discovery.resolve();
            return resolved.getOrDefault(id, discovery.missingModel());
        }

        public ModelManager.ResolvedModels resolvedModels() {
            var resolvedMissingModel = new ModelDiscovery(inputModels, MissingCuboidModel.missingModel()).missingModel();
            LoadingCache<Identifier, ResolvedModel> resolvedModelCache = CacheBuilder.newBuilder().softValues().maximumSize(1000).build(new CacheLoader<>() {
                @Override
                public ResolvedModel load(Identifier key) {
                    return resolveModel(key);
                }
            });
            return new ModelManager.ResolvedModels(resolvedMissingModel, Maps.asMap(inputModels.keySet(), resolvedModelCache::getUnchecked));
        }
    }

    public static class BlockGroupingMap extends AbstractObject2IntMap<BlockState> {
        private final BlockColors blockColors;
        private final BlockStateModelLoader.LoadedModels loadedModels;

        record GroupKey(Object equalityGroup, List<Object> coloringValues) {}

        private final Object2IntMap<GroupKey> groupKeyToId;

        public BlockGroupingMap(BlockColors blockColors, BlockStateModelLoader.LoadedModels loadedModels) {
            this.blockColors = blockColors;
            this.loadedModels = loadedModels;
            this.groupKeyToId = new Object2IntOpenHashMap<>();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public ObjectSet<Entry<BlockState>> object2IntEntrySet() {
            return ObjectSets.emptySet();
        }

        @Override
        public int getInt(Object key) {
            // TODO: Implement
            return -1;
        }
    }

    private static final Object NULL_SENTINEL = new Object();

    public static <K, U, V> Map<K, V> createDynamicBakedRegistry(Map<K, U> input, BiFunction<K, U, V> baker) {
        // TODO: support persistence of overrides
        LoadingCache<K, Object> bakedCache = CacheBuilder.newBuilder().softValues().maximumSize(1000).build(new CacheLoader<>() {
            @Override
            public Object load(K key) throws Exception {
                var unbaked = input.get(key);
                if (unbaked != null) {
                    if (DEBUG_DYNAMIC_MODEL_LOADING) {
                        ModernFix.LOGGER.info("Baking {}", key);
                    }
                    return baker.apply(key, unbaked);
                } else {
                    return NULL_SENTINEL;
                }
            }
        });
        return new DynamicRegistryMap<>(input.keySet(), k -> {
            if (k != null) {
                Object value = bakedCache.getUnchecked(k);
                if (value == NULL_SENTINEL) {
                    value = null;
                }
                return (V) value;
            } else {
                return null;
            }
        });
    }
}