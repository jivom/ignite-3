/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.rest.deployment;

import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.ignite.internal.rest.api.deployment.DeploymentStatus.DEPLOYED;
import static org.apache.ignite.internal.rest.constants.HttpCode.BAD_REQUEST;
import static org.apache.ignite.internal.rest.constants.HttpCode.CONFLICT;
import static org.apache.ignite.internal.rest.constants.HttpCode.NOT_FOUND;
import static org.apache.ignite.internal.rest.constants.HttpCode.OK;
import static org.apache.ignite.internal.testframework.IgniteTestUtils.testNodeName;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.client.multipart.MultipartBody;
import io.micronaut.http.client.multipart.MultipartBody.Builder;
import jakarta.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.apache.ignite.internal.rest.api.deployment.UnitStatus;
import org.apache.ignite.internal.testframework.IntegrationTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

/**
 * Integration test for REST controller {@link DeploymentManagementController}.
 */
public class DeploymentManagementControllerTest extends IntegrationTestBase {
    private static Path dummyFile;

    private static final long SIZE_IN_BYTES = 1024L;

    @Inject
    @Client(NODE_URL + "/management/v1/deployment")
    HttpClient client;

    @BeforeEach
    public void setup(TestInfo testInfo) throws IOException {
        startNodes(testInfo);
        String metaStorageNodeName = testNodeName(testInfo, 0);
        initializeCluster(metaStorageNodeName);

        dummyFile = WORK_DIR.resolve("dummy.txt");

        if (!Files.exists(dummyFile)) {
            try (SeekableByteChannel channel = Files.newByteChannel(dummyFile, WRITE, CREATE)) {
                channel.position(SIZE_IN_BYTES - 4);

                ByteBuffer buf = ByteBuffer.allocate(4).putInt(2);
                buf.rewind();
                channel.write(buf);
            }
        }
    }

    @AfterEach
    public void cleanup(TestInfo testInfo) throws Exception {
        stopNodes(testInfo);
    }

    @Test
    public void testDeploySuccessful() {
        String id = "testId";
        String version = "1.1.1";
        HttpResponse<Object> response = deploy(id, version);

        assertThat(response.code(), is(OK.code()));

        await().timeout(10, SECONDS).untilAsserted(() -> {
            MutableHttpRequest<Object> get = HttpRequest.GET("units");
            UnitStatus status = client.toBlocking().retrieve(get, UnitStatus.class);

            assertThat(status.id(), is(id));
            assertThat(status.versionToStatus().keySet(), equalTo(Set.of(version)));
            assertThat(status.versionToStatus().get(version), equalTo(DEPLOYED));
        });
    }

    @Test
    public void testDeployFailedWithoutId() {
        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> deploy(null, "1.1.1"));
        assertThat(e.getResponse().code(), is(BAD_REQUEST.code()));
    }

    @Test
    public void testDeployFailedWithoutContent() {
        String id = "unitId";
        String version = "1.1.1";
        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> deploy(id, version, null));
        assertThat(e.getResponse().code(), is(BAD_REQUEST.code()));
    }

    @Test
    public void testDeployFailedWithoutVersion() {
        String id = "testId";

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class, () -> deploy(id));
        assertThat(e.getResponse().code(), is(BAD_REQUEST.code()));

        MutableHttpRequest<Object> get = HttpRequest.GET("units");
        List<UnitStatus> status = client.toBlocking().retrieve(get, Argument.listOf(UnitStatus.class));

        assertThat(status.size(), is(0));
    }

    @Test
    public void testDeployExisted() {
        String id = "testId";
        String version = "1.1.1";
        HttpResponse<Object> response = deploy(id, version);

        assertThat(response.code(), is(OK.code()));

        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> deploy(id, version));
        assertThat(e.getResponse().code(), is(CONFLICT.code()));
    }

    @Test
    public void testDeployUndeploy() {
        String id = "testId";
        String version = "1.1.1";

        HttpResponse<Object> response = deploy(id, version);

        assertThat(response.code(), is(OK.code()));

        response = undeploy(id, version);
        assertThat(response.code(), is(OK.code()));
    }

    @Test
    public void testUndeployFailed() {
        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> undeploy("testId", "1.1.1"));
        assertThat(e.getResponse().code(), is(NOT_FOUND.code()));
    }

    @Test
    public void testVersionEmpty() {
        String id = "nonExisted";

        assertThat(versions(id), is(List.of()));
    }

    @Test
    public void testVersionOrder() {
        String id = "unitId";
        deploy(id, "1.1.1");
        deploy(id, "1.1.2");
        deploy(id, "1.2.1");
        deploy(id, "2.0");
        deploy(id, "1.0.0");
        deploy(id, "1.0.1");

        List<String> versions = versions(id);

        assertThat(versions, contains("1.0.0", "1.0.1", "1.1.1", "1.1.2", "1.2.1", "2.0.0"));
    }

    private HttpResponse<Object> deploy(String id) {
        return deploy(id, null);
    }

    private HttpResponse<Object> deploy(String id, String version) {
        return deploy(id, version, dummyFile.toFile());
    }

    private HttpResponse<Object> deploy(String id, String version, File file) {
        Builder builder = MultipartBody.builder()
                .addPart("unitVersion", version);

        if (id != null) {
            builder.addPart("unitId", id);
        }
        if (file != null) {
            builder.addPart("unitContent", file);
        }

        MutableHttpRequest<MultipartBody> post = HttpRequest.POST("units", builder.build())
                .contentType(MediaType.MULTIPART_FORM_DATA);
        return client.toBlocking().exchange(post);
    }

    private HttpResponse<Object> undeploy(String id, String version) {
        MutableHttpRequest<Object> delete = HttpRequest
                .DELETE("units/" + id + "/" + version)
                .contentType(MediaType.APPLICATION_JSON);

        return client.toBlocking().exchange(delete);
    }

    private List<String> versions(String id) {
        MutableHttpRequest<Object> versions = HttpRequest
                .GET("units/" + id + "/versions")
                .contentType(MediaType.APPLICATION_JSON);

        return client.toBlocking().retrieve(versions, Argument.listOf(String.class));

    }

    private UnitStatus status(String id) {
        MutableHttpRequest<Object> get = HttpRequest.GET("units/" + id + "/status");
        return client.toBlocking().retrieve(get, UnitStatus.class);
    }
}

