package com.judgex.web;

import com.judgex.auth.AuthFilter;
import com.judgex.auth.UserPrincipal;
import com.judgex.service.ProgressHub;
import com.judgex.service.SubmissionService;
import com.judgex.web.dto.Dto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/submissions")
public class SubmissionController {

    private final SubmissionService submissions;
    private final ProgressHub hub;

    public SubmissionController(SubmissionService submissions, ProgressHub hub) {
        this.submissions = submissions;
        this.hub = hub;
    }

    @PostMapping
    public Dto.SubmissionRow create(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user,
                                    @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
                                    @RequestBody Dto.CreateSubmissionRequest req) {
        return submissions.create(user, req, idemKey);
    }

    @GetMapping
    public List<Dto.SubmissionRow> list(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user,
                                        @RequestParam(defaultValue = "false") boolean mine,
                                        @RequestParam(required = false) Long problemId,
                                        @RequestParam(defaultValue = "30") int limit) {
        return submissions.list(mine ? user.id() : null, problemId, limit);
    }

    @GetMapping("/{id}")
    public Dto.SubmissionDetail detail(@PathVariable long id) {
        return submissions.detail(id);
    }

    /**
     * Live judge feed. The initial snapshot matters: a client that connects late
     * (or reconnects) still sees the current state instead of waiting for the
     * next event.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable long id) {
        SseEmitter emitter = hub.subscribeSubmission(id);
        try {
            emitter.send(SseEmitter.event().name("snapshot").data(Map.of(
                    "submission", submissions.detail(id))));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
