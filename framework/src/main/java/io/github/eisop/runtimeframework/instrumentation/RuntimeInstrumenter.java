package io.github.eisop.runtimeframework.instrumentation;

import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassElement;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;

public abstract class RuntimeInstrumenter {

  protected final Filter<ClassInfo> scopeFilter;

  protected RuntimeInstrumenter(Filter<ClassInfo> scopeFilter) {
    this.scopeFilter = scopeFilter;
  }

  public ClassTransform asClassTransform(ClassModel classModel, ClassLoader loader) {
    boolean isCheckedScope =
        scopeFilter.test(new ClassInfo(classModel.thisClass().asInternalName(), loader, null));

    return new ClassTransform() {
      @Override
      public void accept(ClassBuilder classBuilder, ClassElement classElement) {
        if (classElement instanceof MethodModel methodModel && methodModel.code().isPresent()) {
          classBuilder.transformMethod(
              methodModel,
              (methodBuilder, methodElement) -> {
                if (methodElement instanceof CodeAttribute codeModel) {
                  // Use transformCode to delegate the iteration loop to the library.
                  // The CodeTransform (EnforcementTransform) handles stateful logic
                  // like inserting parameter checks at the beginning.
                  methodBuilder.transformCode(
                      codeModel,
                      createCodeTransform(classModel, methodModel, isCheckedScope, loader));
                } else {
                  methodBuilder.with(methodElement);
                }
              });
        } else {
          classBuilder.with(classElement);
        }
      }

      @Override
      public void atEnd(ClassBuilder builder) {
        if (isCheckedScope) {
          generateBridgeMethods(builder, classModel, loader);
        }
      }
    };
  }

  // Factory method to get the specific transform (Enforcement, Inference, etc.)
  protected abstract CodeTransform createCodeTransform(
      ClassModel classModel, MethodModel methodModel, boolean isCheckedScope, ClassLoader loader);

  protected abstract void generateBridgeMethods(
      ClassBuilder builder, ClassModel model, ClassLoader loader);
}
