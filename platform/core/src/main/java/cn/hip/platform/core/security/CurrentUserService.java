package cn.hip.platform.core.security;

import cn.hip.platform.core.repository.SysUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/** 从登录态解析当前用户 id（各业务模块通用） */
@Service
@RequiredArgsConstructor
public class CurrentUserService {

    private final SysUserRepository userRepository;

    public Long idOf(Authentication authentication) {
        return userRepository.findByUsername(authentication.getName())
                .map(u -> u.getId())
                .orElse(null);
    }
}
