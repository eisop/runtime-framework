package io.github.eisop.runtimeframework.core;

import io.github.eisop.runtimeframework.filter.ClassInfo;
import io.github.eisop.runtimeframework.filter.Filter;
import io.github.eisop.runtimeframework.instrumentation.RuntimeInstrumenter;
import io.github.eisop.runtimeframework.strategy.BoundaryStrategy;
import io.github.eisop.runtimeframework.strategy.InstrumentationStrategy;
import io.github.eisop.runtimeframework.strategy.StrictBoundaryStrategy;

/**
 * Represents a specific type system or check to be enforced (e.g., Nullness, Immutability). This
 * class acts as the configuration and factory for the instrumentation logic.
 */
public abstract class RuntimeChecker {

  /** Returns the name of this checker. This string should match the name used in AnnotatedFor */
  public abstract String getName();

  /**
   * Creates or returns the instrumenter that injects this checker's logic.
   *
   * @param filter The safety filter currently active in the Agent. The instrumenter should use this
   *     to determine boundary checks (Checked vs Unchecked).
   */
  public abstract RuntimeInstrumenter getInstrumenter(Filter<ClassInfo> filter);

  /**
   * Helper method to create the appropriate InstrumentationStrategy based on the framework's
   * configuration (e.g., -Druntime.global=true).
   *
   * <p>Subclasses should use this instead of manually checking system properties.
   *
   * @param config The TypeSystemConfiguration for this checker.
   * @param filter The filter defining the boundary between Checked and Unchecked code.
   * @return A configured InstrumentationStrategy (Standard or Global).
   */
  protected InstrumentationStrategy createStrategy(
      TypeSystemConfiguration config, Filter<ClassInfo> filter) {

    boolean isGlobalMode = Boolean.getBoolean("runtime.global");
    if (isGlobalMode) {
      return new StrictBoundaryStrategy(config, filter);
    } else {
      return new BoundaryStrategy(config, filter);
    }
  }
}
