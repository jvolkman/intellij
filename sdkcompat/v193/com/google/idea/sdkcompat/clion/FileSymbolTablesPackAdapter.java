/*
 * Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.idea.sdkcompat.clion;

import com.intellij.openapi.project.Project;
import com.jetbrains.cidr.lang.symbols.symtable.FileSymbolTablesPack;

/**
 * Adapter to bridge different SDK versions.
 *
 * <p>FileSymbolTablesPack::onRemove changed params #api183
 */
public class FileSymbolTablesPackAdapter extends FileSymbolTablesPack {
  public static void onRemove(FileSymbolTablesPack pack, Project project) {
    pack.onRemove(project);
  }
}
