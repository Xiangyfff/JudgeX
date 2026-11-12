package com.judgex.repo;

import com.judgex.web.dto.Dto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface WorkerMapper {

    int upsertHeartbeat(@Param("id") String id,
                        @Param("host") String host,
                        @Param("concurrency") int concurrency,
                        @Param("activeTasks") int activeTasks,
                        @Param("now") LocalDateTime now);

    List<Dto.WorkerView> selectAll(@Param("now") LocalDateTime now, @Param("staleAfterMs") long staleAfterMs);

    int sumCapacitySince(@Param("threshold") LocalDateTime threshold);

    int countOnlineSince(@Param("threshold") LocalDateTime threshold);

    int deleteStaleBefore(@Param("threshold") LocalDateTime threshold);

    /** Upsert heartbeat; the workers table doubles as the discovery + health view. */
    default void heartbeat(String id, String host, int concurrency, int activeTasks) {
        upsertHeartbeat(id, host, concurrency, activeTasks, LocalDateTime.now());
    }

    default List<Dto.WorkerView> list(long staleAfterMs) {
        return selectAll(LocalDateTime.now(), staleAfterMs);
    }

    default int capacity() {
        return sumCapacitySince(LocalDateTime.now().minusSeconds(15));
    }

    default int onlineCount(long staleAfterMs) {
        return countOnlineSince(LocalDateTime.now().minusSeconds(staleAfterMs / 1000));
    }

    default void prune(long staleAfterMs) {
        deleteStaleBefore(LocalDateTime.now().minusSeconds(staleAfterMs / 1000));
    }
}
