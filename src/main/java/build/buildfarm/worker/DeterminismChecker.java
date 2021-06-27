// Copyright 2021 The Bazel Authors. All rights reserved.
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

package build.buildfarm.worker;

import build.bazel.remote.execution.v2.ActionResult;
import build.bazel.remote.execution.v2.Digest;
import build.buildfarm.common.DigestUtil;
import build.buildfarm.common.DigestUtil.HashFunction;
import build.buildfarm.worker.resources.ResourceLimits;
import com.google.protobuf.ByteString;
import com.google.rpc.Code;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;

/**
 * @class DeterminismChecker
 * @brief Run an action multiple times and fail it if its outputs are not deterministic in content
 * @details The determinism checker can be used by the client to find nondeterministic actions and
 *     sources of cache positioning.
 */
public class DeterminismChecker {
  /**
   * @brief Check the determinism of an action by running it multiple times and comparing the
   *     outputs of each execution.
   * @details This allows clients to find nondeterministic actions and sources of cache positioning.
   * @param workerContext x
   * @param operationContext x
   * @param processBuilder The process to run.
   * @param limits The resource limitations of an execution (contains determinism check settings).
   * @param resultBuilder Used to report back debug information.
   * @return Return code for the debugged execution.
   * @note Suggested return identifier: code.
   */
  public static Code checkDeterminism(
      WorkerContext workerContext,
      OperationContext operationContext,
      ProcessBuilder processBuilder,
      ResourceLimits limits,
      ActionResult.Builder resultBuilder) {
    String message =
        getDeterminismResults(
            workerContext, operationContext, processBuilder, limits, resultBuilder);
    resultBuilder.setStderrRaw(ByteString.copyFromUtf8(message));
    resultBuilder.setExitCode(-1);
    return Code.OK;
  }

  private static String getDeterminismResults(
      WorkerContext workerContext,
      OperationContext operationContext,
      ProcessBuilder processBuilder,
      ResourceLimits limits,
      ActionResult.Builder resultBuilder) {
    
    // Run the action once to create a baseline set of output digests.
    runAction(processBuilder);
    HashMap<Path, Digest> fileDigests = computeFileDigests(operationContext);
    
    // Re-run the action a specified number of times to create additional output digests.
    List<HashMap<Path, Digest>> rerunDigests = new ArrayList<HashMap<Path, Digest>>();
    for (int i = 0; i < limits.checkDeterminism; ++i){
      resetWorkingDirectory();
      runAction(processBuilder);
      rerunDigests.add(computeFileDigests(operationContext));
    }
    
    // Find any digest discrepancies between the runs.  If any discrepancies are found we consider the action nondeterministic.
    for (Map.Entry<Path, Digest> entry : fileDigests.entrySet()){
      System.out.println(entry.getKey() + " " + entry.getValue());
    }
    System.out.println("-------");
    
    for (HashMap<Path, Digest> digests : rerunDigests){
      for (Map.Entry<Path, Digest> entry : digests.entrySet()){
        System.out.println(entry.getKey() + " " + entry.getValue());
      }
    }

    return "";
  }
  
  
  private static void resetWorkingDirectory(){
  }
  
  

  private static void runAction(ProcessBuilder processBuilder) {
    try {
      Process process = processBuilder.start();
      int exitCode = process.waitFor();
    } catch (IOException | InterruptedException e) {
    }
  }

  private static HashMap<Path, Digest> computeFileDigests(OperationContext operationContext) {
    HashMap<Path, Digest> fileDigests = new HashMap<>();

    DigestUtil digestUtil = new DigestUtil(HashFunction.SHA256);

    for (String outputFile : operationContext.command.getOutputFilesList()) {
      Path outputPath = operationContext.execDir.resolve(outputFile);
      addPathDigest(fileDigests, outputPath, digestUtil);
    }
    for (String outputDir : operationContext.command.getOutputDirectoriesList()) {
      Path outputDirPath = operationContext.execDir.resolve(outputDir);
      addDirDigests(fileDigests, outputDirPath, digestUtil);
    }

    return fileDigests;
  }

  private static void addPathDigest(
      HashMap<Path, Digest> fileDigests, Path path, DigestUtil digestUtil) {
    try {
      Digest digest = digestUtil.compute(path);
      fileDigests.put(path, digest);
    } catch (IOException e) {
    }
  }

  private static void addDirDigests(
      HashMap<Path, Digest> fileDigests, Path path, DigestUtil digestUtil) {
    try {
      Files.walkFileTree(
          path,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              addPathDigest(fileDigests, file, digestUtil);
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
    }
  }
}
