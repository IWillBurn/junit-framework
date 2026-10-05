/*
 * Copyright 2015-2026 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine.descriptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TemplateInvocationValidationException;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.jupiter.engine.extension.ExtensionRegistry;
import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.hierarchical.Node;

/**
 * Executes the invocations of a template: the invocation contexts of all
 * supported providers of one <em>level</em> are chained, numbered
 * continuously before filtering, and executed lazily as dynamic descendants of
 * the parent of the level.
 *
 * <p>For a template whose invocations are all on a single level, the parent is
 * the template itself; see {@link InvocationParent}.
 */
abstract class TemplateExecutor<P extends Extension, C> {

	private final TestDescriptor parent;
	private final Class<P> providerType;
	private final DynamicDescendantFilter dynamicDescendantFilter;

	<T extends TestDescriptor & Filterable> TemplateExecutor(T parent, Class<P> providerType) {
		this.parent = parent;
		this.providerType = providerType;
		this.dynamicDescendantFilter = parent.getDynamicDescendantFilter();
	}

	void execute(JupiterEngineExecutionContext context, Node.DynamicTestExecutor dynamicTestExecutor) {
		ExtensionContext extensionContext = context.getExtensionContext();
		List<P> providers = validateProviders(extensionContext, context.getExtensionRegistry());
		executeProviders(providers,
			new InvocationParent(this.parent, extensionContext, this.dynamicDescendantFilter, 0), dynamicTestExecutor);
	}

	/**
	 * Execute the invocations of the supplied active providers of the template.
	 *
	 * <p>The default implementation executes them as a single level directly
	 * below the template.
	 */
	void executeProviders(List<P> providers, InvocationParent invocationParent,
			Node.DynamicTestExecutor dynamicTestExecutor) {

		executeLevel(providers, invocationParent, dynamicTestExecutor);
	}

	/**
	 * Execute one level of invocations: the streams of the supplied providers
	 * are chained and invocation indices are counted continuously across them.
	 */
	final void executeLevel(List<P> providers, InvocationParent invocationParent,
			Node.DynamicTestExecutor dynamicTestExecutor) {

		AtomicInteger invocationIndex = new AtomicInteger();
		for (P provider : providers) {
			executeForProvider(provider, invocationIndex, invocationParent, dynamicTestExecutor);
		}
	}

	private void executeForProvider(P provider, AtomicInteger invocationIndex, InvocationParent invocationParent,
			Node.DynamicTestExecutor dynamicTestExecutor) {

		int initialValue = invocationIndex.get();
		ExtensionContext extensionContext = invocationParent.extensionContext();

		Stream<? extends C> stream = provideContexts(provider, extensionContext);
		try {
			stream.forEach(invocationContext -> createInvocationTestDescriptor(invocationContext,
				invocationIndex.incrementAndGet(), invocationParent) //
						.ifPresent(testDescriptor -> execute(dynamicTestExecutor, testDescriptor, invocationParent)));
		}
		catch (Throwable t) {
			try {
				stream.close();
			}
			catch (TemplateInvocationValidationException ignore) {
				// ignore exceptions from close() to avoid masking the original failure
			}
			throw ExceptionUtils.throwAsUncheckedException(t);
		}
		finally {
			stream.close();
		}

		Preconditions.condition(
			invocationIndex.get() != initialValue || mayReturnZeroContexts(provider, extensionContext),
			getZeroContextsProvidedErrorMessage(provider));
	}

	private List<P> validateProviders(ExtensionContext extensionContext, ExtensionRegistry extensionRegistry) {
		List<P> providers = extensionRegistry.stream(providerType) //
				.filter(provider -> supports(provider, extensionContext)) //
				.toList();
		return Preconditions.notEmpty(providers, this::getNoRegisteredProviderErrorMessage);
	}

	private Optional<TestDescriptor> createInvocationTestDescriptor(C invocationContext, int index,
			InvocationParent invocationParent) {
		UniqueId invocationUniqueId = createInvocationUniqueId(invocationParent.node().getUniqueId(), index);
		if (invocationParent.filter().test(invocationUniqueId, index - 1)) {
			return Optional.of(
				createInvocationTestDescriptor(invocationUniqueId, invocationContext, index, invocationParent));
		}
		return Optional.empty();
	}

	private void execute(Node.DynamicTestExecutor dynamicTestExecutor, TestDescriptor testDescriptor,
			InvocationParent invocationParent) {
		testDescriptor.setParent(invocationParent.node());
		dynamicTestExecutor.execute(testDescriptor);
	}

	abstract boolean supports(P provider, ExtensionContext extensionContext);

	protected abstract String getNoRegisteredProviderErrorMessage();

	abstract Stream<? extends C> provideContexts(P provider, ExtensionContext extensionContext);

	abstract boolean mayReturnZeroContexts(P provider, ExtensionContext extensionContext);

	protected abstract String getZeroContextsProvidedErrorMessage(P provider);

	abstract UniqueId createInvocationUniqueId(UniqueId parentUniqueId, int index);

	abstract TestDescriptor createInvocationTestDescriptor(UniqueId uniqueId, C invocationContext, int index);

	/**
	 * Create the descriptor of an invocation of the supplied level.
	 *
	 * <p>The default implementation ignores the level and delegates to
	 * {@link #createInvocationTestDescriptor(UniqueId, Object, int)}.
	 */
	TestDescriptor createInvocationTestDescriptor(UniqueId uniqueId, C invocationContext, int index,
			InvocationParent invocationParent) {
		return createInvocationTestDescriptor(uniqueId, invocationContext, index);
	}

	/**
	 * The parent of the invocations of one level.
	 *
	 * @param node the descriptor the invocations are added to
	 * @param extensionContext the extension context of {@code node} that is
	 * passed to the providers of the level
	 * @param filter the filter for the invocations of the level
	 * @param level the zero-based level of the invocations
	 */
	record InvocationParent(TestDescriptor node, ExtensionContext extensionContext, DynamicDescendantFilter filter,
			int level) {
	}

}
