package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.entity.SysMenu;
import cn.hip.platform.core.entity.SysRole;
import cn.hip.platform.core.repository.SysMenuRepository;
import cn.hip.platform.core.repository.SysRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/system/roles")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleRepository roleRepository;
    private final SysMenuRepository menuRepository;

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(roleRepository.findAll().stream()
                .map(r -> Map.<String, Object>of(
                        "id", r.getId(),
                        "name", r.getName(),
                        "code", r.getCode(),
                        "remark", r.getRemark() == null ? "" : r.getRemark(),
                        "menuCount", r.getMenus().size(),
                        "menuIds", r.getMenus().stream().map(SysMenu::getId).toList()))
                .toList());
    }

    /** 全部菜单（授权树用） */
    @GetMapping("/menus")
    @PreAuthorize("hasRole('ADMIN')")
    public R<List<SysMenu>> allMenus() {
        return R.ok(menuRepository.findAll());
    }

    public record GrantRequest(List<Long> menuIds) {}

    /** 保存角色授权（整表替换）。内置 ADMIN 角色不可改，防止把自己锁在门外 */
    @PutMapping("/{id}/menus")
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public R<Void> grant(@PathVariable Long id, @RequestBody GrantRequest req) {
        SysRole role = roleRepository.findById(id).orElse(null);
        if (role == null) return R.fail(1301, "角色不存在");
        if ("ADMIN".equals(role.getCode())) return R.fail(1302, "内置管理员角色不可修改授权");
        role.getMenus().clear();
        if (req.menuIds() != null) {
            role.getMenus().addAll(menuRepository.findAllById(req.menuIds()));
        }
        roleRepository.save(role);
        return R.ok();
    }
}
