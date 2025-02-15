// Copyright 2000-2025 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.java.codeserver.highlighting;

import com.intellij.codeInsight.daemon.impl.analysis.JavaGenericsUtil;
import com.intellij.java.codeserver.highlighting.errors.JavaErrorKinds;
import com.intellij.java.codeserver.highlighting.errors.JavaIncompatibleTypeErrorContext;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.IncompleteModelUtil;
import com.intellij.psi.impl.PsiClassImplUtil;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

final class GenericsChecker {
  private final @NotNull JavaErrorVisitor myVisitor;

  GenericsChecker(@NotNull JavaErrorVisitor visitor) { myVisitor = visitor; }

  void checkElementInTypeParameterExtendsList(@NotNull PsiReferenceList referenceList,
                                              @NotNull PsiTypeParameter typeParameter,
                                              @NotNull JavaResolveResult resolveResult,
                                              @NotNull PsiJavaCodeReferenceElement element) {
    PsiJavaCodeReferenceElement[] referenceElements = referenceList.getReferenceElements();
    PsiClass extendFrom = (PsiClass)resolveResult.getElement();
    if (extendFrom == null) return;
    if (!extendFrom.isInterface() && referenceElements.length != 0 && element != referenceElements[0]) {
      myVisitor.report(JavaErrorKinds.TYPE_PARAMETER_EXTENDS_INTERFACE_EXPECTED.create(element, typeParameter));
    }
    else if (referenceElements.length != 0 &&
             element != referenceElements[0] &&
             referenceElements[0].resolve() instanceof PsiTypeParameter) {
      myVisitor.report(JavaErrorKinds.TYPE_PARAMETER_CANNOT_BE_FOLLOWED_BY_OTHER_BOUNDS.create(element, typeParameter));
    }
  }

  void checkCannotInheritFromTypeParameter(@Nullable PsiClass superClass, @NotNull PsiJavaCodeReferenceElement toHighlight) {
    if (superClass instanceof PsiTypeParameter) {
      myVisitor.report(JavaErrorKinds.CLASS_INHERITS_TYPE_PARAMETER.create(toHighlight, superClass));
    }
  }

  void checkTypeParametersList(@NotNull PsiTypeParameterList list, PsiTypeParameter @NotNull [] parameters) {
    PsiElement parent = list.getParent();
    if (parent instanceof PsiClass psiClass && psiClass.isEnum()) {
      myVisitor.report(JavaErrorKinds.TYPE_PARAMETER_ON_ENUM.create(list));
      return;
    }
    if (PsiUtil.isAnnotationMethod(parent)) {
      myVisitor.report(JavaErrorKinds.TYPE_PARAMETER_ON_ANNOTATION_MEMBER.create(list));
      return;
    }
    if (parent instanceof PsiClass psiClass && psiClass.isAnnotationType()) {
      myVisitor.report(JavaErrorKinds.TYPE_PARAMETER_ON_ANNOTATION.create(list));
      return;
    }

    for (int i = 0; i < parameters.length; i++) {
      PsiTypeParameter typeParameter1 = parameters[i];
      myVisitor.myClassChecker.checkCyclicInheritance(typeParameter1);
      if (myVisitor.hasErrorResults()) return;
      String name1 = typeParameter1.getName();
      for (int j = i + 1; j < parameters.length; j++) {
        PsiTypeParameter typeParameter2 = parameters[j];
        String name2 = typeParameter2.getName();
        if (Objects.equals(name1, name2)) {
          myVisitor.report(JavaErrorKinds.TYPE_PARAMETER_DUPLICATE.create(typeParameter2));
        }
      }
    }
  }

  void checkForEachParameterType(@NotNull PsiForeachStatement statement, @NotNull PsiParameter parameter) {
    PsiExpression expression = statement.getIteratedValue();
    PsiType itemType = expression == null ? null : JavaGenericsUtil.getCollectionItemType(expression);
    if (itemType == null) return;

    PsiType parameterType = parameter.getType();
    if (TypeConversionUtil.isAssignable(parameterType, itemType)) return;
    if (IncompleteModelUtil.isIncompleteModel(statement) && IncompleteModelUtil.isPotentiallyConvertible(parameterType, itemType, expression)) {
      return;
    }
    myVisitor.report(JavaErrorKinds.TYPE_INCOMPATIBLE.create(parameter, new JavaIncompatibleTypeErrorContext(itemType, parameterType)));
  }

  void checkDiamondTypeNotAllowed(@NotNull PsiNewExpression expression) {
    PsiReferenceParameterList typeArgumentList = expression.getTypeArgumentList();
    PsiTypeElement[] typeParameterElements = typeArgumentList.getTypeParameterElements();
    if (typeParameterElements.length == 1 && typeParameterElements[0].getType() instanceof PsiDiamondType) {
      myVisitor.report(JavaErrorKinds.NEW_EXPRESSION_DIAMOND_NOT_ALLOWED.create(typeArgumentList));
    }
  }

  void checkSelectStaticClassFromParameterizedType(@Nullable PsiElement resolved, @NotNull PsiJavaCodeReferenceElement ref) {
    if (resolved instanceof PsiClass psiClass && psiClass.hasModifierProperty(PsiModifier.STATIC)) {
      PsiElement qualifier = ref.getQualifier();
      if (qualifier instanceof PsiJavaCodeReferenceElement referenceElement) {
        PsiReferenceParameterList parameterList = referenceElement.getParameterList();
        if (parameterList != null && parameterList.getTypeArguments().length > 0) {
          myVisitor.report(JavaErrorKinds.REFERENCE_TYPE_ARGUMENT_STATIC_CLASS.create(parameterList, psiClass));
        }
      }
    }
  }

  /**
   * see <a href="http://docs.oracle.com/javase/specs/jls/se7/html/jls-4.html#jls-4.8">JLS 4.8 on raw types</a>
   */
  void checkRawOnParameterizedType(@NotNull PsiJavaCodeReferenceElement parent, @Nullable PsiElement resolved) {
    PsiReferenceParameterList list = parent.getParameterList();
    if (list == null || list.getTypeArguments().length > 0) return;
    if (parent.getQualifier() instanceof PsiJavaCodeReferenceElement ref &&
        ref.getTypeParameters().length > 0 &&
        resolved instanceof PsiTypeParameterListOwner typeParameterListOwner &&
        typeParameterListOwner.hasTypeParameters() &&
        !typeParameterListOwner.hasModifierProperty(PsiModifier.STATIC) && 
        parent.getReferenceNameElement() != null) {
      myVisitor.report(JavaErrorKinds.REFERENCE_TYPE_NEEDS_TYPE_ARGUMENTS.create(parent));
    }
  }

  void checkInterfaceMultipleInheritance(@NotNull PsiClass aClass) {
    PsiClassType[] types = aClass.getSuperTypes();
    if (types.length < 2) return;
    checkInterfaceMultipleInheritance(aClass,
                                      aClass,
                                      PsiSubstitutor.EMPTY, new HashMap<>(),
                                      new HashSet<>());
  }

  private void checkInterfaceMultipleInheritance(@NotNull PsiClass aClass,
                                                 @NotNull PsiClass place,
                                                 @NotNull PsiSubstitutor derivedSubstitutor,
                                                 @NotNull Map<PsiClass, PsiSubstitutor> inheritedClasses,
                                                 @NotNull Set<? super PsiClass> visited) {
    List<PsiClassType.ClassResolveResult> superTypes = PsiClassImplUtil.getScopeCorrectedSuperTypes(aClass, place.getResolveScope());
    for (PsiClassType.ClassResolveResult result : superTypes) {
      PsiClass superClass = result.getElement();
      if (superClass == null || visited.contains(superClass)) continue;
      PsiSubstitutor superTypeSubstitutor = result.getSubstitutor();
      PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(aClass.getProject());
      //JLS 4.8 The superclasses (respectively, superinterfaces) of a raw type are the erasures 
      // of the superclasses (superinterfaces) of any of the parameterizations of the generic type.
      superTypeSubstitutor = PsiUtil.isRawSubstitutor(aClass, derivedSubstitutor)
                             ? elementFactory.createRawSubstitutor(superClass)
                             : MethodSignatureUtil.combineSubstitutors(superTypeSubstitutor, derivedSubstitutor);

      PsiSubstitutor inheritedSubstitutor = inheritedClasses.get(superClass);
      if (inheritedSubstitutor != null) {
        PsiTypeParameter[] typeParameters = superClass.getTypeParameters();
        for (PsiTypeParameter typeParameter : typeParameters) {
          PsiType type1 = inheritedSubstitutor.substitute(typeParameter);
          PsiType type2 = superTypeSubstitutor.substitute(typeParameter);

          if (!Comparing.equal(type1, type2)) {
            var context = new JavaErrorKinds.InheritTypeClashContext(superClass, type1, type2);
            if (type1 != null && type2 != null) {
              myVisitor.report(JavaErrorKinds.CLASS_INHERITANCE_DIFFERENT_TYPE_ARGUMENTS.create(place, context));
            }
            else {
              myVisitor.report(JavaErrorKinds.CLASS_INHERITANCE_RAW_AND_GENERIC.create(place, context));
            }
            return;
          }
        }
      }
      inheritedClasses.put(superClass, superTypeSubstitutor);
      visited.add(superClass);
      checkInterfaceMultipleInheritance(superClass, place, superTypeSubstitutor, inheritedClasses, visited);
      visited.remove(superClass);
      if (myVisitor.hasErrorResults()) return;
    }
  }

  void checkClassSupersAccessibility(@NotNull PsiClass aClass) {
    checkClassSupersAccessibility(aClass, aClass, aClass.getResolveScope(), true);
  }

  void checkClassSupersAccessibility(@NotNull PsiClass aClass, @NotNull PsiElement ref, @NotNull GlobalSearchScope scope) {
    checkClassSupersAccessibility(ref, aClass, scope, false);
  }

  private void checkClassSupersAccessibility(@NotNull PsiElement anchor,
                                             @NotNull PsiClass aClass,
                                             @NotNull GlobalSearchScope resolveScope,
                                             boolean checkParameters) {
    JavaPsiFacade factory = JavaPsiFacade.getInstance(aClass.getProject());
    for (PsiClassType superType : aClass.getSuperTypes()) {
      HashSet<PsiClass> checked = new HashSet<>();
      checked.add(aClass);
      checkTypeAccessible(anchor, superType, checked, checkParameters, true, resolveScope, factory);
    }
  }

  private void checkTypeAccessible(@NotNull PsiElement anchor,
                                   @Nullable PsiType type,
                                   @NotNull Set<? super PsiClass> classes,
                                   boolean checkParameters,
                                   boolean checkSuperTypes,
                                   @NotNull GlobalSearchScope resolveScope,
                                   @NotNull JavaPsiFacade factory) {
    type = PsiClassImplUtil.correctType(type, resolveScope);

    PsiClass aClass = PsiUtil.resolveClassInType(type);
    if (aClass != null && classes.add(aClass)) {
      VirtualFile vFile = PsiUtilCore.getVirtualFile(aClass);
      if (vFile == null) return;
      FileIndexFacade index = FileIndexFacade.getInstance(aClass.getProject());
      if (!index.isInSource(vFile) && !index.isInLibraryClasses(vFile)) return;

      PsiImplicitClass parentImplicitClass = PsiTreeUtil.getParentOfType(aClass, PsiImplicitClass.class);
      String qualifiedName = aClass.getQualifiedName();
      if (parentImplicitClass == null && qualifiedName != null && factory.findClass(qualifiedName, resolveScope) == null) {
        myVisitor.report(JavaErrorKinds.CLASS_NOT_ACCESSIBLE.create(anchor, aClass));
        return;
      }

      if (!checkParameters) return;

      if (type instanceof PsiClassType classType) {
        for (PsiType parameterType : classType.getParameters()) {
          checkTypeAccessible(anchor, parameterType, classes, true, false, resolveScope, factory);
          if (myVisitor.hasErrorResults()) return;
        }
      }

      if (!checkSuperTypes) return;

      boolean isInLibrary = !index.isInContent(vFile);
      for (PsiClassType superType : aClass.getSuperTypes()) {
        checkTypeAccessible(anchor, superType, classes, !isInLibrary, true, resolveScope, factory);
        if (myVisitor.hasErrorResults()) return;
      }
    }
  }
}
