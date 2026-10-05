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

import static org.apiguardian.api.API.Status.INTERNAL;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.UnaryOperator;

import org.apiguardian.api.API;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.UniqueId;

/**
 * Filter for dynamic descendants of {@link TestDescriptor TestDescriptors} that
 * implement {@link Filterable}.
 *
 * @since 5.1
 * @see Filterable
 */
@API(status = INTERNAL, since = "5.1")
public class DynamicDescendantFilter implements BiPredicate<UniqueId, Integer> {

	private final Set<UniqueId> allowedUniqueIds = new HashSet<>();
	private final Set<Integer> allowedIndices = new HashSet<>();
	private Mode mode = Mode.EXPLICIT;

	public void allowUniqueIdPrefix(UniqueId uniqueId) {
		if (this.mode == Mode.EXPLICIT) {
			this.allowedUniqueIds.add(uniqueId);
		}
	}

	public void allowIndex(int index) {
		if (this.mode == Mode.EXPLICIT) {
			this.allowedIndices.add(index);
		}
	}

	public void allowIndex(Set<Integer> indices) {
		if (this.mode == Mode.EXPLICIT) {
			this.allowedIndices.addAll(indices);
		}
	}

	public void allowAll() {
		this.mode = Mode.ALLOW_ALL;
		this.allowedUniqueIds.clear();
		this.allowedIndices.clear();
	}

	@Override
	public boolean test(UniqueId uniqueId, Integer index) {
		return isEverythingAllowed() //
				|| isUniqueIdAllowed(uniqueId) //
				|| allowedIndices.contains(index);
	}

	private boolean isEverythingAllowed() {
		return allowedUniqueIds.isEmpty() && allowedIndices.isEmpty();
	}

	private boolean isUniqueIdAllowed(UniqueId uniqueId) {
		return allowedUniqueIds.stream().anyMatch(allowedUniqueId -> isPrefixOrViceVersa(uniqueId, allowedUniqueId));
	}

	private boolean isPrefixOrViceVersa(UniqueId currentUniqueId, UniqueId allowedUniqueId) {
		return allowedUniqueId.hasPrefix(currentUniqueId) || currentUniqueId.hasPrefix(allowedUniqueId);
	}

	public DynamicDescendantFilter withoutIndexFiltering() {
		return new WithoutIndexFiltering();
	}

	/**
	 * Create a filter for the dynamic descendants of the admitted dynamic
	 * descendant with the supplied unique ID and zero-based index.
	 *
	 * <p>If the descendant itself or one of its ancestors was selected by its
	 * unique ID, if it was selected by its index, or if everything is allowed,
	 * all of its descendants are allowed. Otherwise, only the descendants with
	 * a unique ID that was selected below the supplied one are allowed.
	 *
	 * <p>Only used for the invocations of composed test templates.
	 *
	 * @since 6.2
	 */
	DynamicDescendantFilter forDescendantsOf(UniqueId uniqueId, int index) {
		DynamicDescendantFilter filter = new DynamicDescendantFilter();
		if (this.mode == Mode.ALLOW_ALL || isEverythingAllowed() || this.allowedIndices.contains(index)
				|| this.allowedUniqueIds.stream().anyMatch(uniqueId::hasPrefix)) {
			filter.allowAll();
			return filter;
		}
		this.allowedUniqueIds.stream() //
				.filter(allowedUniqueId -> allowedUniqueId.hasPrefix(uniqueId)) //
				.forEach(filter.allowedUniqueIds::add);
		if (filter.allowedUniqueIds.isEmpty()) {
			filter.allowAll();
		}
		return filter;
	}

	private enum Mode {
		EXPLICIT, ALLOW_ALL
	}

	public DynamicDescendantFilter copy(UnaryOperator<UniqueId> uniqueIdTransformer) {
		return configure(uniqueIdTransformer, new DynamicDescendantFilter());
	}

	protected DynamicDescendantFilter configure(UnaryOperator<UniqueId> uniqueIdTransformer,
			DynamicDescendantFilter copy) {
		this.allowedUniqueIds.stream().map(uniqueIdTransformer).forEach(copy.allowedUniqueIds::add);
		copy.allowedIndices.addAll(this.allowedIndices);
		copy.mode = this.mode;
		return copy;
	}

	private class WithoutIndexFiltering extends DynamicDescendantFilter {

		@Override
		public boolean test(UniqueId uniqueId, Integer index) {
			return isEverythingAllowed() || isUniqueIdAllowed(uniqueId);
		}

		@Override
		public DynamicDescendantFilter withoutIndexFiltering() {
			return this;
		}

		@Override
		public DynamicDescendantFilter copy(UnaryOperator<UniqueId> uniqueIdTransformer) {
			return configure(uniqueIdTransformer, new WithoutIndexFiltering());
		}
	}
}
