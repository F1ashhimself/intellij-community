/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.diff.impl.patch.formove;

import com.intellij.history.Label;
import com.intellij.history.LocalHistory;
import com.intellij.history.LocalHistoryException;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.diff.impl.patch.ApplyPatchContext;
import com.intellij.openapi.diff.impl.patch.ApplyPatchStatus;
import com.intellij.openapi.diff.impl.patch.FilePatch;
import com.intellij.openapi.diff.impl.patch.PatchUtil;
import com.intellij.openapi.diff.impl.patch.apply.ApplyFilePatchBase;
import com.intellij.openapi.diff.impl.patch.formove.PathsVerifier.PatchAndFile;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vcs.*;
import com.intellij.openapi.vcs.changes.*;
import com.intellij.openapi.vcs.changes.patch.ApplyPatchAction;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcsUtil.VcsImplUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.CalledInAwt;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.intellij.util.ObjectUtils.chooseNotNull;

/**
 * for patches. for shelve.
 */
public class PatchApplier<BinaryType extends FilePatch> {
  private static final Logger LOG = Logger.getInstance(PatchApplier.class);
  private final Project myProject;
  private final VirtualFile myBaseDirectory;
  @NotNull private final List<FilePatch> myPatches;
  private final CustomBinaryPatchApplier<BinaryType> myCustomForBinaries;
  private final CommitContext myCommitContext;
  @Nullable private final LocalChangeList myTargetChangeList;
  @NotNull private final List<FilePatch> myRemainingPatches;
  @NotNull private final List<FilePatch> myFailedPatches;
  private final PathsVerifier<BinaryType> myVerifier;

  private final boolean myReverseConflict;
  @Nullable private final String myLeftConflictPanelTitle;
  @Nullable private final String myRightConflictPanelTitle;

  public PatchApplier(@NotNull Project project,
                      @NotNull VirtualFile baseDirectory,
                      @NotNull List<FilePatch> patches,
                      @Nullable LocalChangeList targetChangeList,
                      @Nullable CustomBinaryPatchApplier<BinaryType> customForBinaries,
                      @Nullable CommitContext commitContext,
                      boolean reverseConflict,
                      @Nullable String leftConflictPanelTitle,
                      @Nullable String rightConflictPanelTitle) {
    myProject = project;
    myBaseDirectory = baseDirectory;
    myPatches = patches;
    myTargetChangeList = targetChangeList;
    myCustomForBinaries = customForBinaries;
    myCommitContext = commitContext;
    myReverseConflict = reverseConflict;
    myLeftConflictPanelTitle = leftConflictPanelTitle;
    myRightConflictPanelTitle = rightConflictPanelTitle;
    myRemainingPatches = new ArrayList<>();
    myFailedPatches = new ArrayList<>();
    myVerifier = new PathsVerifier<>(myProject, myBaseDirectory, myPatches);
  }

  public void setIgnoreContentRootsCheck() {
    myVerifier.setIgnoreContentRootsCheck(true);
  }

  public PatchApplier(@NotNull Project project,
                      @NotNull VirtualFile baseDirectory,
                      @NotNull List<FilePatch> patches,
                      @Nullable LocalChangeList targetChangeList,
                      @Nullable CustomBinaryPatchApplier<BinaryType> customForBinaries,
                      @Nullable CommitContext commitContext) {
    this(project, baseDirectory, patches, targetChangeList, customForBinaries, commitContext, false, null, null);
  }

  @NotNull
  public List<FilePatch> getPatches() {
    return myPatches;
  }

  @NotNull
  public List<FilePatch> getRemainingPatches() {
    return myRemainingPatches;
  }

  @NotNull
  private Collection<FilePatch> getFailedPatches() {
    return myFailedPatches;
  }

  @NotNull
  private List<BinaryType> getBinaryPatches() {
    return ContainerUtil.mapNotNull(myVerifier.getBinaryPatches(),
                                    patchInfo -> (BinaryType)patchInfo.getApplyPatch().getPatch());
  }

  @CalledInAwt
  public void execute() {
    execute(true, false);
  }

  private class ApplyPatchTask {
    private final boolean myShowNotification;
    private final boolean mySystemOperation;
    private VcsShowConfirmationOption.Value myAddconfirmationvalue;
    private VcsShowConfirmationOption.Value myDeleteconfirmationvalue;

    public ApplyPatchTask(final boolean showNotification, boolean systemOperation) {
      myShowNotification = showNotification;
      mySystemOperation = systemOperation;
    }

    @CalledInAwt
    public ApplyPatchStatus run() {
      myRemainingPatches.addAll(myPatches);

      final ApplyPatchStatus patchStatus = nonWriteActionPreCheck();
      final Label beforeLabel = LocalHistory.getInstance().putSystemLabel(myProject, "Before patch");
      final TriggerAdditionOrDeletion trigger = new TriggerAdditionOrDeletion(myProject);
      final ApplyPatchStatus applyStatus = getApplyPatchStatus(trigger);
      ApplyPatchStatus status = ApplyPatchStatus.SUCCESS.equals(patchStatus) ? applyStatus :
                                ApplyPatchStatus.and(patchStatus, applyStatus);
      // listeners finished, all 'legal' file additions/deletions with VCS are done
      trigger.processIt();
      LocalHistory.getInstance().putSystemLabel(myProject, "After patch"); // insert a label to be visible in local history dialog
      if (status == ApplyPatchStatus.FAILURE) {
        suggestRollback(myProject, Collections.singletonList(PatchApplier.this), beforeLabel);
      }
      else if (status == ApplyPatchStatus.ABORT) {
        rollbackUnderProgress(myProject, myProject.getBaseDir(), beforeLabel);
      }
      if (myShowNotification || !ApplyPatchStatus.SUCCESS.equals(status)) {
        showApplyStatus(myProject, status);
      }
      refreshFiles(trigger.getAffected());

      return status;
    }

    @CalledInAwt
    @NotNull
    private ApplyPatchStatus getApplyPatchStatus(@NotNull final TriggerAdditionOrDeletion trigger) {
      final Ref<ApplyPatchStatus> refStatus = Ref.create(null);
      try {
        setConfirmationToDefault();
        CommandProcessor.getInstance().executeCommand(myProject, () -> {
          //consider pre-check status only if not successful, otherwise we could not detect already applied status
          refStatus.set(createFiles());

          addSkippedItems(trigger);
          trigger.prepare();
          refStatus.set(ApplyPatchStatus.and(refStatus.get(), executeWritable()));
        }, VcsBundle.message("patch.apply.command"), null);
      }
      finally {
        returnConfirmationBack();
        VcsFileListenerContextHelper.getInstance(myProject).clearContext();
      }
      final ApplyPatchStatus status = refStatus.get();
      return status == null ? ApplyPatchStatus.ALREADY_APPLIED : status;
    }

    private void returnConfirmationBack() {
      if (mySystemOperation) {
        final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
        final VcsShowConfirmationOption addConfirmation = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, null);
        addConfirmation.setValue(myAddconfirmationvalue);
        final VcsShowConfirmationOption deleteConfirmation = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, null);
        deleteConfirmation.setValue(myDeleteconfirmationvalue);
      }
    }

    private void setConfirmationToDefault() {
      if (mySystemOperation) {
        final ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(myProject);
        final VcsShowConfirmationOption addConfirmation = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.ADD, null);
        myAddconfirmationvalue = addConfirmation.getValue();
        addConfirmation.setValue(VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY);

        final VcsShowConfirmationOption deleteConfirmation = vcsManager.getStandardConfirmation(VcsConfiguration.StandardConfirmation.REMOVE, null);
        myDeleteconfirmationvalue = deleteConfirmation.getValue();
        deleteConfirmation.setValue(VcsShowConfirmationOption.Value.DO_ACTION_SILENTLY);
      }
    }
  }

  @CalledInAwt
  public ApplyPatchStatus execute(boolean showSuccessNotification, boolean silentAddDelete) {
    return new ApplyPatchTask(showSuccessNotification, silentAddDelete).run();
  }

  @CalledInAwt
  public static ApplyPatchStatus executePatchGroup(final Collection<PatchApplier> group, final LocalChangeList localChangeList) {
    if (group.isEmpty()) return ApplyPatchStatus.SUCCESS; //?
    final Project project = group.iterator().next().myProject;

    ApplyPatchStatus result = ApplyPatchStatus.SUCCESS;
    for (PatchApplier patchApplier : group) {
      result = ApplyPatchStatus.and(result, patchApplier.nonWriteActionPreCheck());
    }
    final Label beforeLabel = LocalHistory.getInstance().putSystemLabel(project, "Before patch");
    final TriggerAdditionOrDeletion trigger = new TriggerAdditionOrDeletion(project);
    final Ref<ApplyPatchStatus> refStatus = new Ref<>(result);
    try {
      CommandProcessor.getInstance().executeCommand(project, () -> {
        for (PatchApplier applier : group) {
          refStatus.set(ApplyPatchStatus.and(refStatus.get(), applier.createFiles()));
          applier.addSkippedItems(trigger);
        }
        trigger.prepare();
        if (refStatus.get() == ApplyPatchStatus.SUCCESS) {
          // all pre-check results are valuable only if not successful; actual status we can receive after executeWritable
          refStatus.set(null);
        }
        for (PatchApplier applier : group) {
          refStatus.set(ApplyPatchStatus.and(refStatus.get(), applier.executeWritable()));
          if (refStatus.get() == ApplyPatchStatus.ABORT) break;
        }
      }, VcsBundle.message("patch.apply.command"), null);
    } finally {
      VcsFileListenerContextHelper.getInstance(project).clearContext();
      LocalHistory.getInstance().putSystemLabel(project, "After patch");
    }
    result =  refStatus.get();
    result = result == null ? ApplyPatchStatus.FAILURE : result;

    trigger.processIt();
    final Set<FilePath> directlyAffected = new HashSet<>();
    final Set<VirtualFile> indirectlyAffected = new HashSet<>();
    for (PatchApplier applier : group) {
      directlyAffected.addAll(applier.getDirectlyAffected());
      indirectlyAffected.addAll(applier.getIndirectlyAffected());
    }
    directlyAffected.addAll(trigger.getAffected());
    refreshPassedFilesAndMoveToChangelist(project, directlyAffected, indirectlyAffected, localChangeList);
    if (result == ApplyPatchStatus.FAILURE) {
      suggestRollback(project, group, beforeLabel);
    }
    else if (result == ApplyPatchStatus.ABORT) {
      rollbackUnderProgress(project, project.getBaseDir(), beforeLabel);
    }
    showApplyStatus(project, result);
    return result;
  }

  private static void suggestRollback(@NotNull Project project, @NotNull Collection<PatchApplier> group, @NotNull Label beforeLabel) {
    Collection<FilePatch> allFailed = ContainerUtil.concat(group, applier -> applier.getFailedPatches());
    boolean shouldInformAboutBinaries = ContainerUtil.exists(group, applier -> !applier.getBinaryPatches().isEmpty());
    List<FilePath> filePaths = ContainerUtil.map(allFailed, filePatch -> {
      return VcsUtil.getFilePath(chooseNotNull(filePatch.getAfterName(), filePatch.getBeforeName()));
    });

    final UndoApplyPatchDialog undoApplyPatchDialog = new UndoApplyPatchDialog(project, filePaths, shouldInformAboutBinaries);
    if (undoApplyPatchDialog.showAndGet()) {
      rollbackUnderProgress(project, project.getBaseDir(), beforeLabel);
    }
  }

  private static void rollbackUnderProgress(@NotNull final Project project,
                                            @NotNull final VirtualFile virtualFile,
                                            @NotNull final Label labelToRevert) {
    ProgressManager.getInstance().runProcessWithProgressSynchronously(() -> {
      try {
        labelToRevert.revert(project, virtualFile);
        VcsNotifier.getInstance(project)
          .notifyImportantWarning("Apply Patch Aborted", "All files changed during apply patch action were rolled back");
      }
      catch (LocalHistoryException e) {
        VcsNotifier.getInstance(project)
          .notifyImportantWarning("Rollback Failed", String.format("Try using local history dialog for %s and perform revert manually.",
                                                                   virtualFile.getName()));
      }
    }, "Rollback Applied Changes...", true, project);
  }


  private void addSkippedItems(final TriggerAdditionOrDeletion trigger) {
    trigger.addExisting(myVerifier.getToBeAdded());
    trigger.addDeleted(myVerifier.getToBeDeleted());
  }

  @NotNull
  private ApplyPatchStatus nonWriteActionPreCheck() {
    final List<FilePatch> failedPreCheck = myVerifier.nonWriteActionPreCheck();
    myFailedPatches.addAll(failedPreCheck);
    myPatches.removeAll(failedPreCheck);
    final List<FilePatch> skipped = myVerifier.getSkipped();
    final boolean applyAll = skipped.isEmpty();
    myPatches.removeAll(skipped);
    if (!failedPreCheck.isEmpty()) return ApplyPatchStatus.FAILURE;
    return applyAll
           ? ApplyPatchStatus.SUCCESS
           : ((skipped.size() == myPatches.size()) ? ApplyPatchStatus.ALREADY_APPLIED : ApplyPatchStatus.PARTIAL);
  }

  @Nullable
  private ApplyPatchStatus executeWritable() {
    final ReadonlyStatusHandler.OperationStatus readOnlyFilesStatus = getReadOnlyFilesStatus(myVerifier.getWritableFiles());
    if (readOnlyFilesStatus.hasReadonlyFiles()) {
      showError(myProject, readOnlyFilesStatus.getReadonlyFilesMessage());
      return ApplyPatchStatus.ABORT;
    }
    myFailedPatches.addAll(myVerifier.filterBadFileTypePatches());
    ApplyPatchStatus result = myFailedPatches.isEmpty() ? null : ApplyPatchStatus.FAILURE;
    final List<PatchAndFile> textPatches = myVerifier.getTextPatches();
    try {
      markInternalOperation(textPatches, true);
      return ApplyPatchStatus.and(result, actualApply(textPatches, myVerifier.getBinaryPatches(), myCommitContext));
    }
    finally {
      markInternalOperation(textPatches, false);
    }
  }

  @NotNull
  private ApplyPatchStatus createFiles() {
    final Application application = ApplicationManager.getApplication();
    Boolean isSuccess = application.runWriteAction((Computable<Boolean>)() -> {
      final List<FilePatch> filePatches = myVerifier.execute();
      myFailedPatches.addAll(filePatches);
      myPatches.removeAll(filePatches);
      return myFailedPatches.isEmpty();
    });
    return isSuccess ? ApplyPatchStatus.SUCCESS : ApplyPatchStatus.FAILURE;
  }

  private static void markInternalOperation(List<PatchAndFile> textPatches, boolean set) {
    for (PatchAndFile patch : textPatches) {
      ChangesUtil.markInternalOperation(patch.getFile(), set);
    }
  }

  @CalledInAwt
  private void refreshFiles(final Collection<FilePath> additionalDirectly) {
    final List<FilePath> directlyAffected = getDirectlyAffected();
    final List<VirtualFile> indirectlyAffected = getIndirectlyAffected();
    directlyAffected.addAll(additionalDirectly);

    refreshPassedFilesAndMoveToChangelist(myProject, directlyAffected, indirectlyAffected, myTargetChangeList);
  }

  private List<FilePath> getDirectlyAffected() {
    return myVerifier.getDirectlyAffected();
  }

  private List<VirtualFile> getIndirectlyAffected() {
    return myVerifier.getAllAffected();
  }

  @CalledInAwt
  private static void refreshPassedFilesAndMoveToChangelist(@NotNull final Project project,
                                                           @NotNull Collection<FilePath> directlyAffected,
                                                           @NotNull Collection<VirtualFile> indirectlyAffected,
                                                           @Nullable LocalChangeList targetChangeList) {
    final LocalFileSystem lfs = LocalFileSystem.getInstance();
    for (FilePath filePath : directlyAffected) {
      lfs.refreshAndFindFileByIoFile(filePath.getIOFile());
    }
    if (project.isDisposed()) return;

    final ChangeListManager changeListManager = ChangeListManager.getInstance(project);
    if (!directlyAffected.isEmpty() && targetChangeList != null &&
        !changeListManager.getDefaultListName().equals(targetChangeList.getName())) {
      changeListManager.invokeAfterUpdate(() -> movePathsToChangeList(changeListManager, directlyAffected, targetChangeList),
                                          InvokeAfterUpdateMode.SYNCHRONOUS_CANCELLABLE,
                                          VcsBundle.message("change.lists.manager.move.changes.to.list"),
                                          vcsDirtyScopeManager -> markDirty(vcsDirtyScopeManager, directlyAffected, indirectlyAffected),
                                          null);
    }
    else {
      markDirty(VcsDirtyScopeManager.getInstance(project), directlyAffected, indirectlyAffected);
    }
  }

  private static void movePathsToChangeList(@NotNull ChangeListManager changeListManager,
                                            @NotNull Collection<FilePath> directlyAffected,
                                            @Nullable LocalChangeList targetChangeList) {
    List<Change> changes = ContainerUtil.mapNotNull(directlyAffected, changeListManager::getChange);
    changeListManager.moveChangesTo(targetChangeList, ArrayUtil.toObjectArray(changes, Change.class));
  }

  private static void markDirty(@NotNull VcsDirtyScopeManager vcsDirtyScopeManager,
                                @NotNull Collection<FilePath> directlyAffected,
                                @NotNull Collection<VirtualFile> indirectlyAffected) {
    vcsDirtyScopeManager.filePathsDirty(directlyAffected, null);
    vcsDirtyScopeManager.filesDirty(indirectlyAffected, null);
  }

  @Nullable
  private ApplyPatchStatus actualApply(final List<PatchAndFile> textPatches,
                                       final List<PatchAndFile> binaryPatches,
                                       final CommitContext commitContext) {
    final ApplyPatchContext context = new ApplyPatchContext(myBaseDirectory, 0, true, true);
    ApplyPatchStatus status;

    try {
      status = applyList(textPatches, context, null, commitContext);

      if (status == ApplyPatchStatus.ABORT) return status;

      if (myCustomForBinaries == null) {
        status = applyList(binaryPatches, context, status, commitContext);
      }
      else {
        ApplyPatchStatus patchStatus = myCustomForBinaries.apply(binaryPatches);
        final List<FilePatch> appliedPatches = myCustomForBinaries.getAppliedPatches();
        moveForCustomBinaries(binaryPatches, appliedPatches);

        status = ApplyPatchStatus.and(status, patchStatus);
        myRemainingPatches.removeAll(appliedPatches);
      }
    }
    catch (IOException e) {
      showError(myProject, e.getMessage());
      return ApplyPatchStatus.ABORT;
    }
    return status;
  }

  private void moveForCustomBinaries(final List<PatchAndFile> patches,
                                     final List<FilePatch> appliedPatches) throws IOException {
    for (PatchAndFile patch : patches) {
      if (appliedPatches.contains(patch.getApplyPatch().getPatch())) {
        myVerifier.doMoveIfNeeded(patch.getFile());
      }
    }
  }

  private ApplyPatchStatus applyList(final List<PatchAndFile> patches,
                                     final ApplyPatchContext context,
                                     ApplyPatchStatus status,
                                     CommitContext commiContext) throws IOException {
    for (PatchAndFile patch : patches) {
      ApplyFilePatchBase<?> applyFilePatch = patch.getApplyPatch();
      ApplyPatchStatus patchStatus = ApplyPatchAction.applyContent(myProject, applyFilePatch, context, patch.getFile(), commiContext,
                                                                   myReverseConflict, myLeftConflictPanelTitle, myRightConflictPanelTitle);
      if (patchStatus == ApplyPatchStatus.SUCCESS || patchStatus == ApplyPatchStatus.ALREADY_APPLIED) {
        applyAdditionalPatchData(patch.getFile(), applyFilePatch.getPatch());
      }
      if (patchStatus == ApplyPatchStatus.ABORT) return patchStatus;
      status = ApplyPatchStatus.and(status, patchStatus);
      if (patchStatus == ApplyPatchStatus.FAILURE) {
        myFailedPatches.add(applyFilePatch.getPatch());
        continue;
      }
      if (patchStatus != ApplyPatchStatus.SKIP) {
        myVerifier.doMoveIfNeeded(patch.getFile());
        myRemainingPatches.remove(applyFilePatch.getPatch());
      }
    }
    return status;
  }

  private static <V extends FilePatch> void applyAdditionalPatchData(@NotNull VirtualFile fileToApplyData, @NotNull V filePatch) {
    int newFileMode = filePatch.getNewFileMode();
    File file = VfsUtilCore.virtualToIoFile(fileToApplyData);
    if (newFileMode == PatchUtil.EXECUTABLE_FILE_MODE || newFileMode == PatchUtil.REGULAR_FILE_MODE) {
      try {
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(newFileMode == PatchUtil.EXECUTABLE_FILE_MODE);
      }
      catch (Exception e) {
        LOG.warn("Can't change file mode for " + fileToApplyData.getPresentableName());
      }
    }
  }

  private static void showApplyStatus(@NotNull Project project, final ApplyPatchStatus status) {
    VcsNotifier vcsNotifier = VcsNotifier.getInstance(project);
    if (status == ApplyPatchStatus.ALREADY_APPLIED) {
      vcsNotifier.notifyMinorInfo(VcsBundle.message("patch.apply.dialog.title"), VcsBundle.message("patch.apply.already.applied"));
    }
    else if (status == ApplyPatchStatus.PARTIAL) {
      vcsNotifier.notifyMinorInfo(VcsBundle.message("patch.apply.dialog.title"), VcsBundle.message("patch.apply.partially.applied"));
    }
    else if (status == ApplyPatchStatus.SUCCESS) {
      vcsNotifier.notifySuccess(VcsBundle.message("patch.apply.success.applied.text"));
    }
  }

  private ReadonlyStatusHandler.OperationStatus getReadOnlyFilesStatus(@NotNull final List<VirtualFile> filesToMakeWritable) {
    final VirtualFile[] fileArray = VfsUtilCore.toVirtualFileArray(filesToMakeWritable);
    return ReadonlyStatusHandler.getInstance(myProject).ensureFilesWritable(fileArray);
  }

  public static void showError(final Project project, final String message) {
    if (ApplicationManager.getApplication().isUnitTestMode()) return;
    VcsImplUtil.showErrorMessage(project, message, VcsBundle.message("patch.apply.dialog.title"));
  }
}
