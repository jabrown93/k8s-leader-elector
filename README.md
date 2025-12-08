# leader-elector

**Under development, bugs are likely and I will be slow to address them for the foreseeable future**

Tiny Kubernetes sidecar binary that:

- Acquires leadership using a `Lease` (coordination.k8s.io/v1)
- On gaining leadership, patches its own Pod with a label (default: `dns.jb.io/leader=true`)
- On losing leadership, removes the label


