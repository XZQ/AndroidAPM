package com.apm.e2e;

import com.apm.model.ApmEvent;
import com.apm.model.ApmEventKind;
import com.apm.model.ApmNativeFrameIdentity;
import com.apm.model.ApmOccurrenceContext;
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
    /** Prevents construction of the command-line probe. */
    private CollectorE2eProbe() {}

    /** Sends and replays one V2 batch and one occurrence-bound V3 batch. */
    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected <endpoint>");
        }
        String ingestKey = System.getenv("APM_E2E_INGEST_KEY");
        if (ingestKey == null || !ingestKey.startsWith("apm1_")) {
            throw new IllegalStateException("APM_E2E_INGEST_KEY is missing");
        }
        HttpApmUploader v2Uploader =
                uploader(
                        arguments[0],
                        ingestKey,
                        SerializationFormat.PROTOBUF_ENVELOPE_V2,
                        "2.4.1",
                        new ApmResourceContext(
                                "com.example.collector.e2e",
                                "2.4.1",
                                "e2e",
                                "anonymous-e2e-installation"),
                        false);
        List<ApmEvent> v2Events = v2Events();
        requireUploaded(v2Uploader.uploadBatch(v2Events), "initial V2 upload");
        requireUploaded(v2Uploader.uploadBatch(v2Events), "V2 duplicate replay");

        HttpApmUploader v3Uploader =
                uploader(
                        arguments[0],
                        ingestKey,
                        SerializationFormat.PROTOBUF_ENVELOPE_V3,
                        "batch-declared-3.0.0",
                        new ApmResourceContext(
                                "com.example.collector.e2e",
                                "batch-declared-3.0.0",
                                "e2e",
                                "batch-installation-must-not-persist"),
                        true);
        List<ApmEvent> v3Events = v3Events();
        requireUploaded(v3Uploader.uploadBatch(v3Events), "initial V3 upload");
        requireUploaded(v3Uploader.uploadBatch(v3Events), "V3 duplicate replay");
        System.out.println("COLLECTOR_E2E_PROBE_PASSED");
    }

    /** Builds the real HTTP uploader with scoped credentials and optional lower-quality headers. */
    private static HttpApmUploader uploader(
            String endpoint,
            String ingestKey,
            SerializationFormat format,
            String batchAppVersion,
            ApmResourceContext resource,
            boolean includeLowerQualityReleaseHeaders) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + ingestKey);
        headers.put("X-Apm-App-Id", "com.example.collector.e2e");
        headers.put("X-Apm-Environment", "e2e");
        headers.put("X-Apm-App-Version", batchAppVersion);
        if (includeLowerQualityReleaseHeaders) {
            headers.put("X-Apm-App-Build", "request-build-must-not-win");
            headers.put("X-Apm-Version-Code", "999");
            headers.put("X-Apm-Variant", "request-variant-must-not-win");
        }
        return new HttpApmUploader(
                endpoint,
                (HttpEndpointProvider) defaultEndpoint -> defaultEndpoint,
                headers,
                (HttpHeaderProvider) Collections::emptyMap,
                5_000,
                5_000,
                true,
                format,
                new FailFastLogger(),
                resource,
                1_048_576);
    }

    /** Returns the V2 typed-scalar and replay-deduplication fixtures. */
    private static List<ApmEvent> v2Events() {
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

    /** Returns V3 fixtures with complete occurrence identity and optional native frames. */
    private static List<ApmEvent> v3Events() {
        List<ApmNativeFrameIdentity> nativeFrames =
                Collections.singletonList(
                        new ApmNativeFrameIdentity(
                                "arm64-v8a",
                                "0123456789abcdef",
                                "libcollector-e2e.so",
                                4_660L,
                                4_096L));
        ApmOccurrenceContext withNativeFrame =
                new ApmOccurrenceContext(
                        "3.1.4",
                        "314",
                        "build-314",
                        "release",
                        "occurrence-e2e-installation",
                        nativeFrames);
        ApmOccurrenceContext withoutNativeFrame =
                new ApmOccurrenceContext(
                        "3.1.4",
                        "314",
                        "build-314",
                        "release",
                        "occurrence-e2e-installation",
                        Collections.emptyList());

        List<ApmEvent> events = new ArrayList<>();
        events.add(
                event(
                                "collector-e2e-v3-event-1",
                                "occurrence_identity",
                                Collections.singletonMap("durationMs", 42L),
                                Collections.singletonMap("traceId", "trace-e2e-v3-1"))
                        .withOccurrenceContext(withNativeFrame));
        events.add(
                event(
                                "collector-e2e-v3-event-2",
                                "occurrence_replay",
                                Collections.singletonMap("attempt", 1),
                                Collections.emptyMap())
                        .withOccurrenceContext(withoutNativeFrame));
        return events;
    }

    /** Creates one stable event shared by the V2 and V3 scenarios. */
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

    /** Fails the probe unless the uploader received an exact whole-batch acknowledgement. */
    private static void requireUploaded(boolean uploaded, String operation) {
        if (!uploaded) {
            throw new IllegalStateException(operation + " did not receive an exact versioned ACK");
        }
    }

    /** Keeps diagnostics payload-free while preserving warnings and failures. */
    private static final class FailFastLogger implements UploaderLogger {
        /** Ignores debug output for the deterministic probe. */
        @Override
        public void d(String message) {
            // Debug output is intentionally quiet.
        }

        /** Writes bounded uploader warnings to stderr. */
        @Override
        public void w(String message) {
            System.err.println("WARN: " + message);
        }

        /** Writes bounded uploader errors without exposing request credentials or payloads. */
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
