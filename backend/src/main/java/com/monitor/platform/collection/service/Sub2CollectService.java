package com.monitor.platform.collection.service;

import com.monitor.platform.collection.dto.Sub2KeysResponse;
import com.monitor.platform.collection.dto.Sub2ProfileResponse;
import org.springframework.stereotype.Service;


public interface Sub2CollectService {

    /**
     * 登录（带缓存 + 重试）
     */
    void login(String baseUrl, String email);

    /**
     * 获取个人信息（带重试）
     */
    Sub2ProfileResponse fetchProfile(String baseUrl, String email);

    /**
     * 获取apikey
     */

    Sub2KeysResponse fetchKeys(String baseUrl, String email);
}
