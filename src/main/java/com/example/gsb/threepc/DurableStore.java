package com.example.gsb.threepc;

/**
 * 持久化存储抽象。协调者与参与者把协议状态写入其中，
 * “重启”表现为用同一个 store 构造新实例。
 * 存入的值应为不可变对象，以获得落盘快照语义。
 */
public interface DurableStore {

    void put(String key, Object value);

    <T> T get(String key, Class<T> type);

    boolean contains(String key);
}
