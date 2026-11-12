package com.judgex.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.judgex.config.JudgexProps;
import com.judgex.mq.OutboxMapper;
import com.judgex.repo.SubmissionMapper;
import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes the verdict and the outbox row atomically.
 *
 * <p>This is the "transactional" half of the transactional-outbox pattern: either both the
 * submission reaches DONE and the event is queued, or neither happens. Delivery to the broker
 * is a separate, retryable step.
 */
@Service
public class ResultService {

    private static final Logger log = LoggerFactory.getLogger(ResultService.class);

    private final SubmissionMapper submissions;
    private final OutboxMapper outbox;
    private final ObjectMapper mapper;
    private final JudgexProps props;

    public ResultService(SubmissionMapper submissions, OutboxMapper outbox, ObjectMapper mapper, JudgexProps props) {
        this.submissions = submissions;
        this.outbox = outbox;
        this.mapper = mapper;
        this.props = props;
    }

    @Transactional
    public boolean finishAndPublish(Dto.SubmissionRow row, String verdict, int score,
                                    String compileOutput, long totalMs) {
        int written = submissions.finish(row.id(), "DONE", verdict, score, compileOutput, totalMs);
        if (written == 0) {
            // Already terminal: the verdict the user saw stays, and no second event goes out.
            log.info("submission {} already finished, late verdict {} ignored", row.id(), verdict);
            return false;
        }
        if (!props.mq().enabled()) {
            return true;
        }
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("submissionId", row.id());
            event.put("userId", row.userId());
            event.put("userName", row.user());
            event.put("problemId", row.problemId());
            event.put("problemTitle", row.problemTitle());
            event.put("language", row.language());
            event.put("verdict", verdict);
            event.put("score", score);
            event.put("totalMs", totalMs);
            event.put("at", Instant.now().toString());
            outbox.append("verdict:" + row.id(), props.mq().topic(), mapper.writeValueAsString(event));
        } catch (Exception e) {
            // Serialisation problems must not roll back a real verdict; the submission is the truth.
            log.error("cannot enqueue verdict event for {}: {}", row.id(), e.toString());
        }
        return true;
    }
}
