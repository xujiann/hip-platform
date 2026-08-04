package cn.hip.inpatient.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 病区床位 */
@Getter
@Setter
@Entity
@Table(name = "inp_bed")
public class InpBed {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 病区（sys_dept，type=NURSING） */
    @Column(nullable = false)
    private Long wardId;

    @Column(nullable = false, length = 16)
    private String bedNo;

    /** FREE 空床 / OCCUPIED 占用 */
    @Column(nullable = false, length = 16)
    private String status = "FREE";

    /** 当前占用的住院 id */
    private Long admissionId;
}
