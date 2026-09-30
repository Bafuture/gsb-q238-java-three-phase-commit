package com.example.gsb.tpc;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 基于 ConcurrentHashMap 的内存持久化存储，模拟 WAL/磁盘。 */
public class InMemoryStateStore implements StateStore {

  private final Map<String, Object> data = new ConcurrentHashMap<>();

  @Override
  public void put(String key, Object value) {
    data.put(key, value);
  }

  @Override
  public Object get(String key) {
    return data.get(key);
  }
}
