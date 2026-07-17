// semantic-release configuration.
//
// Versioning is automated from Conventional Commits:
//   * push to `main` -> stable release (feat -> minor, fix/perf -> patch, ! -> major)
//   * push to `beta` -> prerelease (vX.Y.Z-beta.N)
//
// Routine runtime dependency bumps (fix(deps), from Renovate via the shared
// preset) do NOT cut a release on ordinary pushes -- they would otherwise tag
// a new version, and build and push a new image, per merged Renovate PR. The
// weekly scheduled run in .github/workflows/version-release.yml sets
// RELEASE_DEPS=true, which promotes the accumulated bumps into one patch
// release. Maven compile/runtime/provided/parent deps are typed fix(deps) by
// the preset and take part in that roll-up; test-scope deps stay chore(deps)
// and never release. Vulnerability fixes are typed fix(security), not
// fix(deps), so they are unaffected by the suppression and still release
// immediately. See jabrown93/.github's README, "Weekly dependency releases".
//
// This file is CommonJS (package.json does not set "type": "module");
// semantic-release loads it via cosmiconfig. `${...}` placeholders are
// expanded by semantic-release, not by JS -- keep them inside double-quoted
// strings so JS does not interpolate them.

const releaseDeps = process.env.RELEASE_DEPS === "true";

const depReleaseRules = [
  // Required: commit-analyzer evaluates every matching custom rule and keeps
  // the highest release type, so without this a breaking fix(deps)! would
  // match ONLY the suppression rule below and never release. Listed first so
  // the analyzer short-circuits on major.
  { type: "fix", scope: "deps", breaking: true, release: "major" },
  releaseDeps
    ? { type: "fix", scope: "deps", release: "patch" }
    : { type: "fix", scope: "deps", release: false },
];

module.exports = {
  branches: ["main", { name: "beta", prerelease: true }],
  tagFormat: "v${version}",
  plugins: [
    ["@semantic-release/commit-analyzer", { releaseRules: depReleaseRules }],
    "@semantic-release/release-notes-generator",
    ["@semantic-release/changelog", { changelogFile: "CHANGELOG.md" }],
    [
      "@semantic-release/exec",
      {
        prepareCmd:
          "mvn -B -q versions:set -DnewVersion=${nextRelease.version} -DgenerateBackupPoms=false",
        successCmd:
          '{ echo "published=true"; echo "version=${nextRelease.version}"; echo "channel=${nextRelease.channel}"; } >> "$GITHUB_OUTPUT"',
      },
    ],
    [
      "@semantic-release/git",
      {
        assets: ["pom.xml", "CHANGELOG.md"],
        message: "chore(release): ${nextRelease.version} [skip ci]\n\n${nextRelease.notes}",
      },
    ],
    "@semantic-release/github",
  ],
};
