# Automatic administrative visit closure

This is an unreleased correction under review. Keep automatic closure paused;
this PR does not change tasks, inactivity thresholds or deployment configuration.
The published `3.5.1-sihsalus.2` release still contains the previous timestamp
policy described in [the release notes](../SIHSALUS-RELEASE.md).

## Timestamp policy

`CloseStaleVisitsTask` calls `AdtService.closeInactiveVisits()`. Previously that
method backdated the visit stop to its latest non-voided encounter, or its start
when no encounter remained. A queue entry can start later, so Queue correctly
rejects the resulting end before or at the entry's start. The existing
transaction protection rolls back the batch but cannot make that timestamp valid.

The correction records the server time of automatic administrative closure,
truncated to the database's second precision. It records when the operation
happened, without asserting a clinical discharge. Queue's existing save handler
uses that visit stop time to end its open entries. No opt-in property, replacement
scheduler or external repair script is introduced.

Manual `closeAndSaveVisit` keeps its existing timestamp and validation policy.
Encounter dates, queue starts, dispositions and already-ended historical entries
are not rewritten. A future or same-second queue start still fails validation and
rolls back the whole batch; the task does not fabricate a future stop time.

## Eligibility and the meaning of 24 hours

- `emrapi.visitExpireHours` controls outpatient inactivity; its native default
  is **12 hours**, not 24. This PR does not change that value.
- Activity is the later of the visit start and its non-voided encounter dates.
  Editing an observation or changing queue status does not restart that clock.
- A future activity timestamp is not elapsed inactivity.
- Visits are selected from locations tagged `Visit Location`.
- Admissions, pending admissions and dispositions that keep a visit open retain
  protection when `emrapi.inpatientVisitExpireHours` is absent.
- The scheduler interval determines how often the task checks eligibility. An
  interval of 86,400 seconds is not a 24-hour inactivity rule.

Core's `org.openmrs.scheduler.tasks.AutoCloseVisitsTask` is a different task:
it uses `visits.autoCloseVisitType` and must not be substituted for EMR API's
inactivity calculation. The QLTY read-only review on 2026-10-11 found the Core
task paused (`started=false`, `startOnStartup=false`, interval 86,400 seconds),
its visit-type property empty, and the EMR API task and both inactivity
properties absent. The review made no scheduler or property changes.

## Verification

Unit regressions cover the administrative timestamp, encounter selection,
unchanged manual closure, future activity and recent visits. Spring/H2 transaction
tests write visit and queue rows before rejecting a timestamp, then verify that
earlier writes in the same batch roll back and later visits are not processed.

The `queue-compatibility` Maven profile uses real OpenMRS services, Queue save
handlers and validators with Hibernate/H2. It verifies persisted visit and queue
end times, preservation of historical entries, recent visits, repeated execution,
manual validation and rejection of a future queue start.

CI builds the pinned Queue source
`openmrs/openmrs-module-queue@fb6de2acbf31b4dd50e518f74badf28163d6e67d`
as `queue-api:3.1.0-sihsalus.1` for test dependency resolution. Queue is test-only
and is not packaged in EMR API. Run the reactor on Java 21 with both Core baselines:

```sh
mvn -B -ntp -Dformatter.skip=true -Dspotless.skip=true -Dmaven.javadoc.skip=true \
  -DopenmrsPlatformVersion=2.8.9 -Pqueue-compatibility clean install
python3 tools/verify_omod.py omod/target/emrapi-3.5.1-sihsalus.2.omod
```

Repeat with `-DopenmrsPlatformVersion=2.8.0`. The development build retains the
current POM version; it must not replace the existing immutable release assets.
A separately reviewed release needs a new version.

## Remaining acceptance

Keep the task paused. Runtime acceptance in DEV and QLTY is still required with
the exact future release and synthetic visits/queues before any separately
authorized activation. Agree on the outpatient threshold and what activity means
with the responsible team; this PR does not approve a 24-hour clinical policy.

The batch remains atomic: one incompatible visit aborts all its changes. The
correction does not add concurrency locking against a clinician adding activity
while the task evaluates a visit. Do not disable validators or change original
timestamps to make a batch pass.
