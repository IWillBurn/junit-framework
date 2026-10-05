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

import static java.util.stream.Collectors.joining;
import static org.apiguardian.api.API.Status.INTERNAL;
import static org.junit.jupiter.engine.descriptor.ExtensionUtils.populateNewExtensionRegistryFromExtendWithAnnotation;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.apiguardian.api.API;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstances;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.engine.config.JupiterConfiguration;
import org.junit.jupiter.engine.execution.JupiterEngineExecutionContext;
import org.junit.jupiter.engine.extension.MutableExtensionRegistry;
import org.junit.platform.commons.util.ExceptionUtils;
import org.junit.platform.commons.util.Preconditions;
import org.junit.platform.commons.util.UnrecoverableExceptions;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;

/**
 * {@link TestDescriptor} for {@link org.junit.jupiter.api.TestTemplate @TestTemplate}
 * methods.
 *
 * @since 5.0
 */
@API(status = INTERNAL, since = "5.0")
public class TestTemplateTestDescriptor extends MethodBasedTestDescriptor implements Filterable {

	public static final String SEGMENT_TYPE = "test-template";
	private final DynamicDescendantFilter dynamicDescendantFilter;

	public TestTemplateTestDescriptor(UniqueId uniqueId, Class<?> testClass, Method templateMethod,
			Supplier<List<Class<?>>> enclosingInstanceTypes, JupiterConfiguration configuration) {
		super(uniqueId, testClass, templateMethod, enclosingInstanceTypes, configuration);
		this.dynamicDescendantFilter = new DynamicDescendantFilter();
	}

	private TestTemplateTestDescriptor(UniqueId uniqueId, String displayName, Class<?> testClass, Method templateMethod,
			JupiterConfiguration configuration, DynamicDescendantFilter dynamicDescendantFilter) {
		super(uniqueId, displayName, testClass, templateMethod, configuration);
		this.dynamicDescendantFilter = dynamicDescendantFilter;
	}

	// --- JupiterTestDescriptor -----------------------------------------------

	@Override
	protected TestTemplateTestDescriptor withUniqueId(UnaryOperator<UniqueId> uniqueIdTransformer) {
		return new TestTemplateTestDescriptor(uniqueIdTransformer.apply(getUniqueId()), getDisplayName(),
			getTestClass(), getTestMethod(), this.configuration,
			this.dynamicDescendantFilter.copy(uniqueIdTransformer));
	}

	// --- Filterable ----------------------------------------------------------

	@Override
	public DynamicDescendantFilter getDynamicDescendantFilter() {
		return dynamicDescendantFilter;
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

	// --- Node ----------------------------------------------------------------

	@Override
	public JupiterEngineExecutionContext prepare(JupiterEngineExecutionContext context) {
		MutableExtensionRegistry registry = populateNewExtensionRegistryFromExtendWithAnnotation(
			context.getExtensionRegistry(), getTestMethod());

		// The test instance should be properly maintained by the enclosing class's ExtensionContext.
		TestInstances testInstances = context.getExtensionContext().getTestInstances().orElse(null);

		ExtensionContext extensionContext = new TestTemplateExtensionContext(context.getExtensionContext(),
			context.getExecutionListener(), this, context.getConfiguration(), registry,
			context.getLauncherStoreFacade(), testInstances);

		// @formatter:off
		return context.extend()
				.withExtensionRegistry(registry)
				.withExtensionContext(extensionContext)
				.build();
		// @formatter:on
	}

	@Override
	public JupiterEngineExecutionContext execute(JupiterEngineExecutionContext context,
			DynamicTestExecutor dynamicTestExecutor) throws Exception {

		new TestTemplateExecutor().execute(context, dynamicTestExecutor);
		return context;
	}

	private class TestTemplateExecutor
			extends TemplateExecutor<TestTemplateInvocationContextProvider, TestTemplateInvocationContext> {

		TestTemplateExecutor() {
			super(TestTemplateTestDescriptor.this, TestTemplateInvocationContextProvider.class);
		}

		@Override
		void executeProviders(List<TestTemplateInvocationContextProvider> providers, InvocationParent invocationParent,
				DynamicTestExecutor dynamicTestExecutor) {

			InvocationCompositionPlan plan = InvocationCompositionPlan.create(providers,
				invocationParent.extensionContext(), TestTemplateTestDescriptor.this);
			if (plan.isComposed()) {
				new ComposedTestTemplateExecutor(plan).executeComposedLevel(plan.getProviders(0), invocationParent,
					dynamicTestExecutor);
			}
			else {
				super.executeProviders(providers, invocationParent, dynamicTestExecutor);
			}
		}

		@Override
		boolean supports(TestTemplateInvocationContextProvider provider, ExtensionContext extensionContext) {
			return provider.supportsTestTemplate(extensionContext);
		}

		@Override
		protected String getNoRegisteredProviderErrorMessage() {
			return "You must register at least one %s that supports @%s method [%s]".formatted(
				TestTemplateInvocationContextProvider.class.getSimpleName(), TestTemplate.class.getSimpleName(),
				getTestMethod());
		}

		@Override
		Stream<? extends TestTemplateInvocationContext> provideContexts(TestTemplateInvocationContextProvider provider,
				ExtensionContext extensionContext) {
			return provider.provideTestTemplateInvocationContexts(extensionContext);
		}

		@Override
		boolean mayReturnZeroContexts(TestTemplateInvocationContextProvider provider,
				ExtensionContext extensionContext) {
			return provider.mayReturnZeroTestTemplateInvocationContexts(extensionContext);
		}

		@Override
		protected String getZeroContextsProvidedErrorMessage(TestTemplateInvocationContextProvider provider) {
			return """
					Provider [%s] did not provide any invocation contexts, but was expected to do so. \
					You may override mayReturnZeroTestTemplateInvocationContexts() to allow this.""".formatted(
				provider.getClass().getSimpleName());
		}

		@Override
		UniqueId createInvocationUniqueId(UniqueId parentUniqueId, int index) {
			return parentUniqueId.append(TestTemplateInvocationTestDescriptor.SEGMENT_TYPE, "#" + index);
		}

		@Override
		TestDescriptor createInvocationTestDescriptor(UniqueId uniqueId,
				TestTemplateInvocationContext invocationContext, int index) {
			return new TestTemplateInvocationTestDescriptor(uniqueId, getTestClass(), getTestMethod(),
				invocationContext, index, TestTemplateTestDescriptor.this.configuration);
		}
	}

	/**
	 * Executor for a test template whose active providers are placed on
	 * several levels by {@link org.junit.jupiter.api.InvocationComposition
	 * @InvocationComposition} declarations.
	 *
	 * <p>Each invocation that is not on the innermost level is executed as a
	 * {@link ComposedTestTemplateInvocationTestDescriptor} whose nested
	 * invocations are provided by the providers of the next level with the
	 * extension context of that invocation.
	 */
	private final class ComposedTestTemplateExecutor extends TestTemplateExecutor {

		private final InvocationCompositionPlan plan;

		ComposedTestTemplateExecutor(InvocationCompositionPlan plan) {
			this.plan = plan;
		}

		void executeComposedLevel(List<TestTemplateInvocationContextProvider> providers,
				InvocationParent invocationParent, DynamicTestExecutor dynamicTestExecutor) {
			try {
				executeLevel(providers, invocationParent, dynamicTestExecutor);
			}
			catch (Throwable t) {
				UnrecoverableExceptions.rethrowIfUnrecoverable(t);
				// Do not close the Store of the parent before its already submitted
				// invocations have finished.
				awaitSubmittedInvocations(dynamicTestExecutor, t);
				throw ExceptionUtils.throwAsUncheckedException(t);
			}
		}

		private void executeNestedLevel(int level, ComposedTestTemplateInvocationTestDescriptor parent,
				ExtensionContext parentContext, DynamicTestExecutor dynamicTestExecutor) {

			List<TestTemplateInvocationContextProvider> providers = this.plan.getProviders(level).stream() //
					.filter(provider -> provider.supportsTestTemplate(parentContext)) //
					.toList();
			Preconditions.notEmpty(providers,
				() -> """
						None of the providers %s of nested level [%d] of @TestTemplate method [%s] supports the \
						enclosing invocation [%s]. A provider that does not apply to a particular enclosing invocation \
						should provide no invocation contexts and override mayReturnZeroTestTemplateInvocationContexts() \
						instead.""".formatted(
					this.plan.getProviders(level).stream().map(it -> it.getClass().getSimpleName()).collect(
						joining(", ", "[", "]")),
					level + 1, InvocationCompositionPlan.methodSignature(getTestMethod()), parent.getDisplayName()));
			executeComposedLevel(providers,
				new InvocationParent(parent, parentContext, parent.getDynamicDescendantFilter(), level),
				dynamicTestExecutor);
		}

		@Override
		TestDescriptor createInvocationTestDescriptor(UniqueId uniqueId,
				TestTemplateInvocationContext invocationContext, int index, InvocationParent invocationParent) {

			int level = invocationParent.level();
			if (this.plan.isInnermost(level)) {
				return createInvocationTestDescriptor(uniqueId, invocationContext, index);
			}
			return new ComposedTestTemplateInvocationTestDescriptor(uniqueId, getTestClass(), getTestMethod(),
				invocationContext, index, TestTemplateTestDescriptor.this.configuration,
				invocationParent.filter().forDescendantsOf(uniqueId, index - 1),
				(parent, parentContext, dynamicTestExecutor) -> executeNestedLevel(level + 1, parent, parentContext,
					dynamicTestExecutor));
		}

		private static void awaitSubmittedInvocations(DynamicTestExecutor dynamicTestExecutor, Throwable original) {
			try {
				dynamicTestExecutor.awaitFinished();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				original.addSuppressed(e);
			}
			catch (Throwable t) {
				UnrecoverableExceptions.rethrowIfUnrecoverable(t);
				original.addSuppressed(t);
			}
		}
	}
}
