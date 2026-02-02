package io.github.eisop.runtimeframework.strategy;

import io.github.eisop.runtimeframework.core.CheckGenerator;
import io.github.eisop.runtimeframework.core.TypeSystemConfiguration;
import io.github.eisop.runtimeframework.core.ValidationKind;
import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import io.github.eisop.runtimeframework.qual.AnnotatedFor;
import java.io.IOException;
import java.io.InputStream;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.util.List;

public class StrictBoundaryStrategy extends BoundaryStrategy {

  private static final String ANNOTATED_FOR_DESC = AnnotatedFor.class.descriptorString();

  public StrictBoundaryStrategy(
      TypeSystemConfiguration configuration, Filter<ClassInfo> safetyFilter) {
    super(configuration, safetyFilter);
  }

  @Override
  public CheckGenerator getBoundaryFieldWriteCheck(String owner, String fieldName, TypeKind type) {
    if (isClassChecked(owner)) {
      if (type == TypeKind.REFERENCE) {
        if (isFieldOptOut(owner, fieldName)) {
          return null;
        }
        TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
        if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
          return defaultEntry.verifier();
        }
      }
    }
    return null;
  }

  @Override
  public CheckGenerator getUncheckedOverrideReturnCheck(
      ClassModel classModel, MethodModel method, ClassLoader loader) {
    String superName = classModel.superclass().map(sc -> sc.asInternalName()).orElse(null);
    if (superName == null || superName.equals("java/lang/Object")) return null;

    try {
      ClassModel parentModel = loadClassModel(superName, loader);
      while (parentModel != null
          && !parentModel.thisClass().asInternalName().equals("java/lang/Object")) {

        if (isClassCheckedModel(parentModel)) {
          for (MethodModel m : parentModel.methods()) {
            if (m.methodName().stringValue().equals(method.methodName().stringValue())) {
              if (m.methodTypeSymbol()
                  .descriptorString()
                  .equals(method.methodTypeSymbol().descriptorString())) {

                // Check parent method annotations
                if (hasNoopAnnotation(getMethodAnnotations(m))) return null;

                // Check return type annotations
                if (hasNoopAnnotation(getMethodReturnAnnotations(m))) return null;

                TypeKind returnType = TypeKind.from(m.methodTypeSymbol().returnType());
                if (returnType == TypeKind.REFERENCE) {
                  TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
                  if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
                    return defaultEntry.verifier();
                  }
                }
              }
            }
          }
        }

        String nextSuper = parentModel.superclass().map(sc -> sc.asInternalName()).orElse(null);
        if (nextSuper == null) break;
        parentModel = loadClassModel(nextSuper, loader);
      }
    } catch (Throwable e) {
      System.out.println("bytecode parsing fail in method override: " + e.getMessage());
    }
    return null;
  }

  private boolean isClassChecked(String internalName) {
    if (safetyFilter.test(new ClassInfo(internalName, null, null))) {
      return true;
    }
    try {
      // Load bytecode to check annotations
      ClassModel model =
          loadClassModel(internalName, Thread.currentThread().getContextClassLoader());
      return model != null && isClassCheckedModel(model);
    } catch (Throwable e) {
      System.out.println("Override check fail: " + e.getMessage());
    }
    return false;
  }

  private boolean isClassCheckedModel(ClassModel model) {
    return model
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .map(
            attr -> {
              for (Annotation anno : attr.annotations()) {
                if (anno.classSymbol().descriptorString().equals(ANNOTATED_FOR_DESC)) {
                  return true;
                }
              }
              return false;
            })
        .orElse(false);
  }

  private boolean isFieldOptOut(String owner, String fieldName) {
    try {
      ClassModel model = loadClassModel(owner, Thread.currentThread().getContextClassLoader());
      if (model == null) return false;

      for (FieldModel field : model.fields()) {
        if (field.fieldName().stringValue().equals(fieldName)) {
          if (hasNoopAnnotation(getFieldAnnotations(field))) return true;
        }
      }
    } catch (Throwable t) {
      System.out.println("bytecode fail in is field opt out");
    }
    return false;
  }

  private ClassModel loadClassModel(String internalName, ClassLoader loader) {
    String resource = internalName + ".class";
    try (InputStream is =
        (loader != null)
            ? loader.getResourceAsStream(resource)
            : ClassLoader.getSystemResourceAsStream(resource)) {
      if (is == null) return null;
      return ClassFile.of().parse(is.readAllBytes());
    } catch (IOException e) {
      return null;
    }
  }

  private boolean hasNoopAnnotation(List<Annotation> annotations) {
    for (Annotation anno : annotations) {
      String desc = anno.classSymbol().descriptorString();
      TypeSystemConfiguration.ConfigEntry entry = configuration.find(desc);
      if (entry != null && entry.kind() == ValidationKind.NOOP) return true;
    }
    return false;
  }
}
