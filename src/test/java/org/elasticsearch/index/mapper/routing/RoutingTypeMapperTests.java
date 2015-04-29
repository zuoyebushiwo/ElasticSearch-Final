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

package org.elasticsearch.index.mapper.routing;

import org.apache.lucene.index.IndexOptions;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.index.mapper.SourceToParse;
import org.elasticsearch.test.ElasticsearchSingleNodeTest;
import org.junit.Test;

import java.util.Map;

import static org.hamcrest.Matchers.*;

/**
 *
 */
public class RoutingTypeMapperTests extends ElasticsearchSingleNodeTest {

    @Test
    public void simpleRoutingMapperTests() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .endObject().endObject().string();
        DocumentMapper docMapper = createIndex("test").mapperService().documentMapperParser().parse(mapping);

        ParsedDocument doc = docMapper.parse(SourceToParse.source(XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "value")
                .endObject()
                .bytes()).type("type").id("1").routing("routing_value"));

        assertThat(doc.rootDoc().get("_routing"), equalTo("routing_value"));
        assertThat(doc.rootDoc().get("field"), equalTo("value"));
    }

    @Test
    public void testFieldTypeSettingsBackcompat() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_routing")
                .field("store", "no")
                .field("index", "no")
                .endObject()
                .endObject().endObject().string();
        Settings indexSettings = ImmutableSettings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.V_1_4_2.id).build();
        DocumentMapper docMapper = createIndex("test", indexSettings).mapperService().documentMapperParser().parse(mapping);
        assertThat(docMapper.routingFieldMapper().fieldType().stored(), equalTo(false));
        assertEquals(IndexOptions.NONE, docMapper.routingFieldMapper().fieldType().indexOptions());
    }

    @Test
    public void testFieldTypeSettingsSerializationBackcompat() throws Exception {
        String enabledMapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("_routing").field("store", "no").field("index", "no").endObject()
                .endObject().endObject().string();
        Settings indexSettings = ImmutableSettings.builder().put(IndexMetaData.SETTING_VERSION_CREATED, Version.V_1_4_2.id).build();
        DocumentMapper enabledMapper = createIndex("test", indexSettings).mapperService().documentMapperParser().parse(enabledMapping);

        XContentBuilder builder = JsonXContent.contentBuilder().startObject();
        enabledMapper.routingFieldMapper().toXContent(builder, ToXContent.EMPTY_PARAMS).endObject();
        builder.close();
        Map<String, Object> serializedMap = JsonXContent.jsonXContent.createParser(builder.bytes()).mapAndClose();
        assertThat(serializedMap, hasKey("_routing"));
        assertThat(serializedMap.get("_routing"), instanceOf(Map.class));
        Map<String, Object> routingConfiguration = (Map<String, Object>) serializedMap.get("_routing");
        assertThat(routingConfiguration, hasKey("store"));
        assertThat(routingConfiguration.get("store").toString(), is("false"));
        assertThat(routingConfiguration, hasKey("index"));
        assertThat(routingConfiguration.get("index").toString(), is("no"));
    }
}
