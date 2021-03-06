/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package org.jetbrains.uast

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import org.jetbrains.uast.internal.log

class UComment(override val psi: PsiComment, override val uastParent: UElement) : UElement {
    @Deprecated("Use a constructor that takes PsiComment as parameter")
    constructor(psi: PsiElement, parent: UElement) : this(psi as PsiComment, parent)

    val text: String
        get() = asSourceString()

    override fun asLogString() = log()

    override fun asRenderString(): String = asSourceString()
    override fun asSourceString(): String = psi.text
}