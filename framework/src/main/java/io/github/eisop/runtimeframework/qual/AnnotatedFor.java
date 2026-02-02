package io.github.eisop.runtimeframework.qual;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Indicates that this class has been annotated for the given type system.
 *
 * <p>This is a runtime-retention version of the Checker Framework's {@code
 * org.checkerframework.framework.qual.AnnotatedFor}. It allows the Runtime Framework agent to
 * detect which classes are intended to be checked at runtime without requiring build-time
 * configuration flags.
 *
 * @see <a
 *     href="https://checkerframework.org/api/org/checkerframework/framework/qual/AnnotatedFor.html">Original
 *     AnnotatedFor Documentation</a>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface AnnotatedFor {
  /**
   * Returns the type systems for which the class has been annotated. Legal arguments are any string
   * that may be passed to the {@code -processor} command-line argument: the fully-qualified class
   * name for the checker, or a shorthand for built-in checkers (e.g. "nullness").
   *
   * @return the type systems for which the class has been annotated
   */
  String[] value();
}
