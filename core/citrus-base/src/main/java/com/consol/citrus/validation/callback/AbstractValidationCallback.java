/*
 * Copyright 2006-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.consol.citrus.validation.callback;

import java.util.Map;

import com.consol.citrus.spi.ReferenceResolver;
import com.consol.citrus.spi.ReferenceResolverAware;
import com.consol.citrus.context.TestContext;
import com.consol.citrus.message.Message;

/**
 * Validation callback automatically extracts message payload and headers so we work with
 * Java code for validation.
 *
 * @author Christoph Deppisch
 */
public abstract class AbstractValidationCallback<T> implements ValidationCallback, ReferenceResolverAware {

    /** Bean reference resolver injected before validation callback is called */
    protected ReferenceResolver referenceResolver;

    @Override
    public void validate(Message message, TestContext context) {
        validate((T) message.getPayload(), message.getHeaders(), context);
    }

    /**
     * Subclasses do override this method for validation purpose.
     * @param payload the message payload object.
     * @param headers the message headers
     * @param context the current test context
     */
    public abstract void validate(T payload, Map<String, Object> headers, TestContext context);

    @Override
    public void setReferenceResolver(ReferenceResolver referenceResolver) {
        this.referenceResolver = referenceResolver;
    }
}
