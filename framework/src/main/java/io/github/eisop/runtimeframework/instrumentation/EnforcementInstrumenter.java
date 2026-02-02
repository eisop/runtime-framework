package io.github.eisop.runtimeframework.instrumentation;

import io.github.eisop.runtimeframework.core.CheckGenerator;
import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import io.github.eisop.runtimeframework.resolution.HierarchyResolver;
import io.github.eisop.runtimeframework.resolution.ParentMethod;
import io.github.eisop.runtimeframework.strategy.InstrumentationStrategy;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassModel;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.reflect.Modifier;
import java.util.List;

public class EnforcementInstrumenter extends RuntimeInstrumenter {

  private final InstrumentationStrategy strategy;
  private final HierarchyResolver hierarchyResolver;

  public EnforcementInstrumenter(
      InstrumentationStrategy strategy,
      HierarchyResolver hierarchyResolver,
      Filter<ClassInfo> safetyFilter) {
    super(safetyFilter);
    this.strategy = strategy;
    this.hierarchyResolver = hierarchyResolver;
  }

  @Override
  protected CodeTransform createCodeTransform(
      ClassModel classModel, MethodModel methodModel, boolean isCheckedScope, ClassLoader loader) {
    return new EnforcementTransform(strategy, classModel, methodModel, isCheckedScope, loader);
  }

  @Override
  protected void generateBridgeMethods(ClassBuilder builder, ClassModel model, ClassLoader loader) {
    for (ParentMethod parentMethod : hierarchyResolver.resolveUncheckedMethods(model, loader)) {
      if (strategy.shouldGenerateBridge(parentMethod)) {
        emitBridge(builder, parentMethod);
      }
    }
  }

  private void emitBridge(ClassBuilder builder, ParentMethod parentMethod) {
    MethodModel method = parentMethod.method();
    String methodName = method.methodName().stringValue();
    MethodTypeDesc desc = method.methodTypeSymbol();

    builder.withMethod(
        methodName,
        desc,
        Modifier.PUBLIC,
        methodBuilder -> {
          methodBuilder.withCode(
              codeBuilder -> {
                int slotIndex = 1;
                List<ClassDesc> paramTypes = desc.parameterList();

                // 1. Parameter Checks (Bridge acts as entry point)
                for (int i = 0; i < paramTypes.size(); i++) {
                  TypeKind type = TypeKind.from(paramTypes.get(i));
                  CheckGenerator target = strategy.getBridgeParameterCheck(parentMethod, i);
                  if (target != null) {
                    codeBuilder.aload(slotIndex);
                    target.generateCheck(
                        codeBuilder, type, "Parameter " + i + " in inherited method " + methodName);
                  }
                  slotIndex += type.slotSize();
                }

                // 2. Call Super
                codeBuilder.aload(0);
                slotIndex = 1;
                for (ClassDesc pType : paramTypes) {
                  TypeKind type = TypeKind.from(pType);
                  loadLocal(codeBuilder, type, slotIndex);
                  slotIndex += type.slotSize();
                }

                ClassDesc parentDesc =
                    ClassDesc.of(
                        parentMethod.owner().thisClass().asInternalName().replace('/', '.'));
                codeBuilder.invokespecial(parentDesc, methodName, desc);

                // 3. Return Check (Bridge acts as exit point)
                CheckGenerator returnTarget = strategy.getBridgeReturnCheck(parentMethod);
                if (returnTarget != null) {
                  codeBuilder.dup();
                  returnTarget.generateCheck(
                      codeBuilder,
                      TypeKind.REFERENCE,
                      "Return value of inherited method " + methodName);
                }

                returnResult(
                    codeBuilder, ClassDesc.ofDescriptor(desc.returnType().descriptorString()));
              });
        });
  }

  private void loadLocal(CodeBuilder b, TypeKind type, int slot) {
    switch (type) {
      case INT, BYTE, CHAR, SHORT, BOOLEAN -> b.iload(slot);
      case LONG -> b.lload(slot);
      case FLOAT -> b.fload(slot);
      case DOUBLE -> b.dload(slot);
      case REFERENCE -> b.aload(slot);
      default -> throw new IllegalArgumentException("Unknown type");
    }
  }

  private void returnResult(CodeBuilder b, ClassDesc returnType) {
    String desc = returnType.descriptorString();
    if (desc.equals("V")) b.return_();
    else if (desc.equals("I")
        || desc.equals("Z")
        || desc.equals("B")
        || desc.equals("S")
        || desc.equals("C")) b.ireturn();
    else if (desc.equals("J")) b.lreturn();
    else if (desc.equals("F")) b.freturn();
    else if (desc.equals("D")) b.dreturn();
    else b.areturn();
  }
}
