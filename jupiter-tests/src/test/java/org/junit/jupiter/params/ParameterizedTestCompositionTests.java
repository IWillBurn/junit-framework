/*
 * Copyright 2015-2026 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.params;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.uniqueIdSubstring;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.cause;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.jspecify.annotations.NullMarked;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.InvocationComposition;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.engine.AbstractJupiterTestEngineTests;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.testkit.engine.Event;

/**
 * Integration tests for {@link ParameterizedTest @ParameterizedTest} methods
 * whose invocations are composed with the invocations of other providers via
 * {@link InvocationComposition @InvocationComposition}.
 *
 * @since 6.2
 */
class ParameterizedTestCompositionTests extends AbstractJupiterTestEngineTests {

	static final List<String> log = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void clearLog() {
		log.clear();
	}

	@Test
	void autoCloseableArgumentsAreClosedOnceAfterAllNestedInvocations() {
		var results = executeTests(selectMethod(CompositionTestCase.class, "autoCloseableArguments",
			Resource.class.getName() + "," + Implementation.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(4).succeeded(4));
		assertThat(log).containsExactly( //
			"test r1 with First", "test r1 with Second", "closed r1", //
			"test r2 with First", "test r2 with Second", "closed r2");
	}

	@Test
	void failureToCloseArgumentFailsEnclosingInvocationAfterSuccessfulNestedInvocations() {
		var results = executeTests(selectMethod(CompositionTestCase.class, "failingToClose",
			FailingResource.class.getName() + "," + Implementation.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).succeeded(2));
		results.containerEvents().assertThatEvents().haveExactly(1,
			event(uniqueIdSubstring("failingToClose"),
				finishedWithFailure(message("Failed to close extension context"), cause(message("failed to close"))),
				new org.assertj.core.api.Condition<>(
					it -> it.getTestDescriptor().getUniqueId().getLastSegment().getType().equals(
						"test-template-invocation"),
					"enclosing invocation")));
	}

	@Test
	void argumentCountIsValidatedOncePerEnclosingInvocation() {
		var results = executeTests(selectMethod(CompositionTestCase.class, "strictArgumentCount",
			"java.lang.String," + Implementation.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(0));
		results.containerEvents().assertThatEvents().haveExactly(1, event(
			uniqueIdSubstring("test-template-invocation:#1"),
			finishedWithFailure(instanceOf(PreconditionViolationException.class), message(it -> it.startsWith(
				"Configuration error: @ParameterizedTest consumes 2 parameters but there were 3 arguments provided.")))));
	}

	@Test
	void argumentPayloadsAreSharedByNestedInvocations() {
		var results = executeTests(selectMethod(CompositionTestCase.class, "sharedPayload",
			"java.util.List," + Implementation.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).succeeded(2));
		assertThat(log).containsExactly("First sees []", "Second sees [First]");
	}

	@Test
	void argumentsSourcesOfNestedLevelAreEvaluatedPerEnclosingInvocation() {
		NestedSourceTestCase.sourceInvocations.set(0);

		var results = executeTests(selectMethod(NestedSourceTestCase.class, "test", "java.lang.String"));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(4).succeeded(4));
		assertThat(NestedSourceTestCase.sourceInvocations).hasValue(2);
	}

	@Test
	void parameterInfoIsStoredForTheLevelOfTheParameterizedTest() {
		var results = executeTestsForClass(ParameterInfoTestCase.class);

		results.testEvents().assertStatistics(stats -> stats.started(4).succeeded(4));
		assertThat(log).containsExactlyInAnyOrder( //
			"enclosing: leaf=[a] invocation=[a] class=[1]", //
			"enclosing: leaf=[a] invocation=[a] class=[1]", //
			"nested: leaf=[b] invocation=[1] class=[1]", //
			"nested: leaf=[b] invocation=[1] class=[1]");
	}

	@Test
	void parameterizedClassAndParameterizedTestAndImplementations() {
		var results = executeTests(selectClass(ParameterizedClassTestCase.class));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(8).succeeded(8));
		assertThat(results.testEvents().dynamicallyRegistered().map(Event::getTestDescriptor).map(
			ParameterizedTestCompositionTests::describe)).containsExactly(
				"#1/#1/#1 First test(String, int, Implementation)[1][1][1]",
				"#1/#1/#2 Second test(String, int, Implementation)[1][1][2]",
				"#1/#2/#1 First test(String, int, Implementation)[1][2][1]",
				"#1/#2/#2 Second test(String, int, Implementation)[1][2][2]",
				"#2/#1/#1 First test(String, int, Implementation)[2][1][1]",
				"#2/#1/#2 Second test(String, int, Implementation)[2][1][2]",
				"#2/#2/#1 First test(String, int, Implementation)[2][2][1]",
				"#2/#2/#2 Second test(String, int, Implementation)[2][2][2]");
		assertThat(log).containsExactly( //
			"utf8 a 1 First", "utf8 a 1 Second", "utf8 b 2 First", "utf8 b 2 Second", //
			"bytes a 1 First", "bytes a 1 Second", "bytes b 2 First", "bytes b 2 Second");
	}

	@Test
	void leafBelowParameterizedClassCanBeSelectedByUniqueId() {
		var leafId = UniqueId.forEngine("junit-jupiter") //
				.append("class-template", ParameterizedClassTestCase.class.getName()) //
				.append("class-template-invocation", "#2") //
				.append("test-template", "test(java.lang.String, int, " + Implementation.class.getName() + ")") //
				.append("test-template-invocation", "#1") //
				.append("test-template-invocation", "#2");

		var results = executeTests(org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId(leafId));

		assertThat(results.testEvents().dynamicallyRegistered().map(Event::getTestDescriptor).map(
			TestDescriptor::getUniqueId)).containsExactly(leafId);
		assertThat(log).containsExactly("bytes a 1 Second");
	}

	// -------------------------------------------------------------------------

	private static String describe(TestDescriptor descriptor) {
		String path = descriptor.getUniqueId().getSegments().stream() //
				.filter(segment -> segment.getType().endsWith("-invocation")) //
				.map(UniqueId.Segment::getValue) //
				.collect(joining("/"));
		return path + " " + descriptor.getDisplayName() + " " + descriptor.getLegacyReportingName();
	}

	/**
	 * Minimal library that adds an implementation level below the invocations
	 * of a test template, built on public API only.
	 */
	@Retention(RUNTIME)
	@Target({ TYPE, ANNOTATION_TYPE })
	@ExtendWith(ImplementationsProvider.class)
	@InvocationComposition(levels = Implementations.class)
	@interface Implementations {
		Class<? extends Implementation>[] value();
	}

	interface Implementation {
	}

	static class First implements Implementation {
	}

	static class Second implements Implementation {
	}

	static class ImplementationsProvider implements TestTemplateInvocationContextProvider {

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			return Arrays.asList(context.getRequiredTestMethod().getParameterTypes()).contains(Implementation.class);
		}

		@Override
		public Stream<? extends TestTemplateInvocationContext> provideTestTemplateInvocationContexts(
				ExtensionContext context) {
			Implementations implementations = org.junit.platform.commons.support.AnnotationSupport.findAnnotation(
				context.getRequiredTestClass(), Implementations.class, context.getEnclosingTestClasses()).orElseThrow();
			return Arrays.stream(implementations.value()).map(ImplementationContext::new);
		}

		@Override
		public boolean mayEncloseTestTemplateInvocations(ExtensionContext context) {
			return true;
		}
	}

	record ImplementationContext(Class<? extends Implementation> type) implements TestTemplateInvocationContext {

		@Override
		public String getDisplayName(int invocationIndex) {
			return this.type.getSimpleName();
		}

		@Override
		public List<Extension> getAdditionalExtensions() {
			return List.of(new ImplementationResolver(this.type));
		}
	}

	record ImplementationResolver(Class<? extends Implementation> type) implements ParameterResolver {

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return parameterContext.getParameter().getType() == Implementation.class;
		}

		@Override
		public Implementation resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return org.junit.platform.commons.support.ReflectionSupport.newInstance(this.type);
		}
	}

	static class Resource implements AutoCloseable {

		private final String name;
		private final AtomicInteger closeCount = new AtomicInteger();

		Resource(String name) {
			this.name = name;
		}

		boolean isClosed() {
			return this.closeCount.get() > 0;
		}

		@Override
		public void close() {
			this.closeCount.incrementAndGet();
			log.add("closed " + this.name);
		}

		@Override
		public String toString() {
			return this.name;
		}
	}

	static class FailingResource implements AutoCloseable {

		@Override
		public void close() {
			throw new IllegalStateException("failed to close");
		}

		@Override
		public String toString() {
			return "failing";
		}
	}

	@Implementations({ First.class, Second.class })
	static class CompositionTestCase {

		@ParameterizedTest
		@MethodSource("resources")
		void autoCloseableArguments(Resource resource, Implementation implementation) {
			assertFalse(resource.isClosed());
			log.add("test " + resource + " with " + implementation.getClass().getSimpleName());
		}

		static Stream<Resource> resources() {
			return Stream.of(new Resource("r1"), new Resource("r2"));
		}

		@ParameterizedTest
		@MethodSource("failingResources")
		void failingToClose(FailingResource resource, Implementation implementation) {
		}

		static Stream<FailingResource> failingResources() {
			return Stream.of(new FailingResource());
		}

		@ParameterizedTest(argumentCountValidation = ArgumentCountValidationMode.STRICT)
		@CsvSource("a, b, c")
		void strictArgumentCount(String value, Implementation implementation) {
		}

		@ParameterizedTest
		@MethodSource("mutablePayloads")
		void sharedPayload(List<String> payload, Implementation implementation) {
			log.add(implementation.getClass().getSimpleName() + " sees " + payload);
			payload.add(implementation.getClass().getSimpleName());
		}

		static Stream<Arguments> mutablePayloads() {
			return Stream.of(Arguments.of(new ArrayList<String>()));
		}
	}

	static class NestedSourceTestCase {

		static final AtomicInteger sourceInvocations = new AtomicInteger();

		@RepeatedTest(2)
		@ParameterizedTest
		@MethodSource("values")
		@InvocationComposition(levels = ParameterizedTest.class)
		void test(String value) {
		}

		static Stream<String> values() {
			sourceInvocations.incrementAndGet();
			return Stream.of("x", "y");
		}
	}

	@ParameterizedClass
	@ValueSource(ints = 1)
	@Implementations({ First.class, Second.class })
	@ExtendWith(ParameterInfoRecorder.class)
	record ParameterInfoTestCase(int i) {

		@ParameterizedTest
		@ValueSource(strings = "a")
		void enclosing(String value, Implementation implementation) {
		}

		@RepeatedTest(2)
		@ParameterizedTest
		@ValueSource(strings = "b")
		@InvocationComposition(levels = ParameterizedTest.class)
		void nested(String value) {
		}
	}

	@NullMarked
	static class ParameterInfoRecorder implements BeforeEachCallback {

		@Override
		public void beforeEach(ExtensionContext leafContext) {
			ExtensionContext invocationContext = leafContext.getParent().orElseThrow();
			ExtensionContext classContext = invocationContext.getParent().orElseThrow().getParent().orElseThrow();
			log.add(leafContext.getRequiredTestMethod().getName() + ": leaf=" + arguments(leafContext) + " invocation="
					+ arguments(invocationContext) + " class=" + arguments(classContext));
		}

		private static String arguments(ExtensionContext context) {
			ParameterInfo parameterInfo = ParameterInfo.get(context);
			return parameterInfo == null ? "null" : parameterInfo.getArguments().toList().toString();
		}
	}

	@ParameterizedClass
	@ValueSource(strings = { "utf8", "bytes" })
	@Implementations({ First.class, Second.class })
	static class ParameterizedClassTestCase {

		@Parameter
		String codec;

		@ParameterizedTest
		@CsvSource({ "a, 1", "b, 2" })
		void test(String key, int value, Implementation implementation) {
			assertNotNull(this.codec);
			log.add(this.codec + " " + key + " " + value + " " + implementation.getClass().getSimpleName());
		}
	}

}
