package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.entity.SysRole;
import cn.hip.platform.core.entity.SysUser;
import cn.hip.platform.core.repository.SysRoleRepository;
import cn.hip.platform.core.repository.SysUserRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SysUserController {

    private final SysUserRepository userRepository;
    private final SysRoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public record UserDto(Long id, String username, String realName, String title,
                          Long deptId, String phone, Boolean enabled, List<String> roleCodes) {
        static UserDto from(SysUser u) {
            return new UserDto(u.getId(), u.getUsername(), u.getRealName(), u.getTitle(),
                    u.getDeptId(), u.getPhone(), u.getEnabled(),
                    u.getRoles().stream().map(SysRole::getCode).toList());
        }
    }

    public record SaveUserRequest(@NotBlank String username, String password, @NotBlank String realName,
                                  String title, Long deptId, String phone, List<String> roleCodes) {}

    @GetMapping
    public R<Map<String, Object>> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        var p = userRepository.findAll(PageRequest.of(page, size, Sort.by("id")));
        return R.ok(Map.of(
                "total", p.getTotalElements(),
                "records", p.getContent().stream().map(UserDto::from).toList()));
    }

    @PostMapping
    @Transactional
    public R<UserDto> create(@RequestBody SaveUserRequest req) {
        if (userRepository.findByUsername(req.username()).isPresent()) {
            return R.fail(1101, "用户名已存在");
        }
        String pwdError = passwordPolicyError(req.password());
        if (pwdError != null) {
            return R.fail(1102, pwdError);
        }
        SysUser u = new SysUser();
        u.setUsername(req.username());
        u.setPassword(passwordEncoder.encode(req.password()));
        applyFields(u, req);
        return R.ok(UserDto.from(userRepository.save(u)));
    }

    @PutMapping("/{id}")
    @Transactional
    public R<UserDto> update(@PathVariable Long id, @RequestBody SaveUserRequest req) {
        SysUser u = userRepository.findById(id).orElse(null);
        if (u == null) return R.fail(1103, "用户不存在");
        applyFields(u, req);
        if (req.password() != null && !req.password().isBlank()) {
            String pwdError = passwordPolicyError(req.password());
            if (pwdError != null) return R.fail(1102, pwdError);
            u.setPassword(passwordEncoder.encode(req.password()));
        }
        return R.ok(UserDto.from(userRepository.save(u)));
    }

    @PutMapping("/{id}/enabled")
    @Transactional
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        SysUser u = userRepository.findById(id).orElse(null);
        if (u == null) return R.fail(1103, "用户不存在");
        if ("admin".equals(u.getUsername()) && !enabled) return R.fail(1104, "不能停用内置管理员");
        u.setEnabled(enabled);
        userRepository.save(u);
        return R.ok();
    }

    /** 等保密码策略：至少 8 位且同时含字母与数字 */
    private String passwordPolicyError(String pwd) {
        if (pwd == null || pwd.length() < 8) return "密码不能少于 8 位";
        if (!pwd.matches(".*[A-Za-z].*") || !pwd.matches(".*\\d.*")) return "密码须同时包含字母和数字";
        return null;
    }

    private void applyFields(SysUser u, SaveUserRequest req) {
        u.setRealName(req.realName());
        u.setTitle(req.title());
        u.setDeptId(req.deptId());
        u.setPhone(req.phone());
        if (req.roleCodes() != null) {
            u.getRoles().clear();
            req.roleCodes().forEach(code ->
                    roleRepository.findByCode(code).ifPresent(u.getRoles()::add));
        }
    }
}
