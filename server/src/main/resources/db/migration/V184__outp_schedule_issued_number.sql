-- Capacity is reusable; a committed receipt number is not. Preserve all historical receipts.
alter table outp_schedule add column last_issued_no integer not null default 0
    check (last_issued_no >= 0);

with historical as (
    select schedule_id, max(number) as number
    from (
        select schedule_id, reg_no as number from outp_registration
        union all
        select schedule_id, appt_no as number from outp_appointment
    ) receipts
    group by schedule_id
)
update outp_schedule s
set last_issued_no = greatest(0, s.booked, coalesce(h.number, 0))
from (select s0.id, h0.number from outp_schedule s0
      left join historical h0 on h0.schedule_id = s0.id) h
where h.id = s.id;

comment on column outp_schedule.last_issued_no is
    'Last committed schedule-wide ticket number; shared walk-in/appointment cursor, never reduced on cancellation. Internal service field.';
