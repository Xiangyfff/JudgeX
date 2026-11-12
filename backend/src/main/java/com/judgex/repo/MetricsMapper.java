package com.judgex.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface MetricsMapper {

    long countSince(@Param("since") LocalDateTime since);

    long countAll();

    long countAccepted();

    long avgJudgeMs();

    default long submissionsToday() {
        return countSince(LocalDateTime.now().toLocalDate().atStartOfDay());
    }

    default long totalSubmissions() {
        return countAll();
    }

    default long acceptedSubmissions() {
        return countAccepted();
    }
}
