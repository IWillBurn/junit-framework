/*
 * Copyright 2015-2026 the original author or authors.
 *
 * All rights reserved. This program and the accompanying materials are
 * made available under the terms of the Eclipse Public License v2.0 which
 * accompanies this distribution and is available at
 *
 * https://www.eclipse.org/legal/epl-v20.html
 */

package org.junit.jupiter.api;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.apiguardian.api.API;

/**
 * {@code @InvocationCompositions} is a container for one or more
 * {@link InvocationComposition @InvocationComposition} declarations.
 *
 * <p>Note, however, that use of the {@code @InvocationCompositions} container
 * is completely optional since {@code @InvocationComposition} is a
 * {@linkplain java.lang.annotation.Repeatable repeatable} annotation.
 *
 * @since 6.2
 * @see InvocationComposition
 * @see java.lang.annotation.Repeatable
 */
@Target({ ElementType.TYPE, ElementType.METHOD, ElementType.ANNOTATION_TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@API(status = EXPERIMENTAL, since = "6.2")
public @interface InvocationCompositions {

	/**
	 * An array of one or more {@link InvocationComposition @InvocationComposition}
	 * declarations.
	 */
	InvocationComposition[] value();

}
