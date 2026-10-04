package cn.hip.platform.masterdata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * v77 车道 A：检验流向规则（V174 {@code lab_route_rule}，偏离表 1016★）。
 *
 * <p>一条规则 = 「满足这些键的检验申请 → 去这个执行科室」。键三元组
 * {@code chargeItemId} / {@code specimenType} / {@code orderDeptId} 全部可空，空即通配；
 * 匹配口径（具体度 → priority → id）见 {@link cn.hip.platform.masterdata.service.LabRouteService}。
 *
 * <p>{@code specimenType} 落库的是<b>规范化值</b>（去首尾与内部空白、大写），由 service 统一处理，
 * 实体不做转换——实体只是列的映射，口径只在一处。
 */
@Getter
@Setter
@Entity
@Table(name = "lab_route_rule")
public class LabRouteRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String name;

    /** 本轮恒为 LAB（EXAM/TREAT 不参与分流，列先建好不另起表） */
    @Column(nullable = false, length = 16)
    private String itemCategory = "LAB";

    /** 收费项目键，可空 = 任意检验项目；非空时必须是 category=LAB 的项目 */
    private Long chargeItemId;

    /** 标本类型键（规范化值），可空 = 任意标本 */
    @Column(length = 32)
    private String specimenType;

    /** 开单科室键（挂号 dept_id），可空 = 任意科室 */
    private Long orderDeptId;

    /** 目标执行科室，必填 */
    @Column(nullable = false)
    private Long execDeptId;

    /** 同具体度内的先后：小者先，缺省 100 */
    @Column(nullable = false)
    private Integer priority = 100;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(length = 255)
    private String remark;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Column(nullable = false)
    private Instant updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
}
