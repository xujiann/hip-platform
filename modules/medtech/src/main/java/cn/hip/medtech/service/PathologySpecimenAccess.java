package cn.hip.medtech.service;

import cn.hip.platform.core.common.HipBizException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Clinical specimen writes serialize source O -> S; physical rescue is independent of payment. */
@Service
@RequiredArgsConstructor
public class PathologySpecimenAccess {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final PathologyRegistrationAccess registrationAccess;

    public Long actor(Authentication supplied, boolean signerOnly) {
        Long uid = registrationAccess.operator(supplied);
        if (signerOnly && SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .noneMatch(a -> Set.of("ROLE_ADMIN", "ROLE_DOCTOR_OUTP").contains(a.getAuthority())))
            throw new AccessDeniedException("无病理报告签名权限");
        return uid;
    }

    public Map<String, Object> lockId(Long id, Authentication supplied, boolean signerOnly, int changedCode) {
        actor(supplied, signerOnly);
        entityManager.flush();
        var refs = jdbc.queryForList("select id, order_id, inp_order_id from path_specimen where id=?", id);
        return refs.isEmpty() ? null : lock(refs.getFirst(), null, supplied, signerOnly, changedCode);
    }

    public Map<String, Object> lockBarcode(String barcode, Authentication supplied, boolean signerOnly, int changedCode) {
        actor(supplied, signerOnly);
        entityManager.flush();
        var refs = jdbc.queryForList("select id, order_id, inp_order_id from path_specimen where barcode=?", barcode);
        return refs.isEmpty() ? null : lock(refs.getFirst(), barcode, supplied, signerOnly, changedCode);
    }

    private Map<String, Object> lock(Map<String, Object> ref, String barcode, Authentication supplied,
                                     boolean signerOnly, int changedCode) {
        Object outp = ref.get("order_id"), inp = ref.get("inp_order_id");
        var source = jdbc.queryForList(outp != null
                ? "select id from outp_order where id=? for update"
                : "select id from inp_order where id=? for update", outp != null ? outp : inp);
        actor(supplied, signerOnly);
        if (source.isEmpty()) throw changed(changedCode);
        var rows = jdbc.queryForList("select * from path_specimen where id=? for update", ref.get("id"));
        actor(supplied, signerOnly);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!Objects.equals(outp, row.get("order_id")) || !Objects.equals(inp, row.get("inp_order_id"))
                || (barcode != null && !Objects.equals(barcode, row.get("barcode"))))
            throw changed(changedCode);
        return row;
    }

    private static HipBizException changed(int code) {
        return new HipBizException(code, "标本来源已变化，请刷新后重试");
    }
}
