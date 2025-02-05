/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.client;

import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.ImmutableOpenMap;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ToXContent;
import org.elasticsearch.xcontent.XContent;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/**
 * Base class for HLRC response parsing tests.
 *
 * This case class facilitates generating server side response test instances and
 * verifies that they are correctly parsed into HLRC response instances.
 *
 * @param <S> The class representing the response on the server side.
 * @param <C> The class representing the response on the client side.
 */
public abstract class AbstractResponseTestCase<S extends ToXContent, C> extends ESTestCase {

    public final void testFromXContent() throws IOException {
        final XContentType xContentType = randomFrom(XContentType.values());
        final S serverTestInstance = createServerTestInstance(xContentType);
        final BytesReference bytes = toShuffledXContent(serverTestInstance, xContentType, getParams(), randomBoolean());

        final XContent xContent = XContentFactory.xContent(xContentType);
        final XContentParser parser = xContent.createParser(
            NamedXContentRegistry.EMPTY,
            LoggingDeprecationHandler.INSTANCE,
            bytes.streamInput());
        final C clientInstance = doParseToClientInstance(parser);
        assertInstances(serverTestInstance, clientInstance);
    }

    /**
     * @param xContentType The xcontent type that will be used to serialize the test instance.
     *                     This is parameter is needed if the test instance contains serialized xcontent as bytes or string.
     *
     * @return The server side test instance to will be serialized as xcontent to be used to parse client side response class.
     */
    protected abstract S createServerTestInstance(XContentType xContentType);

    /**
     * @param parser The xcontent parser
     * @return The client side instance that is parsed from the xcontent generated from the server side test instance.
     */
    protected abstract C doParseToClientInstance(XContentParser parser) throws IOException;

    /**
     * Assert that the server instance and client instance contain the same content.
     * Typically by asserting whether each property of both instances are equal to each other.
     *
     * @param serverTestInstance The server side instance that was created by {@link #createServerTestInstance(XContentType)}
     * @param clientInstance     The client side instance that was created by {@link #doParseToClientInstance(XContentParser)}
     */
    protected abstract void assertInstances(S serverTestInstance, C clientInstance);

    /**
     * @return The params used when generated the xcontent from server side test instance as bytes
     */
    protected ToXContent.Params getParams() {
        return ToXContent.EMPTY_PARAMS;
    }

    protected static <T> void assertMapEquals(ImmutableOpenMap<String, T> expected, Map<String, T> actual) {
        Set<String> expectedKeys = expected.keySet();
        assertEquals(expectedKeys, actual.keySet());
        for (String key : expectedKeys) {
            assertEquals(expected.get(key), actual.get(key));
        }
    }

}
