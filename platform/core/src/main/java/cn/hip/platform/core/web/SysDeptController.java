package cn.hip.platform.core.web;

import cn.hip.platform.core.common.R;
import cn.hip.platform.core.entity.SysDept;
import cn.hip.platform.core.repository.SysDeptRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/system/depts")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptRepository deptRepository;

    public record SaveDeptRequest(@NotBlank String name, @NotBlank String code,
                                  @NotBlank String type, Long parentId, Integer sortNo) {}

    /** 所有登录用户可读（下拉选择用） */
    @GetMapping
    public R<List<SysDept>> list() {
        return R.ok(deptRepository.findAll(Sort.by("sortNo", "id")));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public R<SysDept> create(@RequestBody SaveDeptRequest req) {
        SysDept d = new SysDept();
        d.setName(req.name());
        d.setCode(req.code());
        d.setType(req.type());
        d.setParentId(req.parentId());
        if (req.sortNo() != null) d.setSortNo(req.sortNo());
        return R.ok(deptRepository.save(d));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public R<SysDept> update(@PathVariable Long id, @RequestBody SaveDeptRequest req) {
        SysDept d = deptRepository.findById(id).orElse(null);
        if (d == null) return R.fail(1201, "科室不存在");
        d.setName(req.name());
        d.setCode(req.code());
        d.setType(req.type());
        d.setParentId(req.parentId());
        if (req.sortNo() != null) d.setSortNo(req.sortNo());
        return R.ok(deptRepository.save(d));
    }

    @PutMapping("/{id}/enabled")
    @PreAuthorize("hasRole('ADMIN')")
    public R<Void> setEnabled(@PathVariable Long id, @RequestParam boolean enabled) {
        SysDept d = deptRepository.findById(id).orElse(null);
        if (d == null) return R.fail(1201, "科室不存在");
        d.setEnabled(enabled);
        deptRepository.save(d);
        return R.ok();
    }
}
