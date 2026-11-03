package com.judgex.redis;

import com.judgex.config.JudgexProps;
import com.judgex.repo.SubmissionRepo;
import com.judgex.web.dto.Dto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Solved-count ranking backed by a sorted set.
 * <p>
 * A judge platform's ranking has two axes that move independently — how many problems you
 * solved, and how fast your accepted runs were. A ZSET gives the ordering in O(log N); the
 * readable part (name, solved, time) rides along in a hash so one round trip renders the board.
 * <p>
 * The board is derived data: it can be rebuilt from `submissions` at any time, so dropping it
 * is safe.
 */
@Component
public class LeaderboardService {

    private static final Logger log = LoggerFactory.getLogger(LeaderboardService.class);
    private static final String SEP = "\u0001";

    private final RedisGate gate;
    private final String prefix;
    private final SubmissionRepo submissions;

    public LeaderboardService(RedisGate gate, JudgexProps props, SubmissionRepo submissions) {
        this.gate = gate;
        this.prefix = props.redis() == null ? "jx:" : props.redis().keyPrefix();
        this.submissions = submissions;
    }

    /** Recompute one user's row from the database, then publish it to the board. */
    public void refresh(long userId, String displayName) {
        if (!gate.up()) {
            return;
        }
        try {
            SubmissionRepo.Best best = submissions.bestAccepted(userId);
            double score = best.solved() * 1_000_000d + Math.max(0, 999_999d - best.totalMs());
            String member = String.valueOf(userId);
            gate.template().opsForZSet().add(prefix + "lb", member, score);
            gate.template().opsForHash().put(prefix + "lb:meta", member,
                    displayName + SEP + best.solved() + SEP + best.totalMs());
        } catch (Exception e) {
            log.debug("leaderboard refresh failed for user {}: {}", userId, e.getMessage());
        }
    }

    public List<Dto.LeaderboardRow> top(int limit) {
        if (!gate.up()) {
            return List.of();
        }
        try {
            Set<ZSetOperations.TypedTuple<String>> rows =
                    gate.template().opsForZSet().reverseRangeWithScores(prefix + "lb", 0, limit - 1);
            if (rows == null || rows.isEmpty()) {
                return List.of();
            }
            List<Dto.LeaderboardRow> out = new ArrayList<>();
            int rank = 1;
            for (ZSetOperations.TypedTuple<String> row : rows) {
                String member = row.getValue();
                Object meta = gate.template().opsForHash().get(prefix + "lb:meta", member);
                String name = member;
                int solved = 0;
                long totalMs = 0;
                if (meta != null) {
                    String[] parts = meta.toString().split(SEP);
                    if (parts.length == 3) {
                        name = parts[0];
                        solved = Integer.parseInt(parts[1]);
                        totalMs = Long.parseLong(parts[2]);
                    }
                }
                out.add(new Dto.LeaderboardRow(rank++, name, solved, totalMs));
            }
            return out;
        } catch (Exception e) {
            log.debug("leaderboard read failed: {}", e.getMessage());
            return List.of();
        }
    }
}
