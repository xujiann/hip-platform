package cn.hip.inpatient.web;

import cn.hip.inpatient.entity.InpAdmission;
import cn.hip.inpatient.entity.InpDeposit;
import cn.hip.inpatient.entity.InpOrder;
import cn.hip.inpatient.repository.*;
import cn.hip.inpatient.service.InpatientService;
import cn.hip.inpatient.service.InpatientService.InpException;
import cn.hip.platform.core.common.R;
import cn.hip.platform.core.repository.SysDeptRepository;
import cn.hip.platform.core.security.CurrentUserService;
import cn.hip.platform.empi.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inpatient")
@RequiredArgsConstructor
public class InpatientController {

    private final InpatientService inpatientService;
    private final BedRepo bedRepo;
    private final AdmissionRepo admissionRepo;
    private final DepositRepo depositRepo;
    private final OrderRepo orderRepo;
    private final PatientRepository patientRepository;
    private final SysDeptRepository deptRepository;
    private final CurrentUserService currentUserService;

    public record CreateBedsRequest(Long wardId, Integer count) {}

    /** 产品化一期：病区床位批量创建（实施工具；bed_no 自增补位，重复号自动跳过） */
    @PostMapping("/beds")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public R<Map<String, Object>> createBeds(@RequestBody CreateBedsRequest req) {
        if (req.wardId() == null || req.count() == null || req.count() < 1 || req.count() > 200) {
            return R.fail(9020, "wardId 必填，count 须为 1-200");
        }
        var existing = bedRepo.findByWardIdOrderByBedNo(req.wardId()).stream()
                .map(cn.hip.inpatient.entity.InpBed::getBedNo).collect(java.util.stream.Collectors.toSet());
        int created = 0;
        for (int no = 1; created < req.count() && no <= 999; no++) {
            String bedNo = String.format("%02d", no);
            if (existing.contains(bedNo)) continue;
            var b = new cn.hip.inpatient.entity.InpBed();
            b.setWardId(req.wardId());
            b.setBedNo(bedNo);
            bedRepo.save(b);
            created++;
        }
        return R.ok(Map.of("created", created));
    }

    @GetMapping("/beds")
    public R<List<Map<String, Object>>> beds(@RequestParam Long wardId) {
        return R.ok(bedRepo.findByWardIdOrderByBedNo(wardId).stream().map(b -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("id", b.getId());
            m.put("bedNo", b.getBedNo());
            m.put("status", b.getStatus());
            if (b.getAdmissionId() != null) {
                admissionRepo.findById(b.getAdmissionId()).ifPresent(a ->
                        patientRepository.findById(a.getPatientId()).ifPresent(p ->
                                m.put("patientName", p.getName())));
            }
            return (Map<String, Object>) m;
        }).toList());
    }

    public record AdmitRequest(Long patientId, Long deptId, Long bedId, Long doctorId,
                               String diagIcd, String diagName, BigDecimal deposit, String payMethod) {}

    @PostMapping("/admissions")
    public R<Object> admit(@RequestBody AdmitRequest req, Authentication auth) {
        try {
            return R.ok(toDto(inpatientService.admit(req.patientId(), req.deptId(), req.bedId(),
                    req.doctorId(), req.diagIcd(), req.diagName(),
                    req.deposit(), req.payMethod(), currentUserService.idOf(auth))));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @GetMapping("/admissions")
    public R<List<Map<String, Object>>> inHospital() {
        return R.ok(admissionRepo.findByStatusOrderByIdDesc("IN_HOSPITAL").stream()
                .map(this::toDto).toList());
    }

    /** 住院工作区：医嘱 + 押金 + 费用汇总 */
    @GetMapping("/admissions/{id}/workspace")
    public R<Map<String, Object>> workspace(@PathVariable Long id) {
        var m = new LinkedHashMap<String, Object>();
        admissionRepo.findById(id).ifPresent(a -> m.put("admission", toDto(a)));
        List<InpOrder> orders = orderRepo.findByAdmissionIdOrderByIdAsc(id);
        List<InpDeposit> deposits = depositRepo.findByAdmissionIdOrderByIdAsc(id);
        m.put("orders", orders);
        m.put("deposits", deposits);
        m.put("totalAmount", orders.stream().filter(o -> !"CANCELLED".equals(o.getStatus()))
                .map(InpOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.put("depositAmount", deposits.stream().map(InpDeposit::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        return R.ok(m);
    }

    public record DepositRequest(BigDecimal amount, String payMethod) {}

    @PostMapping("/admissions/{id}/deposits")
    public R<Object> addDeposit(@PathVariable Long id, @RequestBody DepositRequest req, Authentication auth) {
        try {
            return R.ok(inpatientService.addDeposit(id, req.amount(), req.payMethod(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record TransferRequest(Long toDeptId, Long toBedId) {}

    @PostMapping("/admissions/{id}/transfer")
    public R<Object> transfer(@PathVariable Long id, @RequestBody TransferRequest req, Authentication auth) {
        try {
            var adm = inpatientService.transfer(id, req.toDeptId(), req.toBedId(), currentUserService.idOf(auth));
            return R.ok(Map.of("admissionNo", adm.getAdmissionNo(),
                    "deptId", adm.getDeptId(), "wardId", adm.getWardId(), "bedId", adm.getBedId()));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    public record CreateOrdersRequest(List<InpatientService.OrderLine> lines) {}

    @PostMapping("/admissions/{id}/orders")
    public R<Object> createOrders(@PathVariable Long id, @RequestBody CreateOrdersRequest req, Authentication auth) {
        try {
            return R.ok(inpatientService.createOrders(id, req.lines(), currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    /** 护士执行队列（全院未执行医嘱） */
    @GetMapping("/orders/pending")
    public R<List<Map<String, Object>>> pendingOrders() {
        return R.ok(orderRepo.findByStatusOrderByIdAsc("CREATED").stream().map(o -> {
            var m = new LinkedHashMap<String, Object>();
            m.put("orderId", o.getId());
            m.put("groupNo", o.getGroupNo());
            m.put("orderType", o.getOrderType());
            m.put("itemName", o.getItemName());
            m.put("qty", o.getQty());
            m.put("usageRoute", o.getUsageRoute());
            m.put("frequency", o.getFrequency());
            m.put("dosePerTime", o.getDosePerTime());
            admissionRepo.findById(o.getAdmissionId()).ifPresent(a -> {
                m.put("admissionNo", a.getAdmissionNo());
                patientRepository.findById(a.getPatientId()).ifPresent(p -> m.put("patientName", p.getName()));
            });
            return (Map<String, Object>) m;
        }).toList());
    }

    @PutMapping("/orders/{orderId}/execute")
    public R<Object> execute(@PathVariable Long orderId, Authentication auth) {
        try {
            return R.ok(inpatientService.execute(orderId, currentUserService.idOf(auth)));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    @PostMapping("/admissions/{id}/discharge")
    public R<Object> discharge(@PathVariable Long id,
                               @RequestParam(required = false, defaultValue = "CASH") String payMethod,
                               Authentication auth) {
        try {
            return R.ok(inpatientService.discharge(id, currentUserService.idOf(auth), payMethod));
        } catch (InpException e) {
            return R.fail(e.code, e.getMessage());
        }
    }

    private Map<String, Object> toDto(InpAdmission a) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", a.getId());
        m.put("admissionNo", a.getAdmissionNo());
        m.put("patientId", a.getPatientId());
        patientRepository.findById(a.getPatientId()).ifPresent(p -> {
            m.put("patientNo", p.getPatientNo());
            m.put("patientName", p.getName());
            m.put("sex", p.getSex());
        });
        m.put("deptName", deptRepository.findById(a.getDeptId()).map(d -> d.getName()).orElse(""));
        m.put("wardName", deptRepository.findById(a.getWardId()).map(d -> d.getName()).orElse(""));
        m.put("bedId", a.getBedId());
        bedRepo.findById(a.getBedId()).ifPresent(b -> m.put("bedNo", b.getBedNo()));
        m.put("admitDiagName", a.getAdmitDiagName());
        m.put("status", a.getStatus());
        m.put("admitAt", a.getAdmitAt());
        return m;
    }
}
