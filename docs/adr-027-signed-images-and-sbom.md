# ADR-027: Publish signed container images with an SBOM from CI

- **Status:** Accepted
- **Date:** 2026-10-02

## Context

CI built and scanned all six images on every change but threw them away. The Helm chart (ADR-025) expects images in
a registry, so anyone deploying had to build them on a workstation: an artefact nobody could trace back to a commit,
a green pipeline or a known set of dependencies. A cluster had no way to tell an image built by this pipeline from
one pushed by anyone holding registry credentials.

## Decision

1. **A `publish` job in the CI workflow,** one matrix entry per image. It runs only on a push to `main` or a `v*` tag,
   and only after the backend, UI, Helm chart and image build-and-scan jobs have passed. Pull requests never publish.
2. **Registry: GHCR,** as `ghcr.io/techeazyhq-cpu/notification-<component>`, which is the chart's default
   `image.registry` and `image.repositoryPrefix`. Authentication uses the workflow's own `GITHUB_TOKEN`; there is no
   stored registry secret.
3. **Tags:**
   - every build: `sha-<full commit>`, so a deployment can name the exact commit;
   - `main` builds: `main`, a moving tag for development environments;
   - release tags `vX.Y.Z`: `X.Y.Z` and `X.Y`. The chart's `appVersion` resolves to these.

   Tags are for people. Production pins the digest (`tag: "0.1.0@sha256:..."`), which is what the signature covers.
4. **Build provenance:** BuildKit records SLSA provenance (`mode=max`: source commit, workflow, build arguments) in
   the image index.
5. **SBOM:** Syft generates an SPDX JSON SBOM from the pushed image. It is kept as a workflow artifact and attached to
   the image as a cosign attestation of type `spdxjson`.
6. **Keyless signing with cosign.** The job signs the image digest and the SBOM attestation with a short-lived
   certificate that Sigstore's Fulcio issues against the workflow's GitHub OIDC token, and records both in the Rekor
   transparency log. There is no signing key to store, rotate or leak. The certificate names the workflow and ref
   that signed, which is what verifiers check.
7. **The job verifies its own output** with `cosign verify` and `cosign verify-attestation`, pinned to this
   repository's CI workflow, ref and the GitHub OIDC issuer, and fails if either check fails.
8. **Least privilege:** only the publish job gets `packages: write` and `id-token: write`. Every other job keeps
   read-only `contents`. CI runs on `main` are no longer cancelled by a newer push (only pull-request runs are), so a
   publish is never interrupted half way through the matrix.

Verifying an image before deploying it:

```bash
cosign verify ghcr.io/techeazyhq-cpu/notification-client-api@sha256:<digest> \
  --certificate-identity-regexp '^https://github.com/techeazyhq-cpu/notification-service/.github/workflows/ci.yml@' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

`cosign verify-attestation --type spdxjson` with the same identity flags returns the signed SBOM. An admission
controller (Kyverno `verifyImages`, Sigstore policy-controller) can enforce the same check in the cluster.

## Options considered

- **Push from the existing `Docker images build` job.** No second build, but that job runs on pull requests, so it
  would need write and OIDC permissions there too. A separate job keeps those permissions off pull requests and
  publishes only commits on which every check passed. The rebuild reuses the build job's layer cache.
- **Key-based cosign signing.** Works with any registry and offline verification, but adds a long-lived secret to
  protect and rotate. Keyless ties the signature to the workflow identity instead.
- **GitHub artifact attestations (`actions/attest-build-provenance`).** Similar guarantees, verified with `gh
  attestation verify`. Cosign is the format admission controllers already understand.
- **BuildKit's own SBOM attestation.** Unsigned on its own and not readable by `cosign verify-attestation`; one signed
  SBOM is enough.
- **Another registry (ECR, ACR, Artifact Registry, Nexus).** Waits on the choice of cloud. Changing it is one
  environment variable in the job and `image.registry` in the chart.

## Consequences

Positive: every published image traces to a commit and a green pipeline, carries a signed list of what it contains
for vulnerability triage, and can be checked at admission time. No signing or registry secret exists to leak.

Negative / accepted:

- **GHCR packages start private.** Make them public, or give the cluster a pull secret through `imagePullSecrets`.
- **Signing depends on the public Sigstore services** (Fulcio, Rekor) being reachable during the build.
- **The publish job rebuilds** rather than promoting the scanned image byte for byte. With the shared cache the
  layers are the same unless a base image changed in the minutes between the two jobs; pinning base images by digest
  closes that gap.
- **Admission-time enforcement is not part of this change.** It belongs to whichever cluster runs the chart.
