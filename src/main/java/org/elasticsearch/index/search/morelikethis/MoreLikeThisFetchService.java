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

package org.elasticsearch.index.search.morelikethis;

import org.apache.lucene.index.Fields;
import org.elasticsearch.action.termvectors.*;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.MoreLikeThisQueryBuilder.Item;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 *
 */
public class MoreLikeThisFetchService extends AbstractComponent {

    private final Client client;

    @Inject
    public MoreLikeThisFetchService(Client client, Settings settings) {
        super(settings);
        this.client = client;
    }

    public Fields[] fetch(MultiTermVectorsRequest requests) throws IOException {
        return getFields(fetchResponse(requests), requests);
    }

    public MultiTermVectorsResponse fetchResponse(MultiTermVectorsRequest requests) throws IOException {
        return client.multiTermVectors(requests).actionGet();
    }

    public static Fields[] getFields(MultiTermVectorsResponse responses, MultiTermVectorsRequest requests) throws IOException {
        List<Fields> likeFields = new ArrayList<>();

        Set<Item> items = new HashSet<>();
        for (TermVectorsRequest request : requests) {
            items.add(new Item(request.index(), request.type(), request.id()));
        }

        for (MultiTermVectorsItemResponse response : responses) {
            if (!hasResponseFromRequest(response, items)) {
                continue;
            }
            if (response.isFailed()) {
                continue;
            }
            TermVectorsResponse getResponse = response.getResponse();
            if (!getResponse.isExists()) {
                continue;
            }
            likeFields.add(getResponse.getFields());
        }
        return likeFields.toArray(Fields.EMPTY_ARRAY);
    }

    private static boolean hasResponseFromRequest(MultiTermVectorsItemResponse response, Set<Item> items) {
        return items.contains(new Item(response.getIndex(), response.getType(), response.getId()));
    }
}
