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

    @Column(nullable = false)
    private Boolean enabled = true;
}
