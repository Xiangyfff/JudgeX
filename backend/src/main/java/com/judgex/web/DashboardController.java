package com.judgex.web;

import com.judgex.auth.AuthFilter;
import com.judgex.auth.UserPrincipal;
import com.judgex.config.JudgexProps;
import com.judgex.mq.JudgeResultConsumer;
import com.judgex.mq.MqProducer;
import com.judgex.mq.OutboxRepo;
import com.judgex.redis.LeaderboardService;
import com.judgex.redis.PresenceService;
import com.judgex.redis.RedisGate;
import com.judgex.service.MetricsService;
import com.judgex.service.ProgressHub;
import com.judgex.sandbox.LanguageRegistry;
import com.judgex.web.dto.Dto;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class DashboardController {

    private final MetricsService metrics;
    private final LanguageRegistry languages;
    private final ProgressHub hub;
    private final JudgexProps props;
    private final RedisGate redis;
    private final PresenceService presence;
    private final LeaderboardService leaderboard;
    private final OutboxRepo outbox;
    private final MqProducer mqProducer;
    private final JudgeResultConsumer mqConsumer;

    public DashboardController(MetricsService metrics, LanguageRegistry languages, ProgressHub hub,
                               JudgexProps props, RedisGate redis, PresenceService presence,
                               LeaderboardService leaderboard, OutboxRepo outbox,
                               MqProducer mqProducer, JudgeResultConsumer mqConsumer) {
        this.metrics = metrics;
        this.languages = languages;
        this.hub = hub;
        this.props = props;
        this.redis = redis;
        this.presence = presence;
        this.leaderboard = leaderboard;
        this.outbox = outbox;
        this.mqProducer = mqProducer;
        this.mqConsumer = mqConsumer;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "time", Instant.now().toString(),
                "worker", props.worker().id(),
                "sandbox", metrics.sandboxName(),
                "redis", redis.status(),
                "languages", languages.all().size());
    }

    @GetMapping("/leaderboard")
    public List<Dto.LeaderboardRow> leaderboard() {
        return leaderboard.top(10);
    }

    @GetMapping("/presence")
    public Map<String, Object> presence() {
        return Map.of("redis", redis.status(), "nodes", presence.online());
    }

    /** Outbox depth + broker health: the operations view of the message channel. */
    @GetMapping("/mq/stats")
    public Dto.MqStats mqStats() {
        Map<String, Integer> counts = outbox.counts();
        return new Dto.MqStats(
                props.mq().enabled(),
                mqProducer.status(),
                mqConsumer.status(),
                props.mq().topic(),
                counts.getOrDefault("pending", 0),
                counts.getOrDefault("sending", 0),
                counts.getOrDefault("sent", 0),
                counts.getOrDefault("dead", 0),
                outbox.lastError());
    }

    @GetMapping("/notifications")
    public List<Dto.NotificationRow> notifications(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user,
                                                   @RequestParam(required = false) Boolean mine,
                                                   @RequestParam(defaultValue = "20") int limit) {
        Long scope = Boolean.TRUE.equals(mine) ? user.id() : null;
        return outbox.notifications(scope, Math.min(Math.max(limit, 1), 100)).stream()
                .map(n -> new Dto.NotificationRow(n.id(), n.submissionId(), n.verdict(), n.message(), n.createdAt()))
                .toList();
    }

    @GetMapping("/languages")
    public List<Dto.LanguageStatus> languages() {
        return metrics.languageStatus();
    }

    @GetMapping("/dashboard")
    public Dto.Dashboard dashboard(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        return metrics.dashboard();
    }

    @GetMapping("/workers")
    public List<Dto.WorkerView> workers(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        return metrics.workers();
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        SseEmitter emitter = hub.subscribeGlobal();
        try {
            emitter.send(SseEmitter.event().name("hello").data(Map.of(
                    "queued", metrics.dashboard().queued(),
                    "worker", props.worker().id())));
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
