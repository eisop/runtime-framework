package io.github.eisop.runtimeframework.instrumentation;

import io.github.eisop.runtimeframework.core.CheckGenerator;
import io.github.eisop.runtimeframework.strategy.InstrumentationStrategy;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.FieldModel;
import java.lang.classfile.Instruction;
import java.lang.classfile.MethodModel;
import java.lang.classfile.Opcode;
import java.lang.classfile.TypeKind;
import java.lang.classfile.instruction.ArrayLoadInstruction;
import java.lang.classfile.instruction.ArrayStoreInstruction;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.classfile.instruction.LineNumber;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.classfile.instruction.StoreInstruction;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Modifier;

/** A CodeTransform that injects runtime checks based on an InstrumentationStrategy. */
public class EnforcementTransform implements CodeTransform {

  private final InstrumentationStrategy strategy;
  private final ClassModel classModel;
  private final MethodModel methodModel;
  private final boolean isCheckedScope;
  private final ClassLoader loader;
  private boolean entryChecksEmitted;

  public EnforcementTransform(
      InstrumentationStrategy strategy,
      ClassModel classModel,
      MethodModel methodModel,
      boolean isCheckedScope,
      ClassLoader loader) {
    this.strategy = strategy;
    this.classModel = classModel;
    this.methodModel = methodModel;
    this.isCheckedScope = isCheckedScope;
    this.loader = loader;
    this.entryChecksEmitted = !isCheckedScope;
  }

  @Override
  public void accept(CodeBuilder builder, CodeElement element) {
    if (maybeEmitEntryChecks(builder, element)) {
      return;
    }

    switch (element) {
      case FieldInstruction f -> handleField(builder, f);
      case ReturnInstruction r -> handleReturn(builder, r);
      case InvokeInstruction i -> handleInvoke(builder, i);
      case ArrayStoreInstruction a -> handleArrayStore(builder, a);
      case ArrayLoadInstruction a -> handleArrayLoad(builder, a);
      case StoreInstruction s -> handleStore(builder, s);
      default -> builder.with(element);
    }
  }

  private boolean maybeEmitEntryChecks(CodeBuilder builder, CodeElement element) {
    if (entryChecksEmitted) {
      return false;
    }

    if (element instanceof LineNumber) {
      builder.with(element);
      emitParameterChecks(builder);
      entryChecksEmitted = true;
      return true;
    } else if (element instanceof Instruction) {
      emitParameterChecks(builder);
      entryChecksEmitted = true;
      return false;
    }

    return false;
  }

  private void handleField(CodeBuilder b, FieldInstruction f) {
    if (isFieldWrite(f)) {
      emitFieldWriteCheck(b, f);
      b.with(f);
    } else if (isFieldRead(f)) {
      b.with(f);
      if (isCheckedScope) {
        emitFieldReadCheck(b, f);
      }
    } else {
      b.with(f);
    }
  }

  private void handleReturn(CodeBuilder b, ReturnInstruction r) {
    if (isCheckedScope) {
      emitReturnCheck(b);
    } else {
      emitUncheckedReturnCheck(b, r);
    }
    b.with(r);
  }

  private void handleInvoke(CodeBuilder b, InvokeInstruction i) {
    b.with(i);
    if (isCheckedScope) {
      emitMethodCallCheck(b, i);
    }
  }

  private void handleArrayStore(CodeBuilder b, ArrayStoreInstruction a) {
    emitArrayStoreCheck(b, a);
    b.with(a);
  }

  private void handleArrayLoad(CodeBuilder b, ArrayLoadInstruction a) {
    b.with(a);
    if (isCheckedScope) {
      emitArrayLoadCheck(b, a);
    }
  }

  private void handleStore(CodeBuilder b, StoreInstruction s) {
    if (isCheckedScope) {
      emitStoreCheck(b, s);
    }
    b.with(s);
  }

  public void emitParameterChecks(CodeBuilder builder) {
    boolean isStatic = (methodModel.flags().flagsMask() & Modifier.STATIC) != 0;
    int slotIndex = isStatic ? 0 : 1;
    MethodTypeDesc methodDesc = methodModel.methodTypeSymbol();
    int paramCount = methodDesc.parameterList().size();

    for (int i = 0; i < paramCount; i++) {
      TypeKind type = TypeKind.from(methodDesc.parameterList().get(i));
      CheckGenerator target = strategy.getParameterCheck(methodModel, i, type);

      if (target != null) {
        builder.aload(slotIndex);
        target.generateCheck(builder, type, "Parameter " + i);
      }

      slotIndex += type.slotSize();
    }
  }

  private void emitFieldWriteCheck(CodeBuilder b, FieldInstruction field) {
    CheckGenerator target = null;
    TypeKind type = TypeKind.fromDescriptor(field.typeSymbol().descriptorString());

    if (field.owner().asInternalName().equals(classModel.thisClass().asInternalName())) {
      FieldModel targetField = findField(classModel, field);
      if (targetField != null) {
        target = strategy.getFieldWriteCheck(targetField, type);
      }
    } else {
      target =
          strategy.getBoundaryFieldWriteCheck(
              field.owner().asInternalName(), field.name().stringValue(), type);
    }

    if (target != null) {
      if (field.opcode() == Opcode.PUTSTATIC) {
        b.dup();
        target.generateCheck(b, type, "Static Field '" + field.name().stringValue() + "'");
      } else if (field.opcode() == Opcode.PUTFIELD) {
        b.dup_x1();
        target.generateCheck(b, type, "Field '" + field.name().stringValue() + "'");
        b.swap();
      }
    }
  }

  private void emitFieldReadCheck(CodeBuilder b, FieldInstruction field) {
    CheckGenerator target = null;
    TypeKind type = TypeKind.fromDescriptor(field.typeSymbol().descriptorString());

    if (field.owner().asInternalName().equals(classModel.thisClass().asInternalName())) {
      FieldModel targetField = findField(classModel, field);
      if (targetField != null) {
        target = strategy.getFieldReadCheck(targetField, type);
      }
    } else {
      target =
          strategy.getBoundaryFieldReadCheck(
              field.owner().asInternalName(), field.name().stringValue(), type);
    }

    if (target != null) {
      if (type.slotSize() == 1) {
        b.dup();
        target.generateCheck(b, type, "Read Field '" + field.name().stringValue() + "'");
      }
    }
  }

  private void emitReturnCheck(CodeBuilder b) {
    CheckGenerator target = strategy.getReturnCheck(methodModel);
    if (target != null) {
      b.dup();
      target.generateCheck(
          b, TypeKind.REFERENCE, "Return value of " + methodModel.methodName().stringValue());
    }
  }

  private void emitUncheckedReturnCheck(CodeBuilder b, ReturnInstruction ret) {
    if (ret.opcode() != Opcode.ARETURN) return;
    CheckGenerator target =
        strategy.getUncheckedOverrideReturnCheck(classModel, methodModel, loader);

    if (target != null) {
      b.dup();
      target.generateCheck(
          b,
          TypeKind.REFERENCE,
          "Return value of overridden method " + methodModel.methodName().stringValue());
    }
  }

  private void emitMethodCallCheck(CodeBuilder b, InvokeInstruction invoke) {
    CheckGenerator target =
        strategy.getBoundaryCallCheck(invoke.owner().asInternalName(), invoke.typeSymbol());

    if (target != null) {
      b.dup();
      target.generateCheck(
          b, TypeKind.REFERENCE, "Return value of " + invoke.name().stringValue() + " (Boundary)");
    }
  }

  private void emitArrayStoreCheck(CodeBuilder b, ArrayStoreInstruction instruction) {
    if (instruction.opcode() == Opcode.AASTORE) {
      CheckGenerator target = strategy.getArrayStoreCheck(TypeKind.REFERENCE);
      if (target != null) {
        b.dup();
        target.generateCheck(b, TypeKind.REFERENCE, "Array Element Write");
      }
    }
  }

  private void emitArrayLoadCheck(CodeBuilder b, ArrayLoadInstruction instruction) {
    if (instruction.opcode() == Opcode.AALOAD) {
      CheckGenerator target = strategy.getArrayLoadCheck(TypeKind.REFERENCE);
      if (target != null) {
        b.dup();
        target.generateCheck(b, TypeKind.REFERENCE, "Array Element Read");
      }
    }
  }

  private void emitStoreCheck(CodeBuilder b, StoreInstruction instruction) {
    boolean isRefStore =
        switch (instruction.opcode()) {
          case ASTORE, ASTORE_0, ASTORE_1, ASTORE_2, ASTORE_3 -> true;
          default -> false;
        };

    if (!isRefStore) return;

    int slot = instruction.slot();
    CheckGenerator target =
        strategy.getLocalVariableWriteCheck(methodModel, slot, TypeKind.REFERENCE);

    if (target != null) {
      b.dup();
      target.generateCheck(b, TypeKind.REFERENCE, "Local Variable Assignment (Slot " + slot + ")");
    }
  }

  private boolean isFieldWrite(FieldInstruction f) {
    return f.opcode() == Opcode.PUTFIELD || f.opcode() == Opcode.PUTSTATIC;
  }

  private boolean isFieldRead(FieldInstruction f) {
    return f.opcode() == Opcode.GETFIELD || f.opcode() == Opcode.GETSTATIC;
  }

  private FieldModel findField(ClassModel classModel, FieldInstruction field) {
    for (FieldModel fm : classModel.fields()) {
      if (fm.fieldName().stringValue().equals(field.name().stringValue())
          && fm.fieldType().stringValue().equals(field.type().stringValue())) {
        return fm;
      }
    }
    return null;
  }
}
