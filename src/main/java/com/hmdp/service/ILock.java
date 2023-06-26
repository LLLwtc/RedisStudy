package com.hmdp.service;

public interface ILock {

    Boolean tryLock(long timeoutSec);

    void unlock();
}
