package com.example.gsb.tpc;

/**
 * 持久化状态存储。生产实现可落盘 / 落库；本组件用内存实现模拟，
 * 关键语义是：组件“重启”后新实例仍能从 store 读回崩溃前写入的状态。
 */
public interface StateStore {

  void put(String key, Object value);

  Object get(String key);
}
