// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.usageView;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.NlsContexts;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;


public abstract class UsageViewContentManager {
  public static UsageViewContentManager getInstance(Project project) {
    return project.getService(UsageViewContentManager.class);
  }

  public abstract @NotNull Content addContent(@NlsContexts.TabTitle @NotNull String contentName, boolean reusable, @NotNull JComponent component, boolean toOpenInNewTab, boolean isLockable);

  public abstract @NotNull Content addContent(@NlsContexts.TabTitle @NotNull String contentName,
                                              @NlsContexts.TabTitle String tabName,
                                              @NlsContexts.TabTitle String toolwindowTitle,
                                              boolean reusable,
                                              @NotNull JComponent component,
                                              boolean toOpenInNewTab,
                                              boolean isLockable);

  public abstract int getReusableContentsCount();

  public abstract Content getSelectedContent(boolean reusable);

  public abstract Content getSelectedContent();

  public abstract void closeContent(@NotNull Content usageView);
}
