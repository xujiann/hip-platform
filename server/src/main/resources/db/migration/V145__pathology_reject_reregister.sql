-- v48 补丁：拒收后必须能重新登记同一部位。
--
-- 【V144 的缺陷】部分唯一索引写成 `(order_id, part_no) where order_id is not null`，
-- **不排除已拒收的行**。而 v48 的拒收是「不删记录、不改 status」——被拒的行永远占着
-- (order_id, part_no) 这个坑位。后果：标本因固定不规范被拒收、临床重新送检时，
-- **同一部位再也登记不进去**，只能被迫用 part_no=2 顶替，从此部位序号与真实部位对不上，
-- 而部位序号正是蜡块编码 `病理号-块号` 与报告上「3 号蜡块」的来源。
--
-- 拒收后重送是**临床常态**（固定液不对、离体过久、标本量不足都会拒），
-- 这条不通等于「拒收即死」——这个功能反而变成了给自己下的绊子。
--
-- 【修法】唯一性只约束**未拒收**的行：已拒收的行退出唯一性竞争，但**仍留在表里**
-- （拒收不删记录的口径不变，送检总数与拒收率的分母照旧完整）。

drop index if exists uq_path_specimen_outp_part;
drop index if exists uq_path_specimen_inp_part;

create unique index uq_path_specimen_outp_part on path_specimen (order_id, part_no)
    where order_id is not null and rejected_at is null;
create unique index uq_path_specimen_inp_part on path_specimen (inp_order_id, part_no)
    where inp_order_id is not null and rejected_at is null;

comment on index uq_path_specimen_outp_part is
    'v48：同一门诊申请下部位序号唯一——**只约束未拒收的行**，拒收后可用同一 part_no 重新登记';
comment on index uq_path_specimen_inp_part is
    'v48：同一住院医嘱下部位序号唯一——**只约束未拒收的行**，同上';
