package com.example.gsb.threepc;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 {@link DurableStore}，模拟进程之外的磁盘：
 * 协调者/参与者对象销毁后，store 中的数据仍在。
 */
public final class InMemoryDurableStore implements DurableStore {

    private final Map<String, Object> data = new ConcurrentHashMap<>();

    @Override
    public void put(String key, Object value) {
        data.put(key, Objects.requireNonNull(value, "value"));
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        Object value = data.get(key);
        return type.isInstance(value) ? type.cast(value) : null;
    }

    @Override
    public boolean contains(String key) {
        return data.containsKey(key);
    }
}
