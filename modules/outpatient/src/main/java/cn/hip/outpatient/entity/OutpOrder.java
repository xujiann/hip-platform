package cn.hip.outpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 门诊医嘱/费用明细：处方药品、检验、检查、治疗统一为订单行，
 * 是收费、发药、执行的共同数据源（费用不落两处账）。
 */
@Getter
@Setter
@Entity
@Table(name = "outp_order")
public class OutpOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long registrationId;

    /** 处方号（同一次开立的一组药品共号），非药品为申请单号 */
    @Column(nullable = false, length = 32)
    private String groupNo;

    /** DRUG 药品 / LAB 检验 / EXAM 检查 / TREAT 治疗 */
    @Column(nullable = false, length = 8)
    private String orderType;

    /** 药品 id 或收费项目 id */
    @Column(nullable = false)
    private Long itemId;

    @Column(nullable = false, length = 32)
    private String itemCode;

    @Column(nullable = false, length = 128)
    private String itemName;

    @Column(length = 64)
    private String spec;

    @Column(nullable = false, length = 8)
    private String unit;

    @Column(nullable = false)
    private Integer qty;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    /** 用法（口服/静滴…），药品用 */
    @Column(length = 32)
    private String usageRoute;

    /** 频次（bid/tid/qd…），药品用 */
    @Column(length = 16)
    private String frequency;

    /** 单次剂量描述，如 1粒 */
    @Column(length = 32)
    private String dosePerTime;

    /** 用药天数 */
    private Integer days;

    /** CREATED 已开立 / CHARGED 已收费 / DISPENSED 已发药 / EXECUTED 已执行 / CANCELLED 已作废 */
    @Column(nullable = false, length = 16)
    private String status = "CREATED";

    /** 结算单 id，收费后回填 */
    private Long chargeId;

    /** 审方：APPROVED 通过 / REJECTED 拒绝（拒绝即作废）；null 未审 */
    @Column(length = 16)
    private String reviewStatus;

    @Column(length = 255)
    private String reviewNote;

    private Long reviewerId;

    private Long doctorId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    // ==================== v44 车道G：医嘱附属字段（V137，偏离表 1006★/1013★/1014★/1016★） ====================
    // 七列全部可空、无 CHECK、无索引，历史医嘱行必然为 null（迁移头注释写明为何不回填）。
    // 适用范围由**界面**决定（哪类医嘱显示哪几栏），不由数据库约束焊死——真实院内会出现
    // EXAM 类要送检标本、TREAT 类要写注意事项的情况。

    /** 医嘱备注（1006★「所有医嘱均有备注功能」）。全类型医嘱通用，如"饭后半小时服""患者自备药已带"。 */
    @Column(length = 200)
    private String remark;

    /**
     * 加急标志（1013★ 检验申请单加急）。默认 false；V137 的 default 已把历史行一并置 false
     * ——"未标加急"是历史行的真实状态，不是伪造。
     */
    private Boolean urgent = false;

    /**
     * 临床/病情摘要（1014★ 检查申请单临床摘要、1016★ 检验申请自动获取病情摘要）。
     * 语义是**开单当时的一份快照**：由界面从本次门诊病历（主诉/现病史）自动带入后医生可改写，
     * 落在医嘱行上而不是每次打印时现查病历——病历此后还会被续写/修改，
     * 而申请单该印的是"申请这一刻医生给医技科室交代的病情"，两者不能混为一谈。
     */
    @Column(length = 500)
    private String clinicalSummary;

    /** 检查目的（1014★），如"排除肺部占位""术前评估"。 */
    @Column(length = 200)
    private String examPurpose;

    /** 注意事项（1014★），给医技科室与患者看，如"空腹""检查前排空膀胱"。 */
    @Column(length = 200)
    private String notice;

    /** 标本类型（1016★），如 血液/尿液/痰/分泌物。 */
    @Column(length = 32)
    private String specimenType;

    /** 采样部位（1016★），如 肘正中静脉/指尖/咽拭子。 */
    @Column(length = 32)
    private String samplingSite;

    /**
     * 库存预警（阻塞6，非持久化）：开药嘱时若药品当前库存低于本次开量则置为当前库存值，
     * 否则为 null。开单不因此拦截（医生可能有临时进药安排），仅在返回结果里带出，
     * 让医生当场知情、换药或安排进药——把"缴费后到药房才撞 6002 只能退费重开"的坑
     * 提前到开单环节。仅药品(orderType=DRUG)可能非空。
     */
    @Transient
    private Integer stockWarnAvailable;
}
