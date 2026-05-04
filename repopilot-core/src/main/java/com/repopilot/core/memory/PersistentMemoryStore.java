package com.repopilot.core.memory;

import java.util.List;
import java.util.Optional;

/**
 * 持久记忆存储抽象。
 * 一期先用文件系统实现，但接口层保持最小读写边界。
 */
public interface PersistentMemoryStore {

    void save(MemoryRecord record);

    Optional<MemoryRecord> get(String id);

    List<MemoryIndexEntry> list();

    void delete(String id);
}
