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

package com.intellij.openapi.application;

import com.intellij.util.Processor;
import com.intellij.openapi.util.Computable;

/**
 * @author cdr
 */
public abstract class ReadActionProcessor<T> implements Processor<T> {
  public boolean process(final T t) {
    return ApplicationManager.getApplication().runReadAction(new Computable<Boolean>(){
      public Boolean compute() {
        return processInReadAction(t);
      }
    });
  }
  public abstract boolean processInReadAction(T t);
}
