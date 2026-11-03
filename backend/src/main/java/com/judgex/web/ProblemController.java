package com.judgex.web;

import com.judgex.auth.AuthFilter;
import com.judgex.auth.UserPrincipal;
import com.judgex.common.ApiException;
import com.judgex.repo.ProblemRepo;
import com.judgex.web.dto.Dto;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/problems")
public class ProblemController {

    private final ProblemRepo problems;

    public ProblemController(ProblemRepo problems) {
        this.problems = problems;
    }

    @GetMapping
    public List<Dto.ProblemSummary> list(@RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        return problems.summaries(user.isAdmin());
    }

    @GetMapping("/{id}")
    public Dto.ProblemDetail detail(@PathVariable long id,
                                    @RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        Dto.ProblemSummary summary = problems.find(id).orElseThrow(() -> ApiException.notFound("problem"));
        return new Dto.ProblemDetail(summary, problems.statement(id), problems.cases(id, user.isAdmin()));
    }

    @GetMapping("/slug/{slug}")
    public Dto.ProblemDetail bySlug(@PathVariable String slug,
                                    @RequestAttribute(AuthFilter.USER_ATTR) UserPrincipal user) {
        Dto.ProblemSummary summary = problems.findBySlug(slug).orElseThrow(() -> ApiException.notFound("problem"));
        return new Dto.ProblemDetail(summary, problems.statement(summary.id()),
                problems.cases(summary.id(), user.isAdmin()));
    }
}
