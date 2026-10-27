package cn.hip.medtech.service;

import cn.hip.platform.core.common.HipBizException;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.*;

/** Clinical specimen writes serialize source O -> S; physical rescue is independent of payment. */
@Service
@RequiredArgsConstructor
public class PathologySpecimenAccess {
    private final JdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final PathologyRegistrationAccess registrationAccess;

    public Long actor(Authentication supplied, boolean signerOnly) {
        Long uid = registrationAccess.operator(supplied);
        if (signerOnly) {
            if (SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                    .noneMatch(a -> Set.of("ROLE_ADMIN", "ROLE_DOCTOR_OUTP").contains(a.getAuthority())))
                throw new AccessDeniedException("无病理报告签名权限");
            // A current technical duty alone cannot authorize a signature after O/S waits.
            var signers = jdbc.queryForList("""
                    select u.id from sys_user u where u.id=? and u.enabled=true
                      and exists (select 1 from sys_user_role ur join sys_role r on r.id=ur.role_id
                                  where ur.user_id=u.id and r.code in ('ADMIN','DOCTOR_OUTP'))
                    """, Long.class, uid);
            if (signers.isEmpty()) throw new AccessDeniedException("病理报告签名人不存在、已停用或岗位已变化");
        }
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

    /** All source rows precede all specimens, including batches spanning OUTP and INP. */
    private Map<Long, Map<String, Object>> lockSet(List<Long> ids, Authentication auth, int code) {
        var refs = new TreeMap<Long, Map<String, Object>>();
        for (Long id : ids) {
            var rows = jdbc.queryForList("select id, order_id, inp_order_id from path_specimen where id=?", id);
            if (rows.isEmpty()) throw changed(code);
            refs.put(id, rows.getFirst());
        }
        // Fixed table order, then numeric id. Never take the next O while already holding S.
        for (String key : List.of("order_id", "inp_order_id")) {
            var sources = refs.values().stream().map(m -> (Long) m.get(key)).filter(Objects::nonNull)
                    .distinct().sorted().toList();
            String table = key.equals("order_id") ? "outp_order" : "inp_order";
            for (Long id : sources) {
                var rows = jdbc.queryForList("select id from " + table + " where id=? for update", id);
                actor(auth, false);
                if (rows.isEmpty()) throw changed(code);
            }
        }
        var result = new LinkedHashMap<Long, Map<String, Object>>();
        for (var entry : refs.entrySet()) {
            var rows = jdbc.queryForList("select * from path_specimen where id=? for update", entry.getKey());
            actor(auth, false);
            if (rows.isEmpty()) throw changed(code);
            var row = rows.getFirst();
            for (String key : List.of("order_id", "inp_order_id"))
                if (!Objects.equals(row.get(key), entry.getValue().get(key))) throw changed(code);
            result.put(entry.getKey(), row);
        }
        return result;
    }

    public boolean lockBlocks(List<Long> ids, Authentication auth, int code) {
        return lockChildren(ids, auth, code, false) != null;
    }

    public boolean lockSlides(List<Long> ids, Authentication auth, int code) {
        return lockChildren(ids, auth, code, true) != null;
    }

    private Map<Long, Map<String, Object>> lockChildren(List<Long> ids, Authentication auth, int code, boolean slides) {
        actor(auth, false);
        entityManager.flush();
        var refs = new ArrayList<Map<String, Object>>();
        for (Long id : ids.stream().distinct().sorted().toList()) {
            var rows = jdbc.queryForList(slides
                    ? "select sl.id, sl.block_id, b.specimen_id, sl.tech_order_id from path_slide sl join path_block b on b.id=sl.block_id where sl.id=?"
                    : "select b.id, b.id as block_id, b.specimen_id, b.tech_order_id from path_block b where b.id=?", id);
            if (rows.isEmpty()) return null;
            refs.add(rows.getFirst());
        }
        var specimens = lockSet(refs.stream().map(m -> (Long) m.get("specimen_id")).distinct().toList(), auth, code);
        if (specimens.values().stream().anyMatch(m -> m.get("rejected_at") != null))
            throw new HipBizException(code, "标本已拒收，不能登记制片，请刷新后重试");
        var blocks = new TreeMap<Long, Long>();
        for (var ref : refs) blocks.put((Long) ref.get("block_id"), (Long) ref.get("specimen_id"));
        for (var entry : blocks.entrySet()) {
            var rows = jdbc.queryForList("select * from path_block where id=? for update", entry.getKey());
            actor(auth, false);
            if (rows.isEmpty() || !Objects.equals(entry.getValue(), rows.getFirst().get("specimen_id"))) throw changed(code);
        }
        var technical = new TreeMap<Long, Long>();
        for (var ref : refs) if (ref.get("tech_order_id") != null)
            technical.put((Long) ref.get("tech_order_id"), (Long) ref.get("specimen_id"));
        for (var entry : technical.entrySet()) {
            var rows = jdbc.queryForList("select specimen_id from path_tech_order where id=? and specimen_id=? for update",
                    entry.getKey(), entry.getValue());
            actor(auth, false);
            if (rows.isEmpty() || !Objects.equals(entry.getValue(), rows.getFirst().get("specimen_id"))) throw changed(code);
        }
        // The specimen lock serializes all application producers. Lock physical children too so a
        // waiting stale request cannot follow a direct relationship repair to a different specimen.
        for (var ref : refs) {
            var rows = jdbc.queryForList(slides
                    ? "select * from path_slide where id=? for update"
                    : "select * from path_block where id=? for update", ref.get("id"));
            actor(auth, false);
            if (rows.isEmpty()) throw changed(code);
            var row = rows.getFirst();
            String parent = slides ? "block_id" : "specimen_id";
            if (!Objects.equals(ref.get(parent), row.get(parent))
                    || !Objects.equals(ref.get("tech_order_id"), row.get("tech_order_id"))) throw changed(code);
        }
        return specimens;
    }

    /** Resolve T without locking it, then O -> S -> T and verify its original parent binding. */
    public Map<String, Object> lockTechOrder(Long id, Authentication auth) {
        actor(auth, false);
        entityManager.flush();
        var refs = jdbc.queryForList("select id, specimen_id, block_id from path_tech_order where id=?", id);
        if (refs.isEmpty()) return null;
        var ref = refs.getFirst();
        Long sid = (Long) ref.get("specimen_id");
        var specimens = lockSet(List.of(sid), auth, 5268);
        var rows = jdbc.queryForList("select * from path_tech_order where id=? and specimen_id=? for update", id, sid);
        actor(auth, false);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!Objects.equals(ref.get("specimen_id"), row.get("specimen_id"))) throw changed(5268);
        if (!Objects.equals(ref.get("block_id"), row.get("block_id"))) {
            // RESAMPLE legitimately backfills its first newly produced block while this request
            // waits on O. This is the same technical order, with fresh physical evidence.
            boolean backfilled = ref.get("block_id") == null && row.get("block_id") != null
                    && "RESAMPLE".equals(row.get("tech_type"))
                    && !jdbc.queryForList("select id from path_block where id=? and specimen_id=? and tech_order_id=?",
                            row.get("block_id"), sid, id).isEmpty();
            if (!backfilled) throw changed(5268);
        }
        return specimens.get(sid);
    }

    private static HipBizException changed(int code) {
        return new HipBizException(code, "标本来源已变化，请刷新后重试");
    }
}
