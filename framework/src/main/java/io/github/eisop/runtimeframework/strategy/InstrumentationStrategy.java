package io.github.eisop.runtimeframework.strategy;

import io.github.eisop.runtimeframework.core.CheckGenerator;
import io.github.eisop.runtimeframework.resolution.ParentMethod;
import java.lang.classfile.ClassModel;
import java.lang.classfile.FieldModel;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.constant.MethodTypeDesc;

/** Defines the rules for WHEN to inject a runtime check. */
public interface InstrumentationStrategy {

  /** Should we check this specific parameter at method entry? */
  CheckGenerator getParameterCheck(MethodModel method, int paramIndex, TypeKind type);

  /** Should we check a write to this field? */
  CheckGenerator getFieldWriteCheck(FieldModel field, TypeKind type);

  /** Should we check a read from this field? */
  CheckGenerator getFieldReadCheck(FieldModel field, TypeKind type);

  /** Should we check this return value? */
  CheckGenerator getReturnCheck(MethodModel method);

  /**
   * Should we check a write to a field in an EXTERNAL class? (Used when Unchecked code writes to
   * Checked code).
   */
  default CheckGenerator getBoundaryFieldWriteCheck(String owner, String fieldName, TypeKind type) {
    return null;
  }

  /** We are calling a method on 'owner'. Should we check the result? */
  CheckGenerator getBoundaryCallCheck(String owner, MethodTypeDesc desc);

  /** We are reading field from an EXTERNAL class. Should we check the value? */
  CheckGenerator getBoundaryFieldReadCheck(String owner, String fieldName, TypeKind type);

  /** Should we generate a bridge for this inherited method? */
  boolean shouldGenerateBridge(ParentMethod parentMethod);

  /** For a bridge we are generating, what check applies to this parameter? */
  CheckGenerator getBridgeParameterCheck(ParentMethod parentMethod, int paramIndex);

  /** For a bridge we are generating, what check applies to the return value? */
  default CheckGenerator getBridgeReturnCheck(ParentMethod parentMethod) {
    return null;
  }

  /** Should we check an value being stored into an array? */
  CheckGenerator getArrayStoreCheck(TypeKind componentType);

  /** Should we check a value being read from an array? */
  CheckGenerator getArrayLoadCheck(TypeKind componentType);

  /** Should we check a value being stored in a variable? */
  CheckGenerator getLocalVariableWriteCheck(MethodModel method, int slot, TypeKind type);

  /** Should we check the return of an unchecked override? */
  default CheckGenerator getUncheckedOverrideReturnCheck(
      ClassModel classModel, MethodModel method, ClassLoader loader) {
    return null;
  }
}
