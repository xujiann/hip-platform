package cn.hip.platform.core.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/** 系统用户（医护/管理人员账号） */
@Getter
@Setter
@Entity
@Table(name = "sys_user")
public class SysUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String username;

    /** BCrypt 密文 */
    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 64)
    private String realName;

    /** 职称/岗位：医师、护士、药师、技师、管理员… */
    @Column(length = 32)
    private String title;

    private Long deptId;

    @Column(length = 32)
    private String phone;

    @Column(nullable = false)
    private Boolean enabled = true;

    /** 连续登录失败次数（防爆破，成功登录清零） */
    @Column(nullable = false)
    private Integer failedAttempts = 0;

    /** 锁定截止时间 */
    private Instant lockedUntil;

    /** 密码最近修改时间（等保：定期更换口令提醒） */
    @Column(nullable = false)
    private Instant passwordUpdatedAt = Instant.now();

    @Column(nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "sys_user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<SysRole> roles = new LinkedHashSet<>();
}
