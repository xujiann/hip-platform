package cn.hip.bureau.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 案由字典（苏医保督〔2024〕1号：4 类主体 / 9 种案由 / 35 项违法情形） */
@Getter
@Setter
@Entity
@Table(name = "case_cause")
public class CaseCause {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** AGENCY 经办机构 / PROVIDER 定点医药机构 / INDIVIDUAL 自然人 / OTHER 其他主体 */
    @Column(nullable = false, length = 16)
    private String subjectType;

    /** 案由种类（案件办理适用的二级案由） */
    @Column(nullable = false, length = 64)
    private String category;

    /** 违法情形序号 */
    @Column(nullable = false, unique = true)
    private Integer itemNo;

    /** 违法情形 */
    @Column(nullable = false, length = 512)
    private String description;

    @Column(nullable = false)
    private Boolean enabled = true;
}
