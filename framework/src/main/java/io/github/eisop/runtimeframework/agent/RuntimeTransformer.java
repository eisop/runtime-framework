package io.github.eisop.runtimeframework.agent;

import io.github.eisop.runtimeframework.core.RuntimeChecker;
import io.github.eisop.runtimeframework.filter.AnnotatedForFilter;
import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import io.github.eisop.runtimeframework.instrumentation.RuntimeInstrumenter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public class RuntimeTransformer implements ClassFileTransformer {

  private final Filter<ClassInfo> scanFilter;
  private final Filter<ClassInfo> strategyFilter;
  private final RuntimeChecker checker;
  private final boolean trustAnnotatedFor;
  private final boolean isGlobalMode;
  private final AnnotatedForFilter annotatedForFilter;

  public RuntimeTransformer(
      Filter<ClassInfo> scanFilter,
      Filter<ClassInfo> strategyFilter,
      RuntimeChecker checker,
      boolean trustAnnotatedFor,
      boolean isGlobalMode) {
    this.scanFilter = scanFilter;
    this.strategyFilter = strategyFilter;
    this.checker = checker;
    this.trustAnnotatedFor = trustAnnotatedFor;
    this.isGlobalMode = isGlobalMode;
    this.annotatedForFilter = trustAnnotatedFor ? new AnnotatedForFilter(checker.getName()) : null;
  }

  @Override
  public byte[] transform(
      Module module,
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {

    if (className != null
        && (className.startsWith("java/")
            || className.startsWith("sun/")
            || className.startsWith("jdk/")
            || className.startsWith("org/gradle"))) {
      return null;
    }

    ClassInfo info = new ClassInfo(className, loader, module);

    if (!scanFilter.test(info)) {
      return null;
    }

    System.out.println("[RuntimeFramework] Processing: " + className);

    try {
      ClassFile cf = ClassFile.of();
      ClassModel classModel = cf.parse(classfileBuffer);

      boolean isChecked = strategyFilter.test(info);

      if (!isChecked && trustAnnotatedFor && annotatedForFilter != null) {
        if (annotatedForFilter.test(classModel, loader)) {
          System.out.println(
              "[RuntimeFramework] Auto-detected Checked Class/Package: " + className);
          isChecked = true;
        }
      }

      if (!isChecked && !isGlobalMode) {
        return null;
      }

      boolean finalIsChecked = isChecked;
      Filter<ClassInfo> dynamicFilter =
          ctx -> {
            ClassLoader effectiveLoader = ctx.loader();
            if (effectiveLoader == null) {
              effectiveLoader = loader;
            }
            ClassInfo effectiveCtx =
                new ClassInfo(ctx.internalName(), effectiveLoader, ctx.module());

            if (effectiveCtx.internalName().equals(className)) {
              return finalIsChecked;
            }
            if (trustAnnotatedFor
                && annotatedForFilter != null
                && annotatedForFilter.test(effectiveCtx)) {
              return true;
            }
            return strategyFilter.test(effectiveCtx);
          };

      RuntimeInstrumenter instrumenter = checker.getInstrumenter(dynamicFilter);
      return cf.transformClass(classModel, instrumenter.asClassTransform(classModel, loader));

    } catch (Throwable t) {
      System.err.println("[RuntimeFramework] CRASH transforming: " + className);
      t.printStackTrace();
      return null;
    }
  }
}
