/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker.rest.api.v2;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.pulsar.common.policies.data.ErrorData;
import org.apache.pulsar.common.util.FutureUtil;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.functions.proto.Function.*;
import org.apache.pulsar.functions.runtime.RuntimeFactory;
import org.apache.pulsar.functions.source.TopicSchema;
import org.apache.pulsar.functions.utils.SourceConfig;
import org.apache.pulsar.functions.utils.SourceConfigUtils;
import org.apache.pulsar.functions.worker.*;
import org.apache.pulsar.functions.worker.request.RequestResult;
import org.apache.pulsar.functions.worker.rest.api.FunctionsImpl;
import org.apache.pulsar.io.core.Source;
import org.apache.pulsar.io.core.SourceContext;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.testng.Assert;
import org.testng.IObjectFactory;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.ObjectFactory;
import org.testng.annotations.Test;

import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import java.io.*;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.doNothing;
import static org.powermock.api.mockito.PowerMockito.doReturn;
import static org.powermock.api.mockito.PowerMockito.doThrow;
import static org.powermock.api.mockito.PowerMockito.*;
import static org.testng.Assert.assertEquals;

/**
 * Unit test of {@link SourceApiV2Resource}.
 */
@PrepareForTest({Utils.class,SourceConfigUtils.class})
@PowerMockIgnore({ "javax.management.*", "javax.ws.*", "org.apache.logging.log4j.*" })
@Slf4j
public class SourceApiV2ResourceTest {

    @ObjectFactory
    public IObjectFactory getObjectFactory() {
        return new org.powermock.modules.testng.PowerMockObjectFactory();
    }

    private static final class TestSource implements Source<String> {

        @Override public void open(final Map<String, Object> config, SourceContext sourceContext) {
        }

        @Override public Record<String> read() { return null; }

        @Override public void close() { }
    }

    private static final String tenant = "test-tenant";
    private static final String namespace = "test-namespace";
    private static final String source = "test-source";
    private static final String outputTopic = "test-output-topic";
    private static final String outputSerdeClassName = TopicSchema.DEFAULT_SERDE;
    private static final String className = TestSource.class.getName();
    private static final String serde = TopicSchema.DEFAULT_SERDE;
    private static final int parallelism = 1;

    private WorkerService mockedWorkerService;
    private FunctionMetaDataManager mockedManager;
    private FunctionRuntimeManager mockedFunctionRunTimeManager;
    private RuntimeFactory mockedRuntimeFactory;
    private Namespace mockedNamespace;
    private FunctionsImpl resource;
    private InputStream mockedInputStream;
    private FormDataContentDisposition mockedFormData;

    @BeforeMethod
    public void setup() throws Exception {
        this.mockedManager = mock(FunctionMetaDataManager.class);
        this.mockedFunctionRunTimeManager = mock(FunctionRuntimeManager.class);
        this.mockedRuntimeFactory = mock(RuntimeFactory.class);
        this.mockedInputStream = mock(InputStream.class);
        this.mockedNamespace = mock(Namespace.class);
        this.mockedFormData = mock(FormDataContentDisposition.class);
        when(mockedFormData.getFileName()).thenReturn("test");

        this.mockedWorkerService = mock(WorkerService.class);
        when(mockedWorkerService.getFunctionMetaDataManager()).thenReturn(mockedManager);
        when(mockedWorkerService.getFunctionRuntimeManager()).thenReturn(mockedFunctionRunTimeManager);
        when(mockedFunctionRunTimeManager.getRuntimeFactory()).thenReturn(mockedRuntimeFactory);
        when(mockedWorkerService.getDlogNamespace()).thenReturn(mockedNamespace);
        when(mockedWorkerService.isInitialized()).thenReturn(true);

        // worker config
        WorkerConfig workerConfig = new WorkerConfig()
            .setWorkerId("test")
            .setWorkerPort(8080)
            .setDownloadDirectory("/tmp/pulsar/functions")
            .setFunctionMetadataTopicName("pulsar/functions")
            .setNumFunctionPackageReplicas(3)
            .setPulsarServiceUrl("pulsar://localhost:6650/");
        when(mockedWorkerService.getWorkerConfig()).thenReturn(workerConfig);

        this.resource = spy(new FunctionsImpl(() -> mockedWorkerService));
        doReturn(null).when(resource).extractNarClassLoader(anyString(), anyString(), anyObject(), anyBoolean());
        mockStatic(SourceConfigUtils.class);
        when(SourceConfigUtils.convert(anyObject(), anyObject())).thenReturn(FunctionDetails.newBuilder().build());
    }

    //
    // Register Functions
    //

    @Test
    public void testRegisterSourceMissingTenant() throws IOException {
        testRegisterSourceMissingArguments(
            null,
            namespace,
                source,
            mockedInputStream,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Tenant is not provided");
    }

    @Test
    public void testRegisterSourceMissingNamespace() throws IOException {
        testRegisterSourceMissingArguments(
            tenant,
            null,
                source,
            mockedInputStream,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Namespace is not provided");
    }

    @Test
    public void testRegisterSourceMissingFunctionName() throws IOException {
        testRegisterSourceMissingArguments(
            tenant,
            namespace,
            null,
            mockedInputStream,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Function Name is not provided");
    }

    @Test
    public void testRegisterSourceMissingPackage() throws IOException {
        testRegisterSourceMissingArguments(
            tenant,
            namespace,
                source,
            null,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    @Test
    public void testRegisterSourceMissingPackageDetails() throws IOException {
        testRegisterSourceMissingArguments(
            tenant,
            namespace,
                source,
            mockedInputStream,
            null,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    private void testRegisterSourceMissingArguments(
            String tenant,
            String namespace,
            String function,
            InputStream inputStream,
            FormDataContentDisposition details,
            String outputTopic,
            String outputSerdeClassName,
            String className,
            Integer parallelism,
            String errorExpected) throws IOException {
        SourceConfig sourceConfig = new SourceConfig();
        if (tenant != null) {
            sourceConfig.setTenant(tenant);
        }
        if (namespace != null) {
            sourceConfig.setNamespace(namespace);
        }
        if (function != null) {
            sourceConfig.setName(function);
        }
        if (outputTopic != null) {
            sourceConfig.setTopicName(outputTopic);
        }
        if (outputSerdeClassName != null) {
            sourceConfig.setSerdeClassName(outputSerdeClassName);
        }
        if (className != null) {
            sourceConfig.setClassName(className);
        }
        if (parallelism != null) {
            sourceConfig.setParallelism(parallelism);
        }

        Response response = resource.registerFunction(
                tenant,
                namespace,
                function,
                inputStream,
                details,
                null,
                null,
                null,
                new Gson().toJson(sourceConfig),
                null,
                null);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        Assert.assertEquals(((ErrorData) response.getEntity()).reason, new ErrorData(errorExpected).reason);
    }

    private Response registerDefaultSource() {
        SourceConfig sourceConfig = new SourceConfig();
        sourceConfig.setTenant(tenant);
        sourceConfig.setNamespace(namespace);
        sourceConfig.setName(source);
        sourceConfig.setClassName(className);
        sourceConfig.setParallelism(parallelism);
        sourceConfig.setTopicName(outputTopic);
        sourceConfig.setSerdeClassName(outputSerdeClassName);
        return resource.registerFunction(
            tenant,
            namespace,
                source,
            mockedInputStream,
            mockedFormData,
            null,
            null,
            null,
            new Gson().toJson(sourceConfig),
                null,
                null);
    }

    @Test
    public void testRegisterExistedSource() throws IOException {
        Configurator.setRootLevel(Level.DEBUG);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        Response response = registerDefaultSource();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + source + " already exists").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testRegisterSourceUploadFailure() throws Exception {
        mockStatic(Utils.class);
        doThrow(new IOException("upload failure")).when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        Response response = registerDefaultSource();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("upload failure").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testRegisterSourceSuccess() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        RequestResult rr = new RequestResult()
            .setSuccess(true)
            .setMessage("source registered");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = registerDefaultSource();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testRegisterSourceFailure() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        RequestResult rr = new RequestResult()
            .setSuccess(false)
            .setMessage("source failed to register");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = registerDefaultSource();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(rr.getMessage()).reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testRegisterSourceInterrupted() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        CompletableFuture<RequestResult> requestResult = FutureUtil.failedFuture(
            new IOException("Function registeration interrupted"));
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = registerDefaultSource();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function registeration interrupted").reason, ((ErrorData) response.getEntity()).reason);
    }

    //
    // Update Functions
    //

    @Test
    public void testUpdateSourceMissingTenant() throws IOException {
        testUpdateSourceMissingArguments(
            null,
            namespace,
                source,
            mockedInputStream,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Tenant is not provided");
    }

    @Test
    public void testUpdateSourceMissingNamespace() throws IOException {
        testUpdateSourceMissingArguments(
            tenant,
            null,
                source,
            mockedInputStream,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Namespace is not provided");
    }

    @Test
    public void testUpdateSourceMissingFunctionName() throws IOException {
        testUpdateSourceMissingArguments(
            tenant,
            namespace,
            null,
            mockedInputStream,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Function Name is not provided");
    }

    @Test
    public void testUpdateSourceMissingPackage() throws IOException {
        testUpdateSourceMissingArguments(
            tenant,
            namespace,
                source,
            null,
            mockedFormData,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    @Test
    public void testUpdateSourceMissingPackageDetails() throws IOException {
        testUpdateSourceMissingArguments(
            tenant,
            namespace,
                source,
            mockedInputStream,
            null,
            outputTopic,
                outputSerdeClassName,
            className,
            parallelism,
                "Function Package is not provided");
    }

    private void testUpdateSourceMissingArguments(
            String tenant,
            String namespace,
            String function,
            InputStream inputStream,
            FormDataContentDisposition details,
            String outputTopic,
            String outputSerdeClassName,
            String className,
            Integer parallelism,
            String expectedError) throws IOException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(function))).thenReturn(true);

        SourceConfig sourceConfig = new SourceConfig();
        if (tenant != null) {
            sourceConfig.setTenant(tenant);
        }
        if (namespace != null) {
            sourceConfig.setNamespace(namespace);
        }
        if (function != null) {
            sourceConfig.setName(function);
        }
        if (outputTopic != null) {
            sourceConfig.setTopicName(outputTopic);
        }
        if (outputSerdeClassName != null) {
            sourceConfig.setSerdeClassName(outputSerdeClassName);
        }
        if (className != null) {
            sourceConfig.setClassName(className);
        }
        if (parallelism != null) {
            sourceConfig.setParallelism(parallelism);
        }

        Response response = resource.updateFunction(
            tenant,
            namespace,
            function,
            inputStream,
            details,
            null,
            null,
            null,
            new Gson().toJson(sourceConfig),
                null,
                null);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        Assert.assertEquals(((ErrorData) response.getEntity()).reason, new ErrorData(expectedError).reason);
    }

    private Response updateDefaultSource() throws IOException {
        SourceConfig sourceConfig = new SourceConfig();
        sourceConfig.setTenant(tenant);
        sourceConfig.setNamespace(namespace);
        sourceConfig.setName(source);
        sourceConfig.setClassName(className);
        sourceConfig.setParallelism(parallelism);
        sourceConfig.setTopicName(outputTopic);
        sourceConfig.setSerdeClassName(outputSerdeClassName);

        return resource.updateFunction(
            tenant,
            namespace,
                source,
            mockedInputStream,
            mockedFormData,
            null,
            null,
            null,
            new Gson().toJson(sourceConfig),
                null,
                null);
    }

    @Test
    public void testUpdateNotExistedSource() throws IOException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        Response response = updateDefaultSource();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + source + " doesn't exist").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testUpdateSourceUploadFailure() throws Exception {
        mockStatic(Utils.class);
        doThrow(new IOException("upload failure")).when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        Response response = updateDefaultSource();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("upload failure").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testUpdateSourceSuccess() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(true)
            .setMessage("source registered");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = updateDefaultSource();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testUpdateSourceWithUrl() throws IOException {
        Configurator.setRootLevel(Level.DEBUG);

        String fileLocation = FutureUtil.class.getProtectionDomain().getCodeSource().getLocation().getPath();
        String filePackageUrl = "file://" + fileLocation;

        SourceConfig sourceConfig = new SourceConfig();
        sourceConfig.setTopicName(outputTopic);
        sourceConfig.setSerdeClassName(outputSerdeClassName);
        sourceConfig.setTenant(tenant);
        sourceConfig.setNamespace(namespace);
        sourceConfig.setName(source);
        sourceConfig.setClassName(className);
        sourceConfig.setParallelism(parallelism);

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);
        RequestResult rr = new RequestResult()
                .setSuccess(true)
                .setMessage("source registered");
            CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
            when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = resource.updateFunction(
            tenant,
            namespace,
                source,
            null,
            null,
            filePackageUrl,
            null,
            null,
            new Gson().toJson(sourceConfig),
                null,
                null);

        assertEquals(Status.OK.getStatusCode(), response.getStatus());
    }

    @Test
    public void testUpdateSourceFailure() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(false)
            .setMessage("source failed to register");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = updateDefaultSource();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(rr.getMessage()).reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testUpdateSourceInterrupted() throws Exception {
        mockStatic(Utils.class);
        doNothing().when(Utils.class);
        Utils.uploadToBookeeper(
            any(Namespace.class),
            any(InputStream.class),
            anyString());

        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        CompletableFuture<RequestResult> requestResult = FutureUtil.failedFuture(
            new IOException("Function registeration interrupted"));
        when(mockedManager.updateFunction(any(FunctionMetaData.class))).thenReturn(requestResult);

        Response response = updateDefaultSource();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function registeration interrupted").reason, ((ErrorData) response.getEntity()).reason);
    }

    //
    // deregister source
    //

    @Test
    public void testDeregisterSourceMissingTenant() throws Exception {
        testDeregisterSourceMissingArguments(
            null,
            namespace,
                source,
            "Tenant");
    }

    @Test
    public void testDeregisterSourceMissingNamespace() throws Exception {
        testDeregisterSourceMissingArguments(
            tenant,
            null,
                source,
            "Namespace");
    }

    @Test
    public void testDeregisterSourceMissingFunctionName() throws Exception {
        testDeregisterSourceMissingArguments(
            tenant,
            namespace,
            null,
            "Function Name");
    }

    private void testDeregisterSourceMissingArguments(
        String tenant,
        String namespace,
        String function,
        String missingFieldName
    ) {
        Response response = resource.deregisterFunction(
            tenant,
            namespace,
            function,
            null);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(missingFieldName + " is not provided").reason, ((ErrorData) response.getEntity()).reason);
    }

    private Response deregisterDefaultSource() {
        return resource.deregisterFunction(
            tenant,
            namespace,
                source,
            null);
    }

    @Test
    public void testDeregisterNotExistedSource() {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        Response response = deregisterDefaultSource();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + source + " doesn't exist").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testDeregisterSourceSuccess() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(true)
            .setMessage("source deregistered");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.deregisterFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(requestResult);

        Response response = deregisterDefaultSource();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals(rr.toJson(), response.getEntity());
    }

    @Test
    public void testDeregisterSourceFailure() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        RequestResult rr = new RequestResult()
            .setSuccess(false)
            .setMessage("source failed to deregister");
        CompletableFuture<RequestResult> requestResult = CompletableFuture.completedFuture(rr);
        when(mockedManager.deregisterFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(requestResult);

        Response response = deregisterDefaultSource();
        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(rr.getMessage()).reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testDeregisterSourceInterrupted() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        CompletableFuture<RequestResult> requestResult = FutureUtil.failedFuture(
            new IOException("Function deregisteration interrupted"));
        when(mockedManager.deregisterFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(requestResult);

        Response response = deregisterDefaultSource();
        assertEquals(Status.INTERNAL_SERVER_ERROR.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function deregisteration interrupted").reason, ((ErrorData) response.getEntity()).reason);
    }

    // Source Info doesn't exist. Maybe one day they might be added
    //
    // Get Function Info
    //

    /*
    @Test
    public void testGetFunctionMissingTenant() throws Exception {
        testGetFunctionMissingArguments(
            null,
            namespace,
                source,
            "Tenant");
    }

    @Test
    public void testGetFunctionMissingNamespace() throws Exception {
        testGetFunctionMissingArguments(
            tenant,
            null,
                source,
            "Namespace");
    }

    @Test
    public void testGetFunctionMissingFunctionName() throws Exception {
        testGetFunctionMissingArguments(
            tenant,
            namespace,
            null,
            "Function Name");
    }

    private void testGetFunctionMissingArguments(
        String tenant,
        String namespace,
        String function,
        String missingFieldName
    ) throws IOException {
        Response response = resource.getFunctionInfo(
            tenant,
            namespace,
            function);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(missingFieldName + " is not provided").reason, ((ErrorData) response.getEntity()).reason);
    }

    private Response getDefaultFunctionInfo() throws IOException {
        return resource.getFunctionInfo(
            tenant,
            namespace,
                source);
    }

    @Test
    public void testGetNotExistedFunction() throws IOException {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(false);

        Response response = getDefaultFunctionInfo();
        assertEquals(Status.NOT_FOUND.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData("Function " + source + " doesn't exist").reason, ((ErrorData) response.getEntity()).reason);
    }

    @Test
    public void testGetFunctionSuccess() throws Exception {
        when(mockedManager.containsFunction(eq(tenant), eq(namespace), eq(source))).thenReturn(true);

        SinkSpec sinkSpec = SinkSpec.newBuilder()
                .setTopic(outputTopic)
                .setSerDeClassName(outputSerdeClassName).build();
        FunctionDetails functionDetails = FunctionDetails.newBuilder()
                .setClassName(className)
                .setSink(sinkSpec)
                .setName(source)
                .setNamespace(namespace)
                .setProcessingGuarantees(ProcessingGuarantees.ATMOST_ONCE)
                .setTenant(tenant)
                .setParallelism(parallelism)
                .setSource(SourceSpec.newBuilder().setSubscriptionType(subscriptionType)
                        .putAllTopicsToSerDeClassName(topicsToSerDeClassName)).build();
        FunctionMetaData metaData = FunctionMetaData.newBuilder()
            .setCreateTime(System.currentTimeMillis())
            .setFunctionDetails(functionDetails)
            .setPackageLocation(PackageLocationMetaData.newBuilder().setPackagePath("/path/to/package"))
            .setVersion(1234)
            .build();
        when(mockedManager.getFunctionMetaData(eq(tenant), eq(namespace), eq(source))).thenReturn(metaData);

        Response response = getDefaultFunctionInfo();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals(
            org.apache.pulsar.functions.utils.Utils.printJson(functionDetails),
            response.getEntity());
    }

    //
    // List Functions
    //

    @Test
    public void testListFunctionsMissingTenant() throws Exception {
        testListFunctionsMissingArguments(
            null,
            namespace,
            "Tenant");
    }

    @Test
    public void testListFunctionsMissingNamespace() throws Exception {
        testListFunctionsMissingArguments(
            tenant,
            null,
            "Namespace");
    }

    private void testListFunctionsMissingArguments(
        String tenant,
        String namespace,
        String missingFieldName
    ) {
        Response response = resource.listFunctions(
            tenant,
            namespace);

        assertEquals(Status.BAD_REQUEST.getStatusCode(), response.getStatus());
        assertEquals(new ErrorData(missingFieldName + " is not provided").reason, ((ErrorData) response.getEntity()).reason);
    }

    private Response listDefaultFunctions() {
        return resource.listFunctions(
            tenant,
            namespace);
    }

    @Test
    public void testListFunctionsSuccess() throws Exception {
        List<String> functions = Lists.newArrayList("test-1", "test-2");
        when(mockedManager.listFunctions(eq(tenant), eq(namespace))).thenReturn(functions);

        Response response = listDefaultFunctions();
        assertEquals(Status.OK.getStatusCode(), response.getStatus());
        assertEquals(new Gson().toJson(functions), response.getEntity());
    }
    */
}
