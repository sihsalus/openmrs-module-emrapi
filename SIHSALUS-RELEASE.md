# SIHSalus EMR API 3.5.1-sihsalus.2

This candidate adds an opt-in administrative timestamp for automatic closure of
inactive visits. Queue entries that start after the last encounter can then be
ended through the normal OpenMRS visit save handler. The earlier
`3.5.1-sihsalus.1` release protected transaction rollback but still calculated an
incompatible historical stop time.

## Scope

- Set `emrapi.useCurrentTimeForAutomaticVisitClosure=true` to use server time,
  at second precision, when the automatic administrative closure executes.
- The default remains `false`. Installing this version does not activate a task.
- Manual closure retains its existing last-encounter/start policy.
- Inactivity thresholds, visit-location selection and inpatient protections are
  preserved. Future activity is excluded from inactivity calculations.
- Original encounters, queue start times and previously-ended entries are not
  rewritten. Invalid visits/queues still roll back the complete batch.
- No runtime dependency on Queue or external scheduling scripts is added.

See [timestamp policy, verification and activation](docs/automatic-visit-closure.md).
This does not fix historical corruption or concurrent changes by a clinician
while the existing task is evaluating a visit.

## Verification and activation

The release workflow must pass the full reactor and the `queue-compatibility`
profile on Java 21 with Core 2.8.0 and 2.8.9. Compatibility tests use Queue's
pinned deployed source, actual OpenMRS services, its validators and save handler,
and Hibernate/H2 with synthetic fixtures. The published OMOD is the tested Core
2.8.9 binary, with checksum and GitHub build attestation.

Clinical timestamp-policy acceptance, synthetic DEV/QLTY acceptance with the
exact release and a current backup remain prerequisites for hospital activation.
Keep the existing task paused until those steps are complete. Publishing a
prerelease does not deploy it or activate a scheduler task. Production records
must never be copied into this repository or test fixtures.

Verify a downloaded OMOD with `gh attestation verify FILE --repo
sihsalus/openmrs-module-emrapi`, and compare SHA-256 with the distribution pin.
