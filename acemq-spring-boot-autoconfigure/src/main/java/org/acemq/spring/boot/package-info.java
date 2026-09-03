/*
 * Copyright 2026 AceMQ.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Spring Boot auto-configuration for acemq-java-amqp.
 *
 * <p>Three things live here and nothing else: the properties under {@code acemq.*}, the
 * beans they configure, and the {@link org.acemq.spring.boot.AceListener} annotation with
 * the registry that runs it.
 *
 * <p>There is no {@code AceTemplate}, and there will not be one. The library's publisher is
 * already a small typed object obtained from the connection, and a template wrapping it
 * would be a second API to document, a second one to keep in step with the first, and the
 * place where a starter starts making decisions the library deliberately left to the caller.
 * Inject {@link org.acemq.amqp.core.AceMq} and call {@code publisher(...)}.
 */
package org.acemq.spring.boot;
