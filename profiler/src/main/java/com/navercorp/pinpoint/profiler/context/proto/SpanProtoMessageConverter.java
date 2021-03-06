/*
 * Copyright 2019 NAVER Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.navercorp.pinpoint.profiler.context.proto;

import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageV3;
import com.navercorp.pinpoint.bootstrap.context.TraceId;
import com.navercorp.pinpoint.common.annotations.VisibleForTesting;
import com.navercorp.pinpoint.common.util.Assert;
import com.navercorp.pinpoint.common.util.CollectionUtils;
import com.navercorp.pinpoint.common.util.IntStringValue;
import com.navercorp.pinpoint.gpc.trace.PAcceptEvent;
import com.navercorp.pinpoint.gpc.trace.PAnnotation;
import com.navercorp.pinpoint.gpc.trace.PAnnotationValue;
import com.navercorp.pinpoint.gpc.trace.PIntStringValue;
import com.navercorp.pinpoint.gpc.trace.PLocalAsyncId;
import com.navercorp.pinpoint.gpc.trace.PMessageEvent;
import com.navercorp.pinpoint.gpc.trace.PNextEvent;
import com.navercorp.pinpoint.gpc.trace.PParentInfo;
import com.navercorp.pinpoint.gpc.trace.PSpan;
import com.navercorp.pinpoint.gpc.trace.PSpanChunk;
import com.navercorp.pinpoint.gpc.trace.PSpanEvent;
import com.navercorp.pinpoint.profiler.context.Annotation;
import com.navercorp.pinpoint.profiler.context.AsyncId;
import com.navercorp.pinpoint.profiler.context.AsyncSpanChunk;
import com.navercorp.pinpoint.profiler.context.LocalAsyncId;
import com.navercorp.pinpoint.profiler.context.Span;
import com.navercorp.pinpoint.profiler.context.SpanChunk;
import com.navercorp.pinpoint.profiler.context.SpanEvent;
import com.navercorp.pinpoint.profiler.context.compress.SpanProcessor;
import com.navercorp.pinpoint.profiler.context.id.Shared;
import com.navercorp.pinpoint.profiler.context.id.TraceRoot;
import com.navercorp.pinpoint.profiler.context.id.TransactionIdEncoder;
import com.navercorp.pinpoint.profiler.context.thrift.MessageConverter;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Not thread safe
 * @author Woonduk Kang(emeroad)
 */
public class SpanProtoMessageConverter implements MessageConverter<GeneratedMessageV3> {

    private final short applicationServiceType;
    private final TransactionIdEncoder transactionIdEncoder;

    private final SpanProcessor<PSpan.Builder, PSpanChunk.Builder> spanProcessor;
    // WARNING not thread safe
    private final AnnotationValueProtoMapper annotationValueProtoMapper = new AnnotationValueProtoMapper();

    private final PSpanEvent.Builder pSpanEventBuilder = PSpanEvent.newBuilder();

    private final PAnnotation.Builder pAnnotationBuilder = PAnnotation.newBuilder();

    public SpanProtoMessageConverter(short applicationServiceType,
                                      TransactionIdEncoder transactionIdEncoder,
                                     SpanProcessor<PSpan.Builder, PSpanChunk.Builder> spanProcessor) {

        this.applicationServiceType = applicationServiceType;
        this.transactionIdEncoder = Assert.requireNonNull(transactionIdEncoder, "transactionIdEncoder must not be null");
        this.spanProcessor = Assert.requireNonNull(spanProcessor, "spanProcessor must not be null");

    }

    @Override
    public GeneratedMessageV3 toMessage(Object message) {
        if (message instanceof SpanChunk) {
            final SpanChunk spanChunk = (SpanChunk) message;
            final PSpanChunk pSpanChunk = buildPSpanChunk(spanChunk);
            return pSpanChunk;
        }
        if (message instanceof Span) {
            final Span span = (Span) message;
            return buildPSpan(span);
        }
        return null;
    }


    @VisibleForTesting
    PSpan buildPSpan(Span span) {
        final PSpan.Builder pSpan = PSpan.newBuilder();

//        tSpan.setVersion(span.getVersion());

//        pSpanBuilder.applicationName(applicationName);
//        pSpanBuilder.setAgentId(agentId);
//        pSpanBuilder.setAgentStartTime(agentStartTime);
        pSpan.setApplicationServiceType(applicationServiceType);

        final TraceRoot traceRoot = span.getTraceRoot();
        final TraceId traceId = traceRoot.getTraceId();
        final ByteBuffer transactionId = transactionIdEncoder.encodeTransactionId(traceId);
        pSpan.setTransactionId(ByteString.copyFrom(transactionId));
        pSpan.setSpanId(traceId.getSpanId());
        pSpan.setParentSpanId(traceId.getParentSpanId());

        pSpan.setStartTime(span.getStartTime());
        pSpan.setElapsed(span.getElapsedTime());
        pSpan.setServiceType(span.getServiceType());

        PAcceptEvent pAcceptEvent = newAcceptEvent(span);

        pSpan.setAcceptEvent(pAcceptEvent);

        pSpan.setFlag(traceId.getFlags());
        Shared shared = span.getTraceRoot().getShared();
        pSpan.setErr(shared.getErrorCode());

        pSpan.setApiId(span.getApiId());

        final IntStringValue exceptionInfo = span.getExceptionInfo();
        if (exceptionInfo != null) {
            PIntStringValue pIntStringValue = buildPIntStringValue(exceptionInfo);
            pSpan.setExceptionInfo(pIntStringValue);
        }

        pSpan.setLoggingTransactionInfo(shared.getLoggingInfo());

        final List<Annotation> annotations = span.getAnnotations();
        if (CollectionUtils.hasLength(annotations)) {
            final List<PAnnotation> tAnnotations = buildPAnnotation(annotations);
            pSpan.addAllAnnotations(tAnnotations);
        }
        this.spanProcessor.preProcess(span, pSpan);
        final List<SpanEvent> spanEventList = span.getSpanEventList();
        if (CollectionUtils.hasLength(spanEventList)) {
            final List<PSpanEvent> pSpanEvents = buildPSpanEventList(spanEventList);
            pSpan.addAllSpanEventList(pSpanEvents);
        }
        this.spanProcessor.postProcess(span, pSpan);
        return pSpan.build();

    }

    private PAcceptEvent newAcceptEvent(Span span) {
        PAcceptEvent.Builder builder = PAcceptEvent.newBuilder();

        builder.setRemoteAddr(span.getRemoteAddr());
        final Shared shared = span.getTraceRoot().getShared();
        builder.setRpc(shared.getRpcName());
        builder.setEndPoint(shared.getEndPoint());

        PParentInfo pParentInfo = newParentInfo(span);
        if (pParentInfo != null) {
            builder.setParentInfo(pParentInfo);
        }
        return builder.build();
    }

    private PParentInfo newParentInfo(Span span) {
        final String parentApplicationName = span.getParentApplicationName();
        if (parentApplicationName == null) {
            return null;
        }
        PParentInfo.Builder builder = PParentInfo.newBuilder();
        builder.setParentApplicationName(parentApplicationName);
        builder.setParentApplicationType(span.getParentApplicationType());
        builder.setAcceptorHost(span.getAcceptorHost());
        return builder.build();
    }

    private List<PSpanEvent> buildPSpanEventList(List<SpanEvent> spanEventList) {
        final int eventSize = spanEventList.size();
        final List<PSpanEvent> pSpanEventList = new ArrayList<PSpanEvent>(eventSize);
        for (SpanEvent spanEvent : spanEventList) {
            final PSpanEvent.Builder pSpanEvent = buildPSpanEvent(spanEvent);
            pSpanEventList.add(pSpanEvent.build());
        }
        return pSpanEventList;
    }

    @VisibleForTesting
    PSpanChunk buildPSpanChunk(SpanChunk spanChunk) {
        final PSpanChunk.Builder pSpanChunk = PSpanChunk.newBuilder();

//        tSpanChunk.setApplicationName(applicationName);
//        tSpanChunk.setAgentId(agentId);
//        tSpanChunk.setAgentStartTime(agentStartTime);
        pSpanChunk.setApplicationServiceType(applicationServiceType);

        final TraceRoot traceRoot = spanChunk.getTraceRoot();
        final TraceId traceId = traceRoot.getTraceId();
        final ByteBuffer transactionId = transactionIdEncoder.encodeTransactionId(traceId);
        pSpanChunk.setTransactionId(ByteString.copyFrom(transactionId));

        pSpanChunk.setSpanId(traceId.getSpanId());

        final Shared shared = traceRoot.getShared();
        pSpanChunk.setEndPoint(shared.getEndPoint());

        if (spanChunk instanceof AsyncSpanChunk) {
            final AsyncSpanChunk asyncSpanChunk = (AsyncSpanChunk) spanChunk;
            final LocalAsyncId localAsyncId = asyncSpanChunk.getLocalAsyncId();
            final PLocalAsyncId.Builder pLocalAsyncId = PLocalAsyncId.newBuilder();
            pLocalAsyncId.setAsyncId(localAsyncId.getAsyncId());
            pLocalAsyncId.setSequence(localAsyncId.getSequence());
            pSpanChunk.setLocalAsyncId(pLocalAsyncId.build());
        }

        this.spanProcessor.preProcess(spanChunk, pSpanChunk);
        final List<SpanEvent> spanEventList = spanChunk.getSpanEventList();
        if (CollectionUtils.hasLength(spanEventList)) {
            final List<PSpanEvent> pSpanEvents = buildPSpanEventList(spanEventList);
            pSpanChunk.addAllSpanEventList(pSpanEvents);
        }
        this.spanProcessor.postProcess(spanChunk, pSpanChunk);

        return pSpanChunk.build();
    }

    @VisibleForTesting
    PSpanEvent.Builder buildPSpanEvent(SpanEvent spanEvent) {
        final PSpanEvent.Builder pSpanEvent = getSpanEventBuilder();

//        if (spanEvent.getStartElapsed() != 0) {
//          tSpanEvent.setStartElapsed(spanEvent.getStartElapsed());
//        }
//        tSpanEvent.setStartElapsed(spanEvent.getStartElapsed());
        if (spanEvent.getElapsedTime() != 0) {
            pSpanEvent.setEndElapsed(spanEvent.getElapsedTime());
        }
        pSpanEvent.setSequence(spanEvent.getSequence());
//        tSpanEvent.setRpc(spanEvent.getRpc());
        pSpanEvent.setServiceType(spanEvent.getServiceType());

        //        tSpanEvent.setAnnotations();
        if (spanEvent.getDepth() != -1) {
            pSpanEvent.setDepth(spanEvent.getDepth());
        }

        pSpanEvent.setApiId(spanEvent.getApiId());

        final IntStringValue exceptionInfo = spanEvent.getExceptionInfo();
        if (exceptionInfo != null) {
            PIntStringValue pIntStringValue = buildPIntStringValue(exceptionInfo);
            pSpanEvent.setExceptionInfo(pIntStringValue);
        }

        buildNextEvent(spanEvent, pSpanEvent);

        final List<Annotation> annotations = spanEvent.getAnnotations();
        if (CollectionUtils.hasLength(annotations)) {
            final List<PAnnotation> pAnnotations = buildPAnnotation(annotations);
            pSpanEvent.addAllAnnotations(pAnnotations);
        }

        return pSpanEvent;
    }

    private PNextEvent buildNextEvent(SpanEvent spanEvent, PSpanEvent.Builder pSpanEvent) {

        final AsyncId asyncIdObject = spanEvent.getAsyncIdObject();
        if (asyncIdObject != null) {
            PNextEvent.Builder nextEvent = PNextEvent.newBuilder();
            nextEvent.setAsyncEvent(asyncIdObject.getAsyncId());
            return nextEvent.build();
        }


        PMessageEvent.Builder messageEvent = PMessageEvent.newBuilder();
        messageEvent.setEndPoint(spanEvent.getEndPoint());
        if (spanEvent.getNextSpanId() != -1) {
            messageEvent.setNextSpanId(spanEvent.getNextSpanId());
        }
        messageEvent.setDestinationId(spanEvent.getDestinationId());

        PNextEvent.Builder nextEvent = PNextEvent.newBuilder();
        nextEvent.setMessageEvent(messageEvent.build());
        return nextEvent.build();
    }

    private PIntStringValue buildPIntStringValue(IntStringValue exceptionInfo) {
        PIntStringValue.Builder builder = PIntStringValue.newBuilder();
        builder.setIntValue(exceptionInfo.getIntValue());
        final String stringValue = exceptionInfo.getStringValue();
        if (stringValue != null) {
            builder.setStringValue(stringValue);
        }
        return builder.build();
    }

    @VisibleForTesting
    List<PAnnotation> buildPAnnotation(List<Annotation> annotations) {
        final List<PAnnotation> tAnnotationList = new ArrayList<PAnnotation>(annotations.size());
        for (Annotation annotation : annotations) {
            final PAnnotation.Builder builder = getAnnotationBuilder();
            builder.setKey(annotation.getAnnotationKey());
            final PAnnotationValue pAnnotationValue = annotationValueProtoMapper.buildPAnnotationValue(annotation.getValue());
            if (pAnnotationValue != null) {
                builder.setValue(pAnnotationValue);
            }
            PAnnotation pAnnotation = builder.build();
            tAnnotationList.add(pAnnotation);
        }
        return tAnnotationList;
    }

    private PAnnotation.Builder getAnnotationBuilder() {
        this.pAnnotationBuilder.clear();
        return pAnnotationBuilder;
    }

    private PSpanEvent.Builder getSpanEventBuilder() {
        pSpanEventBuilder.clear();
        return pSpanEventBuilder;
    }

    @Override
    public String toString() {
        return "SpanThriftMessageConverter{" +
                ", applicationServiceType=" + applicationServiceType +
                '}';
    }
}
