package com.hmdp.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    private Object data;
    //逻辑过期
    private LocalDateTime expireTime;
}
