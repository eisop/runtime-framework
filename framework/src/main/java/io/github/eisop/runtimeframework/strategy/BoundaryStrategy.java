package io.github.eisop.runtimeframework.strategy;

import io.github.eisop.runtimeframework.core.CheckGenerator;
import io.github.eisop.runtimeframework.core.TypeSystemConfiguration;
import io.github.eisop.runtimeframework.core.ValidationKind;
import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import io.github.eisop.runtimeframework.resolution.ParentMethod;
import io.github.eisop.runtimeframework.runtime.AttributionKind;
import java.lang.classfile.Annotation;
import java.lang.classfile.Attributes;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.TypeKind;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.List;

public class BoundaryStrategy implements InstrumentationStrategy {

  protected final TypeSystemConfiguration configuration;
  protected final Filter<ClassInfo> safetyFilter;

  public BoundaryStrategy(TypeSystemConfiguration configuration, Filter<ClassInfo> safetyFilter) {
    this.configuration = configuration;
    this.safetyFilter = safetyFilter;
  }

  protected CheckGenerator resolveGenerator(List<Annotation> annotations) {
    for (Annotation a : annotations) {
      String desc = a.classSymbol().descriptorString();
      TypeSystemConfiguration.ConfigEntry entry = configuration.find(desc);
      if (entry != null) {
        if (entry.kind() == ValidationKind.ENFORCE) {
          return entry.verifier();
        } else if (entry.kind() == ValidationKind.NOOP) {
          return null;
        }
      }
    }

    TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
    if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
      return defaultEntry.verifier();
    }

    return null;
  }

  @Override
  public CheckGenerator getParameterCheck(MethodModel method, int paramIndex, TypeKind type) {
    if (type != TypeKind.REFERENCE) return null;
    List<Annotation> annos = getMethodParamAnnotations(method, paramIndex);
    CheckGenerator generator = resolveGenerator(annos);
    return (generator != null) ? generator.withAttribution(AttributionKind.CALLER) : null;
  }

  @Override
  public CheckGenerator getFieldWriteCheck(FieldModel field, TypeKind type) {
    return null;
  }

  @Override
  public CheckGenerator getFieldReadCheck(FieldModel field, TypeKind type) {
    if (type != TypeKind.REFERENCE) return null;
    List<Annotation> annos = getFieldAnnotations(field);
    return resolveGenerator(annos);
  }

  @Override
  public CheckGenerator getReturnCheck(MethodModel method) {
    return null;
  }

  @Override
  public CheckGenerator getLocalVariableWriteCheck(MethodModel method, int slot, TypeKind type) {
    if (type != TypeKind.REFERENCE) return null;
    List<Annotation> annos = getLocalVariableAnnotations(method, slot);
    return resolveGenerator(annos);
  }

  @Override
  public CheckGenerator getArrayStoreCheck(TypeKind componentType) {
    if (componentType == TypeKind.REFERENCE) {
      TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
      if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
        return defaultEntry.verifier();
      }
    }
    return null;
  }

  @Override
  public CheckGenerator getArrayLoadCheck(TypeKind componentType) {
    if (componentType == TypeKind.REFERENCE) {
      TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
      if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
        return defaultEntry.verifier();
      }
    }
    return null;
  }

  @Override
  public CheckGenerator getBoundaryCallCheck(String owner, MethodTypeDesc desc) {
    boolean isUnchecked = !safetyFilter.test(new ClassInfo(owner, null, null));
    TypeKind returnType = TypeKind.from(desc.returnType());

    if (isUnchecked && returnType == TypeKind.REFERENCE) {
      TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
      if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
        return defaultEntry.verifier();
      }
    }
    return null;
  }

  @Override
  public CheckGenerator getBoundaryFieldReadCheck(String owner, String fieldName, TypeKind type) {
    boolean isUnchecked = !safetyFilter.test(new ClassInfo(owner, null, null));
    if (isUnchecked && type == TypeKind.REFERENCE) {
      TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
      if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
        return defaultEntry.verifier();
      }
    }
    return null;
  }

  @Override
  public boolean shouldGenerateBridge(ParentMethod parentMethod) {
    if (parentMethod.owner().thisClass().asInternalName().equals("java/lang/Object")) return false;

    MethodModel method = parentMethod.method();
    var paramTypes = method.methodTypeSymbol().parameterList();

    // 1. Check Parameters
    for (int i = 0; i < paramTypes.size(); i++) {
      boolean explicitNoop = false;
      boolean explicitEnforce = false;

      List<Annotation> annos = getMethodParamAnnotations(method, i);

      for (Annotation anno : annos) {
        String desc = anno.classSymbol().descriptorString();
        TypeSystemConfiguration.ConfigEntry entry = configuration.find(desc);
        if (entry != null) {
          if (entry.kind() == ValidationKind.ENFORCE) explicitEnforce = true;
          if (entry.kind() == ValidationKind.NOOP) explicitNoop = true;
        }
      }

      if (explicitEnforce) return true;

      TypeKind pType = TypeKind.from(paramTypes.get(i));
      if (pType == TypeKind.REFERENCE && !explicitNoop) {
        TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
        if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
          return true;
        }
      }
    }

    TypeKind returnType = TypeKind.from(method.methodTypeSymbol().returnType());
    if (returnType == TypeKind.REFERENCE) {
      boolean explicitNoop = false;
      boolean explicitEnforce = false;

      List<Annotation> annos = getMethodReturnAnnotations(method);

      for (Annotation anno : annos) {
        String desc = anno.classSymbol().descriptorString();
        TypeSystemConfiguration.ConfigEntry entry = configuration.find(desc);
        if (entry != null) {
          if (entry.kind() == ValidationKind.ENFORCE) explicitEnforce = true;
          if (entry.kind() == ValidationKind.NOOP) explicitNoop = true;
        }
      }

      if (explicitEnforce) return true;

      if (!explicitNoop) {
        TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
        if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
          return true;
        }
      }
    }

    return false;
  }

  @Override
  public CheckGenerator getBridgeParameterCheck(ParentMethod parentMethod, int paramIndex) {
    MethodModel method = parentMethod.method();
    List<Annotation> annos = getMethodParamAnnotations(method, paramIndex);

    CheckGenerator generator = resolveGenerator(annos);
    if (generator != null) return generator.withAttribution(AttributionKind.CALLER);

    // Check default
    var paramTypes = method.methodTypeSymbol().parameterList();
    TypeKind pType = TypeKind.from(paramTypes.get(paramIndex));

    if (pType == TypeKind.REFERENCE) {
      boolean isExplicitNoop = false;
      for (Annotation a : annos) {
        TypeSystemConfiguration.ConfigEntry entry =
            configuration.find(a.classSymbol().descriptorString());
        if (entry != null && entry.kind() == ValidationKind.NOOP) isExplicitNoop = true;
      }

      if (!isExplicitNoop) {
        TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
        if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
          return defaultEntry.verifier().withAttribution(AttributionKind.CALLER);
        }
      }
    }
    return null;
  }

  @Override
  public CheckGenerator getBridgeReturnCheck(ParentMethod parentMethod) {
    MethodModel method = parentMethod.method();
    TypeKind returnType = TypeKind.from(method.methodTypeSymbol().returnType());
    if (returnType != TypeKind.REFERENCE) return null;

    List<Annotation> annos = getMethodReturnAnnotations(method);

    CheckGenerator generator = resolveGenerator(annos);
    if (generator != null) return generator.withAttribution(AttributionKind.CALLER);

    boolean isExplicitNoop = false;
    for (Annotation a : annos) {
      TypeSystemConfiguration.ConfigEntry entry =
          configuration.find(a.classSymbol().descriptorString());
      if (entry != null && entry.kind() == ValidationKind.NOOP) isExplicitNoop = true;
    }

    if (!isExplicitNoop) {
      TypeSystemConfiguration.ConfigEntry defaultEntry = configuration.getDefault();
      if (defaultEntry != null && defaultEntry.kind() == ValidationKind.ENFORCE) {
        return defaultEntry.verifier().withAttribution(AttributionKind.CALLER);
      }
    }
    return null;
  }

  protected List<Annotation> getMethodAnnotations(MethodModel method) {
    List<Annotation> result = new ArrayList<>();
    method
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(attr -> result.addAll(attr.annotations()));
    return result;
  }

  protected List<Annotation> getMethodParamAnnotations(MethodModel method, int paramIndex) {
    List<Annotation> result = new ArrayList<>();
    method
        .findAttribute(Attributes.runtimeVisibleParameterAnnotations())
        .ifPresent(
            attr -> {
              List<List<Annotation>> all = attr.parameterAnnotations();
              if (paramIndex < all.size()) result.addAll(all.get(paramIndex));
            });
    method
        .findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .ifPresent(
            attr -> {
              for (TypeAnnotation ta : attr.annotations()) {
                if (ta.targetInfo() instanceof TypeAnnotation.FormalParameterTarget pt
                    && pt.formalParameterIndex() == paramIndex) result.add(ta.annotation());
              }
            });
    return result;
  }

  protected List<Annotation> getMethodReturnAnnotations(MethodModel method) {
    List<Annotation> result = new ArrayList<>();
    method
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(attr -> result.addAll(attr.annotations()));
    method
        .findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .ifPresent(
            attr -> {
              for (TypeAnnotation ta : attr.annotations()) {
                if (ta.targetInfo().targetType() == TypeAnnotation.TargetType.METHOD_RETURN) {
                  result.add(ta.annotation());
                }
              }
            });
    return result;
  }

  protected List<Annotation> getFieldAnnotations(FieldModel field) {
    List<Annotation> result = new ArrayList<>();
    field
        .findAttribute(Attributes.runtimeVisibleAnnotations())
        .ifPresent(attr -> result.addAll(attr.annotations()));
    field
        .findAttribute(Attributes.runtimeVisibleTypeAnnotations())
        .ifPresent(
            attr -> {
              for (TypeAnnotation ta : attr.annotations()) {
                if (ta.targetInfo().targetType() == TypeAnnotation.TargetType.FIELD) {
                  result.add(ta.annotation());
                }
              }
            });
    return result;
  }

  protected List<Annotation> getLocalVariableAnnotations(MethodModel method, int slot) {
    List<Annotation> result = new ArrayList<>();
    method
        .code()
        .ifPresent(
            code -> {
              code.findAttribute(Attributes.runtimeVisibleTypeAnnotations())
                  .ifPresent(
                      attr -> {
                        for (TypeAnnotation ta : attr.annotations()) {
                          if (ta.targetInfo() instanceof TypeAnnotation.LocalVarTarget localVar) {
                            for (TypeAnnotation.LocalVarTargetInfo info : localVar.table()) {
                              if (info.index() == slot) {
                                result.add(ta.annotation());
                              }
                            }
                          }
                        }
                      });
            });
    return result;
  }
}
