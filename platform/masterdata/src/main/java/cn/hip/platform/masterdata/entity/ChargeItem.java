package cn.hip.platform.masterdata.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 诊疗/收费项目：检验、检查、治疗、材料等（药品单独在 md_drug） */
@Getter
@Setter
@Entity
@Table(name = "md_charge_item")
public class ChargeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** LAB 检验 / EXAM 检查 / TREAT 治疗 / MATERIAL 材料 */
    @Column(nullable = false, length = 16)
    private String category;

    @Column(nullable = false, length = 8)
    private String unit = "次";

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 执行科室（检查检验的执行地点），可空 */
    private Long execDeptId;

    /**
     * v42 费用类别（md_fee_category.code），可空——未挂类的项目在费用分类报表里显式归入"未分类"行。
     * 与上面的 category 并存而非取代：category 是院内业务分流（决定医嘱走检验/检查/治疗哪条执行线），
     * fee_category_code 是财务口径分类（决定金额进哪个费用类别）。两者语义不同、粒度不同，
     * 强行合一会让"静脉输液(TREAT)"这类既是治疗执行又要按治疗费归集的项目失去其中一维。
     */
    @Column(length = 32)
    private String feeCategoryCode;

    /**
     * v42 补齐 V116 孤儿列：自费项目标记。
     * V116 建了该列但实体无字段、CSV 导入不写、前端无维护入口 → 真实数据恒为 false，
     * 唯一消费方 InpatientService.isSelfPayItem 永远返回 false，
     * 使 gate `emr.gate.consent.selfpay` 即使调成 block 也拦不住任何东西。
     */
    @Column(nullable = false)
    private Boolean selfPay = false;

    @Column(nullable = false)
    private Boolean enabled = true;
}
