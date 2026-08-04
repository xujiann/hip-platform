package cn.hip.platform.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

/** 菜单/功能点：type=DIR 目录、MENU 页面、BUTTON 按钮权限 */
@Getter
@Setter
@Entity
@Table(name = "sys_menu")
public class SysMenu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long parentId;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(nullable = false, length = 16)
    private String type;

    /** 前端路由路径，如 /outpatient/register */
    @Column(length = 128)
    private String path;

    /** 权限标识，如 outp:register:create */
    @Column(length = 64)
    private String perm;

    @Column(length = 64)
    private String icon;

    private Integer sortNo = 0;

    @Column(nullable = false)
    private Boolean enabled = true;
}
