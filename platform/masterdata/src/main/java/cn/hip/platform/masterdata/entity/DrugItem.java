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

    /** 是否抗菌药物（分级管理用） */
    @Column(nullable = false)
    private Boolean antibiotic = false;

    @Column(nullable = false)
    private Boolean enabled = true;
}
