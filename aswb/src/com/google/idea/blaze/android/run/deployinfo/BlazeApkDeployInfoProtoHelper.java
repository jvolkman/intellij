/*
 * Copyright 2016 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.idea.blaze.android.run.deployinfo;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.devtools.build.lib.rules.android.deployinfo.AndroidDeployInfoOuterClass.AndroidDeployInfo;
import com.google.idea.blaze.android.manifest.ParsedManifestService;
import com.google.idea.blaze.base.command.buildresult.BlazeArtifact;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper;
import com.google.idea.blaze.base.command.buildresult.BuildResultHelper.GetArtifactsException;
import com.google.idea.blaze.base.command.info.BlazeInfo;
import com.google.idea.blaze.base.command.info.BlazeInfoRunner;
import com.google.idea.blaze.base.model.primitives.Label;
import com.google.idea.blaze.base.model.primitives.WorkspaceRoot;
import com.google.idea.blaze.base.scope.BlazeContext;
import com.google.idea.blaze.base.settings.Blaze;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

/** Reads the deploy info from a build step. */
public class BlazeApkDeployInfoProtoHelper {
  private static final Logger LOG = Logger.getInstance(BlazeApkDeployInfoProtoHelper.class);

  private final Project project;
  private final WorkspaceRoot workspaceRoot;
  private final ImmutableList<String> buildFlags;

  public BlazeApkDeployInfoProtoHelper(Project project, ImmutableList<String> buildFlags) {
    this.project = project;
    this.buildFlags = buildFlags;
    this.workspaceRoot = WorkspaceRoot.fromProject(project);
  }

  // TODO doc on how it assumes there's only one deployinfo
  // TODO write a test that extracts deploy info after invoking blaze
  // other one.
  @Nullable
  public BlazeAndroidDeployInfo readDeployInfo(
      BlazeContext context, BuildResultHelper buildResultHelper, Predicate<String> pathFilter)
      throws GetArtifactsException {
    String executionRoot = getExecutionRoot(context);
    if (executionRoot == null) {
      return null;
    }

    File deployInfoFile =
        Iterables.getOnlyElement(
            BlazeArtifact.getLocalFiles(buildResultHelper.getAllOutputArtifacts(pathFilter)), null);
    AndroidDeployInfo deployInfo = readDeployInfoProto(deployInfoFile);
    if (deployInfo == null) {
      return null;
    }

    File mergedManifest = new File(executionRoot, deployInfo.getMergedManifest().getExecRootPath());
    List<File> apksToDeploy =
        deployInfo.getApksToDeployList().stream()
            .map(artifact -> new File(executionRoot, artifact.getExecRootPath()))
            .collect(Collectors.toList());
    BlazeAndroidDeployInfo androidDeployInfo =
        new BlazeAndroidDeployInfo(project, mergedManifest, null, apksToDeploy);

    List<File> manifestFiles = androidDeployInfo.getManifestFiles();
    ParsedManifestService.getInstance(project).invalidateCachedManifests(manifestFiles);

    return androidDeployInfo;
  }

  // TODO doc on the purpose of this method and when it should be used.
  // TODO write a test that extracts deploy info after invoking blaze
  @Nullable
  public BlazeAndroidDeployInfo readDeployInfoForInstrumentationTest(
      BlazeContext context,
      BuildResultHelper buildResultHelper,
      Predicate<String> pathFilter,
      Label testLabel,
      Label targetLabel)
      throws GetArtifactsException {
    String executionRoot = getExecutionRoot(context);
    if (executionRoot == null) {
      return null;
    }

    File testDeployInfoFile =
        Iterables.getOnlyElement(
            BlazeArtifact.getLocalFiles(
                buildResultHelper.getBuildArtifactsForTarget(testLabel, pathFilter)),
            null);
    AndroidDeployInfo testDeployInfo = readDeployInfoProto(testDeployInfoFile);
    if (testDeployInfo == null) {
      return null;
    }

    File targetDeployInfoFile =
        Iterables.getOnlyElement(
            BlazeArtifact.getLocalFiles(
                buildResultHelper.getBuildArtifactsForTarget(targetLabel, pathFilter)),
            null);
    AndroidDeployInfo targetDeployInfo = readDeployInfoProto(targetDeployInfoFile);
    if (targetDeployInfo == null) {
      return null;
    }

    File mergedManifest =
        new File(executionRoot, testDeployInfo.getMergedManifest().getExecRootPath());
    File testTargetMergedManifest =
        new File(executionRoot, targetDeployInfo.getMergedManifest().getExecRootPath());
    List<File> apksToDeploy =
        Stream.concat(
                testDeployInfo.getApksToDeployList().stream(),
                targetDeployInfo.getApksToDeployList().stream())
            .map(artifact -> new File(executionRoot, artifact.getExecRootPath()))
            .collect(Collectors.toList());

    BlazeAndroidDeployInfo androidDeployInfo =
        new BlazeAndroidDeployInfo(project, mergedManifest, testTargetMergedManifest, apksToDeploy);

    List<File> manifestFiles = androidDeployInfo.getManifestFiles();
    ParsedManifestService.getInstance(project).invalidateCachedManifests(manifestFiles);

    return androidDeployInfo;
  }

  private static AndroidDeployInfo readDeployInfoProto(@Nullable File deployInfoFile) {
    if (deployInfoFile == null) {
      return null;
    }
    AndroidDeployInfo deployInfo;
    try (InputStream inputStream = new FileInputStream(deployInfoFile)) {
      deployInfo = AndroidDeployInfo.parseFrom(inputStream);
    } catch (IOException e) {
      LOG.error(e);
      return null;
    }
    return deployInfo;
  }

  @Nullable
  private String getExecutionRoot(BlazeContext context) {
    ListenableFuture<String> execRootFuture =
        BlazeInfoRunner.getInstance()
            .runBlazeInfo(
                context,
                Blaze.getBuildSystemProvider(project).getBinaryPath(project),
                workspaceRoot,
                buildFlags,
                BlazeInfo.EXECUTION_ROOT_KEY);
    try {
      return execRootFuture.get();
    } catch (InterruptedException e) {
      context.setCancelled();
    } catch (ExecutionException e) {
      LOG.error(e);
      context.setHasError();
    }
    return null;
  }
}
