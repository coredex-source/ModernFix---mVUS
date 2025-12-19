package org.embeddedt.modernfix.dynamicresources;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ForwardingMap;
import com.google.common.collect.Iterators;
import com.google.common.collect.Maps;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.PlayerSkinRenderCache;
import net.minecraft.client.renderer.block.model.*;
import net.minecraft.client.renderer.item.ClientItem;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.MissingItemModel;
import net.minecraft.client.renderer.item.ModelRenderProperties;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.*;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.embeddedt.modernfix.ModernFix;
import org.embeddedt.modernfix.common.mixin.perf.dynamic_resources.BlockStateDefinitionsAccessor;
import org.embeddedt.modernfix.common.mixin.perf.dynamic_resources.BlockStateModelLoaderMixin;
import org.embeddedt.modernfix.common.mixin.perf.dynamic_resources.ModelWrapperInvoker;
import org.embeddedt.modernfix.duck.IModelHoldingBlockState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Reader;
import java.lang.ref.WeakReference;
import java.util.AbstractSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Handles loading models dynamically, rather than at startup time.
 */
public class DynamicModelProvider {
    private final LoadingCache<Identifier, Optional<BlockStateModelLoader.LoadedModels>> loadedStateDefinitions =
            this.makeLoadingCache(this::loadBlockStateDefinition);

    private final LoadingCache<Identifier, Optional<UnbakedModel>> loadedBlockModels =
            this.makeLoadingCache(this::loadBlockModel);

    private final LoadingCache<Identifier, Optional<ModelDiscovery.ModelWrapper>> resolvedBlockModels =
            this.makeLoadingCache(this::resolveBlockModel);

    private final LoadingCache<BlockState, Optional<BlockStateModel>> loadedBakedModels =
            this.makeLoadingCache(this::loadBakedModel);

    private final LoadingCache<Identifier, Optional<ClientItem>> loadedClientItemProperties =
            this.makeLoadingCache(this::loadClientItemProperties);

    private final LoadingCache<Identifier, Optional<ItemModel>> loadedItemModels =
            this.makeLoadingCache(this::loadItemModel);

    /*
    private final LoadingCache<Identifier, Optional<BakedModel>> loadedStandaloneModels =
            this.makeLoadingCache(this::loadStandaloneModel);

     */

    private final BlockStateModel missingModel;
    private final ModelDiscovery.ModelWrapper resolvedMissingModel;
    private final ItemModel missingItemModel;
    private final UnbakedModel unbakedMissingModel;
    private final Function<Identifier, StateDefinition<Block, BlockState>> stateMapper;
    private final ResourceManager resourceManager;
    private final SpriteGetter textureGetter;
    private final EntityModelSet entityModelSet;
    private final ItemModelGenerator itemModelGenerator;
    private final PlayerSkinRenderCache skinRenderCache;
    private final MaterialSet materialSet;
    private final ModelBaker.PartCache partCache;

    private final Map<BlockState, BlockStateModel> mrlModelOverrides = new ConcurrentHashMap<>();
    private final Map<Identifier, ItemModel> itemStackModelOverrides = new ConcurrentHashMap<>();
    //private final Map<Identifier, BakedModel> standaloneModelOverrides = new ConcurrentHashMap<>();
    private final Map<BlockState, BlockStateModel.Unbaked> unbakedBlockStateModelOverrides = new ConcurrentHashMap<>();

    private final List<DynamicModelProvider.DynamicModelPlugin> pluginList = new ArrayList<>();

    private static final boolean DEBUG_DYNAMIC_MODEL_LOADING = Boolean.getBoolean("modernfix.debugDynamicModelLoading");

    public DynamicModelProvider(ResourceManager resourceManager, EntityModelSet entityModelSet,
                                SpriteLoader.Preparations blockPreparations, SpriteLoader.Preparations itemPreparations,
                                PlayerSkinRenderCache skinRenderCache, MaterialSet materialSet) {
        this.unbakedMissingModel = MissingBlockModel.missingModel();
        this.entityModelSet = entityModelSet;
        this.skinRenderCache = skinRenderCache;
        this.materialSet = materialSet;
        this.partCache = new DynamicPartCache();

        this.textureGetter = new SpriteGetter() {
            @Override
            public @NotNull TextureAtlasSprite get(Material material, ModelDebugName modelDebugName) {
                Identifier atlas = material.atlasLocation();

                Boolean blockOrItemAtlas = atlas.equals(ModelManager.BLOCK_OR_ITEM);
                Boolean itemAtlas = atlas.equals(TextureAtlas.LOCATION_ITEMS);
                Boolean blockAtlas = atlas.equals(TextureAtlas.LOCATION_BLOCKS);

                TextureAtlasSprite sprite = null;

                if (blockOrItemAtlas || itemAtlas) {
                    sprite = itemPreparations.getSprite(material.texture());
                }

                if (sprite == null && (blockOrItemAtlas || blockAtlas)) {
                    sprite = blockPreparations.getSprite(material.texture());
                }

                if (sprite != null) {
                    return sprite;
                } else {
                    ModernFix.LOGGER.warn("Unable to find sprite '{}' referenced by model '{}'", material.texture(), modelDebugName.debugName());
                    if (!blockOrItemAtlas && !blockAtlas && !itemAtlas) {
                        ModernFix.LOGGER.warn(" -> Requested atlas ID '{}' was not part of the item or block atlas", atlas);
                    }
                    return itemAtlas ? itemPreparations.missing() : blockPreparations.missing();
                }
            }

            @Override
            public @NotNull TextureAtlasSprite reportMissingReference(String string, ModelDebugName modelDebugName) {
                return blockPreparations.missing();
            }
        };

        this.stateMapper = BlockStateDefinitions.definitionLocationToBlockStateMapper();
        this.resourceManager = resourceManager;
        this.itemModelGenerator = new ItemModelGenerator();
        this.resolvedMissingModel = ModelWrapperInvoker.mfix$invokeCtor(MissingBlockModel.LOCATION, this.unbakedMissingModel, true);
        var missingModelBaker = new ModelBaker() {
            @Override
            public ResolvedModel getModel(Identifier Identifier) {
                throw new IllegalStateException("Missing model should not have dependencies");
            }

            @Override
            public BlockModelPart missingBlockModelPart() {
                // Vanilla also throws an exception in this case
                throw new IllegalStateException("Asked for missing model's missing model parts!");
            }

            @Override
            public SpriteGetter sprites() {
                return DynamicModelProvider.this.textureGetter;
            }

            @Override
            public PartCache parts() {
                return DynamicModelProvider.this.partCache;
            }

            @Override
            public <T> T compute(SharedOperationKey<T> key) {
                return key.compute(this);
            }
        };
        var textureSlots = this.resolvedMissingModel.getTopTextureSlots();
        var quadCollection = this.resolvedMissingModel.bakeTopGeometry(textureSlots, missingModelBaker, BlockModelRotation.IDENTITY);
        var particleSprite = this.resolvedMissingModel.resolveParticleSprite(textureSlots, missingModelBaker);
        this.missingModel = new BlockStateModel() {
            @Override
            public void collectParts(RandomSource random, List<BlockModelPart> output) {
                output.add(new BlockModelPart() {
                    @Override
                    public List<BakedQuad> getQuads(@Nullable Direction direction) {
                        return quadCollection.getQuads(direction);
                    }

                    @Override
                    public boolean useAmbientOcclusion() {
                        return resolvedMissingModel.getTopAmbientOcclusion();
                    }

                    @Override
                    public TextureAtlasSprite particleIcon() {
                        return particleSprite;
                    }
                });
            }

            @Override
            public TextureAtlasSprite particleIcon() {
                return particleSprite;
            }
        };
        this.missingItemModel = new MissingItemModel(quadCollection.getAll(), new ModelRenderProperties(resolvedMissingModel.getTopGuiLight().lightLikeBlock(), particleSprite, resolvedMissingModel.getTopTransforms()));
        try {
            Class.forName("net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin");
            // TODO
            // pluginList.add(new FabricDynamicModelHandler(this, this.resourceManager));
        } catch(Exception ignored) {
            // Fabric API likely not present
        }

        // Fix item frames because they use a fake air BlockState
        Map<Identifier, StateDefinition<Block, BlockState>> static_definitions = BlockStateDefinitionsAccessor.getStaticDefinitions();

        for (var definition : static_definitions.entrySet()) {
            StateDefinition<Block, BlockState> fakeStateDefinitions = definition.getValue();
            Identifier identifier = definition.getKey();
            Identifier modelIdentifier = identifier.withPath("block/"+identifier.getPath());

            Optional<UnbakedModel> unbakedModel = this.loadedBlockModels.getUnchecked(modelIdentifier);

            for (var fakeState : fakeStateDefinitions.getPossibleStates()) {
                Optional<BlockStateModel> bakedModel = unbakedModel.flatMap(model -> {
                    var optLoadedModels = this.loadedStateDefinitions.getUnchecked(identifier);
                    return optLoadedModels
                            .map(loadedModels -> loadedModels.models().get(fakeState))
                            .map(unbakedRoot -> this.bakeModel(unbakedRoot, fakeState));
                });

                if (bakedModel.isPresent()) {
                    this.mrlModelOverrides.put(fakeState, bakedModel.get());
                } else {
                    ModernFix.LOGGER.error(
                            "Failed to load BlockStateModel for static definition {}, state {}",
                            identifier, fakeState
                    );
                }
            }
        }
        ModernFix.LOGGER.info("Loaded {} BlockState -> BlockStateModel overrides", this.mrlModelOverrides.size());
    }

    public BlockStateModel getMissingBakedModel() {
        return this.missingModel;
    }

    public ItemModel getMissingItemModel() {
        return this.missingItemModel;
    }

    public Map<BlockState, BlockStateModel> getTopLevelEmulatedRegistry() {
        return new EmulatedRegistry<>(BlockState.class, this.loadedBakedModels, BlockStateSet::instance, this.mrlModelOverrides);
    }

    public Map<BlockState, BlockStateModel> getFastTopLevelEmulatedRegistry() {
        var dynamicRegistry = getTopLevelEmulatedRegistry();

        return new ForwardingMap<>() {
            @Override
            protected Map<BlockState, BlockStateModel> delegate() {
                return dynamicRegistry;
            }

            @Override
            public BlockStateModel get(Object key) {
                BlockStateModel result;
                if (key instanceof IModelHoldingBlockState state) {
                    result = state.mfix$getModel();
                    if (result != null) {
                        return result;
                    }
                }
                result = dynamicRegistry.getOrDefault(key, getMissingBakedModel());
                if (key instanceof IModelHoldingBlockState state) {
                    state.mfix$setModel(result);
                }
                return result;
            }
        };
    }

    /*
    public Map<Identifier, BakedModel> getStandaloneEmulatedRegistry() {
        return new EmulatedRegistry<>(Identifier.class, this.loadedStandaloneModels, Set::of, this.standaloneModelOverrides);
    }
     */

    public Map<Identifier, ItemModel> getItemModelEmulatedRegistry() {
        return new EmulatedRegistry<>(Identifier.class, this.loadedItemModels, BuiltInRegistries.ITEM::keySet, this.itemStackModelOverrides);
    }

    public Map<Identifier, ClientItem.Properties> getItemPropertiesEmulatedRegistry() {
        return Maps.transformValues(new EmulatedRegistry<>(Identifier.class, this.loadedClientItemProperties, BuiltInRegistries.ITEM::keySet, Map.of()), ClientItem::properties);
    }

    private <K, V> LoadingCache<K, Optional<V>> makeLoadingCache(Function<K, Optional<V>> loadingFunction) {
        return CacheBuilder.newBuilder()
                .expireAfterAccess(3, TimeUnit.MINUTES)
                .maximumSize(1000)
                .concurrencyLevel(8)
                .softValues()
                .build(new CacheLoader<>() {
                    @Override
                    public Optional<V> load(K key) {
                        return loadingFunction.apply(key);
                    }
                });
    }

    private static class EmulatedRegistry<K, V> implements Map<K, V> {
        private final LoadingCache<K, Optional<V>> realCache;
        private final Supplier<Set<K>> keys;
        private final Map<K, V> overrides;
        private final Class<K> keyClass;

        public EmulatedRegistry(Class<K> keyClass, LoadingCache<K, Optional<V>> realCache, Supplier<Set<K>> keys, Map<K, V> overrides) {
            this.keyClass = keyClass;
            this.realCache = realCache;
            this.keys = keys;
            this.overrides = overrides;
        }

        @Override
        public V get(Object key) {
            if (this.keyClass.isAssignableFrom(key.getClass())) {
                return this.realCache.getUnchecked((K)key).orElse(null);
            } else {
                return null;
            }
        }

        @Override
        public V getOrDefault(Object key, V defaultValue) {
            if (this.keyClass.isAssignableFrom(key.getClass())) {
                return this.realCache.getUnchecked((K)key).orElse(defaultValue);
            } else {
                return defaultValue;
            }
        }

        @Override
        public V put(K key, V value) {
            V oldValue = this.realCache.getUnchecked(key).orElse(null);
            this.overrides.put(key, value);
            this.realCache.invalidate(key);
            return oldValue;
        }

        @Override
        public V remove(Object key) {
            this.overrides.remove(key);
            this.realCache.invalidate(key);
            return null;
        }

        @Override
        public void putAll(@NotNull Map<? extends K, ? extends V> m) {
            m.forEach(this::put);
        }

        @Override
        public void clear() {
            this.overrides.clear();
            this.realCache.invalidateAll();
        }

        @Override
        public @NotNull Set<K> keySet() {
            return keys.get();
        }

        @Override
        public @NotNull Collection<V> values() {
            return Collections.emptyList();
        }

        @Override
        public int size() {
            return keys.get().size();
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean containsKey(Object key) {
            return keys.get().contains(key);
        }

        @Override
        public boolean containsValue(Object value) {
            return false;
        }

        @Override
        public @NotNull Set<Entry<K, V>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<K, V>> iterator() {
                    return Iterators.transform(keys.get().iterator(), key -> new Entry<>() {
                        @Override
                        public K getKey() {
                            return key;
                        }

                        @Override
                        public V getValue() {
                            return get(key);
                        }

                        @Override
                        public V setValue(V value) {
                            return put(key, value);
                        }
                    });
                }

                @Override
                public int size() {
                    return keys.get().size();
                }
            };
        }

        @Override
        public void replaceAll(BiFunction<? super K, ? super V, ? extends V> function) {
            for(K location : keys.get()) {
                /*
                 * Fetching every model is insanely slow. So we call the function with a null object first, since it
                 * probably isn't expecting that. If we get an exception thrown, or it returns nonnull, then we know
                 * it actually cares about the given model.
                 */
                boolean needsReplacement;
                try {
                    needsReplacement = function.apply(location, null) != null;
                } catch(Throwable e) {
                    needsReplacement = true;
                }
                if(needsReplacement) {
                    V existing = get(location);
                    V replacement = function.apply(location, existing);
                    if(replacement != existing) {
                        put(location, replacement);
                    }
                }
            }
        }
    }

    private Optional<BlockStateModelLoader.LoadedModels> loadBlockStateDefinition(Identifier location) {
        StateDefinition<Block, BlockState> stateDefinition = this.stateMapper.apply(location);
        if(stateDefinition == null) {
            return Optional.empty();
        }
        if (DEBUG_DYNAMIC_MODEL_LOADING) {
            ModernFix.LOGGER.info("Loading blockstate definition '{}'", location);
        }
        List<Resource> resources = resourceManager.getResourceStack(Identifier.fromNamespaceAndPath(location.getNamespace(), "blockstates/" + location.getPath() + ".json"));
        List<BlockStateModelLoader.LoadedBlockModelDefinition> loadedDefinitions = new ArrayList<>(resources.size());
        for(Resource resource : resources) {
            try(Reader reader = resource.openAsReader()) {
                JsonObject jsonObject = GsonHelper.parse(reader);
                BlockModelDefinition blockModelDefinition = BlockModelDefinition.CODEC.decode(JsonOps.INSTANCE, jsonObject).getOrThrow().getFirst();
                loadedDefinitions.add(new BlockStateModelLoader.LoadedBlockModelDefinition(resource.sourcePackId(), blockModelDefinition));
            } catch(Exception e) {
                ModernFix.LOGGER.error("Failed to load blockstate definition {} from pack '{}'", location, resource.sourcePackId(), e);
            }
        }
        var loadedModels = new HashMap<>(BlockStateModelLoaderMixin.mfix$invokeLoadBlockStateDefinitionStack(location, stateDefinition, loadedDefinitions).models());
        if (!pluginList.isEmpty()) {
            loadedModels.replaceAll((mrl, oldModel) -> {
                BlockStateModel.UnbakedRoot ubm = oldModel;
                for (var plugin : pluginList) {
                    ubm = plugin.modifyBlockModelOnLoad(oldModel, mrl);
                }
                return ubm;
            });
        }
        return Optional.of(new BlockStateModelLoader.LoadedModels(loadedModels));
    }

    private BlockStateModel bakeModel(BlockStateModel.UnbakedRoot model, BlockState mrl) {
        if (DEBUG_DYNAMIC_MODEL_LOADING) {
            ModernFix.LOGGER.info("Baking model '{}'", mrl);
        }
        synchronized (this) {
            model.resolveDependencies(dep -> {});
            var modelBaker = new DynamicBaker(mrl::toString);
            for (var plugin : pluginList) {
                model = plugin.modifyBlockModelBeforeBake(model, mrl, modelBaker);
            }
            var bakedModel = model.bake(mrl, modelBaker);
            for (var plugin : pluginList) {
                bakedModel = plugin.modifyBlockModelAfterBake(bakedModel, model, mrl, modelBaker);
            }
            return bakedModel;
        }
    }

    private Optional<BlockStateModel> loadBakedModel(BlockState state) {
        var override = this.mrlModelOverrides.get(state);
        if (override != null) {
            return Optional.of(override);
        }
        if (false) { //location.variant().equals("standalone") || location.variant().equals("fabric_resource")) {
            throw new UnsupportedOperationException(); //return this.loadStandaloneModel(location.id());
        } else {
            Optional<BlockStateModel.UnbakedRoot> unbakedModelOpt = Optional.ofNullable(this.unbakedBlockStateModelOverrides.get(state))
                    .map(BlockStateModel.Unbaked::asRoot);
            if (unbakedModelOpt.isEmpty()) {
                var optLoadedModels = this.loadedStateDefinitions.getUnchecked(state.getBlock().builtInRegistryHolder().key().identifier());
                unbakedModelOpt = optLoadedModels.map(loadedModels -> loadedModels.models().get(state));
            }
            return unbakedModelOpt.map(unbakedModel -> {
                return this.bakeModel(unbakedModel, state);
            });
        }
    }

    /*
    private Optional<BakedModel> loadStandaloneModel(Identifier location) {
        var override = this.standaloneModelOverrides.get(location);
        if (override != null) {
            return Optional.of(override);
        }
        return this.loadedBlockModels.getUnchecked(location).map(unbakedModel -> {
            return this.bakeModel(unbakedModel, location);
        });
    }
     */

    private Optional<UnbakedModel> loadBlockModelDefault(Identifier location) {
        if (DEBUG_DYNAMIC_MODEL_LOADING) {
            ModernFix.LOGGER.info("Loading block model '{}'", location);
        }
        if (location.equals(ItemModelGenerator.GENERATED_ITEM_MODEL_ID)) {
            return Optional.of(this.itemModelGenerator);
        } else if (location.equals(MissingBlockModel.LOCATION)) {
            return Optional.of(this.unbakedMissingModel);
        }
        var resource = this.resourceManager.getResource(Identifier.fromNamespaceAndPath(location.getNamespace(), "models/" + location.getPath() + ".json"));
        if(resource.isPresent()) {
            try(Reader reader = resource.get().openAsReader()) {
                BlockModel blockModel = BlockModel.fromStream(reader);
                return Optional.of(blockModel);
            } catch(Exception e) {
                ModernFix.LOGGER.error("Failed to load block model {} from '{}'", location, resource.get().sourcePackId(), e);
                return Optional.empty();
            }
        } else {
            ModernFix.LOGGER.warn("Model '{}' does not exist in any resource packs", location);
            return Optional.empty();
        }
    }

    private Optional<UnbakedModel> loadBlockModel(Identifier location) {
        Optional<UnbakedModel> value = loadBlockModelDefault(location);
        for (var plugin : this.pluginList) {
            value = plugin.modifyModelOnLoad(value, location);
        }
        return value;
    }

    private Optional<ModelDiscovery.ModelWrapper> resolveBlockModel(Identifier location) {
        var unbakedOpt = this.loadedBlockModels.getUnchecked(location);
        if (unbakedOpt.isEmpty()) {
            return Optional.empty();
        }
        ModelDiscovery.ModelWrapper wrapper = ModelWrapperInvoker.mfix$invokeCtor(location, unbakedOpt.get(), true);
        var parent = wrapper.wrapped().parent();
        if (parent != null) {
            Optional<ModelDiscovery.ModelWrapper> resolvedParentOpt;
            try {
                resolvedParentOpt = this.resolvedBlockModels.getUnchecked(parent);
            } catch (Exception e) {
                // Possible recursive load, etc.
                ModernFix.LOGGER.error("Error while resolving model '{}'", location, e);
                return Optional.empty();
            }
            if (resolvedParentOpt.isPresent()) {
                wrapper.parent = resolvedParentOpt.get();
            }
        }
        return Optional.of(wrapper);
    }


    private Optional<ClientItem> loadClientItemProperties(Identifier location) {
        if (DEBUG_DYNAMIC_MODEL_LOADING) {
            ModernFix.LOGGER.info("Loading client item '{}'", location);
        }
        var resource = this.resourceManager.getResource(Identifier.fromNamespaceAndPath(location.getNamespace(), "items/" + location.getPath() + ".json"));
        if(resource.isPresent()) {
            try(Reader reader = resource.get().openAsReader()) {
                ClientItem clientItem = ClientItem.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader)).getOrThrow();
                return Optional.of(clientItem);
            } catch(Exception e) {
                ModernFix.LOGGER.error("Failed to load client item {} from '{}'", location, resource.get().sourcePackId(), e);
                return Optional.empty();
            }
        } else {
            ModernFix.LOGGER.warn("Client item '{}' does not exist in any resource packs", location);
            return Optional.empty();
        }
    }

    private Optional<ItemModel> loadItemModel(Identifier location) {
        if (DEBUG_DYNAMIC_MODEL_LOADING) {
            ModernFix.LOGGER.info("Loading item model '{}'", location);
        }
        var override = this.itemStackModelOverrides.get(location);
        if (override != null) {
            return Optional.of(override);
        }
        return this.loadedClientItemProperties.getUnchecked(location).map(clientItem -> {
            var bakingContext = new ItemModel.BakingContext(new DynamicBaker(location::toString), this.entityModelSet, this.materialSet, this.skinRenderCache, this.missingItemModel, clientItem.registrySwapper());
            return clientItem.model().bake(bakingContext);
        });
    }

    LoadingCache<BlockState, Optional<BlockStateModel>> getBlockStateCache() {
        return this.loadedBakedModels;
    }

    /* IntelliJ says these are unused, commenting them for now
    public BlockStateModel getModel(BlockState location) {
        return this.loadedBakedModels.getUnchecked(location).orElse(this.missingModel);
    }

    public ClientItem.Properties getClientItemProperties(Identifier location) {
        return this.loadedClientItemProperties.getUnchecked(location).map(ClientItem::properties).orElse(ClientItem.Properties.DEFAULT);
    }

    public ItemModel getItemModel(Identifier location) {
        return this.loadedItemModels.getUnchecked(location).orElse(this.missingItemModel);
    }

    public BakedModel getStandaloneModel(Identifier location) {
        return this.loadedStandaloneModels.getUnchecked(location).orElse(this.missingModel);
    }

    public void addUnbakedBlockStateOverride(BlockState location, BlockStateModel.Unbaked model) {
        this.unbakedBlockStateModelOverrides.put(location, model);
    }
     */

    private class DynamicBaker implements ModelBaker {
        private final ModelDebugName modelDebugName;

        private DynamicBaker(ModelDebugName modelDebugName) {
            this.modelDebugName = modelDebugName;
        }

        @Override
        public ResolvedModel getModel(Identifier location) {
            return DynamicModelProvider.this.resolvedBlockModels.getUnchecked(location).orElse(DynamicModelProvider.this.resolvedMissingModel);
        }

        @Override
        public BlockModelPart missingBlockModelPart() {
            return null;
        }

        @Override
        public SpriteGetter sprites() {
            return DynamicModelProvider.this.textureGetter;
        }

        @Override
        public PartCache parts() {
            return partCache;
        }

        @Override
        public <T> T compute(SharedOperationKey<T> key) {
            return key.compute(this);
        }
    }

    public static WeakReference<DynamicModelProvider> currentReloadingModelProvider = new WeakReference<>(null);

    public interface ModelManagerExtension {
        DynamicModelProvider mfix$getModelProvider();
    }

    public interface DynamicModelPlugin {
        Optional<UnbakedModel> modifyModelOnLoad(Optional<UnbakedModel> model, Identifier id);
        BlockStateModel.UnbakedRoot modifyBlockModelOnLoad(BlockStateModel.UnbakedRoot model, BlockState state);

        UnbakedModel modifyModelBeforeBake(UnbakedModel model, Identifier id, ModelState state, ModelBaker baker);
        //BakedModel modifyModelAfterBake(BakedModel bakedModel, UnbakedModel model, Identifier id, ModelState state, ModelBaker baker);

        BlockStateModel.UnbakedRoot modifyBlockModelBeforeBake(BlockStateModel.UnbakedRoot model, BlockState state, ModelBaker baker);
        BlockStateModel modifyBlockModelAfterBake(BlockStateModel bakedModel, BlockStateModel.UnbakedRoot unbaked, BlockState state, ModelBaker baker);
    }
}