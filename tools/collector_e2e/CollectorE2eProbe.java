package com.apm.e2e;

import com.apm.model.ApmEvent;
import com.apm.model.ApmEventKind;
import com.apm.model.ApmPriority;
import com.apm.model.ApmResourceContext;
import com.apm.model.ApmSeverity;
import com.apm.model.SerializationFormat;
import com.apm.uploader.HttpApmUploader;
import com.apm.uploader.HttpEndpointProvider;
import com.apm.uploader.HttpHeaderProvider;
import com.apm.uploader.UploaderLogger;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Executes the real JVM-compatible Android uploader against a live Collector. */
public final class CollectorE2eProbe {
    private CollectorE2eProbe() {}

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected <endpoint>");
        }
        String ingestKey = System.getenv("APM_E2E_INGEST_KEY");
        if (ingestKey == null || !ingestKey.startsWith("apm1_")) {
            throw new IllegalStateException("APM_E2E_INGEST_KEY is missing");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + ingestKey);
        headers.put("X-Apm-App-Id", "com.example.collector.e2e");
        headers.put("X-Apm-Environment", "e2e");
        headers.put("X-Apm-App-Version", "2.4.1");

        HttpApmUploader uploader =
                new HttpApmUploader(
                        arguments[0],
                        (HttpEndpointProvider) defaultEndpoint -> defaultEndpoint,
                        headers,
                        (HttpHeaderProvider) Collections::emptyMap,
                        5_000,
                        5_000,
                        true,
                        SerializationFormat.PROTOBUF_ENVELOPE_V2,
                        new FailFastLogger(),
                        new ApmResourceContext(
                                "com.example.collector.e2e",
                                "2.4.1",
                                "e2e",
                                "anonymous-e2e-installation"),
                        1_048_576);

        List<ApmEvent> events = events();
        requireUploaded(uploader.uploadBatch(events), "initial upload");
        requireUploaded(uploader.uploadBatch(events), "duplicate replay");
        System.out.println("COLLECTOR_E2E_PROBE_PASSED");
    }

    private static List<ApmEvent> events() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("nullValue", null);
        fields.put("stringValue", "hello");
        fields.put("booleanValue", true);
        fields.put("byteValue", (byte) -7);
        fields.put("shortValue", (short) 32_000);
        fields.put("intValue", 42);
        fields.put("longValue", Long.MAX_VALUE);
        fields.put("floatValue", 1.25F);
        fields.put("doubleValue", 2.5D);
        fields.put("floatNaN", Float.NaN);
        fields.put("doublePositiveInfinity", Double.POSITIVE_INFINITY);
        fields.put("charValue", 'A');
        fields.put("bigIntegerValue", new BigInteger("123456789012345678901234567890"));
        fields.put("bigDecimalValue", new BigDecimal("1234567890.0000000001"));

        List<ApmEvent> events = new ArrayList<>();
        events.add(
                event(
                        "collector-e2e-event-1",
                        "typed_scalars",
                        fields,
                        Collections.singletonMap("traceId", "trace-e2e-1")));
        events.add(
                event(
                        "collector-e2e-event-2",
                        "dedup_replay",
                        Collections.singletonMap("attempt", 1),
                        Collections.emptyMap()));
        return events;
    }

    private static ApmEvent event(
            String eventId,
            String name,
            Map<String, Object> fields,
            Map<String, String> extras) {
        return new ApmEvent(
                "collector_e2e",
                name,
                ApmEventKind.METRIC,
                ApmSeverity.INFO,
                ApmPriority.NORMAL,
                1_700_000_000_000L,
                "com.example.collector.e2e",
                "collector-e2e",
                null,
                Boolean.TRUE,
                fields,
                Collections.singletonMap("buildType", "e2e"),
                extras,
                eventId);
    }

    private static void requireUploaded(boolean uploaded, String operation) {
        if (!uploaded) {
            throw new IllegalStateException(operation + " did not receive an exact V2 ACK");
        }
    }

    private static final class FailFastLogger implements UploaderLogger {
        @Override
        public void d(String message) {
            // Debug output is intentionally quiet.
        }

        @Override
        public void w(String message) {
            System.err.println("WARN: " + message);
        }

        @Override
        public void e(String message, Throwable throwable) {
            if (throwable == null) {
                System.err.println("ERROR: " + message);
            } else {
                System.err.println("ERROR: " + message + ": " + throwable);
            }
        }
    }
}
