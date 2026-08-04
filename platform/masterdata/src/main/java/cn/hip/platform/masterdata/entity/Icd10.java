package cn.hip.platform.masterdata.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** ICD-10 诊断字典（简表；全量国临版后续导入） */
@Getter
@Setter
@Entity
@Table(name = "md_icd10")
public class Icd10 {

    @Id
    @Column(length = 16)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 拼音首字母，检索用 */
    @Column(length = 32)
    private String pinyin;
}
