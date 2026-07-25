## [2.3.1](https://github.com/jabrown93/k8s-leader-elector/compare/v2.3.0...v2.3.1) (2026-07-25)


### Bug Fixes

* comment audit cleanup and selector-label validation messages ([#103](https://github.com/jabrown93/k8s-leader-elector/issues/103)) ([f04f959](https://github.com/jabrown93/k8s-leader-elector/commit/f04f9592f222c607f13944b7722f05d55e4f6d8f)), closes [LockCallbacks#reconcileLeaderLabels](https://github.com/LockCallbacks/issues/reconcileLeaderLabels) [TaskSchedulerConfiguration#taskScheduler](https://github.com/TaskSchedulerConfiguration/issues/taskScheduler) [ElectorService#stillOwnsLock](https://github.com/ElectorService/issues/stillOwnsLock) [LockCallbacks#validateSelfPodName](https://github.com/LockCallbacks/issues/validateSelfPodName)
* **deps:** update dhi.io/amazoncorretto docker tag ([aa3e5ec](https://github.com/jabrown93/k8s-leader-elector/commit/aa3e5ecbbba3efe3ef16d44c94ed89187cdae5b6))
* **deps:** update dhi.io/amazoncorretto docker tag ([bc3b387](https://github.com/jabrown93/k8s-leader-elector/commit/bc3b3870e02b49fe69465779866e5ed37d0a6b60))

# [2.3.0](https://github.com/jabrown93/k8s-leader-elector/compare/v2.2.1...v2.3.0) (2026-07-17)


### Bug Fixes

* **ci:** authenticate release as GitHub App, not a long-lived PAT ([#95](https://github.com/jabrown93/k8s-leader-elector/issues/95)) ([dc56e2a](https://github.com/jabrown93/k8s-leader-elector/commit/dc56e2ade349c844424c6ab7f0aca635db056f5d)), closes [#k8s-leader-elector](https://github.com/jabrown93/k8s-leader-elector/issues/k8s-leader-elector) [jabrown93/homelab#1916](https://github.com/jabrown93/homelab/issues/1916)
* **health-probe:** reject non-regular files and stop logging raw content ([#96](https://github.com/jabrown93/k8s-leader-elector/issues/96)) ([1aaea65](https://github.com/jabrown93/k8s-leader-elector/commit/1aaea65723747f7a4f616bf408e09033a78b976c))
* **reconcile:** paginate the pod-label reconcile list call ([#97](https://github.com/jabrown93/k8s-leader-elector/issues/97)) ([3a61058](https://github.com/jabrown93/k8s-leader-elector/commit/3a61058b1112e390065d1f28b9db02128cb010bc))


### Features

* automate conventional-commit versioning and releases ([#88](https://github.com/jabrown93/k8s-leader-elector/issues/88)) ([adb9a74](https://github.com/jabrown93/k8s-leader-elector/commit/adb9a74787b7506a674c1ddbc476ffb0a2b151ce))

# Changelog

All notable changes to this project are documented here, generated automatically by
[semantic-release](https://semantic-release.gitbook.io/) from
[Conventional Commits](https://www.conventionalcommits.org/).
