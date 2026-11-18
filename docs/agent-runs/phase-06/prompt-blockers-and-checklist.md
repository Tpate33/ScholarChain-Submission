# Phase 06 human order: close deployment blockers; sign-in step; checklist (2026-11-17)

Verbatim:

> Prioritize closing the two major deployment blockers and any permissions
> or network issue that would prevent the run. Defer unrelated nits.
>
> Use targeted checks for changed scripts and a focused review of the fixes.
> Reuse passing evidence for unchanged code.
>
> Continue repairs while giving me the next single account sign-in step,
> checking credit implications before enabling Organizations.
>
> Return a concrete deployment-ready checklist separating completed work,
> remaining blockers, and actions needed from me. AWS execution and spending
> remain pending approval.

## Orchestrator handling

- IE-P6-02 repair 01 (infra-engineer) was already running with M1, M2, the permission and network concerns, m1, n1 and n2. It continues; n1 and n2 are small and already in scope.
- Deferred under this order (not deployment blockers): CR-P6-27 build-engineer m2, m3, n3 (local gate integrity; they do not affect the cloud run). Recorded for a later build-engineer work order before `sbt sparkSubmitLocal` evidence is accepted.
- Sign-in: AWS documentation checked for credit and free-plan implications of AWS Organizations and for a non-Organizations alternative before recommending the next step (emr-docs-research-04.md).
