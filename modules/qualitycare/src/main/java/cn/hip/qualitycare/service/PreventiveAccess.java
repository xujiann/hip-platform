package cn.hip.qualitycare.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Preventive-care ownership and cross-patient infection surveillance duties. */
@Service @RequiredArgsConstructor
public class PreventiveAccess {
    private final JdbcTemplate jdbc;
    public record Actor(Long id, boolean crossRead, boolean nurse) {}
    public Actor actor() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new AccessDeniedException("无法识别当前操作人");
        var roles = auth.getAuthorities().stream().map(x -> x.getAuthority()).toList();
        if (roles.stream().noneMatch(Set.of("ROLE_ADMIN","ROLE_NURSE","ROLE_QUALITY")::contains))
            throw new AccessDeniedException("无预防保健或院感岗位");
        var ids = jdbc.queryForList("select id from sys_user where username=? and enabled=true", Long.class, auth.getName());
        if (ids.isEmpty()) throw new AccessDeniedException("无法识别启用操作人");
        boolean admin = roles.contains("ROLE_ADMIN");
        return new Actor(ids.getFirst(), admin || roles.contains("ROLE_QUALITY"), admin || roles.contains("ROLE_NURSE"));
    }
    public Actor recorder() {
        var actor = actor();
        if (!actor.nurse()) throw new AccessDeniedException("仅护理岗位可登记保健记录");
        return actor;
    }
}
