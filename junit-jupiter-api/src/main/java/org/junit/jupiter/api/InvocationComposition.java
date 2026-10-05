/*
 * Copyright 2015-2026 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * {@code @InvocationComposition} declares that the invocations provided by
 * certain {@linkplain org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
 * providers} of a {@linkplain TestTemplate test template} method form separate
 * <em>levels</em> of the test tree instead of being chained with the
 * invocations of the other providers.
 *
 * <p>Without any declaration, the invocation contexts of all active providers
 * of a test template are chained. Each annotation type listed in
 * {@link #levels()} adds a level <em>below</em> the chained invocations: every
 * invocation of the enclosing level becomes a container whose children are the
 * invocations of the providers that belong to the listed annotation type.
 * Levels are ordered from the outermost to the innermost as listed. For
 * example, the following test template executes all sets of arguments within
 * each of the three repetitions:
 *
 * <pre class="code">
 * {@literal @}RepeatedTest(3)
 * {@literal @}ParameterizedTest
 * {@literal @}ValueSource(strings = { "foo", "bar" })
 * {@literal @}InvocationComposition(levels = ParameterizedTest.class)
 * void test(String value) {
 *     // repetition 1 of 3 &gt; [1] value = "foo", [2] value = "bar"
 *     // repetition 2 of 3 &gt; [1] value = "foo", [2] value = "bar"
 *     // ...
 * }
 * </pre>
 *
 * <h2>Providers of an Annotation Type</h2>
 *
 * <p>An active provider <em>belongs to</em> an annotation type {@code A} if
 * {@code A} is directly or meta-annotated with
 * {@link org.junit.jupiter.api.extension.ExtendWith @ExtendWith} declaring the
 * provider's class. A provider that belongs to several listed annotation types
 * is placed on the level of the outermost one; several providers that belong to
 * the same level are chained on that level in registration order.
 *
 * <p>Providers that are registered in any other way &mdash; directly via
 * {@code @ExtendWith} on a test method or class, via
 * {@link org.junit.jupiter.api.extension.RegisterExtension @RegisterExtension},
 * via automatic extension registration, or by another extension &mdash; do not
 * belong to any annotation type and therefore always stay on the outermost
 * level. This includes Jupiter's built-in provider for
 * {@link RepeatedTest @RepeatedTest}: since {@code RepeatedTest} is not
 * annotated with {@code @ExtendWith}, listing it (or an annotation type
 * meta-annotated with it) is a configuration error, and repetitions always
 * stay on the outermost level of a composed test template, chained with the
 * invocations of the other providers that are not listed. To make a custom
 * provider eligible for a level, register it via {@code @ExtendWith} on a
 * dedicated annotation type and list that type.
 *
 * <h2>Enclosing Invocations</h2>
 *
 * <p>The invocations of a provider that are not on the innermost level have
 * nested invocations. Such a provider must declare that it supports this by
 * returning {@code true} from
 * {@link org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider#mayEncloseTestTemplateInvocations
 * mayEncloseTestTemplateInvocations()}; otherwise, the test template fails
 * before any invocation is executed. The providers of a nested level are asked
 * for support and for invocation contexts once per enclosing invocation, with
 * the extension context of that invocation; invocation indices are counted per
 * level and per enclosing invocation.
 *
 * <h2>Declarations</h2>
 *
 * <p>This annotation may be declared on a test method, on a test class (it then
 * applies to the test template methods of the class, its subclasses, and its
 * {@link Nested @Nested} classes), on a test interface, or as a meta-annotation,
 * for example on the annotation of an extension library. All declarations that
 * apply to a test method are combined into a single list of levels: the
 * <em>nearest</em> declaration contributes the <em>outermost</em> levels.
 * Declarations are visited in the following order, and an annotation type that
 * has already been listed by an earlier declaration is ignored.
 *
 * <ol>
 * <li>The test method.</li>
 * <li>The test class, followed by its superclasses from the nearest to the
 * farthest, followed by the interfaces implemented by these classes.</li>
 * <li>The {@linkplain org.junit.jupiter.api.extension.ExtensionContext#getEnclosingTestClasses()
 * enclosing test classes}, from the innermost to the outermost, each visited
 * like a test class.</li>
 * </ol>
 *
 * <p>On each element, declarations that are directly present (including those
 * contained in {@link InvocationCompositions @InvocationCompositions}) are
 * visited before declarations that are meta-present via other annotations. The
 * relative order of levels that are contributed by meta-present declarations
 * of <em>different</em> annotations on the same element, such as the
 * annotations of two independent extension libraries, is undefined; if such
 * levels are not ordered by a nearer declaration and providers of more than one
 * of them are active for a test template, the test template fails and asks for
 * an explicit declaration of the order.
 *
 * <p>Listing an annotation type that no provider can ever belong to is a
 * configuration error for every test template the declaration applies to.
 * Listing an annotation type that no <em>active</em> provider belongs to has no
 * effect. A declaration has no effect on a test template whose active providers
 * all end up on a single level.
 *
 * <p>This annotation never affects
 * {@linkplain org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider
 * class template invocation context providers}.
 *
 * @since 6.2
 * @see InvocationCompositions
 * @see TestTemplate
 * @see org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider
 */
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@Repeatable(InvocationCompositions.class)
@API(status = EXPERIMENTAL, since = "6.2")
public @interface InvocationComposition {

	/**
	 * Annotation types whose providers form nested levels, from the outermost
	 * to the innermost level.
	 *
	 * @return the annotation types of the nested levels; never {@code null}
	 */
	Class<? extends Annotation>[] levels();

}
