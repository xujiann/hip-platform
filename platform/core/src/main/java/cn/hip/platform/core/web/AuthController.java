package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.config.ModuleGate;
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
    private final ModuleGate moduleGate;

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    private static final int MAX_FAILED = 5;
    private static final java.time.Duration LOCK_DURATION = java.time.Duration.ofMinutes(15);

    @PostMapping("/login")
    @org.springframework.transaction.annotation.Transactional
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
        } catch (org.springframework.security.authentication.DisabledException
                 | org.springframework.security.authentication.LockedException e) {
            // 停用/锁定账号在校验密码前就被 Provider 拒绝——原先只捕获 BadCredentials，异常逃逸成 500
            return R.fail(1004, "账号已停用，请联系管理员");
        } catch (BadCredentialsException e) {
            // 防爆破：连续失败 5 次锁定 15 分钟。
            // 必须原子累加——读-判-写在并发猜测下会让计数恒停在 1，锁定形同虚设。
            userRepository.bumpFailedAttempts(req.username(), MAX_FAILED,
                    java.time.Instant.now().plus(LOCK_DURATION));
            return R.fail(1001, "用户名或密码错误");
        }
        userOpt.ifPresent(u -> {
            u.setFailedAttempts(0);
            u.setLockedUntil(null);
            userRepository.save(u);
        });
        String token = jwtService.issue(req.username());
        // 等保：口令使用时长与定期更换提醒（超过 90 天提示）
        long pwdAgeDays = userOpt
                .map(u -> java.time.Duration.between(u.getPasswordUpdatedAt(), java.time.Instant.now()).toDays())
                .orElse(0L);
        return R.ok(Map.of("token", token,
                "passwordAgeDays", pwdAgeDays,
                "passwordExpireWarning", pwdAgeDays >= 90));
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(Authentication authentication) {
        SysUser user = userRepository.findByUsername(authentication.getName()).orElseThrow();
        // 模块开关：停用模块的菜单不下发（API 侧由 ModuleGateFilter 兜底 404）
        var disabledPaths = moduleGate.disabledMenuPaths();
        List<SysMenu> visible = user.getRoles().stream()
                .flatMap(r -> r.getMenus().stream())
                .filter(SysMenu::getEnabled)
                .filter(m -> m.getPath() == null || !disabledPaths.contains(m.getPath()))
                .distinct()
                .sorted(Comparator.comparing(SysMenu::getSortNo))
                .toList();
        // 清理因过滤而空掉的父目录
        var parentIds = visible.stream().map(SysMenu::getParentId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> menus = visible.stream()
                .filter(m -> !"DIR".equals(m.getType()) || parentIds.contains(m.getId()))
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
