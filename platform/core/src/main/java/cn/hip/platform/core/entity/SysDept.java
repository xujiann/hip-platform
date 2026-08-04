package cn.hip.platform.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/** 科室/部门 */
@Getter
@Setter
@Entity
@Table(name = "sys_dept")
public class SysDept {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 上级部门，顶级为 null */
    private Long parentId;

    @Column(nullable = false, length = 64)
    private String name;

    /** 科室编码（院内唯一） */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    /** 类型：CLINICAL 临床 / MEDTECH 医技 / ADMIN 行政 / NURSING 护理单元 */
    @Column(nullable = false, length = 16)
    private String type;

    private Integer sortNo = 0;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();
}
