package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 住院登记（一次住院） */
@Getter
@Setter
@Entity
@Table(name = "inp_admission")
public class InpAdmission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 住院号，如 ZY20260804-000001 */
    @Column(nullable = false, unique = true, length = 32)
    private String admissionNo;

    @Column(nullable = false)
    private Long patientId;

    /** 收治科室（临床科室） */
    @Column(nullable = false)
    private Long deptId;

    @Column(nullable = false)
    private Long wardId;

    @Column(nullable = false)
    private Long bedId;

    /** 主管医生 */
    private Long doctorId;

    @Column(length = 16)
    private String admitDiagIcd;

    @Column(length = 128)
    private String admitDiagName;

    /** 1.0.4：出院诊断（病案编码；DRG 入组优先取此字段） */
    private String dischargeDiagIcd;

    private String dischargeDiagName;

    /** IN_HOSPITAL 在院 / DISCHARGED 已出院 */
    @Column(nullable = false, length = 16)
    private String status = "IN_HOSPITAL";

    @Column(nullable = false, updatable = false)
    /**
     * <b>截断到微秒</b>：{@code Instant.now()} 在本平台是 100ns 粒度，
     * 而 PG {@code timestamptz} 只存到微秒且<b>四舍五入</b>——尾数 ≥500ns 会向上进位，
     * 于是入库后的入院时刻可能比真实时刻<b>晚最多 500ns</b>。
     * 随后录入的体征拿 {@code Instant.now()} 与它比，就会出现
     * 「测量时间 …402559900Z 早于入院时间 …402560Z」这种差 100ns 的假越界，
     * 一条刚录入的正常护理记录撞 4825。
     *
     * <p>这是同一根因在本仓的第五处发作（前四处：v47 手术时间点、v50 体征 measuredAt、
     * 病理接收/拒收时刻、本处）。<b>全部实体的 {@code = Instant.now()} 字段初始化已一次扫净</b>——
     * 修完一处必须全仓扫同构，否则等于把同样的雷换个地方埋。
     */
    private Instant admitAt = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);

    private Instant dischargedAt;
}
