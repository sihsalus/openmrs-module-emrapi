# SIHSalus EMR API 3.5.1-sihsalus.2

**Do not reactivate automatic visit closure based on this release.**
This is not an official OpenMRS 3.5.1 release or a complete fix for closure timestamps.

## Source and scope

Upstream base: `openmrs/openmrs-module-emrapi@a06a2efd651435609a1c4b39ef35501b3401ff5d`
(upstream 3.5.0-SNAPSHOT source, previously pinned in SIHSalus).

This immutable revision includes the native ADT fix merged in
[module PR #2](https://github.com/sihsalus/openmrs-module-emrapi/pull/2) at
`5d3f76e0bffdf4a636733d2a2cf86235543b88bf`:

- Return a mutable empty admission list after the existing query and filters when no admission remains.
- Avoid resolving disposition metadata for requests that cannot attach to an admission.
- Preserve current request hydration and missing-metadata errors when an admission does remain.

This removes an unnecessary inpatient metadata dependency from outpatient mother–child
lookups. It preserves relationship matching, eligibility flags and the admission response
contract. It does not configure missing inpatient disposition concepts or replace a
mother–child relationship with pregnancy, delivery or newborn clinical records.

The automatic-visit-closure containment shipped in `3.5.1-sihsalus.1`, previously proposed
in [distribution PR #305](https://github.com/sihsalus/sihsalus/pull/305), is retained:

- Make `closeInactiveVisits` transactional.
- Propagate runtime save/validation failures through the outer Spring proxy.
- Roll back the whole batch on the first failure, including earlier visit/queue writes.

This is deliberately not per-visit isolation: one incompatible visit still aborts
the batch. It does not change guessed stop dates, Queue validation, historical
clinical rows or scheduler configuration. Callers must not swallow the failure
inside their own transaction and then commit.

## Evidence and remaining acceptance

Six ADT regressions use real Spring, Hibernate and H2 to cover no admissions, all
admissions filtered out, current request hydration, errors with a real admission,
and outpatient mother–child lookup in both directions. Fixture birthdates are fixed
before their visit dates. Existing ADT and maternal suites remain part of the full reactor.

The closure regression uses the real Spring annotation transaction interceptor, H2 and a
VisitService double that writes synthetic visit/queue rows before rejecting an
end date. It covers end before/equal to start, earlier-write rollback, stopping
before later visits, valid batches and empty batches. These tests do not replace
real OpenMRS + Queue/Hibernate integration or clinical acceptance.

CI must pass the complete reactor on Java 21 with Core 2.8.0 and Core 2.8.9.
The published OMOD is built and tested against Core 2.8.9. Its descriptor, nested
API version and compiled containment markers are checked before release.
The workflow publishes a SHA-256 checksum and GitHub build attestation.
This immutable prerelease is for controlled validation, not automatic promotion;
it preserves the already published `3.5.1-sihsalus.1` tag and assets.

QLTY acceptance must verify native family REST responses and frontend navigation in
both directions using newly created synthetic outpatient patients, then clean them up.
It also requires the canonical mother–child relationship metadata mapping. Module
publication alone does not establish that this deployment acceptance has passed.

Keep the affected scheduler task paused. A complete timestamp policy, actual
OpenMRS + Queue integration tests and synthetic DEV/QLTY acceptance remain
required before separately authorized activation. No production data belongs
in this repository or its tests.

Verify a downloaded OMOD with `gh attestation verify FILE --repo
sihsalus/openmrs-module-emrapi`, and compare SHA-256 with the distribution pin.
Publishing this release does not deploy anything or reactivate any task.
