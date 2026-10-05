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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.platform.engine.UniqueId;

/**
 * Unit tests for {@link DynamicDescendantFilter}.
 *
 * @since 6.2
 */
class DynamicDescendantFilterTests {

	private static final UniqueId TEMPLATE = UniqueId.forEngine("junit-jupiter") //
			.append("class", "C") //
			.append("test-template", "m()");

	private static final UniqueId FIRST = invocation(TEMPLATE, 1);
	private static final UniqueId SECOND = invocation(TEMPLATE, 2);

	// --- existing behavior -----------------------------------------------------

	@Test
	void allowsEverythingByDefault() {
		var filter = new DynamicDescendantFilter();

		assertThat(filter.test(FIRST, 0)).isTrue();
		assertThat(filter.test(SECOND, 1)).isTrue();
	}

	@Test
	void allowsUniqueIdsThatArePrefixesOrExtensionsOfAllowedOnes() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(invocation(SECOND, 1));

		assertThat(filter.test(FIRST, 0)).isFalse();
		assertThat(filter.test(SECOND, 1)).isTrue();
	}

	@Test
	void allowsIndices() {
		var filter = new DynamicDescendantFilter();
		filter.allowIndex(Set.of(1));

		assertThat(filter.test(FIRST, 0)).isFalse();
		assertThat(filter.test(SECOND, 1)).isTrue();
	}

	@Test
	void withoutIndexFilteringDisallowsAllDescendantsIfOnlyIndicesWereSelected() {
		var filter = new DynamicDescendantFilter();
		filter.allowIndex(Set.of(0));

		var withoutIndexFiltering = filter.withoutIndexFiltering();

		assertThat(withoutIndexFiltering.test(invocation(FIRST, 1), 0)).isFalse();
	}

	// --- forDescendantsOf ------------------------------------------------------

	@Test
	void descendantsAreAllAllowedIfEverythingIsAllowed() {
		var childFilter = new DynamicDescendantFilter().forDescendantsOf(FIRST, 0);

		assertThat(childFilter.test(invocation(FIRST, 1), 0)).isTrue();
		assertThat(childFilter.test(invocation(FIRST, 2), 1)).isTrue();
	}

	@Test
	void descendantsAreAllAllowedIfAllWasAllowed() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(invocation(FIRST, 2));
		filter.allowAll();

		var childFilter = filter.forDescendantsOf(FIRST, 0);

		assertThat(childFilter.test(invocation(FIRST, 1), 0)).isTrue();
	}

	@Test
	void onlySelectedDescendantIsAllowedIfDeeperUniqueIdWasSelected() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(invocation(SECOND, 1));

		var childFilter = filter.forDescendantsOf(SECOND, 1);

		assertThat(childFilter.test(invocation(SECOND, 1), 0)).isTrue();
		assertThat(childFilter.test(invocation(SECOND, 2), 1)).isFalse();
		assertThat(childFilter.test(invocation(invocation(SECOND, 1), 1), 0)).isTrue();
	}

	@Test
	void descendantsAreAllAllowedIfDescendantItselfWasSelected() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(SECOND);

		var childFilter = filter.forDescendantsOf(SECOND, 1);

		assertThat(childFilter.test(invocation(SECOND, 1), 0)).isTrue();
		assertThat(childFilter.test(invocation(SECOND, 2), 1)).isTrue();
	}

	@Test
	void descendantsAreAllAllowedIfDescendantAndOneOfItsDescendantsWereSelected() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(FIRST);
		filter.allowUniqueIdPrefix(invocation(FIRST, 2));

		var childFilter = filter.forDescendantsOf(FIRST, 0);

		assertThat(childFilter.test(invocation(FIRST, 1), 0)).isTrue();
		assertThat(childFilter.test(invocation(FIRST, 2), 1)).isTrue();
	}

	@Test
	void descendantsAreAllAllowedIfAncestorWasSelected() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(TEMPLATE);

		var childFilter = filter.forDescendantsOf(SECOND, 1);

		assertThat(childFilter.test(invocation(SECOND, 1), 0)).isTrue();
	}

	@Test
	void descendantsAreAllAllowedIfDescendantWasSelectedByIndex() {
		var filter = new DynamicDescendantFilter();
		filter.allowIndex(Set.of(0));
		filter.allowUniqueIdPrefix(invocation(SECOND, 1));

		var firstChildFilter = filter.forDescendantsOf(FIRST, 0);
		var secondChildFilter = filter.forDescendantsOf(SECOND, 1);

		assertThat(firstChildFilter.test(invocation(FIRST, 1), 0)).isTrue();
		assertThat(firstChildFilter.test(invocation(FIRST, 2), 1)).isTrue();
		assertThat(secondChildFilter.test(invocation(SECOND, 1), 0)).isTrue();
		assertThat(secondChildFilter.test(invocation(SECOND, 2), 1)).isFalse();
	}

	@Test
	void indicesAreNotInheritedByDescendants() {
		var filter = new DynamicDescendantFilter();
		filter.allowIndex(Set.of(1));
		filter.allowUniqueIdPrefix(invocation(FIRST, 1));

		var childFilter = filter.forDescendantsOf(FIRST, 0);

		assertThat(childFilter.test(invocation(FIRST, 1), 0)).isTrue();
		assertThat(childFilter.test(invocation(FIRST, 2), 1)).isFalse();
	}

	@Test
	void copyOfDescendantFilterTransformsUniqueIds() {
		var filter = new DynamicDescendantFilter();
		filter.allowUniqueIdPrefix(invocation(SECOND, 1));
		var childFilter = filter.forDescendantsOf(SECOND, 1);
		UniqueId otherTemplate = UniqueId.forEngine("junit-jupiter").append("class", "D").append("test-template",
			"m()");

		var copy = childFilter.copy(uniqueId -> replacePrefix(uniqueId, otherTemplate));

		assertThat(copy.test(invocation(invocation(otherTemplate, 2), 1), 0)).isTrue();
		assertThat(copy.test(invocation(invocation(otherTemplate, 2), 2), 1)).isFalse();
	}

	private static UniqueId replacePrefix(UniqueId uniqueId, UniqueId newPrefix) {
		UniqueId result = newPrefix;
		for (var segment : uniqueId.getSegments().subList(TEMPLATE.getSegments().size(),
			uniqueId.getSegments().size())) {
			result = result.append(segment);
		}
		return result;
	}

	private static UniqueId invocation(UniqueId parent, int index) {
		return parent.append("test-template-invocation", "#" + index);
	}

}
