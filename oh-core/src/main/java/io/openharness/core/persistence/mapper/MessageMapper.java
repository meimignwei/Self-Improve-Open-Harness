package io.openharness.core.persistence.mapper;

import io.openharness.core.persistence.model.Message;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface MessageMapper {
    List<Message> findBySessionId(String sessionId);
    void batchInsert(List<Message> messages);
    void deleteBySessionId(String sessionId);
}
