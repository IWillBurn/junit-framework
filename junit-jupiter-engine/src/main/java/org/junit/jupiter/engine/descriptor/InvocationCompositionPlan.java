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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.InvocationComposition;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.engine.descriptor.InvocationCompositionDeclarations.Placement;
import org.junit.jupiter.engine.descriptor.InvocationCompositionDeclarations.Slot;
import org.junit.platform.commons.util.ClassUtils;

/**
 * The levels of a test template's invocations, from the outermost to the
 * innermost level, each with the active providers whose invocations are
 * chained on that level.
 *
 * <p>Without any applicable {@link InvocationComposition @InvocationComposition}
 * declaration, the plan has a single level and no extension method is invoked
 * while creating it.
 *
 * @since 6.2
 */
final class InvocationCompositionPlan {

	private static final InvocationCompositionPlan SINGLE_LEVEL = new InvocationCompositionPlan(List.of());

	private final List<List<TestTemplateInvocationContextProvider>> levels;

	private InvocationCompositionPlan(List<List<TestTemplateInvocationContextProvider>> levels) {
		this.levels = levels;
	}

	/**
	 * Plan the levels of the supplied active providers of a test template.
	 *
	 * @param activeProviders the providers that support the test template, in
	 * registration order
	 * @param templateContext the extension context of the test template
	 * @param descriptor the descriptor of the test template
	 * @return the plan; never {@code null}
	 * @throws ExtensionConfigurationException if the declarations are invalid
	 * or ambiguous for the active providers, or if a provider whose invocations
	 * would contain nested invocations does not support this
	 */
	static InvocationCompositionPlan create(List<TestTemplateInvocationContextProvider> activeProviders,
			ExtensionContext templateContext, MethodBasedTestDescriptor descriptor) {

		InvocationCompositionDeclarations declarations = InvocationCompositionDeclarations.find(
			descriptor.getTestMethod(), descriptor.getTestClass(), descriptor.getEnclosingTestClasses());
		if (declarations.isEmpty()) {
			return SINGLE_LEVEL;
		}

		List<TestTemplateInvocationContextProvider> unlisted = new ArrayList<>();
		SortedMap<Integer, List<TestTemplateInvocationContextProvider>> listed = new TreeMap<>();
		Map<Integer, Set<Class<? extends Annotation>>> usedTypes = new LinkedHashMap<>();
		for (TestTemplateInvocationContextProvider provider : activeProviders) {
			Optional<Placement> placement = declarations.placementOf(provider);
			if (placement.isPresent()) {
				int slotIndex = placement.get().slotIndex();
				listed.computeIfAbsent(slotIndex, __ -> new ArrayList<>()).add(provider);
				usedTypes.computeIfAbsent(slotIndex, __ -> new LinkedHashSet<>()).add(placement.get().type());
			}
			else {
				unlisted.add(provider);
			}
		}

		usedTypes.forEach((slotIndex, types) -> {
			if (types.size() > 1) {
				throw new ExtensionConfigurationException(
					ambiguousLevelsMessage(descriptor.getTestMethod(), declarations.getSlots().get(slotIndex), types));
			}
		});

		List<List<TestTemplateInvocationContextProvider>> levels = new ArrayList<>();
		if (!unlisted.isEmpty()) {
			levels.add(List.copyOf(unlisted));
		}
		listed.values().forEach(providers -> levels.add(List.copyOf(providers)));
		if (levels.size() < 2) {
			return SINGLE_LEVEL;
		}

		for (int level = 0; level < levels.size() - 1; level++) {
			for (TestTemplateInvocationContextProvider provider : levels.get(level)) {
				if (!provider.mayEncloseTestTemplateInvocations(templateContext)) {
					throw new ExtensionConfigurationException(
						notEnclosingMessage(provider, levels.get(level + 1), descriptor.getTestMethod()));
				}
			}
		}
		return new InvocationCompositionPlan(List.copyOf(levels));
	}

	boolean isComposed() {
		return this.levels.size() > 1;
	}

	boolean isInnermost(int level) {
		return level == this.levels.size() - 1;
	}

	List<TestTemplateInvocationContextProvider> getProviders(int level) {
		return this.levels.get(level);
	}

	private static String ambiguousLevelsMessage(Method testMethod, Slot slot, Set<Class<? extends Annotation>> types) {
		return """
				The order of the levels %s of @TestTemplate method [%s] is undefined: they are declared by \
				different annotations %s. Declare their order explicitly, for example via \
				@InvocationComposition(levels = { %s }) on the test method or the test class.""".formatted(
			types.stream().map(type -> "@" + type.getSimpleName()).collect(joining(", ", "[", "]")),
			methodSignature(testMethod), slot.origin(),
			types.stream().map(type -> type.getSimpleName() + ".class").collect(joining(", ")));
	}

	private static String notEnclosingMessage(TestTemplateInvocationContextProvider provider,
			List<TestTemplateInvocationContextProvider> enclosedProviders, Method testMethod) {
		return """
				Provider [%s] would enclose the invocations of %s for @TestTemplate method [%s], but does not \
				declare that its invocations may have nested invocations (see %s.mayEncloseTestTemplateInvocations()). \
				Make it the innermost level by listing the annotation that registers it last in \
				@InvocationComposition(levels = ...), or ask its maintainers to support enclosing invocations.""".formatted(
			provider.getClass().getSimpleName(),
			enclosedProviders.stream().map(it -> it.getClass().getSimpleName()).collect(joining(", ", "[", "]")),
			methodSignature(testMethod), TestTemplateInvocationContextProvider.class.getSimpleName());
	}

	static String methodSignature(Method method) {
		return "%s(%s)".formatted(method.getName(),
			ClassUtils.nullSafeToString(Class::getSimpleName, method.getParameterTypes()));
	}

}
