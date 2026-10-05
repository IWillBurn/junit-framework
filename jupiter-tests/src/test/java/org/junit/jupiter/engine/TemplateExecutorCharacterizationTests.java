/*
 * Copyright 2015-2026 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectIteration;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;
import static org.junit.platform.testkit.engine.EventConditions.container;
import static org.junit.platform.testkit.engine.EventConditions.displayName;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.EventConditions.legacyReportingName;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassTemplate;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContext;
import org.junit.jupiter.api.extension.ClassTemplateInvocationContextProvider;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TemplateInvocationValidationException;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.engine.descriptor.JupiterEngineDescriptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.testkit.engine.Event;

/**
 * Characterization tests for the protocol between the Jupiter engine and
 * {@link TestTemplateInvocationContextProvider} as well as
 * {@link ClassTemplateInvocationContextProvider} implementations.
 *
 * <p>These tests document the current behavior of the shared template
 * executor: providers are asked for support exactly once and before any
 * invocation contexts are requested, streams of active providers are chained
 * and consumed lazily, invocation indices are continuous across providers and
 * assigned before filtering, streams are closed exactly once, and
 * {@code mayReturnZero...()} is only consulted if a provider returned zero
 * invocation contexts.
 *
 * @since 6.2
 */
class TemplateExecutorCharacterizationTests extends AbstractJupiterTestEngineTests {

	static final List<String> log = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void clearLog() {
		log.clear();
	}

	// --- method templates ----------------------------------------------------

	@Test
	void providersAreAskedForSupportOnceAndStreamsAreChainedAndConsumedLazily() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "twoProviders", TestInfo.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(3).succeeded(3).failed(0));
		assertThat(log).containsExactly( //
			"supports A [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"supports B [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"provide A [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"pull A1", "displayName A1 index=1", "additionalExtensions A1", "prepare A1 [test-template-invocation:#1]",
			"invoke A1@1", //
			"pull A2", "displayName A2 index=2", "additionalExtensions A2", "prepare A2 [test-template-invocation:#2]",
			"invoke A2@2", //
			"close A", //
			"provide B [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"pull B1", "displayName B1 index=3", "additionalExtensions B1", "prepare B1 [test-template-invocation:#3]",
			"invoke B1@3", //
			"close B");
	}

	@Test
	void invocationsHaveContinuousUniqueIdsDisplayNamesAndLegacyReportingNames() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "twoProviders", TestInfo.class.getName()));

		var invocations = results.testEvents().dynamicallyRegistered().map(Event::getTestDescriptor).toList();
		assertThat(invocations).extracting(descriptor -> descriptor.getUniqueId().getLastSegment().getValue()) //
				.containsExactly("#1", "#2", "#3");
		assertThat(invocations).extracting(TestDescriptor::getDisplayName) //
				.containsExactly("A1@1", "A2@2", "B1@3");
		assertThat(invocations).extracting(TestDescriptor::getLegacyReportingName) //
				.containsExactly("twoProviders(TestInfo)[1]", "twoProviders(TestInfo)[2]", "twoProviders(TestInfo)[3]");
	}

	@Test
	void unsupportedProvidersAreNeverAskedForInvocationContexts() {
		executeTests(
			selectMethod(MethodTemplateTestCase.class, "supportedAndUnsupportedProvider", TestInfo.class.getName()));

		assertThat(log).filteredOn(entry -> entry.startsWith("supports") || entry.startsWith("provide")) //
				.containsExactly( //
					"supports Unsupported [test-template:supportedAndUnsupportedProvider(org.junit.jupiter.api.TestInfo)]",
					"supports A [test-template:supportedAndUnsupportedProvider(org.junit.jupiter.api.TestInfo)]",
					"provide A [test-template:supportedAndUnsupportedProvider(org.junit.jupiter.api.TestInfo)]");
	}

	@Test
	void mayReturnZeroIsOnlyConsultedForProvidersThatReturnedNoContexts() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "emptyProviderAllowingZero", TestInfo.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).succeeded(2).failed(0));
		assertThat(log).filteredOn(entry -> !entry.startsWith("pull") && !entry.startsWith("displayName")
				&& !entry.startsWith("additionalExtensions") && !entry.startsWith("prepare")) //
				.containsExactly( //
					"supports Zero [test-template:emptyProviderAllowingZero(org.junit.jupiter.api.TestInfo)]",
					"supports A [test-template:emptyProviderAllowingZero(org.junit.jupiter.api.TestInfo)]",
					"provide Zero [test-template:emptyProviderAllowingZero(org.junit.jupiter.api.TestInfo)]",
					"close Zero", "mayReturnZero Zero",
					"provide A [test-template:emptyProviderAllowingZero(org.junit.jupiter.api.TestInfo)]",
					"invoke A1@1", "invoke A2@2", "close A");
	}

	@Test
	void emptyStreamFailsTemplateIfZeroInvocationsAreNotAllowed() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "emptyProviderNotAllowingZero", TestInfo.class.getName()));

		results.containerEvents().assertThatEvents().haveExactly(1,
			event(container("emptyProviderNotAllowingZero"), finishedWithFailure(message(
				"Provider [ZeroNotAllowedProvider] did not provide any invocation contexts, but was expected to do so. "
						+ "You may override mayReturnZeroTestTemplateInvocationContexts() to allow this."))));
		assertThat(log).containsExactly(
			"supports ZeroNotAllowed [test-template:emptyProviderNotAllowingZero(org.junit.jupiter.api.TestInfo)]",
			"provide ZeroNotAllowed [test-template:emptyProviderNotAllowingZero(org.junit.jupiter.api.TestInfo)]",
			"close ZeroNotAllowed", "mayReturnZero ZeroNotAllowed");
	}

	@Test
	void failingStreamIsClosedOnceAndFailsTemplateAfterAlreadyExecutedInvocations() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "failingStream", TestInfo.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(1).succeeded(1));
		results.containerEvents().assertThatEvents().haveExactly(1,
			event(container("failingStream"), finishedWithFailure(message("stream failure"))));
		assertThat(log).filteredOn(entry -> entry.startsWith("close") || entry.startsWith("invoke")) //
				.containsExactly("invoke F1@1", "close F");
	}

	@Test
	void validationExceptionFromClosingTheStreamFailsTemplate() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "failingClose", TestInfo.class.getName()));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(1).succeeded(1));
		results.containerEvents().assertThatEvents().haveExactly(1, event(container("failingClose"),
			finishedWithFailure(instanceOf(TemplateInvocationValidationException.class), message("close failure"))));
		assertThat(log).filteredOn(entry -> entry.startsWith("close")).containsExactly("close C");
	}

	@Test
	void selectionByUniqueIdConsumesAllStreamsButCreatesOnlyTheSelectedInvocation() {
		var invocationId = UniqueId.forEngine(JupiterEngineDescriptor.ENGINE_ID) //
				.append("class", MethodTemplateTestCase.class.getName()) //
				.append("test-template", "twoProviders(org.junit.jupiter.api.TestInfo)") //
				.append("test-template-invocation", "#2");
		var results = executeTests(selectUniqueId(invocationId));

		results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(1).succeeded(1));
		assertThat(log).containsExactly( //
			"supports A [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"supports B [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"provide A [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"pull A1", //
			"pull A2", "displayName A2 index=2", "additionalExtensions A2", "prepare A2 [test-template-invocation:#2]",
			"invoke A2@2", //
			"close A", //
			"provide B [test-template:twoProviders(org.junit.jupiter.api.TestInfo)]", //
			"pull B1", //
			"close B");
	}

	@Test
	void selectionByIterationIndexUsesZeroBasedIndicesAcrossProviders() {
		var results = executeTests(
			selectIteration(selectMethod(MethodTemplateTestCase.class, "twoProviders", TestInfo.class.getName()), 2));

		results.testEvents().dynamicallyRegistered().assertEventsMatchExactly(event(displayName("B1@3")));
		assertThat(log).filteredOn(entry -> entry.startsWith("invoke")).containsExactly("invoke B1@3");
	}

	@Test
	void builtInProvidersOfRepeatedAndParameterizedTestAreChainedWithRepetitionsFirst() {
		var results = executeTests(
			selectMethod(MethodTemplateTestCase.class, "repeatedAndParameterized", "java.lang.String"));

		var invocations = results.testEvents().dynamicallyRegistered().map(Event::getTestDescriptor).toList();
		assertThat(invocations).extracting(TestDescriptor::getDisplayName) //
				.containsExactly("repetition 1 of 2", "repetition 2 of 2", "[3] value = \"foo\"",
					"[4] value = \"bar\"");
		results.testEvents().assertStatistics(stats -> stats.failed(2).succeeded(2));
	}

	// --- class templates -----------------------------------------------------

	@Test
	void classTemplateProvidersAreAskedForSupportOnceAndStreamsAreChainedAndConsumedLazily() {
		var results = executeTestsForClass(ClassTemplateTestCase.class);

		results.containerEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).failed(0));
		results.testEvents().assertStatistics(stats -> stats.succeeded(2).failed(0));
		assertThat(log).containsExactly( //
			"supports CA [class-template:" + ClassTemplateTestCase.class.getName() + "]", //
			"supports CB [class-template:" + ClassTemplateTestCase.class.getName() + "]", //
			"provide CA", //
			"pull CA1", "displayName CA1 index=1", "additionalExtensions CA1",
			"prepare CA1 [class-template-invocation:#1]", "test CA1@1", //
			"pull CA2", "displayName CA2 index=2", "additionalExtensions CA2",
			"prepare CA2 [class-template-invocation:#2]", "test CA2@2", //
			"close CA", //
			"provide CB", //
			"close CB", "mayReturnZero CB");
		results.containerEvents().dynamicallyRegistered().assertEventsMatchExactly( //
			event(displayName("CA1@1"), legacyReportingName(ClassTemplateTestCase.class.getName() + "[1]")), //
			event(displayName("CA2@2"), legacyReportingName(ClassTemplateTestCase.class.getName() + "[2]")));
	}

	@Test
	void classTemplateSelectionByIterationIndexConsumesAllStreamsButExecutesOnlySelectedInvocation() {
		var results = executeTests(selectIteration(selectClass(ClassTemplateTestCase.class), 1));

		results.containerEvents().dynamicallyRegistered().assertEventsMatchExactly(event(displayName("CA2@2")));
		assertThat(log).containsExactly( //
			"supports CA [class-template:" + ClassTemplateTestCase.class.getName() + "]", //
			"supports CB [class-template:" + ClassTemplateTestCase.class.getName() + "]", //
			"provide CA", //
			"pull CA1", //
			"pull CA2", "displayName CA2 index=2", "additionalExtensions CA2",
			"prepare CA2 [class-template-invocation:#2]", "test CA2@2", //
			"close CA", //
			"provide CB", //
			"close CB", "mayReturnZero CB");
	}

	// -------------------------------------------------------------------------

	static String lastSegment(ExtensionContext context) {
		var segment = UniqueId.parse(context.getUniqueId()).getLastSegment();
		return "[" + segment.getType() + ":" + segment.getValue() + "]";
	}

	static class MethodTemplateTestCase {

		@TestTemplate
		@ExtendWith({ ProviderA.class, ProviderB.class })
		void twoProviders(TestInfo testInfo) {
			log.add("invoke " + testInfo.getDisplayName());
		}

		@TestTemplate
		@ExtendWith({ UnsupportedProvider.class, ProviderA.class })
		void supportedAndUnsupportedProvider(TestInfo testInfo) {
			log.add("invoke " + testInfo.getDisplayName());
		}

		@TestTemplate
		@ExtendWith({ ZeroAllowedProvider.class, ProviderA.class })
		void emptyProviderAllowingZero(TestInfo testInfo) {
			log.add("invoke " + testInfo.getDisplayName());
		}

		@TestTemplate
		@ExtendWith(ZeroNotAllowedProvider.class)
		void emptyProviderNotAllowingZero(TestInfo testInfo) {
			log.add("invoke " + testInfo.getDisplayName());
		}

		@TestTemplate
		@ExtendWith(FailingStreamProvider.class)
		void failingStream(TestInfo testInfo) {
			log.add("invoke " + testInfo.getDisplayName());
		}

		@TestTemplate
		@ExtendWith(FailingCloseProvider.class)
		void failingClose(TestInfo testInfo) {
			log.add("invoke " + testInfo.getDisplayName());
		}

		@RepeatedTest(2)
		@ParameterizedTest
		@ValueSource(strings = { "foo", "bar" })
		void repeatedAndParameterized(String value) {
		}
	}

	/**
	 * Provider that logs every call made by the engine.
	 */
	abstract static class SpyProvider implements TestTemplateInvocationContextProvider {

		private final String name;
		private final int count;
		private final boolean mayReturnZero;

		SpyProvider(String name, int count, boolean mayReturnZero) {
			this.name = name;
			this.count = count;
			this.mayReturnZero = mayReturnZero;
		}

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			log.add("supports " + name + " " + lastSegment(context));
			return true;
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			log.add("provide " + name + " " + lastSegment(context));
			return contexts().onClose(() -> log.add("close " + name));
		}

		Stream<TestTemplateInvocationContext> contexts() {
			return IntStream.rangeClosed(1, count).mapToObj(i -> {
				if (failAt() == i) {
					throw new IllegalStateException("stream failure");
				}
				log.add("pull " + name + i);
				return new SpyContext(name + i);
			});
		}

		int failAt() {
			return -1;
		}

		@Override
		public boolean mayReturnZeroTestTemplateInvocationContexts(ExtensionContext context) {
			log.add("mayReturnZero " + name);
			return mayReturnZero;
		}
	}

	record SpyContext(String name) implements TestTemplateInvocationContext {

		@Override
		public String getDisplayName(int invocationIndex) {
			log.add("displayName " + name + " index=" + invocationIndex);
			return name + "@" + invocationIndex;
		}

		@Override
		public List<Extension> getAdditionalExtensions() {
			log.add("additionalExtensions " + name);
			return List.of();
		}

		@Override
		public void prepareInvocation(ExtensionContext context) {
			log.add("prepare " + name + " " + lastSegment(context));
		}
	}

	static class ProviderA extends SpyProvider {
		ProviderA() {
			super("A", 2, false);
		}
	}

	static class ProviderB extends SpyProvider {
		ProviderB() {
			super("B", 1, false);
		}
	}

	static class UnsupportedProvider extends SpyProvider {

		UnsupportedProvider() {
			super("Unsupported", 1, false);
		}

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			super.supportsTestTemplate(context);
			return false;
		}
	}

	static class ZeroAllowedProvider extends SpyProvider {
		ZeroAllowedProvider() {
			super("Zero", 0, true);
		}
	}

	static class ZeroNotAllowedProvider extends SpyProvider {
		ZeroNotAllowedProvider() {
			super("ZeroNotAllowed", 0, false);
		}
	}

	static class FailingStreamProvider extends SpyProvider {

		FailingStreamProvider() {
			super("F", 2, false);
		}

		@Override
		int failAt() {
			return 2;
		}
	}

	static class FailingCloseProvider extends SpyProvider {

		FailingCloseProvider() {
			super("C", 1, false);
		}

		@Override
		Stream<TestTemplateInvocationContext> contexts() {
			return super.contexts().onClose(() -> {
				throw new TemplateInvocationValidationException("close failure");
			});
		}
	}

	@ClassTemplate
	@ExtendWith({ ClassProviderA.class, ClassProviderB.class })
	static class ClassTemplateTestCase {

		static String currentInvocation = "";

		@Test
		void test() {
			log.add("test " + currentInvocation);
		}
	}

	abstract static class ClassSpyProvider implements ClassTemplateInvocationContextProvider {

		private final String name;
		private final int count;

		ClassSpyProvider(String name, int count) {
			this.name = name;
			this.count = count;
		}

		@Override
		public boolean supportsClassTemplate(ExtensionContext context) {
			log.add("supports " + name + " " + lastSegment(context));
			return true;
		}

		@Override
		public Stream<ClassTemplateInvocationContext> provideClassTemplateInvocationContexts(ExtensionContext context) {
			log.add("provide " + name);
			return IntStream.rangeClosed(1, count).<ClassTemplateInvocationContext> mapToObj(i -> {
				log.add("pull " + name + i);
				return new ClassSpyContext(name + i);
			}).onClose(() -> log.add("close " + name));
		}

		@Override
		public boolean mayReturnZeroClassTemplateInvocationContexts(ExtensionContext context) {
			log.add("mayReturnZero " + name);
			return true;
		}
	}

	record ClassSpyContext(String name) implements ClassTemplateInvocationContext {

		@Override
		public String getDisplayName(int invocationIndex) {
			log.add("displayName " + name + " index=" + invocationIndex);
			return name + "@" + invocationIndex;
		}

		@Override
		public List<Extension> getAdditionalExtensions() {
			log.add("additionalExtensions " + name);
			return List.of();
		}

		@Override
		public void prepareInvocation(ExtensionContext context) {
			log.add("prepare " + name + " " + lastSegment(context));
			ClassTemplateTestCase.currentInvocation = context.getDisplayName();
		}
	}

	static class ClassProviderA extends ClassSpyProvider {
		ClassProviderA() {
			super("CA", 2);
		}
	}

	static class ClassProviderB extends ClassSpyProvider {
		ClassProviderB() {
			super("CB", 0);
		}
	}

}
