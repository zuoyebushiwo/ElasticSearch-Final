/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.aggregations;

import com.google.common.collect.ImmutableMap;

import org.apache.lucene.search.Filter;
import org.apache.lucene.search.FilteredQuery;
import org.apache.lucene.search.Query;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.search.SearchParseElement;
import org.elasticsearch.search.SearchPhase;
import org.elasticsearch.search.aggregations.bucket.global.GlobalAggregator;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.internal.SearchContext;
import org.elasticsearch.search.query.QueryPhaseExecutionException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 */
public class AggregationPhase implements SearchPhase {

    private final AggregationParseElement parseElement;

    private final AggregationBinaryParseElement binaryParseElement;

    @Inject
    public AggregationPhase(AggregationParseElement parseElement, AggregationBinaryParseElement binaryParseElement) {
        this.parseElement = parseElement;
        this.binaryParseElement = binaryParseElement;
    }

    @Override
    public Map<String, ? extends SearchParseElement> parseElements() {
        return ImmutableMap.<String, SearchParseElement>builder()
                .put("aggregations", parseElement)
                .put("aggs", parseElement)
                .put("aggregations_binary", binaryParseElement)
                .put("aggregationsBinary", binaryParseElement)
                .put("aggs_binary", binaryParseElement)
                .put("aggsBinary", binaryParseElement)
                .build();
    }

    @Override
    public void preProcess(SearchContext context) {
        if (context.aggregations() != null) {
            AggregationContext aggregationContext = new AggregationContext(context);
            context.aggregations().aggregationContext(aggregationContext);

            List<Aggregator> collectors = new ArrayList<>();
            Aggregator[] aggregators;
            try {
                aggregators = context.aggregations().factories().createTopLevelAggregators(aggregationContext);
                for (int i = 0; i < aggregators.length; i++) {
                    if (aggregators[i] instanceof GlobalAggregator == false) {
                        collectors.add(aggregators[i]);
                    }
                }
                context.aggregations().aggregators(aggregators);
                if (!collectors.isEmpty()) {
                    final BucketCollector collector = BucketCollector.wrap(collectors);
                    collector.preCollection();
                    context.searcher().queryCollectors().put(AggregationPhase.class, collector);
                }
            } catch (IOException e) {
                throw new AggregationInitializationException("Could not initialize aggregators", e);
            }
        }
    }

    @Override
    public void execute(SearchContext context) throws ElasticsearchException {
        if (context.aggregations() == null) {
            context.queryResult().aggregations(null);
            return;
        }

        if (context.queryResult().aggregations() != null) {
            // no need to compute the aggs twice, they should be computed on a per context basis
            return;
        }

        Aggregator[] aggregators = context.aggregations().aggregators();
        List<Aggregator> globals = new ArrayList<>();
        for (int i = 0; i < aggregators.length; i++) {
            if (aggregators[i] instanceof GlobalAggregator) {
                globals.add(aggregators[i]);
            }
        }

        // optimize the global collector based execution
        if (!globals.isEmpty()) {
            BucketCollector globalsCollector = BucketCollector.wrap(globals);
            Query query = Queries.newMatchAllQuery();
            Filter searchFilter = context.searchFilter(context.types());
            if (searchFilter != null) {
                query = new FilteredQuery(query, searchFilter);
            }
            try {
                globalsCollector.preCollection();
                context.searcher().search(query, globalsCollector);
            } catch (Exception e) {
                throw new QueryPhaseExecutionException(context, "Failed to execute global aggregators", e);
            }
        }

        List<InternalAggregation> aggregations = new ArrayList<>(aggregators.length);
        for (Aggregator aggregator : context.aggregations().aggregators()) {
            try {
                aggregator.postCollection();
                aggregations.add(aggregator.buildAggregation(0));
            } catch (IOException e) {
                throw new AggregationExecutionException("Failed to build aggregation [" + aggregator.name() + "]", e);
            }
        }
        context.queryResult().aggregations(new InternalAggregations(aggregations));

        // disable aggregations so that they don't run on next pages in case of scrolling
        context.aggregations(null);
        context.searcher().queryCollectors().remove(AggregationPhase.class);
    }

}
