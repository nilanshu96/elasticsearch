/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */
package org.elasticsearch.client.graph;

import com.carrotsearch.hppc.ObjectIntHashMap;

import org.elasticsearch.xcontent.ParseField;
import org.elasticsearch.xcontent.ConstructingObjectParser;
import org.elasticsearch.xcontent.ToXContent.Params;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.client.graph.Vertex.VertexId;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.xcontent.ConstructingObjectParser.constructorArg;

/**
 * A Connection links exactly two {@link Vertex} objects. The basis of a
 * connection is one or more documents have been found that contain
 * this pair of terms and the strength of the connection is recorded
 * as a weight.
 */
public class Connection {
    private Vertex from;
    private Vertex to;
    private double weight;
    private long docCount;

    public Connection(Vertex from, Vertex to, double weight, long docCount) {
        this.from = from;
        this.to = to;
        this.weight = weight;
        this.docCount = docCount;
    }

    Connection() {
    }

    public ConnectionId getId() {
        return new ConnectionId(from.getId(), to.getId());
    }

    public Vertex getFrom() {
        return from;
    }

    public Vertex getTo() {
        return to;
    }

    /**
     * @return a measure of the relative connectedness between a pair of {@link Vertex} objects
     */
    public double getWeight() {
        return weight;
    }

    /**
     * @return the number of documents in the sampled set that contained this
     * pair of {@link Vertex} objects.
     */
    public long getDocCount() {
        return docCount;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        Connection other = (Connection) obj;
        return docCount == other.docCount &&
               weight == other.weight &&
               Objects.equals(to, other.to) &&
               Objects.equals(from, other.from);
    }

    @Override
    public int hashCode() {
        return Objects.hash(docCount, weight, from, to);
    }


    private static final ParseField SOURCE = new ParseField("source");
    private static final ParseField TARGET = new ParseField("target");
    private static final ParseField WEIGHT = new ParseField("weight");
    private static final ParseField DOC_COUNT = new ParseField("doc_count");


    void toXContent(XContentBuilder builder, Params params, ObjectIntHashMap<Vertex> vertexNumbers) throws IOException {
        builder.field(SOURCE.getPreferredName(), vertexNumbers.get(from));
        builder.field(TARGET.getPreferredName(), vertexNumbers.get(to));
        builder.field(WEIGHT.getPreferredName(), weight);
        builder.field(DOC_COUNT.getPreferredName(), docCount);
    }

    //When deserializing from XContent we need to wait for all vertices to be loaded before
    // Connection objects can be created that reference them. This class provides the interim
    // state for connections.
    static class UnresolvedConnection {
        int fromIndex;
        int toIndex;
        double weight;
        long docCount;
        UnresolvedConnection(int fromIndex, int toIndex, double weight, long docCount) {
            super();
            this.fromIndex = fromIndex;
            this.toIndex = toIndex;
            this.weight = weight;
            this.docCount = docCount;
        }
        public Connection resolve(List<Vertex> vertices) {
            return new Connection(vertices.get(fromIndex), vertices.get(toIndex), weight, docCount);
        }

        private static final ConstructingObjectParser<UnresolvedConnection, Void> PARSER = new ConstructingObjectParser<>(
                "ConnectionParser", true,
                args -> {
                    int source = (Integer) args[0];
                    int target = (Integer) args[1];
                    double weight = (Double) args[2];
                    long docCount = (Long) args[3];
                    return new UnresolvedConnection(source, target, weight, docCount);
                });

        static {
            PARSER.declareInt(constructorArg(), SOURCE);
            PARSER.declareInt(constructorArg(), TARGET);
            PARSER.declareDouble(constructorArg(), WEIGHT);
            PARSER.declareLong(constructorArg(), DOC_COUNT);
        }
        static UnresolvedConnection fromXContent(XContentParser parser) throws IOException {
            return PARSER.apply(parser, null);
        }
    }


    /**
     * An identifier (implements hashcode and equals) that represents a
     * unique key for a {@link Connection}
     */
    public static class ConnectionId {
        private final VertexId source;
        private final VertexId target;

        public ConnectionId(VertexId source, VertexId target) {
            this.source = source;
            this.target = target;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            ConnectionId vertexId = (ConnectionId) o;

            if (source != null ? source.equals(vertexId.source) == false : vertexId.source != null)
                return false;
            if (target != null ? target.equals(vertexId.target) == false : vertexId.target != null)
                return false;

            return true;
        }

        @Override
        public int hashCode() {
            int result = source != null ? source.hashCode() : 0;
            result = 31 * result + (target != null ? target.hashCode() : 0);
            return result;
        }

        public VertexId getSource() {
            return source;
        }

        public VertexId getTarget() {
            return target;
        }

        @Override
        public String toString() {
            return getSource() + "->" + getTarget();
        }
    }
}
