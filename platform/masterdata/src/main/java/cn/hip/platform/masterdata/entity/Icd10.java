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

    /**
     * v76 停用标记（V172）。只为让检索 JPQL 能写 {@code and i.enabled}；
     * {@code @JsonIgnore}：{@code GET /masterdata/icd10} 直接序列化本实体，返回体键名必须保持
     * code/name/pinyin 三个不变（医生站、入院登记等既有消费方）。维护页走 IcdDictController，
     * 那里才返回 enabled。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(nullable = false)
    private boolean enabled = true;
}
