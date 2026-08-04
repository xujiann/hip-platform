package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.entity.SysMenu;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysUserRepository;
import cn.hip.platform.core.security.JwtService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final SysUserRepository userRepository;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    private static final int MAX_FAILED = 5;
    private static final java.time.Duration LOCK_DURATION = java.time.Duration.ofMinutes(15);

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest req) {
        var userOpt = userRepository.findByUsername(req.username());
        if (userOpt.isPresent()) {
            var u = userOpt.get();
            if (u.getLockedUntil() != null && u.getLockedUntil().isAfter(java.time.Instant.now())) {
                return R.fail(1002, "账号已锁定，请 15 分钟后重试");
            }
        }
        try {
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(req.username(), req.password()));
        } catch (BadCredentialsException e) {
            // 防爆破：连续失败 5 次锁定 15 分钟
            userOpt.ifPresent(u -> {
                u.setFailedAttempts(u.getFailedAttempts() + 1);
                if (u.getFailedAttempts() >= MAX_FAILED) {
                    u.setLockedUntil(java.time.Instant.now().plus(LOCK_DURATION));
                    u.setFailedAttempts(0);
                }
                userRepository.save(u);
            });
            return R.fail(1001, "用户名或密码错误");
        }
        userOpt.ifPresent(u -> {
            u.setFailedAttempts(0);
            u.setLockedUntil(null);
            userRepository.save(u);
        });
        String token = jwtService.issue(req.username());
        return R.ok(Map.of("token", token));
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(Authentication authentication) {
        SysUser user = userRepository.findByUsername(authentication.getName()).orElseThrow();
        List<Map<String, Object>> menus = user.getRoles().stream()
                .flatMap(r -> r.getMenus().stream())
                .filter(SysMenu::getEnabled)
                .distinct()
                .sorted(Comparator.comparing(SysMenu::getSortNo))
                .map(m -> Map.<String, Object>of(
                        "id", m.getId(),
                        "parentId", m.getParentId() == null ? 0 : m.getParentId(),
                        "name", m.getName(),
                        "type", m.getType(),
                        "path", m.getPath() == null ? "" : m.getPath(),
                        "perm", m.getPerm() == null ? "" : m.getPerm(),
                        "icon", m.getIcon() == null ? "" : m.getIcon()))
                .toList();
        return R.ok(Map.of(
                "id", user.getId(),
                "username", user.getUsername(),
                "realName", user.getRealName(),
                "roles", user.getRoles().stream().map(r -> r.getCode()).toList(),
                "menus", menus));
    }
}
