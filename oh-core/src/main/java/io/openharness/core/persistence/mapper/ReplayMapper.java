package io.openharness.core.persistence.mapper;

import io.openharness.core.persistence.model.ReplayEvent;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ReplayMapper {
    void insert(ReplayEvent event);
    List<ReplayEvent> findBySessionId(String sessionId);
}
