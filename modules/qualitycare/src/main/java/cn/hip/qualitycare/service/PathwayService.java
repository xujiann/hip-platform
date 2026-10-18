package cn.hip.qualitycare.service;

import cn.hip.inpatient.service.InpatientReadAccess;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientWriteAccess;
import cn.hip.platform.core.common.HipBizException;
import cn.hip.platform.core.security.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Admission first, enrollment second; service exceptions cross the transaction boundary before HTTP conversion. */
@Service @RequiredArgsConstructor
public class PathwayService {
    private final JdbcTemplate jdbc;
    private final InpatientWriteAccess writes;
    private final InpatientReadAccess reads;
    private final InpatientService inpatient;
    private final CurrentUserService users;
    private final ObjectMapper mapper;

    public InpatientReadAccess.Scope readScope() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        if(auth!=null && auth.getAuthorities().stream().anyMatch(a->"ROLE_QUALITY".equals(a.getAuthority())))
            return new InpatientReadAccess.Scope(true,null);
        return reads.clinicalScope();
    }

    private Long actor() {
        var auth=SecurityContextHolder.getContext().getAuthentication();
        Long id=auth==null?null:users.idOf(auth);
        if(id==null)throw new AccessDeniedException("无法识别当前路径操作人");
        return id;
    }

    @Transactional @SneakyThrows
    public int apply(Long templateId,Long admissionId) {
        writes.assertCanWrite(admissionId);
        Long actor=actor();
        var rows=jdbc.queryForList("select items from cp_pathway_template where id=?",templateId);
        if(rows.isEmpty())throw new HipBizException(9904,"路径模板不存在");
        List<InpatientService.OrderLine> lines=mapper.readValue((String)rows.getFirst().get("items"),new TypeReference<>(){});
        int inserted=jdbc.update("insert into cp_enrollment(admission_id,template_id) values (?,?) on conflict (admission_id,template_id) do nothing",admissionId,templateId);
        if(inserted==0)throw new HipBizException(9906,"该住院已入此路径");
        return inpatient.createOrders(admissionId,lines,actor).size();
    }

    private void lockEnrollment(Long id) {
        var parent=jdbc.queryForList("select admission_id from cp_enrollment where id=?",id);
        if(parent.isEmpty())throw new HipBizException(9908,"入径不存在或已结束");
        Long admission=((Number)parent.getFirst().get("admission_id")).longValue();
        writes.assertCanWrite(admission);
        actor();
        var rows=jdbc.queryForList("select status,admission_id from cp_enrollment where id=? for update",id);
        if(rows.isEmpty() || !admission.equals(((Number)rows.getFirst().get("admission_id")).longValue())
                || !"ACTIVE".equals(rows.getFirst().get("status")))throw new HipBizException(9908,"入径不存在或已结束");
    }

    @Transactional
    public void addVariance(Long id,Integer dayNo,String type,String reason) {
        lockEnrollment(id);
        jdbc.update("insert into cp_variance(enrollment_id,day_no,var_type,reason) values (?,?,?,?)",id,dayNo,type,reason);
        if("EXIT".equals(type))jdbc.update("update cp_enrollment set status='EXITED',ended_at=now() where id=?",id);
    }

    @Transactional
    public void complete(Long id) {
        lockEnrollment(id);
        jdbc.update("update cp_enrollment set status='COMPLETED',ended_at=now() where id=?",id);
    }
}
