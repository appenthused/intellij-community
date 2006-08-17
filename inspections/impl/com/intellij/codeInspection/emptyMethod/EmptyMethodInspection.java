package com.intellij.codeInspection.emptyMethod;

import com.intellij.analysis.AnalysisScope;
import com.intellij.codeInsight.daemon.GroupNames;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.DescriptorProviderInspection;
import com.intellij.codeInspection.ex.GlobalInspectionContextImpl;
import com.intellij.codeInspection.ex.JobDescriptor;
import com.intellij.codeInspection.reference.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiCodeBlock;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.refactoring.safeDelete.SafeDeleteHandler;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * @author max
 */
public class EmptyMethodInspection extends DescriptorProviderInspection {
  public static final String DISPLAY_NAME = InspectionsBundle.message("inspection.empty.method.display.name");
  private QuickFix myQuickFix;
  @NonNls public static final String SHORT_NAME = "EmptyMethod";

  public void runInspection(AnalysisScope scope, final InspectionManager manager) {
    getRefManager().iterate(new RefVisitor() {
      public void visitElement(RefEntity refEntity) {
        if (refEntity instanceof RefMethod) {
          RefMethod refMethod = (RefMethod)refEntity;
          if (!getContext().isToCheckMember(refMethod, EmptyMethodInspection.this)) return;
          ProblemDescriptor[] descriptors = checkMethod(refMethod, manager);
          if (descriptors != null) {
            addProblemElement(refMethod, descriptors);
          }
        }
      }
    });
  }

  public boolean isGraphNeeded() {
    return true;
  }

  @Nullable
  private ProblemDescriptor[] checkMethod(RefMethod refMethod, InspectionManager manager) {
    if (!refMethod.isBodyEmpty()) return null;
    if (refMethod.isConstructor()) return null;
    if (refMethod.isSyntheticJSP()) return null;

    for (RefMethod refSuper : refMethod.getSuperMethods()) {
      if (checkMethod(refSuper, manager) != null) return null;
    }

    String message = null;
    if (refMethod.isOnlyCallsSuper()) {
      RefMethod refSuper = findSuperWithBody(refMethod);
      if (refSuper == null || RefUtil.getInstance().compareAccess(refMethod.getAccessModifier(), refSuper.getAccessModifier()) <= 0) {
        message = InspectionsBundle.message("inspection.empty.method.problem.descriptor");
      }
    }
    else if (refMethod.hasBody() && hasEmptySuperImplementation(refMethod)) {
      message = InspectionsBundle.message("inspection.empty.method.problem.descriptor1");
    }
    else if (areAllImplementationsEmpty(refMethod)) {
      if (refMethod.hasBody()) {
        if (refMethod.getDerivedMethods().size() == 0) {
          if (refMethod.getSuperMethods().size() == 0) {
            message = InspectionsBundle.message("inspection.empty.method.problem.descriptor2");
          }
        }
        else {
          message = InspectionsBundle.message("inspection.empty.method.problem.descriptor3");
        }
      }
      else {
        if (refMethod.getDerivedMethods().size() > 0) {
          message = InspectionsBundle.message("inspection.empty.method.problem.descriptor4");
        }
      }
    }

    if (message != null) {
      return new ProblemDescriptor[]{
        manager.createProblemDescriptor(refMethod.getElement().getNavigationElement(), message, getFix(), ProblemHighlightType.GENERIC_ERROR_OR_WARNING)};
    }

    return null;
  }

  private static RefMethod findSuperWithBody(RefMethod refMethod) {
    for (RefMethod refSuper : refMethod.getSuperMethods()) {
      if (refSuper.hasBody()) return refSuper;
    }
    return null;
  }

  private static boolean areAllImplementationsEmpty(RefMethod refMethod) {
    if (refMethod.hasBody() && !refMethod.isBodyEmpty()) return false;

    for (RefMethod refDerived : refMethod.getDerivedMethods()) {
      if (!areAllImplementationsEmpty(refDerived)) return false;
    }

    return true;
  }

  private static boolean hasEmptySuperImplementation(RefMethod refMethod) {
    for (RefMethod refSuper : refMethod.getSuperMethods()) {
      if (refSuper.hasBody() && refSuper.isBodyEmpty()) return true;
    }

    return false;
  }

  public boolean queryExternalUsagesRequests(final InspectionManager manager) {
    getRefManager().iterate(new RefVisitor() {
      public void visitElement(RefEntity refEntity) {
        if (refEntity instanceof RefElement && getDescriptions(refEntity) != null) {
          refEntity.accept(new RefVisitor() {
            public void visitMethod(final RefMethod refMethod) {
              getContext().enqueueDerivedMethodsProcessor(refMethod, new GlobalInspectionContextImpl.DerivedMethodsProcessor() {
                public boolean process(PsiMethod derivedMethod) {
                  PsiCodeBlock body = derivedMethod.getBody();
                  if (body == null) return true;
                  if (body.getStatements().length == 0) return true;
                  if (RefUtil.getInstance().isMethodOnlyCallsSuper(derivedMethod)) return true;

                  ignoreElement(refMethod);
                  return false;
                }
              });
            }
          });
        }
      }
    });

    return false;
  }

  @NotNull
  public JobDescriptor[] getJobDescriptors() {
    return new JobDescriptor[]{GlobalInspectionContextImpl.BUILD_GRAPH, GlobalInspectionContextImpl.FIND_EXTERNAL_USAGES};
  }

  public String getDisplayName() {
    return DISPLAY_NAME;
  }

  public String getGroupDisplayName() {
    return GroupNames.DECLARATION_REDUNDANCY;
  }

  public String getShortName() {
    return SHORT_NAME;
  }

  private LocalQuickFix getFix() {
    if (myQuickFix == null) {
      myQuickFix = new QuickFix();
    }
    return myQuickFix;
  }

  private class QuickFix implements LocalQuickFix {
    @NotNull
    public String getName() {
      return InspectionsBundle.message("inspection.empty.method.delete.quickfix");
    }

    public void applyFix(@NotNull Project project, ProblemDescriptor descriptor) {
      RefElement refElement = (RefElement)getElement(descriptor);
      if (refElement.isValid() && refElement instanceof RefMethod) {
        List<RefElement> refElements = new ArrayList<RefElement>(1);
        RefMethod refMethod = (RefMethod)refElement;
        final List<PsiElement> psiElements = new ArrayList<PsiElement>();
        if (refMethod.isOnlyCallsSuper()) {
          deleteMethod(refMethod, psiElements, refElements);
        }
        else if (refMethod.hasBody() && hasEmptySuperImplementation(refMethod)) {
          deleteMethod(refMethod, psiElements, refElements);
        }
        else if (areAllImplementationsEmpty(refMethod)) {
          if (refMethod.hasBody()) {
            if (refMethod.getDerivedMethods().size() == 0) {
              if (refMethod.getSuperMethods().size() == 0) {
                deleteMethod(refMethod, psiElements, refElements);
              }
            }
            else {
              deleteHierarchy(refMethod, psiElements, refElements);
            }
          }
          else {
            deleteHierarchy(refMethod, psiElements, refElements);
          }
        }

        ArrayList<RefElement> deletedRefs = new ArrayList<RefElement>(1);
        for (RefElement element : refElements) {
          RefUtil.getInstance().removeRefElement(element, deletedRefs);
        }

        ApplicationManager.getApplication().invokeLater(new Runnable() {
          public void run() {
            SafeDeleteHandler.invoke(getContext().getProject(),
                                     psiElements.toArray(new PsiElement[psiElements.size()]), false);
          }
        });
      }
    }

    @NotNull
    public String getFamilyName() {
      return getName();
    }

    private void deleteHierarchy(RefMethod refMethod, List<PsiElement> result, List<RefElement> refElements) {
      Collection<RefMethod> derivedMethods = refMethod.getDerivedMethods();
      RefMethod[] refMethods = derivedMethods.toArray(new RefMethod[derivedMethods.size()]);
      for (RefMethod refDerived : refMethods) {
        deleteMethod(refDerived, result, refElements);
      }
      deleteMethod(refMethod, result, refElements);
    }

    private void deleteMethod(RefMethod refMethod, List<PsiElement> result, List<RefElement> refElements) {
      refElements.add(refMethod);
      PsiElement psiElement = refMethod.getElement();
      if (psiElement == null) return;
      if (!result.contains(psiElement)) result.add(psiElement);
    }
  }
}
