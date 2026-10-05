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

import static java.util.Collections.emptySet;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.engine.extension.MutableExtensionRegistry.createRegistryFrom;
import static org.junit.jupiter.engine.support.JupiterThrowableCollectorFactory.createThrowableCollector;

import java.lang.reflect.Method;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.jupiter.engine.extension.MutableExtensionRegistry;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.hierarchical.ExclusiveResource;
import org.junit.platform.engine.support.hierarchical.ThrowableCollector;

/**
 * Descriptor for an invocation of a composed test template that has nested
 * invocations, i.e., an invocation on a level that is not the innermost level
 * declared via {@link org.junit.jupiter.api.InvocationComposition @InvocationComposition}.
 *
 * <p>Modeled after {@link ClassTemplateInvocationTestDescriptor}: the
 * invocation is a dynamic container with its own extension context and
 * {@link ExtensionContext.Store Store}. The additional extensions of its
 * invocation context are registered once and inherited by all nested
 * invocations, and the invocation context is prepared exactly once. The
 * {@code Store} is closed after all nested invocations have finished.
 *
 * <p>Unlike {@link ClassTemplateInvocationTestDescriptor}, execution
 * conditions are evaluated for this descriptor.
 *
 * @since 6.2
 */
final class ComposedTestTemplateInvocationTestDescriptor extends MethodBasedTestDescriptor implements Filterable {

	private final int index;
	private final DynamicDescendantFilter dynamicDescendantFilter;
	private final NestedInvocationsExecutor nestedInvocationsExecutor;

	private @Nullable TestTemplateInvocationContext invocationContext;

	ComposedTestTemplateInvocationTestDescriptor(UniqueId uniqueId, Class<?> testClass, Method templateMethod,
			TestTemplateInvocationContext invocationContext, int index, JupiterConfiguration configuration,
			DynamicDescendantFilter dynamicDescendantFilter, NestedInvocationsExecutor nestedInvocationsExecutor) {

		super(uniqueId, invocationContext.getDisplayName(index), testClass, templateMethod, configuration);
		this.invocationContext = invocationContext;
		this.index = index;
		this.dynamicDescendantFilter = dynamicDescendantFilter;
		this.nestedInvocationsExecutor = nestedInvocationsExecutor;
	}

	// --- JupiterTestDescriptor -----------------------------------------------

	@Override
	protected ComposedTestTemplateInvocationTestDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
		return new ComposedTestTemplateInvocationTestDescriptor(uniqueIdTransformer.apply(getUniqueId()),
			getTestClass(), getTestMethod(), requiredInvocationContext(), this.index, this.configuration,
			this.dynamicDescendantFilter.copy(uniqueIdTransformer), this.nestedInvocationsExecutor);
	}

	// --- Filterable ----------------------------------------------------------

	@Override
	public DynamicDescendantFilter getDynamicDescendantFilter() {
		return this.dynamicDescendantFilter;
	}

	// --- TestDescriptor ------------------------------------------------------

	@Override
	public Type getType() {
		return Type.CONTAINER;
	}

	@Override
	public boolean mayRegisterTests() {
		return true;
	}

	@Override
	protected OptionalInt getLegacyReportingIndex() {
		return OptionalInt.of(this.index);
	}

	// --- Node ----------------------------------------------------------------

	@Override
	public Set<ExclusiveResource> getExclusiveResources() {
		// Resources are already collected and returned by the enclosing test template
		return emptySet();
	}

	@Override
	public JupiterEngineExecutionContext prepare(JupiterEngineExecutionContext context) {
		TestTemplateInvocationContext invocationContext = requiredInvocationContext();
		MutableExtensionRegistry registry = context.getExtensionRegistry();
		List<Extension> additionalExtensions = invocationContext.getAdditionalExtensions();
		if (!additionalExtensions.isEmpty()) {
			MutableExtensionRegistry childRegistry = createRegistryFrom(registry, Stream.empty());
			additionalExtensions.forEach(extension -> childRegistry.registerExtension(extension, invocationContext));
			registry = childRegistry;
		}
		// Test instances are only available for the PER_CLASS lifecycle.
		ExtensionContext extensionContext = new TestTemplateExtensionContext(context.getExtensionContext(),
			context.getExecutionListener(), this, context.getConfiguration(), registry,
			context.getLauncherStoreFacade(), context.getExtensionContext().getTestInstances().orElse(null));
		ThrowableCollector throwableCollector = createThrowableCollector();
		throwableCollector.execute(() -> invocationContext.prepareInvocation(extensionContext));
		return context.extend() //
				.withExtensionRegistry(registry) //
				.withExtensionContext(extensionContext) //
				.withThrowableCollector(throwableCollector) //
				.build();
	}

	@Override
	public JupiterEngineExecutionContext execute(JupiterEngineExecutionContext context,
			DynamicTestExecutor dynamicTestExecutor) {
		this.nestedInvocationsExecutor.execute(this, context.getExtensionContext(), dynamicTestExecutor);
		return context;
	}

	@Override
	public void cleanUp(JupiterEngineExecutionContext context) throws Exception {
		// forget invocationContext so it can be garbage collected
		this.invocationContext = null;
		super.cleanUp(context);
	}

	private TestTemplateInvocationContext requiredInvocationContext() {
		return requireNonNull(this.invocationContext);
	}

	/**
	 * Executes the invocations nested in an invocation that has nested
	 * invocations.
	 */
	@FunctionalInterface
	interface NestedInvocationsExecutor {

		void execute(ComposedTestTemplateInvocationTestDescriptor parent, ExtensionContext parentContext,
				DynamicTestExecutor dynamicTestExecutor);

	}

}
