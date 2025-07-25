# leader-elector

Tiny Kubernetes sidecar binary that:
- Acquires leadership using a `Lease` (coordination.k8s.io/v1)
- On gaining leadership, patches its own Pod with a label (default: `dns.jb.io/leader=true`)
- On losing leadership, removes the label

## Usage

```yaml
containers:
- name: leader-elector
  image: ghcr.io/youruser/leader-elector:v0.1.0
  env:
    - name: POD_NAME
      valueFrom:
        fieldRef:
          fieldPath: metadata.name
    - name: POD_NAMESPACE
      valueFrom:
        fieldRef:
          fieldPath: metadata.namespace
  args:
    - --election-id=my-election
    - --label-key=my.leader/active

