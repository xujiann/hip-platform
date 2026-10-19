package cn.hip.platform.empi.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import java.util.Set;

/** Shared EMPI lookup does not grant clinical or identity modification duties. */
@Service @RequiredArgsConstructor
public class PatientAccess {
    private final JdbcTemplate jdbc;
    public record Actor(boolean admin, boolean writer, boolean clinical) {}
    public Actor actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth==null) throw new AccessDeniedException("无法识别患者资料操作人");
        var roles=auth.getAuthorities().stream().map(x->x.getAuthority()).toList();
        if(roles.stream().noneMatch(Set.of("ROLE_ADMIN","ROLE_DOCTOR_OUTP","ROLE_NURSE","ROLE_CASHIER","ROLE_TECHNICIAN","ROLE_QUALITY")::contains))
            throw new AccessDeniedException("无患者资料岗位");
        if(jdbc.queryForList("select id from sys_user where username=? and enabled=true",Long.class,auth.getName()).isEmpty())
            throw new AccessDeniedException("无法识别启用操作人");
        boolean admin=roles.contains("ROLE_ADMIN");
        boolean clinical=admin || roles.contains("ROLE_DOCTOR_OUTP") || roles.contains("ROLE_NURSE");
        return new Actor(admin,clinical || roles.contains("ROLE_CASHIER"),clinical);
    }
    public Actor writer() {
        var actor=actor();
        if(!actor.writer()) throw new AccessDeniedException("当前岗位只可查询患者资料");
        return actor;
    }
}
