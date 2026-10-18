package cn.hip.qualitycare.service;

import cn.hip.platform.core.security.CurrentUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Real nursing actor and admission lifecycle lock; ward assignment is not modeled here. */
@Service @RequiredArgsConstructor
public class NursingWriteAccess {
    private final JdbcTemplate jdbc;
    private final CurrentUserService users;
    public Long requireActor(Authentication auth) {
        Long id = auth == null ? null : users.idOf(auth);
        if (id == null) throw new AccessDeniedException("无法识别当前护理操作人");
        return id;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockActiveAdmission(Long id) {
        var rows = jdbc.queryForList("select status from inp_admission where id = ? for update", id);
        return !rows.isEmpty() && "IN_HOSPITAL".equals(rows.getFirst().get("status"));
    }
}
