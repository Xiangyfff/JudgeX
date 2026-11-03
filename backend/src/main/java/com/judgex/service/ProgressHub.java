package com.judgex.service;

import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Fan-out for live judge progress. Per-submission emitters plus one global feed
 * that drives the dashboard. Emitters are removed on completion, timeout or error.
 */
@Component
public class ProgressHub {

    private static final Logger log = LoggerFactory.getLogger(ProgressHub.class);
    private static final long TIMEOUT_MS = 180_000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> bySubmission = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<SseEmitter> global = new CopyOnWriteArrayList<>();

    public SseEmitter subscribeSubmission(long submissionId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        bySubmission.computeIfAbsent(submissionId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> remove(submissionId, emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    public SseEmitter subscribeGlobal() {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        global.add(emitter);
        Runnable cleanup = () -> global.remove(emitter);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(e -> cleanup.run());
        return emitter;
    }

    public void publish(long submissionId, String type, Object payload) {
        Dto.EventEnvelope envelope = new Dto.EventEnvelope(type, submissionId, payload);
        send(bySubmission.get(submissionId), type, envelope);
        send(global, type, envelope);
    }

    public void finish(long submissionId, Object payload) {
        Dto.EventEnvelope envelope = new Dto.EventEnvelope("submission:done", submissionId, payload);
        List<SseEmitter> list = bySubmission.remove(submissionId);
        if (list != null) {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().name("submission:done").data(envelope));
                    emitter.complete();
                } catch (Exception ignored) {
                    // client already gone
                }
            }
        }
        send(global, "submission:done", envelope);
    }

    private void send(List<SseEmitter> emitters, String event, Object data) {
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    private void remove(long submissionId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> list = bySubmission.get(submissionId);
        if (list != null) {
            list.remove(emitter);
            if (list.isEmpty()) {
                bySubmission.remove(submissionId);
            }
        }
    }

    public int openStreams() {
        return bySubmission.values().stream().mapToInt(List::size).sum() + global.size();
    }

    /** Called by the janitor so a dead browser tab cannot pin resources forever. */
    public void sweep() {
        global.removeIf(e -> {
            try {
                e.send(SseEmitter.event().name("ping").data(Map.of("t", System.currentTimeMillis())));
                return false;
            } catch (Exception ex) {
                return true;
            }
        });
    }
}
