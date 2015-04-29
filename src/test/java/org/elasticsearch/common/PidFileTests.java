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

package org.elasticsearch.common;

import com.google.common.base.Charsets;
import org.elasticsearch.ElasticsearchIllegalArgumentException;
import org.elasticsearch.test.ElasticsearchTestCase;
import org.junit.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * UnitTest for {@link org.elasticsearch.common.PidFile}
 */
public class PidFileTests extends ElasticsearchTestCase {

    @Test(expected = ElasticsearchIllegalArgumentException.class)
    public void testParentIsFile() throws IOException {
        Path dir = createTempDir();
        Path parent = dir.resolve("foo");
        try(BufferedWriter stream = Files.newBufferedWriter(parent, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            stream.write("foo");
        }

        PidFile.create(parent.resolve("bar.pid"), false);
    }

    @Test
    public void testPidFile() throws IOException {
        Path dir = createTempDir();
        Path parent = dir.resolve("foo");
        if (randomBoolean()) {
            Files.createDirectories(parent);
            if (randomBoolean()) {
                try {
                    Path link = dir.resolve("link_to_real_path");
                    Files.createSymbolicLink(link, parent.getFileName());
                    parent = link;
                } catch (UnsupportedOperationException ex) {
                   // fine - no links on this system
                }

            }
        }
        Path pidFile = parent.resolve("foo.pid");
        long pid = randomLong();
        if (randomBoolean() && Files.exists(parent)) {
            try (BufferedWriter stream = Files.newBufferedWriter(pidFile, Charsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                stream.write("foo");
            }
        }

        final PidFile inst = PidFile.create(pidFile, false, pid);
        assertEquals(pidFile, inst.getPath());
        assertEquals(pid, inst.getPid());
        assertFalse(inst.isDeleteOnExit());
        assertTrue(Files.exists(pidFile));
        assertEquals(pid, Long.parseLong(new String(Files.readAllBytes(pidFile), Charsets.UTF_8)));
    }
}
