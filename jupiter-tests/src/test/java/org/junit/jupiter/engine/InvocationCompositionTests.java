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

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Constants.DEFAULT_EXECUTION_MODE_PROPERTY_NAME;
import static org.junit.jupiter.api.Constants.PARALLEL_CONFIG_EXECUTOR_SERVICE_PROPERTY_NAME;
import static org.junit.jupiter.api.Constants.PARALLEL_CONFIG_FIXED_PARALLELISM_PROPERTY_NAME;
import static org.junit.jupiter.api.Constants.PARALLEL_CONFIG_STRATEGY_PROPERTY_NAME;
import static org.junit.jupiter.api.Constants.PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectIteration;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId;
import static org.junit.platform.testkit.engine.EventConditions.container;
import static org.junit.platform.testkit.engine.EventConditions.event;
import static org.junit.platform.testkit.engine.EventConditions.finishedWithFailure;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.instanceOf;
import static org.junit.platform.testkit.engine.TestExecutionResultConditions.message;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.assertj.core.api.Condition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.InvocationComposition;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.jupiter.api.extension.TestTemplateInvocationContext;
import org.junit.jupiter.api.extension.TestTemplateInvocationContextProvider;
import org.junit.jupiter.engine.descriptor.JupiterEngineDescriptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.PreconditionViolationException;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestTag;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.engine.support.hierarchical.ParallelHierarchicalTestExecutorServiceFactory.ParallelExecutorServiceType;
import org.junit.platform.testkit.engine.EngineExecutionResults;
import org.junit.platform.testkit.engine.Event;
import org.junit.platform.testkit.engine.EventType;

/**
 * Integration tests for test templates whose invocations are composed into
 * several levels via {@link InvocationComposition @InvocationComposition}.
 *
 * @since 6.2
 */
class InvocationCompositionTests extends AbstractJupiterTestEngineTests {

	static final List<String> log = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void clearLog() {
		log.clear();
	}

	@Nested
	class TreeTests {

		@Test
		void argumentsNestedBelowRepetitions() {
			var results = executeTests(selectMethod(TreeTestCase.class, "argumentsBelowRepetitions",
				"java.lang.String,org.junit.jupiter.api.RepetitionInfo"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'repetition 1 of 2' argumentsBelowRepetitions(String, RepetitionInfo)[1] SUCCESSFUL", //
				"#1/#1 TEST '[1] value = \"a\"' argumentsBelowRepetitions(String, RepetitionInfo)[1][1] SUCCESSFUL", //
				"#1/#2 TEST '[2] value = \"b\"' argumentsBelowRepetitions(String, RepetitionInfo)[1][2] SUCCESSFUL", //
				"#2 CONTAINER 'repetition 2 of 2' argumentsBelowRepetitions(String, RepetitionInfo)[2] SUCCESSFUL", //
				"#2/#1 TEST '[1] value = \"a\"' argumentsBelowRepetitions(String, RepetitionInfo)[2][1] SUCCESSFUL", //
				"#2/#2 TEST '[2] value = \"b\"' argumentsBelowRepetitions(String, RepetitionInfo)[2][2] SUCCESSFUL");
			assertThat(log).containsExactly("a 1/2", "b 1/2", "a 2/2", "b 2/2");
		}

		@Test
		void displayNamePlaceholderOfNestedLevelRefersToEnclosingInvocation() {
			var results = executeTests(selectMethod(TreeTestCase.class, "nestedArgumentNames", "java.lang.String"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'repetition 1 of 1' nestedArgumentNames(String)[1] SUCCESSFUL", //
				"#1/#1 TEST 'repetition 1 of 1 :: [1]' nestedArgumentNames(String)[1][1] SUCCESSFUL");
		}

		@Test
		void unlistedProvidersAreChainedOnTheOutermostLevel() {
			var results = executeTests(selectMethod(TreeTestCase.class, "threeLevels",
				"java.lang.String,org.junit.jupiter.engine.InvocationCompositionTests$Invocation"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'A1@1' threeLevels(String, Invocation)[1] SUCCESSFUL", //
				"#1/#1 CONTAINER '[1] value = \"x\"' threeLevels(String, Invocation)[1][1] SUCCESSFUL", //
				"#1/#1/#1 TEST 'C1@1' threeLevels(String, Invocation)[1][1][1] SUCCESSFUL", //
				"#1/#1/#2 TEST 'C2@2' threeLevels(String, Invocation)[1][1][2] SUCCESSFUL", //
				"#2 CONTAINER 'A2@2' threeLevels(String, Invocation)[2] SUCCESSFUL", //
				"#2/#1 CONTAINER '[1] value = \"x\"' threeLevels(String, Invocation)[2][1] SUCCESSFUL", //
				"#2/#1/#1 TEST 'C1@1' threeLevels(String, Invocation)[2][1][1] SUCCESSFUL", //
				"#2/#1/#2 TEST 'C2@2' threeLevels(String, Invocation)[2][1][2] SUCCESSFUL", //
				"#3 CONTAINER 'B1@3' threeLevels(String, Invocation)[3] SUCCESSFUL", //
				"#3/#1 CONTAINER '[1] value = \"x\"' threeLevels(String, Invocation)[3][1] SUCCESSFUL", //
				"#3/#1/#1 TEST 'C1@1' threeLevels(String, Invocation)[3][1][1] SUCCESSFUL", //
				"#3/#1/#2 TEST 'C2@2' threeLevels(String, Invocation)[3][1][2] SUCCESSFUL");
			assertThat(log).filteredOn(entry -> entry.startsWith("test")).containsExactly( //
				"test #1/#1/#1 [C1@1, [1] value = \"x\", A1@1]", //
				"test #1/#1/#2 [C2@2, [1] value = \"x\", A1@1]", //
				"test #2/#1/#1 [C1@1, [1] value = \"x\", A2@2]", //
				"test #2/#1/#2 [C2@2, [1] value = \"x\", A2@2]", //
				"test #3/#1/#1 [C1@1, [1] value = \"x\", B1@3]", //
				"test #3/#1/#2 [C2@2, [1] value = \"x\", B1@3]");
		}

		@Test
		void invocationsWithNestedInvocationsHaveMethodSourceAndTags() throws Exception {
			var results = executeTests(selectMethod(TreeTestCase.class, "argumentsBelowRepetitions",
				"java.lang.String,org.junit.jupiter.api.RepetitionInfo"));

			var method = TreeTestCase.class.getDeclaredMethod("argumentsBelowRepetitions", String.class,
				RepetitionInfo.class);
			var descriptors = results.allEvents().dynamicallyRegistered().map(Event::getTestDescriptor).toList();
			assertThat(descriptors).hasSize(6).allSatisfy(descriptor -> {
				assertThat(descriptor.getSource()).contains(MethodSource.from(TreeTestCase.class, method));
				assertThat(descriptor.getTags()).containsExactlyInAnyOrder(TestTag.create("method"),
					TestTag.create("class"));
			});
		}
	}

	// -------------------------------------------------------------------------

	/**
	 * {@return one line per dynamically registered descriptor: invocation path,
	 * type, display name, legacy reporting name, and outcome}
	 */
	static List<String> tree(EngineExecutionResults results) {
		Map<UniqueId, String> outcomes = new HashMap<>();
		results.allEvents().stream().forEach(event -> {
			if (event.getType() == EventType.FINISHED) {
				outcomes.put(event.getTestDescriptor().getUniqueId(),
					event.getRequiredPayload(TestExecutionResult.class).getStatus().name());
			}
			else if (event.getType() == EventType.SKIPPED) {
				outcomes.put(event.getTestDescriptor().getUniqueId(),
					"SKIPPED(" + event.getRequiredPayload(String.class) + ")");
			}
		});
		return results.allEvents().dynamicallyRegistered().map(Event::getTestDescriptor) //
				.map(descriptor -> "%s %s '%s' %s %s".formatted(invocationPath(descriptor.getUniqueId()),
					descriptor.getType(), descriptor.getDisplayName(), descriptor.getLegacyReportingName(),
					outcomes.getOrDefault(descriptor.getUniqueId(), "NOT_FINISHED"))) //
				.toList();
	}

	static String invocationPath(UniqueId uniqueId) {
		return uniqueId.getSegments().stream() //
				.filter(segment -> segment.getType().equals("test-template-invocation")) //
				.map(UniqueId.Segment::getValue) //
				.collect(joining("/"));
	}

	static String invocationPath(ExtensionContext context) {
		String path = invocationPath(UniqueId.parse(context.getUniqueId()));
		return path.isEmpty() ? "TT" : path;
	}

	static UniqueId templateId(Class<?> testClass, String methodSegment) {
		return UniqueId.forEngine(JupiterEngineDescriptor.ENGINE_ID) //
				.append("class", testClass.getName()) //
				.append("test-template", methodSegment);
	}

	/**
	 * The invocation path of a leaf and the display names of its invocation
	 * ancestors, from the leaf to the outermost invocation.
	 */
	record Invocation(String path, List<String> displayNames) {

		@Override
		public String toString() {
			return this.path + " " + this.displayNames;
		}
	}

	static class InvocationResolver implements ParameterResolver {

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return parameterContext.getParameter().getType() == Invocation.class;
		}

		@Override
		public Invocation resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			List<String> displayNames = new ArrayList<>();
			Optional<ExtensionContext> current = Optional.of(extensionContext);
			while (current.isPresent() && UniqueId.parse(current.get().getUniqueId()).getLastSegment().getType().equals(
				"test-template-invocation")) {
				displayNames.add(current.get().getDisplayName());
				current = current.get().getParent();
			}
			return new Invocation(invocationPath(extensionContext), displayNames);
		}
	}

	@Retention(RUNTIME)
	@Target({ METHOD, TYPE, ANNOTATION_TYPE })
	@ExtendWith(ProviderA.class)
	@interface LevelA {
	}

	@Retention(RUNTIME)
	@Target({ METHOD, TYPE, ANNOTATION_TYPE })
	@ExtendWith(ProviderB.class)
	@interface LevelB {
	}

	@Retention(RUNTIME)
	@Target({ METHOD, TYPE, ANNOTATION_TYPE })
	@ExtendWith(ProviderC.class)
	@interface LevelC {
	}

	@Retention(RUNTIME)
	@Target({ METHOD, TYPE, ANNOTATION_TYPE })
	@ExtendWith(NonEnclosingProvider.class)
	@interface NonEnclosingLevel {
	}

	/**
	 * Provider that logs every call made by the engine together with the
	 * invocation path of the supplied extension context.
	 */
	abstract static class LevelProvider implements TestTemplateInvocationContextProvider {

		private final String name;
		private final int count;
		private final boolean mayEnclose;

		LevelProvider(String name, int count, boolean mayEnclose) {
			this.name = name;
			this.count = count;
			this.mayEnclose = mayEnclose;
		}

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			log.add("supports " + this.name + " " + invocationPath(context));
			return true;
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			log.add("provide " + this.name + " " + invocationPath(context));
			return IntStream.rangeClosed(1, count(context)).<TestTemplateInvocationContext> mapToObj(i -> {
				log.add("pull " + this.name + i);
				return new LevelContext(this.name + i);
			}).onClose(() -> log.add("close " + this.name + " " + invocationPath(context)));
		}

		int count(ExtensionContext context) {
			return this.count;
		}

		@Override
		public boolean mayReturnZeroTestTemplateInvocationContexts(ExtensionContext context) {
			log.add("mayReturnZero " + this.name + " " + invocationPath(context));
			return false;
		}

		@Override
		public boolean mayEncloseTestTemplateInvocations(ExtensionContext context) {
			log.add("mayEnclose " + this.name + " " + invocationPath(context));
			return this.mayEnclose;
		}
	}

	record LevelContext(String name) implements TestTemplateInvocationContext {

		@Override
		public String getDisplayName(int invocationIndex) {
			return this.name + "@" + invocationIndex;
		}

		@Override
		public void prepareInvocation(ExtensionContext context) {
			log.add("prepare " + this.name + " " + invocationPath(context));
		}
	}

	static class ProviderA extends LevelProvider {
		ProviderA() {
			super("A", 2, true);
		}
	}

	static class ProviderB extends LevelProvider {
		ProviderB() {
			super("B", 1, true);
		}
	}

	static class ProviderC extends LevelProvider {
		ProviderC() {
			super("C", 2, true);
		}
	}

	static class NonEnclosingProvider extends LevelProvider {
		NonEnclosingProvider() {
			super("N", 1, false);
		}
	}

	@Tag("class")
	@ExtendWith(InvocationResolver.class)
	static class TreeTestCase {

		@Tag("method")
		@RepeatedTest(2)
		@ParameterizedTest
		@ValueSource(strings = { "a", "b" })
		@InvocationComposition(levels = ParameterizedTest.class)
		void argumentsBelowRepetitions(String value, RepetitionInfo repetitionInfo) {
			log.add(value + " " + repetitionInfo.getCurrentRepetition() + "/" + repetitionInfo.getTotalRepetitions());
		}

		@RepeatedTest(1)
		@ParameterizedTest(name = "{displayName} :: [{index}]")
		@ValueSource(strings = "a")
		@InvocationComposition(levels = ParameterizedTest.class)
		void nestedArgumentNames(String value) {
		}

		@LevelA
		@LevelB
		@LevelC
		@ParameterizedTest
		@ValueSource(strings = "x")
		@InvocationComposition(levels = { ParameterizedTest.class, LevelC.class })
		void threeLevels(String value, Invocation invocation) {
			log.add("test " + invocation);
		}
	}

	@Nested
	class DeclarationTests {

		@Test
		void declarationOnTestClassAppliesToItsTestTemplateMethods() {
			var results = executeTestsForClass(ClassDeclarationTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 B1@1", "#2 A2@2", "#2/#1 B1@1");
		}

		@Test
		void nearestDeclarationFormsTheOutermostLevel() {
			var results = executeTestsForClass(MethodAndClassDeclarationTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 B1@1", "#2 A2@2", "#2/#1 B1@1");
		}

		@Test
		void declarationOnSubclassPrecedesDeclarationOnSuperclass() {
			var results = executeTestsForClass(SubclassDeclarationTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 B1@1", "#1/#1 A1@1", "#1/#2 A2@2");
		}

		@Test
		void declarationOnInterfaceIsVisitedAfterTheClassHierarchy() {
			var results = executeTestsForClass(InterfaceDeclarationTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 B1@1", "#1/#1 A1@1", "#1/#2 A2@2");
		}

		@Test
		void declarationOnEnclosingClassAppliesToNestedClasses() {
			var results = executeTestsForClass(EnclosingClassDeclarationTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 B1@1", "#2 A2@2", "#2/#1 B1@1");
		}

		@Test
		void duplicateLevelsAreIgnored() {
			var results = executeTestsForClass(DuplicateLevelsTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 B1@1", "#2 A2@2", "#2/#1 B1@1");
		}

		@Test
		void declarationsOfInheritedNestedClassDependOnTheEnclosingTestClass() {
			var composedFirst = executeTests(selectClass(ComposedSubclassTestCase.class),
				selectClass(PlainSubclassTestCase.class));
			var plainFirst = executeTests(selectClass(PlainSubclassTestCase.class),
				selectClass(ComposedSubclassTestCase.class));

			for (var results : List.of(composedFirst, plainFirst)) {
				assertThat(displayNamesBelow(results, ComposedSubclassTestCase.class)) //
						.containsExactly("#1 A1@1", "#1/#1 B1@1", "#2 A2@2", "#2/#1 B1@1");
				assertThat(displayNamesBelow(results, PlainSubclassTestCase.class)) //
						.containsExactly("#1 A1@1", "#2 A2@2", "#3 B1@3");
			}
		}

		@Test
		void libraryAnnotationDeclaresItsOwnLevel() {
			var results = executeTestsForClass(LibraryTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 [1] value = \"x\"", "#1/#1 LA1@1",
				"#2 [2] value = \"y\"", "#2/#1 LA1@1");
		}

		@Test
		void directDeclarationPrecedesMetaPresentDeclarationsOnTheSameElement() {
			var results = executeTestsForClass(ExplicitOrderOfLibrariesTestCase.class);

			assertThat(displayNames(results)).containsExactly("#1 LB1@1", "#1/#1 LA1@1");
		}

		@Test
		void levelsOfDifferentLibrariesWithoutExplicitOrderAreAmbiguous() {
			var results = executeTests(selectMethod(TwoLibrariesTestCase.class, "bothLibraries"));

			results.containerEvents().assertThatEvents().haveExactly(1, event(container("bothLibraries"),
				finishedWithFailure(instanceOf(ExtensionConfigurationException.class), message("""
						The order of the levels [@LibraryA, @LibraryB] of @TestTemplate method [bothLibraries()] \
						is undefined: they are declared by different annotations [@LibraryA, @LibraryB] on class \
						[%s]. Declare their order explicitly, for example via \
						@InvocationComposition(levels = { LibraryA.class, LibraryB.class }) on the test method or \
						the test class.""".formatted(TwoLibrariesTestCase.class.getName())))));
			assertThat(log).noneMatch(entry -> entry.startsWith("provide") || entry.startsWith("mayEnclose"));
		}

		@Test
		void ambiguityIsIgnoredIfOnlyOneOfTheAmbiguousLevelsIsActive() {
			var results = executeTests(selectMethod(TwoLibrariesTestCase.class, "withoutLB", "java.lang.String"));

			assertThat(displayNames(results)).containsExactly("#1 [1] value = \"x\"", "#1/#1 LA1@1");
		}
	}

	@Nested
	class AnnotationTypeTests {

		@ParameterizedTest
		@ValueSource(strings = { "noPossibleProvider", "repeatedTestListed", "composedRepeatedTestListed" })
		void listedAnnotationTypeWithoutPossibleProviderIsConfigurationError(String methodName) {
			var results = executeTests(selectMethod(AnnotationTypeTestCase.class, methodName, "java.lang.String"));

			Class<?> listedType = switch (methodName) {
				case "noPossibleProvider" -> DisplayName.class;
				case "repeatedTestListed" -> RepeatedTest.class;
				default -> RepeatTwice.class;
			};
			results.containerEvents().assertThatEvents().haveExactly(1,
				event(container(methodName),
					finishedWithFailure(instanceOf(ExtensionConfigurationException.class), message("""
							@InvocationComposition declared on method [%s(String)] in class [%s] \
							lists annotation type [%s], but no TestTemplateInvocationContextProvider can belong to \
							it: it is neither directly nor meta-annotated with @ExtendWith declaring such a \
							provider.""".formatted(methodName, AnnotationTypeTestCase.class.getName(),
						listedType.getName())))));
			results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(0));
		}

		@Test
		void listedAnnotationTypeWithoutActiveProviderHasNoEffect() {
			var results = executeTests(
				selectMethod(AnnotationTypeTestCase.class, "noActiveProvider", "java.lang.String"));

			assertThat(tree(results)).containsExactly( //
				"#1 TEST '[1] value = \"x\"' noActiveProvider(String)[1] SUCCESSFUL", //
				"#2 TEST '[2] value = \"y\"' noActiveProvider(String)[2] SUCCESSFUL");
		}
	}

	@Nested
	class ConsentTests {

		@Test
		void providerThatDoesNotSupportEnclosingFailsTemplateBeforeAnyInvocationContextIsProvided() {
			var results = executeTests(selectMethod(ConsentTestCase.class, "nonEnclosingOuterLevel"));

			results.containerEvents().assertThatEvents().haveExactly(1, event(container("nonEnclosingOuterLevel"),
				finishedWithFailure(instanceOf(ExtensionConfigurationException.class), message("""
						Provider [NonEnclosingProvider] would enclose the invocations of [ProviderA] for \
						@TestTemplate method [nonEnclosingOuterLevel()], but does not declare that its \
						invocations may have nested invocations (see \
						TestTemplateInvocationContextProvider.mayEncloseTestTemplateInvocations()). Make it the \
						innermost level by listing the annotation that registers it last in \
						@InvocationComposition(levels = ...), or ask its maintainers to support enclosing \
						invocations."""))));
			assertThat(log).containsExactly("supports N TT", "supports A TT", "mayEnclose N TT");
		}

		@Test
		void innermostProviderNeedNotSupportEnclosing() {
			var results = executeTests(selectMethod(ConsentTestCase.class, "nonEnclosingInnermostLevel"));

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 N1@1", "#2 A2@2", "#2/#1 N1@1");
			assertThat(log).filteredOn(entry -> entry.startsWith("mayEnclose")).containsExactly("mayEnclose A TT");
		}

		@Test
		void enclosingIsNotQueriedWithoutDeclaration() {
			var results = executeTests(selectMethod(ConsentTestCase.class, "noDeclaration"));

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#2 A2@2", "#3 N1@3");
			assertThat(log).noneMatch(entry -> entry.startsWith("mayEnclose"));
		}

		@Test
		void enclosingIsNotQueriedIfActiveProvidersEndUpOnSingleLevel() {
			var results = executeTests(selectMethod(ConsentTestCase.class, "singleLevel"));

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#2 A2@2", "#3 N1@3");
			assertThat(log).noneMatch(entry -> entry.startsWith("mayEnclose"));
		}

		@Test
		void enclosingIsQueriedOnceWithTemplateContextForProvidersOfAllButInnermostLevel() {
			executeTests(selectMethod(ConsentTestCase.class, "threeLevels"));

			assertThat(log).filteredOn(entry -> entry.startsWith("mayEnclose")) //
					.containsExactly("mayEnclose A TT", "mayEnclose B TT");
		}
	}

	// -------------------------------------------------------------------------

	/**
	 * {@return the invocation path and display name of each dynamically
	 * registered descriptor}
	 */
	static List<String> displayNames(EngineExecutionResults results) {
		return results.allEvents().dynamicallyRegistered().map(Event::getTestDescriptor) //
				.map(descriptor -> invocationPath(descriptor.getUniqueId()) + " " + descriptor.getDisplayName()) //
				.toList();
	}

	static List<String> displayNamesBelow(EngineExecutionResults results, Class<?> testClass) {
		return results.allEvents().dynamicallyRegistered().map(Event::getTestDescriptor) //
				.filter(
					descriptor -> descriptor.getUniqueId().getSegments().get(1).getValue().equals(testClass.getName())) //
				.map(descriptor -> invocationPath(descriptor.getUniqueId()) + " " + descriptor.getDisplayName()) //
				.toList();
	}

	@InvocationComposition(levels = LevelB.class)
	static class ClassDeclarationTestCase {

		@TestTemplate
		@LevelB
		@LevelA
		void test() {
		}
	}

	@InvocationComposition(levels = { LevelB.class, LevelA.class })
	static class MethodAndClassDeclarationTestCase {

		@TestTemplate
		@LevelA
		@LevelB
		@InvocationComposition(levels = LevelA.class)
		void test() {
		}
	}

	@InvocationComposition(levels = { LevelA.class, LevelB.class })
	abstract static class DeclaringSuperclass {

		@TestTemplate
		@LevelA
		@LevelB
		void test() {
		}
	}

	@InvocationComposition(levels = { LevelB.class, LevelA.class })
	static class SubclassDeclarationTestCase extends DeclaringSuperclass {
	}

	@InvocationComposition(levels = LevelA.class)
	interface DeclaringInterface {
	}

	@InvocationComposition(levels = LevelB.class)
	static class InterfaceDeclarationTestCase implements DeclaringInterface {

		@TestTemplate
		@LevelA
		@LevelB
		void test() {
		}
	}

	@InvocationComposition(levels = LevelB.class)
	static class EnclosingClassDeclarationTestCase {

		@Nested
		class NestedTestCase {

			@TestTemplate
			@LevelA
			@LevelB
			void test() {
			}
		}
	}

	@InvocationComposition(levels = LevelB.class)
	static class DuplicateLevelsTestCase {

		@TestTemplate
		@LevelA
		@LevelB
		@InvocationComposition(levels = { LevelB.class, LevelB.class })
		void test() {
		}
	}

	abstract static class BaseWithNestedTestCase {

		@Nested
		class InheritedNestedTestCase {

			@TestTemplate
			@LevelA
			@LevelB
			void test() {
			}
		}
	}

	@InvocationComposition(levels = LevelB.class)
	static class ComposedSubclassTestCase extends BaseWithNestedTestCase {
	}

	static class PlainSubclassTestCase extends BaseWithNestedTestCase {
	}

	@Retention(RUNTIME)
	@Target({ TYPE, METHOD, ANNOTATION_TYPE })
	@ExtendWith(LibraryProviderA.class)
	@InvocationComposition(levels = LibraryA.class)
	@interface LibraryA {
	}

	@Retention(RUNTIME)
	@Target({ TYPE, METHOD, ANNOTATION_TYPE })
	@ExtendWith(LibraryProviderB.class)
	@InvocationComposition(levels = LibraryB.class)
	@interface LibraryB {
	}

	/**
	 * Library provider that supports all test template methods, except those
	 * whose name contains {@code "Without<name>"}.
	 */
	abstract static class LibraryProvider extends LevelProvider {

		private final String name;

		LibraryProvider(String name) {
			super(name, 1, true);
			this.name = name;
		}

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			return super.supportsTestTemplate(context)
					&& !context.getRequiredTestMethod().getName().contains("without" + this.name);
		}
	}

	static class LibraryProviderA extends LibraryProvider {
		LibraryProviderA() {
			super("LA");
		}
	}

	static class LibraryProviderB extends LibraryProvider {
		LibraryProviderB() {
			super("LB");
		}
	}

	@LibraryA
	static class LibraryTestCase {

		@ParameterizedTest
		@ValueSource(strings = { "x", "y" })
		void test(String value) {
		}
	}

	@LibraryA
	@LibraryB
	@InvocationComposition(levels = { LibraryB.class, LibraryA.class })
	static class ExplicitOrderOfLibrariesTestCase {

		@TestTemplate
		void test() {
		}
	}

	@LibraryA
	@LibraryB
	static class TwoLibrariesTestCase {

		@TestTemplate
		void bothLibraries() {
		}

		@ParameterizedTest
		@ValueSource(strings = "x")
		void withoutLB(String value) {
		}
	}

	@Retention(RUNTIME)
	@Target({ METHOD, ANNOTATION_TYPE })
	@RepeatedTest(2)
	@interface RepeatTwice {
	}

	static class AnnotationTypeTestCase {

		@RepeatTwice
		@ParameterizedTest
		@ValueSource(strings = "x")
		@InvocationComposition(levels = RepeatTwice.class)
		void composedRepeatedTestListed(String value) {
		}

		@RepeatedTest(2)
		@ParameterizedTest
		@ValueSource(strings = "x")
		@InvocationComposition(levels = RepeatedTest.class)
		void repeatedTestListed(String value) {
		}

		@ParameterizedTest
		@ValueSource(strings = "x")
		@InvocationComposition(levels = DisplayName.class)
		void noPossibleProvider(String value) {
		}

		@ParameterizedTest
		@ValueSource(strings = { "x", "y" })
		@InvocationComposition(levels = LevelA.class)
		void noActiveProvider(String value) {
		}
	}

	static class ConsentTestCase {

		@TestTemplate
		@NonEnclosingLevel
		@LevelA
		@InvocationComposition(levels = LevelA.class)
		void nonEnclosingOuterLevel() {
		}

		@TestTemplate
		@NonEnclosingLevel
		@LevelA
		@InvocationComposition(levels = NonEnclosingLevel.class)
		void nonEnclosingInnermostLevel() {
		}

		@TestTemplate
		@LevelA
		@NonEnclosingLevel
		void noDeclaration() {
		}

		@TestTemplate
		@LevelA
		@NonEnclosingLevel
		@InvocationComposition(levels = LevelC.class)
		void singleLevel() {
		}

		@TestTemplate
		@LevelA
		@LevelB
		@NonEnclosingLevel
		@InvocationComposition(levels = { LevelB.class, NonEnclosingLevel.class })
		void threeLevels() {
		}
	}

	@Nested
	class ProtocolTests {

		@Test
		void providersOfNestedLevelAreInvokedPerEnclosingInvocationWithItsContext() {
			var results = executeTests(selectMethod(ProtocolTestCase.class, "twoLevels", Invocation.class.getName()));

			results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(4).succeeded(4));
			assertThat(log).containsExactly( //
				"supports A TT", "supports C TT", "mayEnclose A TT", //
				"provide A TT", //
				"pull A1", "prepare A1 #1", //
				"supports C #1", "provide C #1", //
				"pull C1", "prepare C1 #1/#1", "test #1/#1 [C1@1, A1@1]", //
				"pull C2", "prepare C2 #1/#2", "test #1/#2 [C2@2, A1@1]", //
				"close C #1", //
				"pull A2", "prepare A2 #2", //
				"supports C #2", "provide C #2", //
				"pull C1", "prepare C1 #2/#1", "test #2/#1 [C1@1, A2@2]", //
				"pull C2", "prepare C2 #2/#2", "test #2/#2 [C2@2, A2@2]", //
				"close C #2", //
				"close A TT");
		}

		@Test
		void zeroInvocationContextsOnNestedLevelFailEnclosingInvocation() {
			var results = executeTests(selectMethod(ProtocolTestCase.class, "zeroForSecond"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'A1@1' zeroForSecond()[1] SUCCESSFUL", //
				"#1/#1 TEST 'Z1@1' zeroForSecond()[1][1] SUCCESSFUL", //
				"#2 CONTAINER 'A2@2' zeroForSecond()[2] FAILED");
			results.containerEvents().assertThatEvents().haveExactly(1,
				event(invocation("#2"), finishedWithFailure(message(
					"Provider [ZeroForSecondProvider] did not provide any invocation contexts, but was expected to do so. "
							+ "You may override mayReturnZeroTestTemplateInvocationContexts() to allow this."))));
			assertThat(log).filteredOn(entry -> entry.startsWith("mayReturnZero")).containsExactly(
				"mayReturnZero Z #2");
		}

		@Test
		void nestedLevelWithoutSupportingProviderFailsEnclosingInvocation() {
			var results = executeTests(selectMethod(ProtocolTestCase.class, "unsupportedNestedLevel"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'A1@1' unsupportedNestedLevel()[1] FAILED", //
				"#2 CONTAINER 'A2@2' unsupportedNestedLevel()[2] FAILED");
			results.containerEvents().assertThatEvents().haveExactly(1, event(invocation("#1"),
				finishedWithFailure(instanceOf(PreconditionViolationException.class), message("""
						None of the providers [TemplateOnlyProvider] of nested level [2] of @TestTemplate method \
						[unsupportedNestedLevel()] supports the enclosing invocation [A1@1]. A provider that does \
						not apply to a particular enclosing invocation should provide no invocation contexts and \
						override mayReturnZeroTestTemplateInvocationContexts() instead."""))));
			assertThat(log).filteredOn(entry -> entry.startsWith("supports T")).containsExactly("supports T TT",
				"supports T #1", "supports T #2");
		}

		@Test
		void failingStreamOnNestedLevelFailsEnclosingInvocationAfterItsInvocations() {
			var results = executeTests(selectMethod(ProtocolTestCase.class, "failingNestedStream"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'A1@1' failingNestedStream()[1] FAILED", //
				"#1/#1 TEST 'F1@1' failingNestedStream()[1][1] SUCCESSFUL", //
				"#2 CONTAINER 'A2@2' failingNestedStream()[2] FAILED", //
				"#2/#1 TEST 'F1@1' failingNestedStream()[2][1] SUCCESSFUL");
			assertThat(log).filteredOn(entry -> entry.startsWith("close")).containsExactly("close F #1", "close F #2",
				"close A TT");
		}

		@Test
		void stateCreatedBySupportsWithTemplateContextIsVisibleToAllEnclosingInvocations() {
			var results = executeTests(selectMethod(ProtocolTestCase.class, "stateInSupports"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'A1@1' stateInSupports()[1] SUCCESSFUL", //
				"#1/#1 TEST 'I1@1' stateInSupports()[1][1] SUCCESSFUL", //
				"#2 CONTAINER 'A2@2' stateInSupports()[2] FAILED");
		}

		@Test
		void failingPreparationOfEnclosingInvocationPreventsNestedInvocations() {
			var results = executeTests(selectMethod(ProtocolTestCase.class, "failingPreparation"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'P1@1' failingPreparation()[1] FAILED");
			results.containerEvents().assertThatEvents().haveExactly(1,
				event(invocation("#1"), finishedWithFailure(message("preparation failure"))));
			assertThat(log).noneMatch(entry -> entry.startsWith("supports C #"));
		}
	}

	@Nested
	class ExtensionContextTests {

		@Test
		void contextOfEnclosingInvocationIsParentOfNestedContextsAndHasNoTestInstanceForPerMethodLifecycle() {
			executeTests(selectMethod(ContextTestCase.class, "test"));

			assertThat(log).filteredOn(entry -> entry.startsWith("beforeEach")).containsExactly( //
				"beforeEach #1/#1 MethodExtensionContext < TestTemplateExtensionContext(#1, instance=false) "
						+ "< TestTemplateExtensionContext(TT) < ClassExtensionContext", //
				"beforeEach #1/#2 MethodExtensionContext < TestTemplateExtensionContext(#1, instance=false) "
						+ "< TestTemplateExtensionContext(TT) < ClassExtensionContext", //
				"beforeEach #2/#1 MethodExtensionContext < TestTemplateExtensionContext(#2, instance=false) "
						+ "< TestTemplateExtensionContext(TT) < ClassExtensionContext", //
				"beforeEach #2/#2 MethodExtensionContext < TestTemplateExtensionContext(#2, instance=false) "
						+ "< TestTemplateExtensionContext(TT) < ClassExtensionContext");
		}

		@Test
		void contextOfEnclosingInvocationHasTestInstanceForPerClassLifecycle() {
			executeTests(selectMethod(PerClassContextTestCase.class, "test"));

			assertThat(log).filteredOn(entry -> entry.startsWith("beforeEach")) //
					.hasSize(4).allMatch(
						entry -> entry.contains("(#1, instance=true)") || entry.contains("(#2, instance=true)"));
		}

		@Test
		void invocationsInNestedClassHaveEnclosingTestClassesAndTagsOfAllAncestors() {
			var results = executeTestsForClass(NestedContextTestCase.class);

			assertThat(log).filteredOn(entry -> entry.startsWith("enclosing")).containsExactly( //
				"enclosing #1 [NestedContextTestCase] [inner, method, outer]", //
				"enclosing #1/#1 [NestedContextTestCase] [inner, method, outer]", //
				"enclosing #2 [NestedContextTestCase] [inner, method, outer]", //
				"enclosing #2/#1 [NestedContextTestCase] [inner, method, outer]");
			assertThat(results.allEvents().dynamicallyRegistered().map(Event::getTestDescriptor).map(
				descriptor -> descriptor.getTags().stream().map(TestTag::getName).sorted().toList())).containsOnly(
					List.of("inner", "method", "outer"));
		}

		@Test
		void storeOfEnclosingInvocationIsVisibleToNestedInvocationsAndClosedAfterThem() {
			executeTests(selectMethod(StoreTestCase.class, "test", "java.lang.String"));

			assertThat(log).filteredOn(entry -> entry.startsWith("test") || entry.startsWith("closed S")) //
					.containsExactly( //
						"test #1/#1 sees S1", "test #1/#2 sees S1", "closed S1", //
						"test #2/#1 sees S2", "test #2/#2 sees S2", "closed S2");
		}

		@Test
		void executionConditionsAreEvaluatedForEnclosingInvocations() {
			var results = executeTests(selectMethod(ConditionTestCase.class, "test"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'A1@1' test()[1] SUCCESSFUL", //
				"#1/#1 TEST 'C1@1' test()[1][1] SUCCESSFUL", //
				"#1/#2 TEST 'C2@2' test()[1][2] SUCCESSFUL", //
				"#2 CONTAINER 'A2@2' test()[2] SKIPPED(disabled A2@2)");
			assertThat(log).filteredOn(entry -> entry.startsWith("test")) //
					.containsExactly("testSuccessful #1/#1", "testSuccessful #1/#2", "testDisabled #2");
		}
	}

	@Nested
	class SelectionTests {

		private final UniqueId templateId = templateId(SelectionTestCase.class, "test()");

		@Test
		void leafCanBeSelectedByUniqueId() {
			var results = executeTests(selectUniqueId(templateId.append(SEGMENT, "#2").append(SEGMENT, "#1")));

			assertThat(displayNames(results)).containsExactly("#2 A2@2", "#2/#1 C1@1");
		}

		@Test
		void enclosingInvocationSelectedByUniqueIdExecutesAllNestedInvocations() {
			var results = executeTests(selectUniqueId(templateId.append(SEGMENT, "#2")));

			assertThat(displayNames(results)).containsExactly("#2 A2@2", "#2/#1 C1@1", "#2/#2 C2@2");
		}

		@Test
		void enclosingInvocationSelectedTogetherWithOneOfItsLeavesExecutesAllNestedInvocations() {
			var results = executeTests(selectUniqueId(templateId.append(SEGMENT, "#1")),
				selectUniqueId(templateId.append(SEGMENT, "#1").append(SEGMENT, "#2")));

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 C1@1", "#1/#2 C2@2");
		}

		@Test
		void iterationSelectorSelectsOutermostInvocationWithAllNestedInvocations() {
			var results = executeTests(selectIteration(selectMethod(SelectionTestCase.class, "test"), 1));

			assertThat(displayNames(results)).containsExactly("#2 A2@2", "#2/#1 C1@1", "#2/#2 C2@2");
		}

		@Test
		void iterationSelectorCombinedWithUniqueIdOfLeafInOtherInvocation() {
			var results = executeTests(selectIteration(selectMethod(SelectionTestCase.class, "test"), 0),
				selectUniqueId(templateId.append(SEGMENT, "#2").append(SEGMENT, "#1")));

			assertThat(displayNames(results)).containsExactly("#1 A1@1", "#1/#1 C1@1", "#1/#2 C2@2", "#2 A2@2",
				"#2/#1 C1@1");
		}

		@Test
		void leafOfThirdLevelCanBeSelectedByUniqueId() {
			var leafId = templateId(TreeTestCase.class,
				"threeLevels(java.lang.String, org.junit.jupiter.engine.InvocationCompositionTests$Invocation)") //
						.append(SEGMENT, "#2").append(SEGMENT, "#1").append(SEGMENT, "#1");

			var results = executeTests(selectUniqueId(leafId));

			assertThat(displayNames(results)).containsExactly("#2 A2@2", "#2/#1 [1] value = \"x\"", "#2/#1/#1 C1@1");
			assertThat(results.testEvents().dynamicallyRegistered().map(Event::getTestDescriptor).map(
				TestDescriptor::getUniqueId)).containsExactly(leafId);
		}

		@Test
		void nestedIterationSelectorIsNotResolved() {
			var results = executeTests(
				selectIteration(selectIteration(selectMethod(SelectionTestCase.class, "test"), 1), 0));

			assertThat(displayNames(results)).isEmpty();
			results.allEvents().assertStatistics(stats -> stats.started(1).finished(1));
		}
	}

	@Nested
	class RepetitionTests {

		@Test
		void failureThresholdCountsFailedRepetitionsThatEncloseNestedInvocations() {
			var results = executeTests(
				selectMethod(RepetitionTestCase.class, "repetitionsAboveArguments", "java.lang.String"));

			assertThat(tree(results)).containsExactly( //
				"#1 CONTAINER 'repetition 1 of 4' repetitionsAboveArguments(String)[1] SUCCESSFUL", //
				"#1/#1 TEST '[1] value = \"x\"' repetitionsAboveArguments(String)[1][1] FAILED", //
				"#1/#2 TEST '[2] value = \"y\"' repetitionsAboveArguments(String)[1][2] FAILED", //
				"#2 CONTAINER 'repetition 2 of 4' repetitionsAboveArguments(String)[2] SUCCESSFUL", //
				"#2/#1 TEST '[1] value = \"x\"' repetitionsAboveArguments(String)[2][1] FAILED", //
				"#2/#2 TEST '[2] value = \"y\"' repetitionsAboveArguments(String)[2][2] FAILED", //
				"#3 CONTAINER 'repetition 3 of 4' repetitionsAboveArguments(String)[3] SKIPPED(Failure threshold [2] exceeded)", //
				"#4 CONTAINER 'repetition 4 of 4' repetitionsAboveArguments(String)[4] SKIPPED(Failure threshold [2] exceeded)");
		}

	}

	// -------------------------------------------------------------------------

	static final String SEGMENT = "test-template-invocation";

	static Condition<Event> invocation(String invocationPath) {
		return new Condition<>(event -> invocationPath(event.getTestDescriptor().getUniqueId()).equals(invocationPath),
			"invocation " + invocationPath);
	}

	@ExtendWith(InvocationResolver.class)
	static class ProtocolTestCase {

		@TestTemplate
		@LevelA
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void twoLevels(Invocation invocation) {
			log.add("test " + invocation);
		}

		@TestTemplate
		@LevelA
		@ExtendWith(ZeroForSecondProvider.class)
		@InvocationComposition(levels = ExtendWithZeroForSecond.class)
		void zeroForSecond() {
		}

		@TestTemplate
		@LevelA
		@ExtendWith(TemplateOnlyProvider.class)
		@InvocationComposition(levels = ExtendWithTemplateOnly.class)
		void unsupportedNestedLevel() {
		}

		@TestTemplate
		@LevelA
		@ExtendWith(FailingNestedStreamProvider.class)
		@InvocationComposition(levels = ExtendWithFailingNestedStream.class)
		void failingNestedStream() {
		}

		@TestTemplate
		@ExtendWith(FailingPreparationProvider.class)
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void failingPreparation() {
		}

		@TestTemplate
		@LevelA
		@ExtendWith(IteratorInSupportsProvider.class)
		@InvocationComposition(levels = ExtendWithIteratorInSupports.class)
		void stateInSupports() {
		}
	}

	@Retention(RUNTIME)
	@ExtendWith(IteratorInSupportsProvider.class)
	@interface ExtendWithIteratorInSupports {
	}

	/**
	 * Provider that creates its state in {@code supportsTestTemplate()} via
	 * {@code computeIfAbsent()}: the state created for the template is found by
	 * the lookup for every enclosing invocation, so the second enclosing
	 * invocation gets an exhausted iterator (documented behaviour).
	 */
	static class IteratorInSupportsProvider implements TestTemplateInvocationContextProvider {

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			context.getStore(NAMESPACE).computeIfAbsent("iterator",
				key -> List.<TestTemplateInvocationContext> of(new LevelContext("I1")).iterator(), Iterator.class);
			return true;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			Iterator<TestTemplateInvocationContext> iterator = requireNonNull(
				context.getStore(NAMESPACE).get("iterator", Iterator.class));
			return Stream.iterate(iterator, Iterator::hasNext, it -> it).map(Iterator::next);
		}
	}

	@Retention(RUNTIME)
	@ExtendWith(ZeroForSecondProvider.class)
	@interface ExtendWithZeroForSecond {
	}

	@Retention(RUNTIME)
	@ExtendWith(TemplateOnlyProvider.class)
	@interface ExtendWithTemplateOnly {
	}

	@Retention(RUNTIME)
	@ExtendWith(FailingNestedStreamProvider.class)
	@interface ExtendWithFailingNestedStream {
	}

	static class ZeroForSecondProvider extends LevelProvider {

		ZeroForSecondProvider() {
			super("Z", 1, true);
		}

		@Override
		int count(ExtensionContext context) {
			return invocationPath(context).equals("#2") ? 0 : 1;
		}
	}

	static class TemplateOnlyProvider extends LevelProvider {

		TemplateOnlyProvider() {
			super("T", 1, true);
		}

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			return super.supportsTestTemplate(context) && invocationPath(context).equals("TT");
		}
	}

	static class FailingNestedStreamProvider extends LevelProvider {

		FailingNestedStreamProvider() {
			super("F", 2, true);
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			return super.provideTestTemplateInvocationContexts(context).peek(invocationContext -> {
				if (invocationContext.getDisplayName(0).startsWith("F2")) {
					throw new IllegalStateException("stream failure");
				}
			});
		}
	}

	static class FailingPreparationProvider extends LevelProvider {

		FailingPreparationProvider() {
			super("P", 1, true);
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			return Stream.of(new TestTemplateInvocationContext() {
				@Override
				public String getDisplayName(int invocationIndex) {
					return "P1@" + invocationIndex;
				}

				@Override
				public void prepareInvocation(ExtensionContext context) {
					throw new IllegalStateException("preparation failure");
				}
			});
		}
	}

	/**
	 * Logs the shape of the extension context chain of each leaf.
	 */
	static class ChainRecorder implements org.junit.jupiter.api.extension.BeforeEachCallback {

		@Override
		public void beforeEach(ExtensionContext context) {
			List<String> chain = new ArrayList<>();
			Optional<ExtensionContext> current = Optional.of(context);
			while (current.isPresent() && current.get().getParent().isPresent()) {
				ExtensionContext ctx = current.get();
				String name = ctx.getClass().getSimpleName();
				if (name.equals("TestTemplateExtensionContext")) {
					String path = invocationPath(ctx);
					name += path.equals("TT") ? "(TT)"
							: "(" + path + ", instance=" + ctx.getTestInstance().isPresent() + ")";
				}
				chain.add(name);
				current = ctx.getParent();
			}
			log.add("beforeEach " + invocationPath(context) + " " + String.join(" < ", chain));
		}
	}

	@ExtendWith(ChainRecorder.class)
	static class ContextTestCase {

		@TestTemplate
		@LevelA
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void test() {
		}
	}

	/**
	 * Logs enclosing test classes and tags of each composed invocation and leaf.
	 */
	static class EnclosingRecorder implements org.junit.jupiter.api.extension.ExecutionCondition {

		@Override
		public org.junit.jupiter.api.extension.ConditionEvaluationResult evaluateExecutionCondition(
				ExtensionContext context) {
			String path = invocationPath(context);
			if (!path.equals("TT") && context.getTestMethod().isPresent()) {
				log.add("enclosing " + path + " "
						+ context.getEnclosingTestClasses().stream().map(Class::getSimpleName).toList() + " "
						+ context.getTags().stream().sorted().toList());
			}
			return org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled("enabled");
		}
	}

	@Tag("outer")
	@ExtendWith(EnclosingRecorder.class)
	static class NestedContextTestCase {

		@Nested
		@Tag("inner")
		class Inner {

			@TestTemplate
			@Tag("method")
			@LevelA
			@LevelB
			@InvocationComposition(levels = LevelB.class)
			void test() {
			}
		}
	}

	@org.junit.jupiter.api.TestInstance(org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS)
	@ExtendWith(ChainRecorder.class)
	static class PerClassContextTestCase {

		@TestTemplate
		@LevelA
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void test() {
		}
	}

	record StoredValue(String name) implements AutoCloseable {

		@Override
		public void close() {
			log.add("closed " + this.name);
		}
	}

	static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(
		InvocationCompositionTests.class);

	static class StoringProvider extends LevelProvider {

		StoringProvider() {
			super("S", 2, true);
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			return IntStream.rangeClosed(1, 2).mapToObj(i -> new TestTemplateInvocationContext() {
				@Override
				public String getDisplayName(int invocationIndex) {
					return "S" + i;
				}

				@Override
				public void prepareInvocation(ExtensionContext context) {
					context.getStore(NAMESPACE).put("value", new StoredValue("S" + i));
				}
			});
		}
	}

	static class StoredValueResolver implements ParameterResolver {

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return parameterContext.getParameter().getType() == String.class;
		}

		@Override
		public String resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return invocationPath(extensionContext) + " sees "
					+ requireNonNull(extensionContext.getStore(NAMESPACE).get("value", StoredValue.class)).name();
		}
	}

	@Retention(RUNTIME)
	@ExtendWith(StoringProvider.class)
	@interface Storing {
	}

	static class StoreTestCase {

		@TestTemplate
		@Storing
		@LevelC
		@ExtendWith(StoredValueResolver.class)
		@InvocationComposition(levels = LevelC.class)
		void test(String seen) {
			log.add("test " + seen);
		}
	}

	static class DisablingCondition
			implements org.junit.jupiter.api.extension.ExecutionCondition, org.junit.jupiter.api.extension.TestWatcher {

		@Override
		public org.junit.jupiter.api.extension.ConditionEvaluationResult evaluateExecutionCondition(
				ExtensionContext context) {
			return context.getDisplayName().equals("A2@2")
					? org.junit.jupiter.api.extension.ConditionEvaluationResult.disabled("disabled A2@2")
					: org.junit.jupiter.api.extension.ConditionEvaluationResult.enabled("enabled");
		}

		@Override
		public void testDisabled(ExtensionContext context, Optional<String> reason) {
			log.add("testDisabled " + invocationPath(context));
		}

		@Override
		public void testSuccessful(ExtensionContext context) {
			log.add("testSuccessful " + invocationPath(context));
		}
	}

	@ExtendWith(DisablingCondition.class)
	static class ConditionTestCase {

		@TestTemplate
		@LevelA
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void test() {
		}
	}

	static class SelectionTestCase {

		@TestTemplate
		@LevelA
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void test() {
		}
	}

	static class RepetitionTestCase {

		@RepeatedTest(value = 4, failureThreshold = 2)
		@ParameterizedTest
		@ValueSource(strings = { "x", "y" })
		@InvocationComposition(levels = ParameterizedTest.class)
		void repetitionsAboveArguments(String value) {
			throw new AssertionError("always fails");
		}
	}

	@Nested
	class ParallelExecutionTests {

		@ParameterizedTest
		@EnumSource(ParallelExecutorServiceType.class)
		void nestedInvocationsAreExecutedConcurrentlyAndStoreOfEnclosingInvocationIsClosedAfterThem(
				ParallelExecutorServiceType executorServiceType) {

			var results = executeConcurrently(executorServiceType,
				selectMethod(ParallelTestCase.class, "test", "java.lang.String," + Latch.class.getName()));

			results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(4).succeeded(4));
			results.containerEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).succeeded(5));
			for (String value : List.of("S1", "S2")) {
				var entries = log.stream().filter(entry -> entry.endsWith(value)).toList();
				assertThat(entries).hasSize(3).endsWith("closed " + value);
				assertThat(entries.subList(0, 2)).allMatch(entry -> entry.startsWith("test #"));
			}
		}

		@ParameterizedTest
		@EnumSource(ParallelExecutorServiceType.class)
		void failureOfOutermostStreamAwaitsAlreadySubmittedInvocationsBeforeTemplateIsFinished(
				ParallelExecutorServiceType executorServiceType) {

			var results = executeConcurrently(executorServiceType,
				selectMethod(ParallelFailureTestCase.class, "outermostStreamFails"));

			results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).succeeded(2));
			results.containerEvents().assertThatEvents().haveExactly(1,
				event(container("outermostStreamFails"), finishedWithFailure(message("outer stream failure"))));
			assertThat(log).filteredOn(entry -> entry.startsWith("finished") || entry.startsWith("closed")) //
					.containsExactlyInAnyOrder("finished #1/#1", "finished #1/#2", "closed TT") //
					.endsWith("closed TT");
			assertFinishedAfterAllTests(results, "outermostStreamFails");
		}

		@ParameterizedTest
		@EnumSource(ParallelExecutorServiceType.class)
		void failureOfNestedStreamAwaitsAlreadySubmittedInvocationsBeforeEnclosingInvocationIsFinished(
				ParallelExecutorServiceType executorServiceType) {

			var results = executeConcurrently(executorServiceType,
				selectMethod(ParallelFailureTestCase.class, "nestedStreamFails"));

			results.testEvents().assertStatistics(stats -> stats.dynamicallyRegistered(2).succeeded(2));
			results.containerEvents().assertThatEvents().haveExactly(1,
				event(invocation("#1"), finishedWithFailure(message("nested stream failure"))));
			assertThat(log).filteredOn(entry -> entry.startsWith("finished") || entry.equals("closed #1")) //
					.containsExactlyInAnyOrder("finished #1/#1", "finished #1/#2", "closed #1") //
					.endsWith("closed #1");
		}

		private EngineExecutionResults executeConcurrently(ParallelExecutorServiceType executorServiceType,
				org.junit.platform.engine.DiscoverySelector selector) {
			return executeTests(request -> request //
					.selectors(selector) //
					.configurationParameter(PARALLEL_EXECUTION_ENABLED_PROPERTY_NAME, "true") //
					.configurationParameter(PARALLEL_CONFIG_EXECUTOR_SERVICE_PROPERTY_NAME, executorServiceType.name()) //
					.configurationParameter(DEFAULT_EXECUTION_MODE_PROPERTY_NAME, "concurrent") //
					.configurationParameter(PARALLEL_CONFIG_STRATEGY_PROPERTY_NAME, "fixed") //
					.configurationParameter(PARALLEL_CONFIG_FIXED_PARALLELISM_PROPERTY_NAME, "4"));
		}

		private void assertFinishedAfterAllTests(EngineExecutionResults results, String templateMethodName) {
			var events = results.allEvents().list();
			int templateFinished = -1;
			int lastTestFinished = -1;
			for (int i = 0; i < events.size(); i++) {
				var event = events.get(i);
				if (event.getType() == EventType.FINISHED) {
					var descriptor = event.getTestDescriptor();
					if (descriptor.isTest()) {
						lastTestFinished = i;
					}
					else if (descriptor.getUniqueId().getLastSegment().getValue().startsWith(templateMethodName)) {
						templateFinished = i;
					}
				}
			}
			assertThat(templateFinished).isGreaterThan(lastTestFinished);
		}
	}

	// -------------------------------------------------------------------------

	static final class Latch {

		private final java.util.concurrent.CountDownLatch latch;

		Latch(int count) {
			this.latch = new java.util.concurrent.CountDownLatch(count);
		}

		void arriveAndAwait() throws InterruptedException {
			this.latch.countDown();
			assertThat(this.latch.await(10, java.util.concurrent.TimeUnit.SECONDS)).as(
				"concurrent invocations").isTrue();
		}
	}

	static class ConcurrentStoringProvider extends LevelProvider {

		ConcurrentStoringProvider() {
			super("S", 2, true);
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			return IntStream.rangeClosed(1, 2).mapToObj(i -> new TestTemplateInvocationContext() {
				@Override
				public String getDisplayName(int invocationIndex) {
					return "S" + i;
				}

				@Override
				public void prepareInvocation(ExtensionContext context) {
					context.getStore(NAMESPACE).put("value", new StoredValue("S" + i));
					context.getStore(NAMESPACE).put("latch", new Latch(2));
				}
			});
		}
	}

	static class LatchResolver implements ParameterResolver {

		@Override
		public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return parameterContext.getParameter().getType() == Latch.class;
		}

		@Override
		public Latch resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
			return requireNonNull(extensionContext.getStore(NAMESPACE).get("latch", Latch.class));
		}
	}

	@Retention(RUNTIME)
	@ExtendWith(ConcurrentStoringProvider.class)
	@interface ConcurrentStoring {
	}

	static class ParallelTestCase {

		@TestTemplate
		@ConcurrentStoring
		@LevelC
		@ExtendWith({ StoredValueResolver.class, LatchResolver.class })
		@InvocationComposition(levels = LevelC.class)
		void test(String seen, Latch latch) throws InterruptedException {
			latch.arriveAndAwait();
			log.add("test " + seen);
		}
	}

	/**
	 * Provider whose stream fails after its first invocation context; its
	 * {@code supportsTestTemplate()} stores a resource in the {@code Store} of
	 * the supplied context.
	 */
	static class FailingAfterFirstProvider extends LevelProvider {

		private final String failure;
		private final int failAt;

		FailingAfterFirstProvider(String name, String failure, int failAt) {
			super(name, failAt, true);
			this.failure = failure;
			this.failAt = failAt;
		}

		@Override
		public boolean supportsTestTemplate(ExtensionContext context) {
			context.getStore(NAMESPACE).put(this, new StoredValue(invocationPath(context)));
			return super.supportsTestTemplate(context);
		}

		@Override
		public Stream<TestTemplateInvocationContext> provideTestTemplateInvocationContexts(ExtensionContext context) {
			return super.provideTestTemplateInvocationContexts(context).peek(invocationContext -> {
				if (invocationContext.getDisplayName(0).endsWith(this.failAt + "@0")) {
					throw new IllegalStateException(this.failure);
				}
			});
		}
	}

	static class FailingOutermostProvider extends FailingAfterFirstProvider {
		FailingOutermostProvider() {
			super("O", "outer stream failure", 2);
		}
	}

	static class FailingNestedProvider extends FailingAfterFirstProvider {
		FailingNestedProvider() {
			super("I", "nested stream failure", 3);
		}
	}

	@Retention(RUNTIME)
	@ExtendWith(FailingNestedProvider.class)
	@interface FailingNested {
	}

	static class SlowLeafRecorder implements org.junit.jupiter.api.extension.AfterEachCallback {

		@Override
		public void afterEach(ExtensionContext context) throws Exception {
			Thread.sleep(200);
			log.add("finished " + invocationPath(context));
		}
	}

	@ExtendWith(SlowLeafRecorder.class)
	static class ParallelFailureTestCase {

		@TestTemplate
		@ExtendWith(FailingOutermostProvider.class)
		@LevelC
		@InvocationComposition(levels = LevelC.class)
		void outermostStreamFails() {
		}

		@TestTemplate
		@LevelB
		@FailingNested
		@InvocationComposition(levels = FailingNested.class)
		void nestedStreamFails() {
		}
	}

}
