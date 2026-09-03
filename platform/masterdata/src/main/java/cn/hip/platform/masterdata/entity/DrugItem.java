package cn.hip.platform.masterdata.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 药品目录（含门诊药房库存，库存细化到库房/批次留待药库模块） */
@Getter
@Setter
@Entity
@Table(name = "md_drug")
public class DrugItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 规格，如 0.25g*24粒/盒 */
    @Column(length = 64)
    private String spec;

    /** 销售单位：盒/瓶/支 */
    @Column(nullable = false, length = 8)
    private String unit;

    /** 剂型：片剂/胶囊/口服液/注射剂 */
    @Column(length = 16)
    private String doseForm;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 门诊药房当前库存（销售单位） */
    @Column(nullable = false)
    private Integer stock = 0;

    /** 抗菌药物分级：0 非管控 / 1 非限制 / 2 限制 / 3 特殊 */
    @Column(nullable = false)
    private Short abxLevel = (short) 0;

    /** 药品分类：W 西药 / C 中成药 */
    @Column(nullable = false, length = 2)
    private String drugClass = "W";

    /** 每销售单位所含 DDD 数（抗菌药使用强度折算用） */
    @Column(precision = 8, scale = 2)
    private BigDecimal dddPerUnit;

    /** 是否抗菌药物（分级管理用） */
    @Column(nullable = false)
    private Boolean antibiotic = false;

    /**
     * v42 费用类别（md_fee_category.code），可空。
     * V132 已按 drug_class 回填（W→西药费 WM / C→中成药费 CPM）；中草药本仓无单独 drug_class 值，
     * 不臆造映射，由实施期在字典维护页挂类。
     */
    @Column(length = 32)
    private String feeCategoryCode;

    /** v42 补齐 V116 孤儿列：自费药品标记（同 ChargeItem.selfPay，见其注释） */
    @Column(nullable = false)
    private Boolean selfPay = false;

    @Column(nullable = false)
    private Boolean enabled = true;

    /**
     * v43 停用留痕（V134）。三列全部可空：<b>历史停用行必然为 null</b>——本版之前没有任何
     * 启停入口，实施期若有人直接 update md_drug set enabled=false，留痕无从追溯，
     * 严禁用当前时间/任意管理员回填伪造。启用时三列一并清空（见 MasterDataController.enableDrug）。
     */
    @Column(length = 200)
    private String disableReason;

    @Column
    private java.time.Instant disabledAt;

    /** 停用操作人 sys_user.id（不存用户名，v42 已就人字段立规） */
    @Column
    private Long disabledBy;
}
