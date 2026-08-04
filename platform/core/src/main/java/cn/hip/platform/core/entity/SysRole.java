package cn.hip.platform.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/** 角色（权限的集合），如：系统管理员、门诊医生、收费员 */
@Getter
@Setter
@Entity
@Table(name = "sys_role")
public class SysRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String name;

    /** 角色编码，代码中引用，如 ADMIN / DOCTOR_OUTP / CASHIER */
    @Column(nullable = false, unique = true, length = 32)
    private String code;

    @Column(length = 255)
    private String remark;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_role_menu",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "menu_id"))
    private Set<SysMenu> menus = new LinkedHashSet<>();
}
