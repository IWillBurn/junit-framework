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

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.junit.platform.commons.support.AnnotationSupport.findRepeatableAnnotations;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.InvocationComposition;
import org.junit.jupiter.api.InvocationCompositions;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.platform.commons.util.ClassUtils;

/**
 * The levels declared via {@link InvocationComposition @InvocationComposition}
 * that apply to a test template method, merged into a single list ordered from
 * the outermost to the innermost level.
 *
 * <p>Declarations are visited from the nearest to the farthest element: the
 * test method, the test class, its superclasses, the interfaces of these
 * classes, and the enclosing test classes (from the innermost to the
 * outermost, each with its own hierarchy). On each element, directly present
 * declarations are visited before meta-present ones. The nearest declaration
 * contributes the outermost levels; an annotation type that has already been
 * listed is ignored.
 *
 * <p>If levels are contributed by meta-present declarations of different
 * annotations on the same element without a consistent order, they end up in
 * a single <em>ambiguous</em> {@link Slot}. Whether such an ambiguity is an
 * error depends on the active providers of the test template and is decided by
 * {@link InvocationCompositionPlan}.
 *
 * <p>Instances are created per execution of a test template and are not
 * cached: the enclosing test classes are those of the test template's
 * descriptor, which may differ for the same {@link Class} of an inherited
 * {@code @Nested} class.
 *
 * @since 6.2
 */
final class InvocationCompositionDeclarations {

	private static final InvocationCompositionDeclarations NONE = new InvocationCompositionDeclarations(List.of(),
		Map.of());

	private final List<Slot> slots;
	private final Map<Class<? extends Annotation>, ProviderTypes> providerTypes;

	private InvocationCompositionDeclarations(List<Slot> slots,
			Map<Class<? extends Annotation>, ProviderTypes> providerTypes) {
		this.slots = slots;
		this.providerTypes = providerTypes;
	}

	/**
	 * Find the declarations that apply to the supplied test template method.
	 *
	 * @throws ExtensionConfigurationException if a declaration cannot be read
	 * or lists an annotation type no provider can belong to
	 */
	static InvocationCompositionDeclarations find(Method testMethod, Class<?> testClass,
			List<Class<?>> enclosingTestClasses) {

		Collector collector = new Collector();
		collector.visit(testMethod);
		collector.visitHierarchy(testClass);
		for (int i = enclosingTestClasses.size() - 1; i >= 0; i--) {
			collector.visitHierarchy(enclosingTestClasses.get(i));
		}
		if (collector.slots.isEmpty()) {
			return NONE;
		}
		return new InvocationCompositionDeclarations(List.copyOf(collector.slots), Map.copyOf(collector.providerTypes));
	}

	boolean isEmpty() {
		return this.slots.isEmpty();
	}

	List<Slot> getSlots() {
		return this.slots;
	}

	/**
	 * {@return the placement of the supplied provider, i.e., the slot of the
	 * outermost listed annotation type the provider belongs to, if any}
	 */
	Optional<Placement> placementOf(TestTemplateInvocationContextProvider provider) {
		for (int index = 0; index < this.slots.size(); index++) {
			for (Class<? extends Annotation> type : this.slots.get(index).types()) {
				if (requireNonNull(this.providerTypes.get(type)).includes(provider)) {
					return Optional.of(new Placement(index, type));
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * A position in the merged list of levels.
	 *
	 * @param types the annotation types of the slot; more than one if their
	 * relative order is undefined
	 * @param origin a description of where the slot was declared
	 */
	record Slot(List<Class<? extends Annotation>> types, String origin) {

		boolean isAmbiguous() {
			return this.types.size() > 1;
		}
	}

	/**
	 * The placement of a provider.
	 *
	 * @param slotIndex the index of the slot in {@link #getSlots()}
	 * @param type the listed annotation type the provider belongs to
	 */
	record Placement(int slotIndex, Class<? extends Annotation> type) {
	}

	/**
	 * The provider classes that may belong to a listed annotation type: those
	 * declared via {@link ExtendWith @ExtendWith} on the annotation type,
	 * directly or as a meta-annotation.
	 *
	 * <p>Providers that are registered in any other way, including Jupiter's
	 * built-in provider for {@code @RepeatedTest}, do not belong to any
	 * annotation type and therefore always stay on the outermost level.
	 */
	private record ProviderTypes(Set<Class<? extends Extension>> classes) {

		boolean includes(TestTemplateInvocationContextProvider provider) {
			return this.classes.contains(provider.getClass());
		}

		boolean isEmpty() {
			return this.classes.isEmpty();
		}
	}

	private static final class Collector {

		private final List<Slot> slots = new ArrayList<>();
		private final Set<Class<? extends Annotation>> placed = new HashSet<>();
		private final Map<Class<? extends Annotation>, ProviderTypes> providerTypes = new HashMap<>();
		private final Set<Class<?>> visitedInterfaces = new HashSet<>();

		void visitHierarchy(Class<?> testClass) {
			List<Class<?>> classes = new ArrayList<>();
			for (Class<?> current = testClass; current != null
					&& current != Object.class; current = current.getSuperclass()) {
				classes.add(current);
			}
			classes.forEach(this::visit);
			for (Class<?> current : classes) {
				for (Class<?> ifc : current.getInterfaces()) {
					visitInterface(ifc);
				}
			}
		}

		private void visitInterface(Class<?> ifc) {
			if (this.visitedInterfaces.add(ifc)) {
				visit(ifc);
				for (Class<?> superInterface : ifc.getInterfaces()) {
					visitInterface(superInterface);
				}
			}
		}

		void visit(AnnotatedElement element) {
			for (Slot slot : declaredSlots(element, new HashSet<>())) {
				List<Class<? extends Annotation>> remaining = slot.types().stream() //
						.filter(type -> !this.placed.contains(type)) //
						.toList();
				if (!remaining.isEmpty()) {
					this.placed.addAll(remaining);
					this.slots.add(new Slot(remaining, slot.origin()));
				}
			}
		}

		/**
		 * {@return the slots declared on the supplied element, directly present
		 * declarations first, followed by meta-present ones}
		 */
		private List<Slot> declaredSlots(AnnotatedElement element, Set<Class<? extends Annotation>> visitedTypes) {
			Annotation[] annotations = element.getDeclaredAnnotations();
			Set<Class<? extends Annotation>> local = new LinkedHashSet<>();
			List<Slot> result = new ArrayList<>();

			for (Annotation annotation : annotations) {
				if (annotation instanceof InvocationComposition declaration) {
					addDirect(declaration, element, local, result);
				}
				else if (annotation instanceof InvocationCompositions container) {
					for (InvocationComposition declaration : container.value()) {
						addDirect(declaration, element, local, result);
					}
				}
			}

			List<MetaSource> sources = new ArrayList<>();
			for (Annotation annotation : annotations) {
				Class<? extends Annotation> annotationType = annotation.annotationType();
				if (isCompositionAnnotation(annotationType) || isInJavaLangAnnotationPackage(annotationType)
						|| !visitedTypes.add(annotationType)) {
					continue;
				}
				List<Slot> metaSlots = declaredSlots(annotationType, visitedTypes).stream() //
						.map(slot -> without(slot, local)) //
						.flatMap(Optional::stream) //
						.toList();
				if (!metaSlots.isEmpty()) {
					sources.add(new MetaSource(annotationType, metaSlots));
				}
			}
			result.addAll(merge(sources, element));
			return result;
		}

		private void addDirect(InvocationComposition declaration, AnnotatedElement element,
				Set<Class<? extends Annotation>> local, List<Slot> result) {
			for (Class<? extends Annotation> type : levels(declaration, element)) {
				validate(type, element);
				if (local.add(type)) {
					result.add(new Slot(List.of(type), "@InvocationComposition on " + describe(element)));
				}
			}
		}

		/**
		 * Merge the slots of the meta-present declarations of different
		 * annotations on the same element. If their types cannot be ordered
		 * consistently, they are combined into a single ambiguous slot.
		 */
		private static List<Slot> merge(List<MetaSource> sources, AnnotatedElement element) {
			if (sources.isEmpty()) {
				return List.of();
			}
			if (sources.size() == 1) {
				return sources.get(0).slots();
			}
			Set<Class<? extends Annotation>> allTypes = new LinkedHashSet<>();
			sources.forEach(source -> allTypes.addAll(source.types()));
			if (sources.stream().allMatch(MetaSource::isUnambiguous)) {
				for (MetaSource candidate : sources) {
					List<Class<? extends Annotation>> order = candidate.types();
					if (order.size() == allTypes.size()
							&& sources.stream().allMatch(source -> isSubsequence(source.types(), order))) {
						return candidate.slots();
					}
				}
			}
			String origin = sources.stream() //
					.map(source -> "@" + source.annotationType().getSimpleName()) //
					.collect(joining(", ", "[", "] on " + describe(element)));
			return List.of(new Slot(List.copyOf(allTypes), origin));
		}

		private static boolean isSubsequence(List<Class<? extends Annotation>> candidate,
				List<Class<? extends Annotation>> sequence) {
			int position = 0;
			for (Class<? extends Annotation> type : candidate) {
				int found = sequence.subList(position, sequence.size()).indexOf(type);
				if (found < 0) {
					return false;
				}
				position += found + 1;
			}
			return true;
		}

		private static Optional<Slot> without(Slot slot, Set<Class<? extends Annotation>> types) {
			List<Class<? extends Annotation>> remaining = slot.types().stream() //
					.filter(type -> !types.contains(type)) //
					.toList();
			return remaining.isEmpty() ? Optional.empty() : Optional.of(new Slot(remaining, slot.origin()));
		}

		private void validate(Class<? extends Annotation> type, AnnotatedElement element) {
			ProviderTypes types = this.providerTypes.get(type);
			if (types == null) {
				types = providerTypesOf(type, element);
				this.providerTypes.put(type, types);
			}
			if (types.isEmpty()) {
				throw new ExtensionConfigurationException("""
						@InvocationComposition declared on %s lists annotation type [%s], but no %s can \
						belong to it: it is neither directly nor meta-annotated with @ExtendWith declaring \
						such a provider.""".formatted(describe(element), type.getName(),
					TestTemplateInvocationContextProvider.class.getSimpleName()));
			}
		}

		private static ProviderTypes providerTypesOf(Class<? extends Annotation> type, AnnotatedElement element) {
			try {
				Set<Class<? extends Extension>> classes = new LinkedHashSet<>();
				findRepeatableAnnotations(type, ExtendWith.class).stream() //
						.flatMap(extendWith -> Arrays.stream(extendWith.value())) //
						.filter(TestTemplateInvocationContextProvider.class::isAssignableFrom) //
						.forEach(classes::add);
				return new ProviderTypes(classes);
			}
			catch (TypeNotPresentException ex) {
				throw new ExtensionConfigurationException(
					"Failed to determine the providers of annotation type [%s] listed by @InvocationComposition declared on %s".formatted(
						type.getName(), describe(element)),
					ex);
			}
		}

		private static List<Class<? extends Annotation>> levels(InvocationComposition declaration,
				AnnotatedElement element) {
			try {
				return List.of(declaration.levels());
			}
			catch (TypeNotPresentException ex) {
				throw new ExtensionConfigurationException(
					"Failed to read the levels of @InvocationComposition declared on " + describe(element), ex);
			}
		}

		private static boolean isCompositionAnnotation(Class<? extends Annotation> annotationType) {
			return annotationType == InvocationComposition.class || annotationType == InvocationCompositions.class;
		}

		private static boolean isInJavaLangAnnotationPackage(Class<? extends Annotation> annotationType) {
			return annotationType.getName().startsWith("java.lang.annotation");
		}
	}

	private record MetaSource(Class<? extends Annotation> annotationType, List<Slot> slots) {

		List<Class<? extends Annotation>> types() {
			return this.slots.stream().flatMap(slot -> slot.types().stream()).toList();
		}

		boolean isUnambiguous() {
			return this.slots.stream().noneMatch(Slot::isAmbiguous);
		}
	}

	static String describe(AnnotatedElement element) {
		if (element instanceof Method method) {
			return "method [%s(%s)] in class [%s]".formatted(method.getName(),
				ClassUtils.nullSafeToString(Class::getSimpleName, method.getParameterTypes()),
				method.getDeclaringClass().getName());
		}
		if (element instanceof Class<?> clazz) {
			return (clazz.isAnnotation() ? "annotation [@" : clazz.isInterface() ? "interface [" : "class [")
					+ clazz.getName() + "]";
		}
		return "[" + element + "]";
	}

}
