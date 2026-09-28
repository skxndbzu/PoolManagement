package com.poolguard.adapter;

/** 远端人工操作或其他控制者已经改变启停状态，旧检测不能覆盖新决定。 */
public class ControlConflictException extends AdapterException {
    public ControlConflictException(String message) { super(message); }
}
