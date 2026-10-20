package cn.hip.outpatient.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/** The staffed appointment desk is shared by enabled administrators and cashiers. */
@Service @RequiredArgsConstructor
public class AppointmentAccess {
 private final JdbcTemplate jdbc;
 public boolean staff() {
  var auth=SecurityContextHolder.getContext().getAuthentication();
  if(auth==null) throw denied();
  var roles=auth.getAuthorities().stream().map(x->x.getAuthority()).toList();
  boolean admin=roles.contains("ROLE_ADMIN");
  if((!admin&&!roles.contains("ROLE_CASHIER")) || jdbc.queryForList("select id from sys_user where username=? and enabled=true",Long.class,auth.getName()).isEmpty())throw denied();
  return admin;
 }
 public void admin(){if(!staff())throw denied();}
 private AccessDeniedException denied(){return new AccessDeniedException("无预约挂号台操作权限或操作人已停用");}
}
