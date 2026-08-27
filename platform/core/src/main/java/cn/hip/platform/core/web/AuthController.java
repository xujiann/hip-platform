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
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

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
        // authenticate 成功即用户必然存在
        SysUser user = userOpt.orElseThrow();
        user.setFailedAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        // v27-A：token 携带口令戳，改密后旧 token 立即失效（JwtAuthenticationFilter 比对）
        String token = jwtService.issue(req.username(), user.getPasswordUpdatedAt().getEpochSecond());
        // 等保：口令使用时长与定期更换提醒（超过 90 天提示）
        long pwdAgeDays = java.time.Duration
                .between(user.getPasswordUpdatedAt(), java.time.Instant.now()).toDays();
        return R.ok(Map.of("token", token,
                "passwordAgeDays", pwdAgeDays,
                "passwordExpireWarning", pwdAgeDays >= 90,
                "mustChangePassword", user.getMustChangePassword()));
    }

    public record ChangePasswordRequest(@NotBlank String oldPassword, @NotBlank String newPassword) {}

    /**
     * 自助改密（v27-A 等保必查项）。校验顺序：旧密→强度→重复，
     * 先证明"你是你"再谈新口令质量，避免探测者用弱口令响应差异摸库。
     */
    @PostMapping("/change-password")
    @org.springframework.transaction.annotation.Transactional
    public R<Void> changePassword(@RequestBody ChangePasswordRequest req, Authentication authentication) {
        SysUser user = userRepository.findByUsername(authentication.getName()).orElseThrow();
        if (!passwordEncoder.matches(req.oldPassword(), user.getPassword())) {
            return R.fail(1006, "原密码不正确");
        }
        String pwdError = cn.hip.platform.core.security.PasswordPolicy.error(req.newPassword());
        if (pwdError != null) {
            return R.fail(1007, pwdError);
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPassword())) {
            return R.fail(1008, "新密码不能与原密码相同");
        }
        user.setPassword(passwordEncoder.encode(req.newPassword()));
        // 口令戳一变，此前签发的所有新式 token（含本次请求所用的）随即失效——前端改完主动登出
        user.setPasswordUpdatedAt(java.time.Instant.now());
        user.setMustChangePassword(false);
        userRepository.save(user);
        return R.ok();
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
                // 前端刷新页面后仍需知道是否处于强制改密态（login 响应不落地）
                "mustChangePassword", user.getMustChangePassword(),
                "menus", menus));
    }
}
