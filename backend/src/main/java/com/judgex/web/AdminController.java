package com.judgex.web;

import com.judgex.common.ApiException;
import com.judgex.repo.ProblemMapper;
import com.judgex.repo.TaskMapper;
import com.judgex.service.SchedulerService;
import com.judgex.web.dto.Dto;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ProblemMapper problems;
    private final SchedulerService scheduler;
    private final TaskMapper tasks;

    public AdminController(ProblemMapper problems, SchedulerService scheduler, TaskMapper tasks) {
        this.problems = problems;
        this.scheduler = scheduler;
        this.tasks = tasks;
    }

    /**
     * Manual nudge for the dispatcher. The fast path is a queue message; if the broker is
     * down (or someone wants a scan right now) an operator can force one without a restart.
     */
    @PostMapping("/judge/wake")
    public Map<String, Object> wake() {
        scheduler.wakeUp();
        return Map.of("woken", true, "queued", tasks.queueDepth(), "active", scheduler.activeTasks());
    }

    @PostMapping("/problems")
    public Dto.ProblemDetail create(@RequestBody Dto.ProblemUpsertRequest req) {
        validate(req);
        long id = problems.insert(req);
        return detail(id);
    }

    @PutMapping("/problems/{id}")
    public Dto.ProblemDetail update(@PathVariable long id, @RequestBody Dto.ProblemUpsertRequest req) {
        validate(req);
        problems.find(id).orElseThrow(() -> ApiException.notFound("problem"));
        problems.update(id, req);
        return detail(id);
    }

    @DeleteMapping("/problems/{id}")
    public void delete(@PathVariable long id) {
        problems.find(id).orElseThrow(() -> ApiException.notFound("problem"));
        problems.delete(id);
    }

    @PutMapping("/problems/{id}/cases")
    public Dto.ProblemDetail replaceCases(@PathVariable long id,
                                          @RequestBody List<Dto.TestCaseUpsertRequest> cases) {
        problems.find(id).orElseThrow(() -> ApiException.notFound("problem"));
        problems.replaceCases(id, cases);
        return detail(id);
    }

    @PostMapping("/problems/{id}/cases")
    public Dto.ProblemDetail addCase(@PathVariable long id, @RequestBody Dto.TestCaseUpsertRequest c) {
        problems.find(id).orElseThrow(() -> ApiException.notFound("problem"));
        problems.insertCase(id, c);
        return detail(id);
    }

    private Dto.ProblemDetail detail(long id) {
        Dto.ProblemSummary summary = problems.find(id).orElseThrow(() -> ApiException.notFound("problem"));
        return new Dto.ProblemDetail(summary, problems.statement(id), problems.cases(id, true));
    }

    private void validate(Dto.ProblemUpsertRequest req) {
        if (req.slug() == null || req.slug().isBlank()) {
            throw ApiException.badRequest("slug is required");
        }
        if (!req.slug().matches("[a-z0-9-]{2,63}")) {
            throw ApiException.badRequest("slug must be lowercase letters, digits or dash");
        }
        if (req.title() == null || req.title().isBlank()) {
            throw ApiException.badRequest("title is required");
        }
        if (req.statement() == null || req.statement().isBlank()) {
            throw ApiException.badRequest("statement is required");
        }
    }
}
