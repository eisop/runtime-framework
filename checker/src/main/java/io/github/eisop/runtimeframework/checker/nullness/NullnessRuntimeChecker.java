package io.github.eisop.runtimeframework.checker.nullness;

import io.github.eisop.runtimeframework.core.RuntimeChecker;
import io.github.eisop.runtimeframework.core.TypeSystemConfiguration;
import io.github.eisop.runtimeframework.core.ValidationKind;
import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import io.github.eisop.runtimeframework.instrumentation.EnforcementInstrumenter;
import io.github.eisop.runtimeframework.instrumentation.RuntimeInstrumenter;
import io.github.eisop.runtimeframework.resolution.BytecodeHierarchyResolver;
import io.github.eisop.runtimeframework.resolution.HierarchyResolver;
import io.github.eisop.runtimeframework.strategy.InstrumentationStrategy;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class NullnessRuntimeChecker extends RuntimeChecker {

  @Override
  public String getName() {
    return "nullness";
  }

  @Override
  public RuntimeInstrumenter getInstrumenter(Filter<ClassInfo> filter) {
    NullnessCheckGenerator verifier = new NullnessCheckGenerator();

    TypeSystemConfiguration config =
        new TypeSystemConfiguration()
            .onEnforce(NonNull.class, verifier)
            .onNoop(Nullable.class)
            .withDefault(ValidationKind.ENFORCE, verifier);

    InstrumentationStrategy strategy = createStrategy(config, filter);

    HierarchyResolver resolver =
        new BytecodeHierarchyResolver(
            className -> filter.test(new ClassInfo(className.replace('.', '/'), null, null)));

    return new EnforcementInstrumenter(strategy, resolver, filter);
  }
}
