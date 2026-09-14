# Automatic administrative visit closure

`Close Stale Visits Task` calls `AdtService.closeInactiveVisits()` every configured
scheduler interval. A closed visit causes Queue's native save handler to end its
active entries. This requires neither a host cron job nor an external script.

## Timestamp policy

The legacy policy uses the last non-voided encounter time, or the visit start
when there are no encounters. An outpatient queue may start after that time.
Queue correctly rejects closing such an entry before or at its start. The
transaction protection in `3.5.1-sihsalus.1` rolls back the entire batch but does
not make that timestamp compatible.

`3.5.1-sihsalus.2` adds the opt-in global property
`emrapi.useCurrentTimeForAutomaticVisitClosure`:

| Value | Automatic visit stop time |
| --- | --- |
| absent, blank or `false` (default) | Existing last-encounter/start policy |
| `true` | Server time when the automatic closure executes, truncated to seconds |
| any other value | Fail before modifying visits |

The new timestamp records an administrative closure. It does not assert a
clinical discharge or alter encounter times, queue start times, dispositions,
statuses or already-ended historical queue entries. Manual `closeAndSaveVisit`
retains its existing policy. Installing the module does not start any task.

## Eligibility and validation

- `emrapi.visitExpireHours` still controls the number of inactive hours. The
  hospital's existing value is 24; the module's upstream default remains 12.
- Activity means the latest non-voided encounter datetime or visit start time.
  Editing observations or changing a queue status does not restart that clock.
- Visits are limited to locations tagged `Visit Location`.
- Admissions, pending admission and dispositions that keep a visit open retain
  their existing protection when `emrapi.inpatientVisitExpireHours` is unset.
- Future activity is never treated as elapsed inactivity.
- Queue and visit validators remain enabled. Invalid data still rolls back the
  complete batch. A future/same-second queue start is not silently rewritten.
- The change does not repair historical records or provide concurrency locking
  against a clinician adding activity while the task is evaluating a visit.

## Verification

The normal reactor covers eligibility, manual closure, explicit configuration,
future activity, and rollback after a partial batch flush. The
`queue-compatibility` Maven profile adds actual OpenMRS services, Hibernate/H2,
Queue validators and the visit save handler. It reproduces the legacy failure
and verifies the new stop time, historical entry preservation, active-entry
filtering, recent-visit protection and repeated execution.

The workflow builds Queue's pinned source
`openmrs/openmrs-module-queue@fb6de2acbf31b4dd50e518f74badf28163d6e67d`
as `queue-api:3.1.0-sihsalus.1` for test dependency resolution. This is a test-only
dependency and is not packaged inside EMR API. Then run:

```sh
mvn -B -ntp -Dformatter.skip=true -Dspotless.skip=true -Dmaven.javadoc.skip=true \
  -DopenmrsPlatformVersion=2.8.9 -Pqueue-compatibility clean install
python3 tools/verify_omod.py omod/target/emrapi-3.5.1-sihsalus.2.omod
```

## Activation

Before activation, confirm the administrative timestamp policy and whether the
intended operation closes visits as well as queues. Complete synthetic QLTY
acceptance with the exact release, and take a current hospital backup. Review
queue/visit timestamp consistency and activity using aggregate diagnostics.

Use the existing OpenMRS task and global-property administration interfaces.
Set the new property to `true`, retain the approved inactivity threshold, and
schedule only `org.openmrs.module.emrapi.adt.CloseStaleVisitsTask`. Persist
`startOnStartup=true` and retain the existing 3600-second interval. Verify its
in-memory scheduled state, a completed execution, and active queue counts.

If a batch fails, stop that task and investigate its validation error. Do not
disable validators or alter original timestamps to make the batch pass. Rolling
back code also requires pausing the task before removing the opt-in property.
