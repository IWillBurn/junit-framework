/*
 * Copyright 2015-2026 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.api.extension;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;
import static org.apiguardian.api.API.Status.MAINTAINED;
import static org.apiguardian.api.API.Status.STABLE;

import java.util.stream.Stream;

import org.apiguardian.api.API;

/**
 * {@code TestTemplateInvocationContextProvider} defines the API for
 * {@link Extension Extensions} that wish to provide one or multiple contexts
 * for the invocation of a
 * {@link org.junit.jupiter.api.TestTemplate @TestTemplate} method.
 *
 * <p>This extension API makes it possible to execute a test template in
 * different contexts &mdash; for example, with different parameters, by
 * preparing the test class instance differently, or multiple times without
 * modifying the context.
 *
 * <p>This interface defines two main methods: {@link #supportsTestTemplate} and
 * {@link #provideTestTemplateInvocationContexts}. The former is called by the
 * framework to determine whether this extension wants to act on a test template
 * that is about to be executed. If so, the latter is called and must return a
 * {@link Stream} of {@link TestTemplateInvocationContext} instances. Otherwise,
 * this provider is ignored for the execution of the current test template.
 *
 * <p>A provider that has returned {@code true} from its {@link #supportsTestTemplate}
 * method is called <em>active</em>. When multiple providers are active for a
 * test template method, the {@code Streams} returned by their
 * {@link #provideTestTemplateInvocationContexts} methods will be chained, and
 * the test template method will be invoked using the contexts of all active
 * providers, unless an {@link org.junit.jupiter.api.InvocationComposition
 * @InvocationComposition} declaration places some of them on separate levels;
 * within a level, the {@code Streams} of all active providers are always
 * chained.
 *
 * <p>An active provider may return zero invocation contexts from its
 * {@link #provideTestTemplateInvocationContexts} method if it overrides
 * {@link #mayReturnZeroTestTemplateInvocationContexts} to return {@code true}.
 *
 * <h2>Nested Levels</h2>
 *
 * <p>If an {@code @InvocationComposition} declaration places a provider on a
 * nested level, the provider is asked for support and for invocation contexts
 * once per invocation of the enclosing level, each time with the extension
 * context of that enclosing invocation. Consequently, state that the provider
 * stores in the {@link ExtensionContext.Store Store} of the supplied context is
 * kept separately per enclosing invocation, while state stored in the
 * {@code Store} of an enclosing context remains visible to all nested
 * invocations. A provider whose invocations would contain nested invocations
 * must declare that it supports this via
 * {@link #mayEncloseTestTemplateInvocations}.
 *
 * <h2>Constructor Requirements</h2>
 *
 * <p>Consult the documentation in {@link Extension} for details on constructor
 * requirements.
 *
 * @since 5.0
 * @see org.junit.jupiter.api.TestTemplate
 * @see TestTemplateInvocationContext
 */
@API(status = STABLE, since = "5.0")
public interface TestTemplateInvocationContextProvider extends Extension {

	/**
	 * Determine if this provider supports providing invocation contexts for the
	 * test template method represented by the supplied {@code context}.
	 *
	 * @param context the extension context for the test template method about
	 * to be invoked or, if this provider forms a nested level of an
	 * {@link org.junit.jupiter.api.InvocationComposition @InvocationComposition},
	 * the extension context of the enclosing invocation; never {@code null}
	 * @return {@code true} if this provider can provide invocation contexts
	 * @see #provideTestTemplateInvocationContexts
	 * @see ExtensionContext
	 */
	boolean supportsTestTemplate(ExtensionContext context);

	/**
	 * Provide {@linkplain TestTemplateInvocationContext invocation contexts}
	 * for the test template method represented by the supplied {@code context}.
	 *
	 * <p>This method is only called by the framework if {@link #supportsTestTemplate}
	 * previously returned {@code true} for the same {@link ExtensionContext};
	 * this method is allowed to return an empty {@code Stream} but not {@code null}.
	 *
	 * <p>The returned {@code Stream} will be properly closed by calling
	 * {@link Stream#close()}, making it safe to use a resource such as
	 * {@link java.nio.file.Files#lines(java.nio.file.Path) Files.lines()}.
	 *
	 * <p>If this provider forms a nested level of an
	 * {@link org.junit.jupiter.api.InvocationComposition @InvocationComposition},
	 * {@link #supportsTestTemplate} and this method are invoked once per
	 * enclosing invocation, both with the extension context of that invocation.
	 *
	 * @param context the extension context for the test template method about
	 * to be invoked or, if this provider forms a nested level of an
	 * {@link org.junit.jupiter.api.InvocationComposition @InvocationComposition},
	 * the extension context of the enclosing invocation; never {@code null}
	 * @return a {@code Stream} of {@code TestTemplateInvocationContext}
	 * instances for the invocation of the test template method; never {@code null}
	 * @throws TemplateInvocationValidationException if validation fails while
	 * providing or closing the {@link Stream}
	 * @see #supportsTestTemplate
	 * @see ExtensionContext
	 */
	Stream<? extends TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context);

	/**
	 * Signal that this provider may provide zero
	 * {@linkplain TestTemplateInvocationContext invocation contexts} for the test
	 * template method represented by the supplied {@code context}.
	 *
	 * <p>If this method returns {@code false} (which is the default) and the
	 * provider returns an empty stream from
	 * {@link #provideTestTemplateInvocationContexts}, this will be considered
	 * an execution error. Override this method to return {@code true} to ignore
	 * the absence of invocation contexts for this provider.
	 *
	 * @param context the extension context for the test template method about
	 * to be invoked or, if this provider forms a nested level of an
	 * {@link org.junit.jupiter.api.InvocationComposition @InvocationComposition},
	 * the extension context of the enclosing invocation; never {@code null}
	 * @return {@code true} to allow zero contexts, {@code false} to fail
	 * execution in case of zero contexts
	 *
	 * @since 5.12
	 */
	@API(status = MAINTAINED, since = "5.13.3")
	default boolean mayReturnZeroTestTemplateInvocationContexts(ExtensionContext context) {
		return false;
	}

	/**
	 * Signal that the invocations provided by this provider may have nested
	 * invocations, i.e., that they may be placed on a level that is not the
	 * innermost one when an
	 * {@link org.junit.jupiter.api.InvocationComposition @InvocationComposition}
	 * declaration applies to the test template method.
	 *
	 * <p>If this method returns {@code true}, each invocation of this provider
	 * that has nested invocations is executed as a container instead of a test:
	 * <ul>
	 * <li>The {@linkplain TestTemplateInvocationContext#getAdditionalExtensions()
	 * additional extensions} of the invocation context are registered once for
	 * that invocation and apply to all invocations nested in it, possibly
	 * concurrently. For example, an {@link ExecutionCondition} is evaluated for
	 * that invocation and again for each nested invocation, while a
	 * {@link TestWatcher} or {@link ParameterResolver} acts on every nested
	 * test. Argument payloads held by those extensions are shared by all nested
	 * invocations accordingly.</li>
	 * <li>{@link TestTemplateInvocationContext#prepareInvocation(ExtensionContext)}
	 * is called once with the extension context of that invocation, which
	 * provides a test instance only if the
	 * {@link org.junit.jupiter.api.TestInstance.Lifecycle#PER_CLASS PER_CLASS}
	 * lifecycle is used. Resources stored in its
	 * {@link ExtensionContext.Store Store} are closed after all nested
	 * invocations have finished.</li>
	 * <li>The providers of the next level are asked for support and for
	 * invocation contexts with the extension context of that invocation.</li>
	 * </ul>
	 *
	 * <p>If this method returns {@code false} (which is the default) and this
	 * provider would have to provide invocations with nested invocations, the
	 * test template fails without executing any invocation.
	 *
	 * <p>This method is only called if an {@code @InvocationComposition}
	 * declaration places the active providers of the test template on more than
	 * one level, at most once per execution of the test template, and only for
	 * providers on a level that is not the innermost one. It is always called
	 * with the extension context for the test template method, even if this
	 * provider forms a nested level whose invocation contexts are later provided
	 * for the contexts of enclosing invocations.
	 *
	 * @param context the extension context for the test template method about
	 * to be invoked; never {@code null}
	 * @return {@code true} if invocations of this provider may have nested
	 * invocations, {@code false} otherwise
	 * @since 6.2
	 * @see org.junit.jupiter.api.InvocationComposition
	 */
	@API(status = EXPERIMENTAL, since = "6.2")
	default boolean mayEncloseTestTemplateInvocations(ExtensionContext context) {
		return false;
	}

}
