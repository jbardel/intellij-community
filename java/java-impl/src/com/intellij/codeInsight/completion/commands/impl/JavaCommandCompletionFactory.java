// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.codeInsight.completion.commands.impl;

import com.intellij.codeInsight.completion.commands.api.CommandCompletionFactory;
import com.intellij.codeInsight.completion.commands.api.CommandProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class JavaCommandCompletionFactory implements CommandCompletionFactory, DumbAware {

  @Override
  public @NotNull List<@NotNull CommandProvider> commandProviders(@NotNull Project project) {
    if (!Registry.is("java.completion.command.enabled")) return List.of();

    return DumbService.getDumbAwareExtensions(project, CommandProvider.Companion.getEP_NAME());
  }

  @Override
  public boolean isApplicable(@NotNull PsiFile psiFile, int offset) {
    return psiFile instanceof PsiJavaFile;
  }
}
