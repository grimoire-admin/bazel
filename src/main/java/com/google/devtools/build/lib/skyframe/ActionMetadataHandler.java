// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.io.BaseEncoding;
import com.google.devtools.build.lib.actions.ActionInput;
import com.google.devtools.build.lib.actions.ActionInputHelper;
import com.google.devtools.build.lib.actions.ActionInputMap;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Artifact.SpecialArtifact;
import com.google.devtools.build.lib.actions.Artifact.TreeFileArtifact;
import com.google.devtools.build.lib.actions.ArtifactFileMetadata;
import com.google.devtools.build.lib.actions.ArtifactPathResolver;
import com.google.devtools.build.lib.actions.FileArtifactValue;
import com.google.devtools.build.lib.actions.FileStateValue;
import com.google.devtools.build.lib.actions.cache.Md5Digest;
import com.google.devtools.build.lib.actions.cache.MetadataHandler;
import com.google.devtools.build.lib.util.io.TimestampGranularityMonitor;
import com.google.devtools.build.lib.vfs.FileStatus;
import com.google.devtools.build.lib.vfs.FileStatusWithDigest;
import com.google.devtools.build.lib.vfs.FileStatusWithDigestAdapter;
import com.google.devtools.build.lib.vfs.Path;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.vfs.RootedPath;
import com.google.devtools.build.lib.vfs.Symlinks;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.Nullable;

/**
 * Cache provided by an {@link ActionExecutionFunction}, allowing Blaze to obtain data from the
 * graph and to inject data (e.g. file digests) back into the graph. The cache can be in one of two
 * modes. After construction it acts as a cache for input and output metadata for the purpose of
 * checking for an action cache hit. When {@link #discardOutputMetadata} is called, it switches to a
 * mode where it calls chmod on output files before statting them. This is done here to ensure that
 * the chmod always comes before the stat in order to ensure that the stat is up to date.
 *
 * <p>Data for the action's inputs is injected into this cache on construction, using the Skyframe
 * graph as the source of truth.
 *
 * <p>As well, this cache collects data about the action's output files, which is used in three
 * ways. First, it is served as requested during action execution, primarily by the {@code
 * ActionCacheChecker} when determining if the action must be rerun, and then after the action is
 * run, to gather information about the outputs. Second, it is accessed by {@link ArtifactFunction}s
 * in order to construct {@link FileArtifactValue}s, and by this class itself to generate {@link
 * TreeArtifactValue}s. Third, the {@link FilesystemValueChecker} uses it to determine the set of
 * output files to check for inter-build modifications. Because all these use cases are slightly
 * different, we must occasionally store two versions of the data for a value. See {@link
 * OutputStore#getAllAdditionalOutputData} for elaboration on the difference between these cases,
 * and see the javadoc for the various internal maps to see what is stored where.
 */
@VisibleForTesting
public final class ActionMetadataHandler implements MetadataHandler {

  /**
   * Data for input artifacts. Immutable.
   *
   * <p>This should never be read directly. Use {@link #getInputFileArtifactValue} instead.
   */
  private final ActionInputMap inputArtifactData;

  /**
   * Maps output TreeArtifacts to their contents. These maps are either injected or read
   * directly from the filesystem.
   * If the value is null, this means nothing was injected, and the output TreeArtifact
   * is to have its values read from disk instead.
   */
  // TODO(b/115361150): Move this to OutputStore.
  private final ConcurrentMap<Artifact, Set<TreeFileArtifact>> outputDirectoryListings =
      new ConcurrentHashMap<>();

  /** Outputs that are to be omitted. */
  private final Set<Artifact> omittedOutputs = Sets.newConcurrentHashSet();

  private final ImmutableSet<Artifact> outputs;

  /**
   * The timestamp granularity monitor for this build.
   * Use {@link #getTimestampGranularityMonitor(Artifact)} to fetch this member.
   */
  private final TimestampGranularityMonitor tsgm;
  private final ArtifactPathResolver artifactPathResolver;

  /**
   * Whether the action is being executed or not; this flag is set to true in
   * {@link #discardOutputMetadata}.
   */
  private final AtomicBoolean executionMode = new AtomicBoolean(false);

  // TODO(b/115361150): Swap in a different output store when using ActionFS.
  private final OutputStore store = new OutputStore();

  @VisibleForTesting
  public ActionMetadataHandler(
      ActionInputMap inputArtifactData,
      Iterable<Artifact> outputs,
      TimestampGranularityMonitor tsgm,
      ArtifactPathResolver artifactPathResolver)  {
    this.inputArtifactData = Preconditions.checkNotNull(inputArtifactData);
    this.outputs = ImmutableSet.copyOf(outputs);
    this.tsgm = tsgm;
    this.artifactPathResolver = artifactPathResolver;
  }

  /**
   * Gets the {@link TimestampGranularityMonitor} to use for a given artifact.
   *
   * <p>If the artifact is of type "constant metadata", this returns null so that changes to such
   * artifacts do not tickle the timestamp granularity monitor, delaying the build for no reason.
   *
   * @param artifact the artifact for which to fetch the timestamp granularity monitor
   * @return the timestamp granularity monitor to use, which may be null
   */
  private TimestampGranularityMonitor getTimestampGranularityMonitor(Artifact artifact) {
    return artifact.isConstantMetadata() ? null : tsgm;
  }

  private static FileArtifactValue metadataFromValue(FileArtifactValue value)
      throws FileNotFoundException {
    if (value == FileArtifactValue.MISSING_FILE_MARKER
        || value == FileArtifactValue.OMITTED_FILE_MARKER) {
      throw new FileNotFoundException();
    }
    return value;
  }

  @Nullable
  private FileArtifactValue getInputFileArtifactValue(Artifact input) {
    if (outputs.contains(input)) {
      return null;
    }

    if (input.hasParent() && outputs.contains(input.getParent())) {
      return null;
    }

    return inputArtifactData.getMetadata(input);
  }

  @Override
  public FileArtifactValue getMetadata(Artifact artifact) throws IOException {
    FileArtifactValue value = getInputFileArtifactValue(artifact);
    if (value != null) {
      return metadataFromValue(value);
    }
    if (artifact.isSourceArtifact()) {
      // A discovered input we didn't have data for.
      // TODO(bazel-team): Change this to an assertion once Skyframe has native input discovery, so
      // all inputs will already have metadata known.
      return null;
    } else if (artifact.isMiddlemanArtifact()) {
      // A middleman artifact's data was either already injected from the action cache checker using
      // #setDigestForVirtualArtifact, or it has the default middleman value.
      value = store.getAdditionalOutputData(artifact);
      if (value != null) {
        return metadataFromValue(value);
      }
      value = FileArtifactValue.DEFAULT_MIDDLEMAN;
      store.putAdditionalOutputData(artifact, value);
      return metadataFromValue(value);
    } else if (artifact.isTreeArtifact()) {
      TreeArtifactValue setValue = getTreeArtifactValue((SpecialArtifact) artifact);
      if (setValue != null && setValue != TreeArtifactValue.MISSING_TREE_ARTIFACT) {
        return setValue.getMetadata();
      }
      // We use FileNotFoundExceptions to determine if an Artifact was or wasn't found.
      // Calling code depends on this particular exception.
      throw new FileNotFoundException(artifact + " not found");
    }
    // It's an ordinary artifact.
    ArtifactFileMetadata fileMetadata = store.getArtifactData(artifact);
    if (fileMetadata != null) {
      // Non-middleman artifacts should only have additionalOutputData if they have
      // outputArtifactData. We don't assert this because of concurrency possibilities, but at least
      // we don't check additionalOutputData unless we expect that we might see the artifact there.
      value = store.getAdditionalOutputData(artifact);
      // If additional output data is present for this artifact, we use it in preference to the
      // usual calculation.
      if (value != null) {
        return metadataFromValue(value);
      }
      if (!fileMetadata.exists()) {
        throw new FileNotFoundException(artifact.prettyPrint() + " does not exist");
      }
      return FileArtifactValue.createNormalFile(fileMetadata);
    }
    // We do not cache exceptions besides nonexistence here, because it is unlikely that the file
    // will be requested from this cache too many times.
    fileMetadata = constructArtifactFileMetadata(artifact, /*statNoFollow=*/ null);
    return maybeStoreAdditionalData(artifact, fileMetadata, null);
  }

  /**
   * See {@link Outputstore#getAllAdditionalOutputData} for why we sometimes need to store
   * additional data, even for normal (non-middleman) artifacts.
   */
  @Nullable
  private FileArtifactValue maybeStoreAdditionalData(
      Artifact artifact, ArtifactFileMetadata data, @Nullable byte[] injectedDigest)
      throws IOException {
    if (!data.exists()) {
      // Nonexistent files should only occur before executing an action.
      throw new FileNotFoundException(artifact.prettyPrint() + " does not exist");
    }
    boolean isFile = data.isFile();
    if (isFile && !artifact.hasParent() && data.getDigest() != null) {
      // We do not need to store the FileArtifactValue separately -- the digest is in the file value
      // and that is all that is needed for this file's metadata.
      return FileArtifactValue.createNormalFile(data);
    }
    // Unfortunately, the ArtifactFileMetadata does not contain enough information for us to
    // calculate the corresponding FileArtifactValue -- either the metadata must use the modified
    // time, which we do not expose in the ArtifactFileMetadata, or the ArtifactFileMetadata didn't
    // store the digest So we store the metadata separately.
    // Use the ArtifactFileMetadata's digest if no digest was injected, or if the file can't be
    // digested.
    injectedDigest = injectedDigest != null || !isFile ? injectedDigest : data.getDigest();
    FileArtifactValue value = FileArtifactValue.create(artifact, artifactPathResolver, data,
        injectedDigest);
    store.putAdditionalOutputData(artifact, value);
    return metadataFromValue(value);
  }

  @Override
  public void setDigestForVirtualArtifact(Artifact artifact, Md5Digest md5Digest) {
    Preconditions.checkArgument(artifact.isMiddlemanArtifact(), artifact);
    Preconditions.checkNotNull(md5Digest, artifact);
    store.putAdditionalOutputData(
        artifact, FileArtifactValue.createProxy(md5Digest.getDigestBytesUnsafe()));
  }

  private Set<TreeFileArtifact> getTreeArtifactContents(Artifact artifact) {
    Preconditions.checkArgument(artifact.isTreeArtifact(), artifact);
    return outputDirectoryListings.computeIfAbsent(artifact, unused -> Sets.newConcurrentHashSet());
  }

  private TreeArtifactValue getTreeArtifactValue(SpecialArtifact artifact) throws IOException {
    TreeArtifactValue value = store.getTreeArtifactData(artifact);
    if (value != null) {
      return value;
    }

    if (executionMode.get()) {
      // Preserve existing behavior: we don't set non-TreeArtifact directories
      // read only and executable. However, it's unusual for non-TreeArtifact outputs
      // to be directories.
      setTreeReadOnlyAndExecutable(artifact, PathFragment.EMPTY_FRAGMENT);
    }

    Set<TreeFileArtifact> registeredContents = outputDirectoryListings.get(artifact);
    if (registeredContents != null) {
      // Check that our registered outputs matches on-disk outputs. Only perform this check
      // when contents were explicitly registered.
      // TODO(bazel-team): Provide a way for actions to register empty TreeArtifacts.

      // By the time we're constructing TreeArtifactValues, use of the metadata handler
      // should be single threaded and there should be no race condition.
      // The current design of ActionMetadataHandler makes this hard to enforce.
      Set<PathFragment> paths = null;
      paths = TreeArtifactValue.explodeDirectory(artifactPathResolver.toPath(artifact));
      Set<TreeFileArtifact> diskFiles = ActionInputHelper.asTreeFileArtifacts(artifact, paths);
      if (!diskFiles.equals(registeredContents)) {
        // There might be more than one error here. We first look for missing output files.
        Set<TreeFileArtifact> missingFiles = Sets.difference(registeredContents, diskFiles);
        if (!missingFiles.isEmpty()) {
          // Don't throw IOException--getMetadataMaybe() eats them.
          // TODO(bazel-team): Report this error in a better way when called by checkOutputs()
          // Currently it's hard to report this error without refactoring, since checkOutputs()
          // likes to substitute its own error messages upon catching IOException, and falls
          // through to unrecoverable error behavior on any other exception.
          throw new IOException("Output file " + missingFiles.iterator().next()
              + " was registered, but not present on disk");
        }

        Set<TreeFileArtifact> extraFiles = Sets.difference(diskFiles, registeredContents);
        // extraFiles cannot be empty
        throw new IOException(
            "File " + extraFiles.iterator().next().getParentRelativePath()
            + ", present in TreeArtifact " + artifact + ", was not registered");
      }

      value = constructTreeArtifactValue(registeredContents);
    } else {
      value = constructTreeArtifactValueFromFilesystem(artifact);
    }

    store.putTreeArtifactData(artifact, value);
    return value;
  }

  private TreeArtifactValue constructTreeArtifactValue(Collection<TreeFileArtifact> contents)
      throws IOException {
    Map<TreeFileArtifact, FileArtifactValue> values =
        Maps.newHashMapWithExpectedSize(contents.size());

    for (TreeFileArtifact treeFileArtifact : contents) {
      FileArtifactValue cachedValue = store.getAdditionalOutputData(treeFileArtifact);
      if (cachedValue == null) {
        ArtifactFileMetadata fileMetadata = store.getArtifactData(treeFileArtifact);
        // This is similar to what's present in getRealMetadataForArtifact, except
        // we get back the ArtifactFileMetadata, not the metadata.
        // We do not cache exceptions besides nonexistence here, because it is unlikely that the
        // file will be requested from this cache too many times.
        if (fileMetadata == null) {
          try {
            fileMetadata = constructArtifactFileMetadata(treeFileArtifact, /*statNoFollow=*/ null);
          } catch (FileNotFoundException e) {
            String errorMessage = String.format(
                "Failed to resolve relative path %s inside TreeArtifact %s. "
                + "The associated file is either missing or is an invalid symlink.",
                treeFileArtifact.getParentRelativePath(),
                treeFileArtifact.getParent().getExecPathString());
            throw new IOException(errorMessage, e);
          }
        }

        // A minor hack: maybeStoreAdditionalData will force the data to be stored via
        // store.putAdditionalOutputData.
        maybeStoreAdditionalData(treeFileArtifact, fileMetadata, null);
        cachedValue = Preconditions.checkNotNull(
            store.getAdditionalOutputData(treeFileArtifact), treeFileArtifact);
      }

      values.put(treeFileArtifact, cachedValue);
    }

    return TreeArtifactValue.create(values);
  }

  private TreeArtifactValue constructTreeArtifactValueFromFilesystem(SpecialArtifact artifact)
      throws IOException {
    Preconditions.checkState(artifact.isTreeArtifact(), artifact);

    // Make sure the tree artifact root is a regular directory. Note that this is how the Action
    // is initialized, so this should hold unless the Action itself has deleted the root.
    if (!artifactPathResolver.toPath(artifact).isDirectory(Symlinks.NOFOLLOW)) {
      return TreeArtifactValue.MISSING_TREE_ARTIFACT;
    }

    Set<PathFragment> paths =
        TreeArtifactValue.explodeDirectory(artifactPathResolver.toPath(artifact));
    // If you're reading tree artifacts from disk while outputDirectoryListings are being injected,
    // something has gone terribly wrong.
    Object previousDirectoryListing =
        outputDirectoryListings.put(artifact, Sets.newConcurrentHashSet());
    Preconditions.checkState(previousDirectoryListing == null,
        "Race condition while constructing TreeArtifactValue: %s, %s",
        artifact, previousDirectoryListing);
    return constructTreeArtifactValue(ActionInputHelper.asTreeFileArtifacts(artifact, paths));
  }

  @Override
  public void addExpandedTreeOutput(TreeFileArtifact output) {
    Preconditions.checkState(executionMode.get());
    Set<TreeFileArtifact> values = getTreeArtifactContents(output.getParent());
    values.add(output);
  }

  @Override
  public Iterable<TreeFileArtifact> getExpandedOutputs(Artifact artifact) {
    return ImmutableSet.copyOf(getTreeArtifactContents(artifact));
  }

  @Override
  public void injectDigest(ActionInput output, FileStatus statNoFollow, byte[] digest) {
    Preconditions.checkState(executionMode.get());
    // Assumption: any non-Artifact output is 'virtual' and should be ignored here.
    if (output instanceof Artifact) {
      final Artifact artifact = (Artifact) output;
      // We have to add the artifact to injectedFiles before calling constructArtifactFileMetadata
      // to avoid duplicate chmod calls.
      store.injectedFiles().add(artifact);
      ArtifactFileMetadata fileMetadata;
      try {
        // This call may do an unnecessary call to Path#getFastDigest to see if the digest is
        // readily available. We cannot pass the digest in, though, because if it is not available
        // from the filesystem, this ArtifactFileMetadata will not compare equal to another one
        // created for the
        // same file, because the other one will be missing its digest.
        fileMetadata =
            constructArtifactFileMetadata(
                artifact, FileStatusWithDigestAdapter.adapt(statNoFollow));
        // Ensure the digest supplied matches the actual digest if it exists.
        byte[] fileDigest = fileMetadata.getDigest();
        if (fileDigest != null && !Arrays.equals(digest, fileDigest)) {
          BaseEncoding base16 = BaseEncoding.base16();
          String digestString = (digest != null) ? base16.encode(digest) : "null";
          String fileDigestString = base16.encode(fileDigest);
          throw new IllegalStateException(
              "Expected digest "
                  + digestString
                  + " for artifact "
                  + artifact
                  + ", but got "
                  + fileDigestString
                  + " ("
                  + fileMetadata
                  + ")");
        }
      } catch (IOException e) {
        // Do nothing - we just failed to inject metadata. Real error handling will be done later,
        // when somebody will try to access that file.
        return;
      }
      // If needed, insert additional data. Note that this can only be true if the file is empty or
      // the filesystem does not support fast digests. Since we usually only inject digests when
      // running with a filesystem that supports fast digests, this is fairly unlikely.
      try {
        maybeStoreAdditionalData(artifact, fileMetadata, digest);
      } catch (IOException e) {
        throw new IllegalStateException(
            "Filesystem should not have been accessed while injecting data for "
                + artifact.prettyPrint(),
            e);
      }
    }
  }

  @Override
  public void injectRemoteFile(Artifact output, byte[] digest, long size, int locationIndex) {
    Preconditions.checkState(
        executionMode.get(), "Tried to inject %s outside of execution.", output);
    Preconditions.checkArgument(
        locationIndex != 0 || size == 0,
        "output = %s, size = %s, locationIndex =%s",
        output,
        size,
        locationIndex);

    store.injectRemoteFile(output, digest, size, locationIndex);
  }

  @Override
  public void markOmitted(ActionInput output) {
    Preconditions.checkState(executionMode.get());
    if (output instanceof Artifact) {
      Artifact artifact = (Artifact) output;
      Preconditions.checkState(omittedOutputs.add(artifact), artifact);
      store.putAdditionalOutputData(artifact, FileArtifactValue.OMITTED_FILE_MARKER);
    }
  }

  @Override
  public boolean artifactOmitted(Artifact artifact) {
    // TODO(ulfjack): this is currently unreliable, see the documentation on MetadataHandler.
    return omittedOutputs.contains(artifact);
  }

  @Override
  public void discardOutputMetadata() {
    boolean wasExecutionMode = executionMode.getAndSet(true);
    Preconditions.checkState(!wasExecutionMode);
    Preconditions.checkState(store.injectedFiles().isEmpty(),
        "Files cannot be injected before action execution: %s", store.injectedFiles());
    Preconditions.checkState(omittedOutputs.isEmpty(),
        "Artifacts cannot be marked omitted before action execution: %s", omittedOutputs);
    outputDirectoryListings.clear();
    store.clear();
  }

  OutputStore getOutputStore() {
    return store;
  }

  /**
   * Constructs a new {@link ArtifactFileMetadata}, saves it, and checks inconsistent data. This
   * calls chmod on the file if we're in executionMode.
   */
  private ArtifactFileMetadata constructArtifactFileMetadata(
      Artifact artifact, @Nullable FileStatusWithDigest statNoFollow) throws IOException {
    // We first chmod the output files before we construct the FileContentsProxy. The proxy may use
    // ctime, which is affected by chmod.
    if (executionMode.get()) {
      Preconditions.checkState(!artifact.isTreeArtifact());
      setPathReadOnlyAndExecutable(artifact);
    }

    ArtifactFileMetadata value =
        fileMetadataFromArtifact(
            artifact, artifactPathResolver, statNoFollow, getTimestampGranularityMonitor(artifact));
    store.putArtifactData(artifact, value);
    return value;
  }

  @VisibleForTesting
  static ArtifactFileMetadata fileMetadataFromArtifact(
      Artifact artifact,
      @Nullable FileStatusWithDigest statNoFollow,
      @Nullable TimestampGranularityMonitor tsgm)
      throws IOException {
    return fileMetadataFromArtifact(artifact, ArtifactPathResolver.IDENTITY, statNoFollow, tsgm);
  }

  private static ArtifactFileMetadata fileMetadataFromArtifact(
      Artifact artifact,
      ArtifactPathResolver artifactPathResolver,
      @Nullable FileStatusWithDigest statNoFollow,
      @Nullable TimestampGranularityMonitor tsgm)
      throws IOException {
    Path path = artifactPathResolver.toPath(artifact);
    PathFragment pathFragment = path.asFragment();
    RootedPath rootedPath =
        RootedPath.toRootedPath(
            artifactPathResolver.transformRoot(artifact.getRoot().getRoot()),
            artifact.getRootRelativePath());
    if (statNoFollow == null) {
      statNoFollow = FileStatusWithDigestAdapter.adapt(path.statIfFound(Symlinks.NOFOLLOW));
      if (statNoFollow == null) {
        return ArtifactFileMetadata.value(
            pathFragment,
            FileStateValue.NONEXISTENT_FILE_STATE_NODE,
            pathFragment,
            FileStateValue.NONEXISTENT_FILE_STATE_NODE);
      }
    }
    Path realPath = path;
    // We use FileStatus#isSymbolicLink over Path#isSymbolicLink to avoid the unnecessary stat
    // done by the latter.
    if (statNoFollow.isSymbolicLink()) {
      realPath = path.resolveSymbolicLinks();
      // We need to protect against symlink cycles since ArtifactFileMetadata#value assumes it's
      // dealing with a
      // file that's not in a symlink cycle.
      if (realPath.equals(path)) {
        throw new IOException("symlink cycle");
      }
    }
    RootedPath realRootedPath =
        RootedPath.toRootedPathMaybeUnderRoot(
            realPath,
            ImmutableList.of(artifactPathResolver.transformRoot(artifact.getRoot().getRoot())));
    FileStateValue fileStateValue =
        FileStateValue.createWithStatNoFollow(rootedPath, statNoFollow, tsgm);
    // TODO(bazel-team): consider avoiding a 'stat' here when the symlink target hasn't changed
    // and is a source file (since changes to those are checked separately).
    FileStateValue realFileStateValue = realPath.equals(path)
        ? fileStateValue
        : FileStateValue.create(realRootedPath, tsgm);
    return ArtifactFileMetadata.value(
        pathFragment, fileStateValue, realPath.asFragment(), realFileStateValue);
  }

  private void setPathReadOnlyAndExecutable(Artifact artifact) throws IOException {
    // If the metadata was injected, we assume the mode is set correct and bail out early to avoid
    // the additional overhead of resetting it.
    if (store.injectedFiles().contains(artifact)) {
      return;
    }
    Path path = artifactPathResolver.toPath(artifact);
    if (path.isFile(Symlinks.NOFOLLOW)) { // i.e. regular files only.
      // We trust the files created by the execution engine to be non symlinks with expected
      // chmod() settings already applied.
      path.chmod(0555);  // Sets the file read-only and executable.
    }
  }

  private void setTreeReadOnlyAndExecutable(SpecialArtifact parent, PathFragment subpath)
      throws IOException {
    Path path = artifactPathResolver.toPath(parent).getRelative(subpath);
    if (path.isDirectory()) {
      path.chmod(0555);
      for (Path child : path.getDirectoryEntries()) {
        setTreeReadOnlyAndExecutable(parent, subpath.getChild(child.getBaseName()));
      }
    } else {
      setPathReadOnlyAndExecutable(ActionInputHelper.treeFileArtifact(parent, subpath));
    }
  }
}
