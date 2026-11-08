-- v168: retain the exact bytes of explicitly generated INP historical-version previews.
-- No historical backfill, clinical mutation, signing or archival state transition.
create table emr_ofd_document (
    document_id       varchar(32) primary key,
    emr_id            bigint not null,
    version_no        integer not null,
    source_version_id bigint not null references emr_version(id),
    source_hash       varchar(64) not null,
    admission_id      bigint not null references inp_admission(id),
    patient_id        bigint not null references empi_patient(id),
    generated_by      bigint not null references sys_user(id),
    generated_at      timestamptz not null,
    status            varchar(24) not null,
    byte_length       integer not null,
    sha256            varchar(64) not null,
    payload           bytea not null,
    constraint chk_emr_ofd_document_id check (document_id ~ '^[0-9a-f]{32}$'),
    constraint chk_emr_ofd_document_target check (emr_id > 0 and version_no > 0),
    constraint chk_emr_ofd_document_source_hash check (source_hash ~ '^[0-9a-f]{64}$'),
    constraint chk_emr_ofd_document_sha256 check (sha256 ~ '^[0-9a-f]{64}$'),
    constraint chk_emr_ofd_document_status check (status = 'UNSIGNED_PREVIEW'),
    constraint chk_emr_ofd_document_size check (byte_length > 0 and byte_length <= 33554432),
    constraint chk_emr_ofd_document_length check (octet_length(payload) = byte_length)
);

create index idx_emr_ofd_document_version
    on emr_ofd_document (emr_id, version_no, generated_at desc, document_id desc);

-- Application reads never need to rewrite a retained document. These guards are not a claim
-- of protection against a database owner/superuser who can disable triggers or alter the table.
create function reject_emr_ofd_document_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Retained OFD preview documents cannot be updated or deleted'
        using errcode = '55000';
end;
$$;

create trigger trg_emr_ofd_document_immutable
    before update or delete on emr_ofd_document
    for each row execute function reject_emr_ofd_document_mutation();

comment on table emr_ofd_document is
    'Exact generated UNSIGNED_PREVIEW bytes and original source/context; no formal archival claim';
comment on column emr_ofd_document.generated_at is
    'Generation/source-context capture time, also embedded in the original OFD metadata';
comment on column emr_ofd_document.payload is
    'Original complete OFD file; never regenerated for download; PostgreSQL TOAST/backup handles storage';
