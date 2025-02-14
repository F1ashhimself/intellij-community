// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.util.indexing.roots;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.impl.libraries.LibraryEx;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.SmartList;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.indexing.roots.kind.IndexableSetSelfDependentOrigin;
import com.intellij.util.indexing.roots.origin.LibrarySelfDependentOriginImpl;
import com.intellij.util.indexing.roots.origin.SdkSelfDependentOriginImpl;
import com.intellij.workspaceModel.ide.WorkspaceModel;
import com.intellij.workspaceModel.ide.impl.legacyBridge.library.LibraryEntityUtils;
import com.intellij.workspaceModel.ide.legacyBridge.ModifiableRootModelBridge;
import com.intellij.workspaceModel.storage.*;
import com.intellij.workspaceModel.storage.bridgeEntities.api.*;
import kotlin.sequences.SequencesKt;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

class WorkspaceModelSnapshot {
  private final ImmutableMap<WorkspaceEntity, Collection<IndexableSetSelfDependentOrigin>> entitiesToOrigins;
  private final LibrariesSnapshot libraries;
  private final SdkSnapshot sdks;

  static WorkspaceModelSnapshot create(@NotNull Project project) {
    EntityStorage entityStorage = WorkspaceModel.getInstance(project).getEntityStorage().getCurrent();
    ModifiableLibrariesSnapshot snapshot = new ModifiableLibrariesSnapshot(MultiMap.createSet(), new HashMap<>());
    Set<SdkId> sdkIds = new HashSet<>();
    for (ModuleEntity entity : SequencesKt.asIterable(entityStorage.entities(ModuleEntity.class))) {
      for (ModuleDependencyItem dependency : entity.getDependencies()) {
        snapshot.addDependency(entity, dependency, entityStorage, project);
        if (dependency instanceof ModuleDependencyItem.SdkDependency) {
          sdkIds.add(SdkId.create((ModuleDependencyItem.SdkDependency)dependency));
        }
      }
    }
    return new WorkspaceModelSnapshot(createBuilder(project, entityStorage),
                                      snapshot.toImmutableSnapshot(),
                                      SdkSnapshot.create(sdkIds));
  }

  private WorkspaceModelSnapshot(@NotNull ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> builder,
                                 @NotNull LibrariesSnapshot librariesSnapshot,
                                 @NotNull SdkSnapshot sdkSnapshot) {
    this(builder.build().asMap(), librariesSnapshot, sdkSnapshot);
  }

  private WorkspaceModelSnapshot(@NotNull ImmutableMap<WorkspaceEntity, Collection<IndexableSetSelfDependentOrigin>> entitiesToOrigins,
                                 @NotNull LibrariesSnapshot librariesSnapshot,
                                 @NotNull SdkSnapshot sdkSnapshot) {
    this.entitiesToOrigins = entitiesToOrigins;
    libraries = librariesSnapshot;
    sdks = sdkSnapshot;
  }

  private record SdkId(String name, String type) {
    @NotNull
    static SdkId create(@NotNull ModuleDependencyItem.SdkDependency dependency) {
      return new SdkId(dependency.getSdkName(), dependency.getSdkType());
    }
  }

  @NotNull
  private static ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> createBuilder(@NotNull Project project,
                                                                                                              @NotNull EntityStorage storage) {
    ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> builder = new ImmutableSetMultimap.Builder<>();
    for (IndexableEntityProvider<? extends WorkspaceEntity> provider : IndexableEntityProvider.EP_NAME.getExtensionList()) {
      if (!(provider instanceof IndexableEntityProvider.ExistingEx<?>)) {
        continue;
      }
      handleProvider((IndexableEntityProvider.ExistingEx<?>)provider, storage, project, builder);
    }
    return builder;
  }

  @NotNull
  Collection<? extends IndexableSetSelfDependentOrigin> getOrigins() {
    List<IndexableSetSelfDependentOrigin> result = new ArrayList<>(libraries.getOrigins());
    result.addAll(sdks.getOrigins());
    for (Collection<IndexableSetSelfDependentOrigin> value : entitiesToOrigins.values()) {
      result.addAll(value);
    }
    return result;
  }

  private static <E extends WorkspaceEntity> void handleProvider(@NotNull IndexableEntityProvider.ExistingEx<E> provider,
                                                                 @NotNull EntityStorage storage,
                                                                 @NotNull Project project,
                                                                 @NotNull ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> entities) {
    Class<E> aClass = provider.getEntityClass();
    for (E entity : SequencesKt.asIterable(storage.entities(aClass))) {
      addOrigins(entities, entity, provider, storage, project);
    }
  }

  @Nullable
  WorkspaceModelSnapshot createChangedIfNeeded(@NotNull VersionedStorageChange storageChange,
                                               @NotNull Project project) {
    EntityStorageSnapshot storage = storageChange.getStorageAfter();
    MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> toAdd = MultiMap.createSet();
    MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> toRemove = MultiMap.createSet();
    for (IndexableEntityProvider<? extends WorkspaceEntity> provider : IndexableEntityProvider.EP_NAME.getExtensionList()) {
      if (provider instanceof IndexableEntityProvider.ExistingEx<?>) {
        handleWorkspaceModelChange(toAdd, toRemove, storageChange, (IndexableEntityProvider.ExistingEx<?>)provider, storage, project);
      }
    }
    LibrariesSnapshot changedLibraries = libraries.createChangedIfNeeded(storageChange, storage, project);
    if (toAdd.isEmpty() && toRemove.isEmpty() && changedLibraries == null) {
      return null;
    }
    ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> copy = new ImmutableSetMultimap.Builder<>();
    for (Map.Entry<WorkspaceEntity, Collection<IndexableSetSelfDependentOrigin>> entry : entitiesToOrigins.entrySet()) {
      WorkspaceEntity entity = entry.getKey();
      Collection<IndexableSetSelfDependentOrigin> add = toAdd.remove(entity);
      Collection<IndexableSetSelfDependentOrigin> remove = toRemove.get(entity);
      if (add == null && remove.isEmpty()) {
        copy.putAll(entity, entry.getValue());
      }
      else {
        Collection<IndexableSetSelfDependentOrigin> origins = new HashSet<>(entry.getValue());
        origins.removeAll(remove);
        if (add != null) {
          origins.addAll(add);
        }
        copy.putAll(entity, origins);
      }
    }
    for (Map.Entry<WorkspaceEntity, Collection<IndexableSetSelfDependentOrigin>> entry : toAdd.entrySet()) {
      copy.putAll(entry.getKey(), entry.getValue());
    }
    return new WorkspaceModelSnapshot(copy, changedLibraries == null ? libraries : changedLibraries, sdks);
  }

  private static <E extends WorkspaceEntity> void handleWorkspaceModelChange(@NotNull MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> toAdd,
                                                                             @NotNull MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> toRemove,
                                                                             @NotNull VersionedStorageChange storageChange,
                                                                             @NotNull IndexableEntityProvider.ExistingEx<E> provider,
                                                                             @NotNull EntityStorageSnapshot storage,
                                                                             @NotNull Project project) {
    List<EntityChange<E>> changes = storageChange.getChanges(provider.getEntityClass());
    for (EntityChange<E> change : changes) {
      if (change instanceof EntityChange.Added<E>) {
        E entity = ((EntityChange.Added<E>)change).getEntity();
        addOrigins(toAdd, entity, provider, storage, project);
      }
      else if (change instanceof EntityChange.Replaced<E>) {
        E oldEntity = Objects.requireNonNull(change.getOldEntity());
        addOrigins(toRemove, oldEntity, provider, storage, project);

        E newEntity = ((EntityChange.Replaced<E>)change).getNewEntity();
        addOrigins(toAdd, newEntity, provider, storage, project);
      }
      else if (change instanceof EntityChange.Removed<E>) {
        E entity = ((EntityChange.Removed<E>)change).getEntity();
        addOrigins(toRemove, entity, provider, storage, project);
      }
      else {
        throw new IllegalStateException("Unexpected change " + change.getClass());
      }
    }
    if (provider instanceof ContentRootIndexableEntityProvider) {
      SourceRootIndexableEntityProvider sourceRootProvider =
        IndexableEntityProvider.EP_NAME.findExtensionOrFail(SourceRootIndexableEntityProvider.class);
      for (EntityChange<E> change : changes) {
        if (change instanceof EntityChange.Replaced<E>) {
          ContentRootEntity oldEntity = Objects.requireNonNull(((EntityChange.Replaced<ContentRootEntity>)change).getOldEntity());
          for (SourceRootEntity sourceRootEntity : oldEntity.getSourceRoots()) {
            addOrigins(toRemove, sourceRootEntity, sourceRootProvider, storage, project);
          }

          ContentRootEntity newEntity = ((EntityChange.Replaced<ContentRootEntity>)change).getNewEntity();
          for (SourceRootEntity sourceRootEntity : newEntity.getSourceRoots()) {
            addOrigins(toAdd, sourceRootEntity, sourceRootProvider, storage, project);
          }
        }
      }
    }
  }

  private static <E extends WorkspaceEntity> void addOrigins(@NotNull ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> entities,
                                                             @NotNull E entity,
                                                             @NotNull IndexableEntityProvider.ExistingEx<E> provider,
                                                             @NotNull EntityStorage storage,
                                                             @NotNull Project project) {
    Collection<? extends IndexableSetSelfDependentOrigin> origins = provider.getExistingEntityIteratorOrigins(entity, storage, project);
    if (!origins.isEmpty()) {
      entities.putAll(entity, origins);
    }
  }

  private static <E extends WorkspaceEntity> void addOrigins(@NotNull MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> entities,
                                                             @NotNull E entity,
                                                             @NotNull IndexableEntityProvider.ExistingEx<E> provider,
                                                             @NotNull EntityStorage storage,
                                                             @NotNull Project project) {
    Collection<? extends IndexableSetSelfDependentOrigin> origins = provider.getExistingEntityIteratorOrigins(entity, storage, project);
    if (!origins.isEmpty()) {
      entities.putValues(entity, origins);
    }
  }

  @Nullable
  WorkspaceModelSnapshot createWithRefreshedEntitiesIfNeeded(@NotNull List<? extends WorkspaceEntity> entities,
                                                             @NotNull Project project) {
    if (entities.isEmpty()) return null;
    EntityStorage storage = WorkspaceModel.getInstance(project).getEntityStorage().getCurrent();
    MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> refreshed = MultiMap.createSet();
    for (IndexableEntityProvider<? extends WorkspaceEntity> provider : IndexableEntityProvider.EP_NAME.getExtensionList()) {
      if (provider instanceof IndexableEntityProvider.ExistingEx<?>) {
        handleEntitiesRefresh((IndexableEntityProvider.ExistingEx<?>)provider, entities, project, storage, refreshed);
      }
    }
    if (refreshed.isEmpty()) return null;
    ImmutableSetMultimap.Builder<WorkspaceEntity, IndexableSetSelfDependentOrigin> result = new ImmutableSetMultimap.Builder<>();
    for (Map.Entry<WorkspaceEntity, Collection<IndexableSetSelfDependentOrigin>> entry : entitiesToOrigins.entrySet()) {
      if (refreshed.containsKey(entry.getKey())) {
        result.putAll(entry.getKey(), refreshed.get(entry.getKey()));
      }
      else {
        result.putAll(entry.getKey(), entry.getValue());
      }
    }
    return new WorkspaceModelSnapshot(result, libraries, sdks);
  }

  private static <E extends WorkspaceEntity> void handleEntitiesRefresh(@NotNull IndexableEntityProvider.ExistingEx<E> provider,
                                                                        @NotNull List<? extends WorkspaceEntity> entities,
                                                                        @NotNull Project project,
                                                                        @NotNull EntityStorage storage,
                                                                        @NotNull MultiMap<WorkspaceEntity, IndexableSetSelfDependentOrigin> entitiesMap) {
    Class<E> aClass = provider.getEntityClass();
    for (WorkspaceEntity entity : entities) {
      if (aClass.isInstance(entity)) {
        //noinspection unchecked
        Collection<? extends IndexableSetSelfDependentOrigin> origins =
          provider.getExistingEntityIteratorOrigins((E)entity, storage, project);
        entitiesMap.putValues(entity, origins);
      }
    }
  }

  @Nullable
  WorkspaceModelSnapshot referencedLibraryAdded(@NotNull Library library) {
    ModifiableLibrariesSnapshot snapshot = ModifiableLibrariesSnapshot.create(libraries);
    snapshot.addLibrary(library);
    return new WorkspaceModelSnapshot(entitiesToOrigins, snapshot.toImmutableSnapshot(), sdks);
  }

  @Nullable
  WorkspaceModelSnapshot referencedLibraryChanged(@NotNull Library library) {
    return referencedLibraryAdded(library);
  }

  @Nullable
  WorkspaceModelSnapshot referencedLibraryRemoved(@NotNull Library library) {
    ModifiableLibrariesSnapshot snapshot = ModifiableLibrariesSnapshot.create(libraries);
    snapshot.removeLibrary(LibraryEntityUtils.findLibraryId(library));
    return new WorkspaceModelSnapshot(entitiesToOrigins, snapshot.toImmutableSnapshot(), sdks);
  }

  @Nullable
  WorkspaceModelSnapshot addedDependencyOn(@NotNull Sdk sdk) {
    ModifiableSdkSnapshot snapshot = ModifiableSdkSnapshot.create(sdks);
    snapshot.addSdk(sdk);
    return new WorkspaceModelSnapshot(entitiesToOrigins, libraries, snapshot.toImmutableSnapshot());
  }

  @Nullable
  WorkspaceModelSnapshot removedDependencyOn(@NotNull Sdk sdk) {
    ModifiableSdkSnapshot snapshot = ModifiableSdkSnapshot.create(sdks);
    snapshot.removeSdk(sdk);
    return new WorkspaceModelSnapshot(entitiesToOrigins, libraries, snapshot.toImmutableSnapshot());
  }

  @Nullable
  WorkspaceModelSnapshot referencedSdkAdded(@NotNull Sdk sdk) {
    return addedDependencyOn(sdk);
  }

  @Nullable
  WorkspaceModelSnapshot referencedSdkChanged(@NotNull Sdk sdk) {
    ModifiableSdkSnapshot snapshot = ModifiableSdkSnapshot.create(sdks);
    snapshot.updateSdk(sdk);
    return new WorkspaceModelSnapshot(entitiesToOrigins, libraries, snapshot.toImmutableSnapshot());
  }

  @Nullable
  WorkspaceModelSnapshot referencedSdkRemoved(@NotNull Sdk sdk) {
    return removedDependencyOn(sdk);
  }

  private static class LibrariesSnapshot {//todo[lene] write test for library rename
    private final ImmutableMap<LibraryId, Collection<ModuleEntity>> dependencies;
    private final ImmutableMap<LibraryId, IndexableSetSelfDependentOrigin> origins;


    private LibrariesSnapshot(ImmutableMap<LibraryId, Collection<ModuleEntity>> dependencies,
                              ImmutableMap<LibraryId, IndexableSetSelfDependentOrigin> origins) {
      this.dependencies = dependencies;
      this.origins = origins;
    }

    @NotNull
    private Collection<? extends IndexableSetSelfDependentOrigin> getOrigins() {
      return origins.values();
    }

    private LibrariesSnapshot createChangedIfNeeded(@NotNull VersionedStorageChange storageChange,
                                                    EntityStorageSnapshot storage,
                                                    Project project) {
      List<EntityChange<ModuleEntity>> moduleChanges = storageChange.getChanges(ModuleEntity.class);
      List<EntityChange<LibraryEntity>> libraryChanges = storageChange.getChanges(LibraryEntity.class);

      if (moduleChanges.isEmpty() && libraryChanges.isEmpty()) return null;

      ModifiableLibrariesSnapshot snapshot = ModifiableLibrariesSnapshot.create(this);

      for (EntityChange<ModuleEntity> change : moduleChanges) {
        if (change instanceof EntityChange.Added<ModuleEntity>) {
          snapshot.addDependencies(((EntityChange.Added<ModuleEntity>)change).getEntity(), storage, project);
        }
        else if (change instanceof EntityChange.Replaced<ModuleEntity>) {
          snapshot.changeDependencies(((EntityChange.Replaced<ModuleEntity>)change).getOldEntity(),
                                      ((EntityChange.Replaced<ModuleEntity>)change).getNewEntity(), storage, project);
        }
        else if (change instanceof EntityChange.Removed<ModuleEntity>) {
          snapshot.removeDependencies(((EntityChange.Removed<ModuleEntity>)change).getEntity());
        }
        else {
          throw new IllegalStateException("Unexpected change " + change.getClass());
        }
      }

      for (EntityChange<LibraryEntity> change : libraryChanges) {
        if (change instanceof EntityChange.Added<LibraryEntity>) {
          //ignore
        }
        else if (change instanceof EntityChange.Replaced<LibraryEntity>) {
          snapshot.updateLibrary(((EntityChange.Replaced<LibraryEntity>)change).getOldEntity(),
                                 ((EntityChange.Replaced<LibraryEntity>)change).getNewEntity(), storage);
        }
        else if (change instanceof EntityChange.Removed<LibraryEntity>) {
          snapshot.removeLibrary(((EntityChange.Removed<LibraryEntity>)change).getEntity().getPersistentId());
        }
        else {
          throw new IllegalStateException("Unexpected change " + change.getClass());
        }
      }

      return snapshot.toImmutableSnapshot();
    }
  }

  private static class ModifiableLibrariesSnapshot {
    private final MultiMap<LibraryId, ModuleEntity> dependencies;
    private final Map<LibraryId, IndexableSetSelfDependentOrigin> origins;

    private ModifiableLibrariesSnapshot(MultiMap<LibraryId, ModuleEntity> dependencies,
                                        Map<LibraryId, IndexableSetSelfDependentOrigin> origins) {
      this.dependencies = dependencies;
      this.origins = origins;
    }

    @NotNull
    private static ModifiableLibrariesSnapshot create(@NotNull LibrariesSnapshot snapshot) {
      MultiMap<LibraryId, ModuleEntity> dependencies = new MultiMap<>();
      for (Map.Entry<LibraryId, Collection<ModuleEntity>> entry : snapshot.dependencies.entrySet()) {
        dependencies.put(entry.getKey(), entry.getValue());
      }
      return new ModifiableLibrariesSnapshot(dependencies, new HashMap<>(snapshot.origins));
    }

    private void addDependencies(@NotNull ModuleEntity entity, @NotNull EntityStorage storage, Project project) {
      for (ModuleDependencyItem dependency : entity.getDependencies()) {
        addDependency(entity, dependency, storage, project);
      }
    }

    private void addDependency(@NotNull ModuleEntity entity,
                               @NotNull ModuleDependencyItem dependency,
                               @NotNull EntityStorage storage,
                               @NotNull Project project) {
      if (dependency instanceof ModuleDependencyItem.Exportable.LibraryDependency) {
        @NotNull LibraryId libraryId = ((ModuleDependencyItem.Exportable.LibraryDependency)dependency).getLibrary();
        dependencies.putValue(libraryId, entity);
        if (!origins.containsKey(libraryId)) {
          IndexableSetSelfDependentOrigin origin = createLibraryOrigin(libraryId, storage, project);
          if (origin != null) {
            origins.put(libraryId, origin);
          }
        }
      }
    }

    private void removeDependencies(ModuleEntity entity) {
      for (ModuleDependencyItem dependency : entity.getDependencies()) {
        if (dependency instanceof ModuleDependencyItem.Exportable.LibraryDependency) {
          LibraryId libraryId = ((ModuleDependencyItem.Exportable.LibraryDependency)dependency).getLibrary();
          dependencies.remove(libraryId, entity);
          if (!dependencies.containsKey(libraryId)) {
            origins.remove(libraryId);
          }
        }
      }
    }

    private void changeDependencies(@NotNull ModuleEntity oldEntity,
                                    @NotNull ModuleEntity newEntity,
                                    @NotNull EntityStorageSnapshot storage,
                                    @NotNull Project project) {
      SmartList<LibraryId> idsToRemove = new SmartList<>();
      for (ModuleDependencyItem dependency : oldEntity.getDependencies()) {
        if (dependency instanceof ModuleDependencyItem.Exportable.LibraryDependency) {
          LibraryId libraryId = ((ModuleDependencyItem.Exportable.LibraryDependency)dependency).getLibrary();
          dependencies.remove(libraryId, oldEntity);
          idsToRemove.add(libraryId);
        }
      }

      for (ModuleDependencyItem dependency : newEntity.getDependencies()) {
        if (dependency instanceof ModuleDependencyItem.Exportable.LibraryDependency) {
          LibraryId libraryId = ((ModuleDependencyItem.Exportable.LibraryDependency)dependency).getLibrary();
          dependencies.putValue(libraryId, newEntity);
          idsToRemove.remove(libraryId);
          if (!origins.containsKey(libraryId)) {
            IndexableSetSelfDependentOrigin origin = createLibraryOrigin(libraryId, storage, project);
            if (origin != null) {
              origins.put(libraryId, origin);
            }
          }
        }
      }

      for (LibraryId id : idsToRemove) {
        if (!dependencies.containsKey(id)) {
          origins.remove(id);
        }
      }
    }

    private void addLibrary(@NotNull Library library) {
      origins.put(LibraryEntityUtils.findLibraryId(library), createLibraryOrigin(library));
    }

    private void updateLibrary(@NotNull LibraryEntity oldEntity,
                               @NotNull LibraryEntity newEntity,
                               @NotNull EntityStorageSnapshot storage) {
      //LibraryId oldId = oldEntity.getPersistentId();
      LibraryId newId = newEntity.getPersistentId();
      //if (!oldId.equals(newId)) {
      //  todo[lene] test rename of a lib
      //}
      IndexableSetSelfDependentOrigin origin = createLibraryOrigin(newEntity, storage);
      if (origin != null) {
        origins.put(newId, origin);
      }
    }

    private void removeLibrary(@NotNull LibraryId libraryId) {
      dependencies.remove(libraryId);
      origins.remove(libraryId);
    }

    @NotNull
    private LibrariesSnapshot toImmutableSnapshot() {
      return new LibrariesSnapshot(ImmutableMap.copyOf(dependencies.entrySet()), ImmutableMap.copyOf(origins));
    }

    @Nullable
    private static IndexableSetSelfDependentOrigin createLibraryOrigin(@NotNull LibraryId libraryId,
                                                                       @NotNull EntityStorage storage,
                                                                       @NotNull Project project) {
      Library library = LibraryEntityUtils.findLibraryBridge(libraryId, storage, project);
      return createLibraryOrigin(library);
    }

    @Nullable
    private static IndexableSetSelfDependentOrigin createLibraryOrigin(@NotNull LibraryEntity libraryEntity,
                                                                       @NotNull EntityStorage storage) {
      Library library = LibraryEntityUtils.findLibraryBridge(libraryEntity, storage);
      return createLibraryOrigin(library);
    }

    @Contract("null->null;!null -> !null")
    private static LibrarySelfDependentOriginImpl createLibraryOrigin(Library library) {
      if (library == null) {
        return null;
      }
      List<VirtualFile> classFiles = LibraryIndexableFilesIteratorImpl.Companion.collectFiles(library, OrderRootType.CLASSES, null);
      List<VirtualFile> sourceFiles = LibraryIndexableFilesIteratorImpl.Companion.collectFiles(library, OrderRootType.SOURCES, null);
      return new LibrarySelfDependentOriginImpl(classFiles, sourceFiles, Arrays.asList(((LibraryEx)library).getExcludedRoots()));
    }
  }

  private static class SdkSnapshot {
    private final ImmutableMap<Sdk, IndexableSetSelfDependentOrigin> origins;

    private SdkSnapshot(ImmutableMap<Sdk, IndexableSetSelfDependentOrigin> origins) {
      this.origins = origins;
    }

    private Collection<? extends IndexableSetSelfDependentOrigin> getOrigins() {
      return origins.values();
    }

    private static SdkSnapshot create(@NotNull Collection<SdkId> sdkIds) {
      ImmutableMap.Builder<Sdk, IndexableSetSelfDependentOrigin> builder = ImmutableMap.builder();
      for (SdkId id : sdkIds) {
        Sdk sdk = ModifiableSdkSnapshot.findSdk(id);
        if (sdk != null) {
          builder.put(sdk, ModifiableSdkSnapshot.createSdkOrigin(sdk));
        }
      }
      return new SdkSnapshot(builder.build());
    }
  }

  private static class ModifiableSdkSnapshot {
    private final Map<Sdk, IndexableSetSelfDependentOrigin> origins;

    private ModifiableSdkSnapshot(Map<Sdk, IndexableSetSelfDependentOrigin> origins) {
      this.origins = origins;
    }

    @NotNull
    private static WorkspaceModelSnapshot.ModifiableSdkSnapshot create(@NotNull SdkSnapshot snapshot) {
      return new WorkspaceModelSnapshot.ModifiableSdkSnapshot(new HashMap<>(snapshot.origins));
    }

    private void addSdk(@NotNull Sdk sdk) {
      if (!origins.containsKey(sdk)) {
        IndexableSetSelfDependentOrigin origin = createSdkOrigin(sdk);
        origins.put(sdk, origin);
      }
    }

    private void updateSdk(@NotNull Sdk sdk) {
      //todo[lene] write a test
    }

    private void removeSdk(@NotNull Sdk sdk) {
      origins.remove(sdk);
    }

    private SdkSnapshot toImmutableSnapshot() {
      return new SdkSnapshot(ImmutableMap.copyOf(origins));
    }

    @NotNull
    private static IndexableSetSelfDependentOrigin createSdkOrigin(@NotNull Sdk sdk) {
      Collection<VirtualFile> rootsToIndex = SdkIndexableFilesIteratorImpl.Companion.getRootsToIndex(sdk);
      return new SdkSelfDependentOriginImpl(sdk, rootsToIndex);
    }

    @Nullable
    private static Sdk findSdk(@NotNull SdkId sdkId) {
      return ModifiableRootModelBridge.findSdk(sdkId.name, sdkId.type);
    }
  }
}