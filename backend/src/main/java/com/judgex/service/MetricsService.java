package com.judgex.service;

import com.judgex.repo.MetricsRepo;
import com.judgex.repo.SubmissionRepo;
import com.judgex.repo.TaskRepo;
import com.judgex.repo.WorkerRepo;
import com.judgex.sandbox.LanguageRegistry;
import com.judgex.sandbox.SandboxRouter;
import com.judgex.web.dto.Dto;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

@Service
public class MetricsService {

    private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int SERIES_POINTS = 48;

    private final TaskRepo tasks;
    private final WorkerRepo workers;
    private final SubmissionRepo submissions;
    private final MetricsRepo metrics;
    private final LanguageRegistry languages;
    private final SandboxRouter sandbox;

    private final Deque<Dto.QueuePoint> series = new ArrayDeque<>();

    public MetricsService(TaskRepo tasks, WorkerRepo workers, SubmissionRepo submissions,
                          MetricsRepo metrics, LanguageRegistry languages, SandboxRouter sandbox) {
        this.tasks = tasks;
        this.workers = workers;
        this.submissions = submissions;
        this.metrics = metrics;
        this.languages = languages;
        this.sandbox = sandbox;
    }

    /** Sampling the queue instead of storing every transition keeps this cheap. */
    @Scheduled(fixedDelay = 5000)
    public void sample() {
        synchronized (series) {
            series.addLast(new Dto.QueuePoint(LocalTime.now().format(HHMMSS), tasks.queueDepth(), tasks.running()));
            while (series.size() > SERIES_POINTS) {
                series.removeFirst();
            }
        }
    }

    public Dto.Dashboard dashboard() {
        long total = metrics.totalSubmissions();
        long accepted = metrics.acceptedSubmissions();
        double rate = total == 0 ? 0 : Math.round(accepted * 1000.0 / total) / 10.0;
        List<Dto.QueuePoint> points;
        synchronized (series) {
            points = List.copyOf(series);
        }
        return new Dto.Dashboard(
                tasks.queueDepth(),
                tasks.running(),
                workers.capacity(),
                workers.onlineCount(15_000),
                workers.capacity(),
                total,
                accepted,
                rate,
                metrics.avgJudgeMs(),
                metrics.submissionsToday(),
                points,
                submissions.recent(12),
                workers.list(12_000),
                languageStatus());
    }

    public List<Dto.LanguageStatus> languageStatus() {
        return languages.all().stream()
                .map(l -> new Dto.LanguageStatus(l.id(), l.name(), l.extension(), true, l.runtime()))
                .toList();
    }

    public List<Dto.WorkerView> workers() {
        return workers.list(12_000);
    }

    public String sandboxName() {
        return sandbox.name();
    }

    public int deadTasks() {
        return tasks.deadCount();
    }
}
