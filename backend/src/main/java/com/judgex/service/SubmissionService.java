package com.judgex.service;

import com.judgex.auth.UserPrincipal;
import com.judgex.common.ApiException;
import com.judgex.config.JudgexProps;
import com.judgex.repo.ProblemMapper;
import com.judgex.repo.SubmissionMapper;
import com.judgex.repo.TaskMapper;
import com.judgex.redis.TokenBucketLimiter;
import com.judgex.sandbox.LanguageRegistry;
import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SubmissionService {

    private static final Logger log = LoggerFactory.getLogger(SubmissionService.class);

    private final SubmissionMapper submissions;
    private final ProblemMapper problems;
    private final TaskMapper tasks;
    private final LanguageRegistry languages;
    private final JudgexProps props;
    private final ProgressHub hub;
    private final TokenBucketLimiter limiter;

    public SubmissionService(SubmissionMapper submissions, ProblemMapper problems, TaskMapper tasks,
                             LanguageRegistry languages, JudgexProps props, ProgressHub hub,
                             TokenBucketLimiter limiter) {
        this.submissions = submissions;
        this.problems = problems;
        this.tasks = tasks;
        this.languages = languages;
        this.props = props;
        this.hub = hub;
        this.limiter = limiter;
    }

    @Transactional
    public Dto.SubmissionRow create(UserPrincipal user, Dto.CreateSubmissionRequest req, String idemKey) {
        if (req.problemId() == null) {
            throw ApiException.badRequest("problemId is required");
        }
        if (req.sourceCode() == null || req.sourceCode().isBlank()) {
            throw ApiException.badRequest("sourceCode is empty");
        }
        if (req.sourceCode().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > props.limits().maxSourceBytes()) {
            throw ApiException.badRequest("source exceeds " + props.limits().maxSourceBytes() + " bytes");
        }
        if (!languages.supports(req.language())) {
            throw ApiException.badRequest("language not available on this node: " + req.language());
        }
        Dto.ProblemSummary problem = problems.find(req.problemId())
                .orElseThrow(() -> ApiException.notFound("problem"));

        if (idemKey != null && !idemKey.isBlank()) {
            var existing = submissions.findIdByIdem(user.id(), idemKey);
            if (existing.isPresent()) {
                log.info("idempotent replay for user {} key {}", user.id(), idemKey);
                return submissions.find(existing.get()).orElseThrow();
            }
        }

        enforceRate(user.id());
        if (submissions.activeCount(user.id()) >= props.limits().maxActivePerUser()) {
            throw ApiException.tooMany("you already have a submission in flight; wait for it to finish");
        }

        long id = submissions.insert(user.id(), problem.id(), req.language(), req.sourceCode(),
                idemKey == null || idemKey.isBlank() ? null : idemKey);
        tasks.enqueue(id);
        Dto.SubmissionRow row = submissions.find(id).orElseThrow();
        hub.publish(id, "status", Map.of("status", "QUEUED", "submissionId", id));
        log.info("submission {} accepted for user {} problem {}", id, user.id(), problem.slug());
        return row;
    }

    private void enforceRate(long userId) {
        limiter.acquire(userId, props.limits().submitRatePerMinute());
    }

    /**
     * Source code and per-case output belong to the author. Everyone else, except an admin,
     * gets a 403 — the id is sequential, so "logged in" is not an authorisation.
     */
    public Dto.SubmissionDetail detail(long id, UserPrincipal viewer) {
        Dto.SubmissionRow row = submissions.find(id).orElseThrow(() -> ApiException.notFound("submission"));
        if (row.userId() != viewer.id() && !viewer.isAdmin()) {
            throw ApiException.forbidden("this submission belongs to another user");
        }
        return new Dto.SubmissionDetail(row, submissions.source(id),
                submissions.compileOutput(id), submissions.results(id));
    }

    public List<Dto.SubmissionRow> list(Long userId, Long problemId, int limit) {
        return submissions.list(userId, problemId, Math.min(Math.max(limit, 1), 100));
    }
}
