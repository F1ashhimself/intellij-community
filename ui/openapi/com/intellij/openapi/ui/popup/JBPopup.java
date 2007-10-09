/*
 * Copyright 2000-2007 JetBrains s.r.o.
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

package com.intellij.openapi.ui.popup;


import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.Disposable;
import com.intellij.ui.awt.RelativePoint;
import org.jetbrains.annotations.NotNull;

import java.awt.*;

/**
 * Base interface for popup windows.
 *
 * @author mike
 * @see com.intellij.openapi.ui.popup.JBPopupFactory
 * @since 6.0
 */
public interface JBPopup extends Disposable {
  /**
   * Shows the popup at the bottom left corner of the specified component.
   *
   * @param componentUnder the component near which the popup should be displayed.
   */
  void showUnderneathOf(@NotNull Component componentUnder);

  /**
   * Shows the popup at the specified point.
   *
   * @param point the relative point where the popup should be displayed.
   */
  void show(@NotNull RelativePoint point);

  void showInScreenCoordinates(@NotNull Component owner, @NotNull Point point);

  /**
   * Shows the popup in the position most appropriate for the specified data context.
   *
   * @param dataContext the data context to which the popup is related.
   * @see com.intellij.openapi.ui.popup.JBPopupFactory#guessBestPopupLocation(com.intellij.openapi.actionSystem.DataContext)
   */
  void showInBestPositionFor(@NotNull DataContext dataContext);

  /**
   * Shows the popup near the cursor location in the specified editor.
   *
   * @param editor the editor relative to which the popup should be displayed.
   * @see com.intellij.openapi.ui.popup.JBPopupFactory#guessBestPopupLocation(com.intellij.openapi.editor.Editor)
   */
  void showInBestPositionFor(@NotNull Editor editor);

  /**
   * Shows the popup in the center of the specified component.
   *
   * @param focusOwner the component at which the popup should be centered.
   */
  void showInCenterOf(@NotNull Component focusOwner);


  /**
   * Shows in best position with a given owner
   */
  void show(Component owner);  

  /**
   * Shows the popup in the center of the active window in the IDEA frame for the specified project.
   *
   * @param project the project in which the popup should be displayed.
   */
  void showCenteredInCurrentWindow(@NotNull Project project);

  /**
   * Cancels the popup (as if Esc was pressed).
   */
  void cancel();

  /**
   * Checks if it's currently allowed to close the popup.
   *
   * @return true if the popup can be closed, false if a callback disallowed closing the popup.
   * @see com.intellij.openapi.ui.popup.ComponentPopupBuilder#setCancelCallback(com.intellij.openapi.util.Computable<java.lang.Boolean>)
   */
  boolean canClose();

  /**
   * Checks if the popup is currently visible.
   *
   * @return true if the popup is visible, false otherwise.
   */
  boolean isVisible();

  /**
   * Returns the Swing component contained in the popup.
   *
   * @return the contents of the popup.
   */
  Component getContent();

  /**
   * Moves popup to the given point. Does nothhinbg if popup is invisible.
   * @param screenPoint
   */
  void setLocation(@NotNull Point screenPoint);

  void setSize(@NotNull Dimension size);

}
