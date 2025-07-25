package main

import (
	"context"
	"flag"
	"log"
	"os"
	"time"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	coordination "k8s.io/client-go/kubernetes/typed/coordination/v1"
	corev1 "k8s.io/client-go/kubernetes/typed/core/v1"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/leaderelection"
	"k8s.io/client-go/tools/leaderelection/resourcelock"
)

func mustEnv(k string) string {
	v := os.Getenv(k)
	if v == "" {
		log.Fatalf("missing required env var %s", k)
	}
	return v
}

func main() {
	// Flags
	var (
		electionID    = flag.String("election-id", "pihole-election", "Lease name")
		leaseDur      = flag.Duration("lease-duration", 15*time.Second, "Lease duration")
		renewDeadline = flag.Duration("renew-deadline", 10*time.Second, "Renew deadline")
		retryPeriod   = flag.Duration("retry-period", 2*time.Second, "Retry period")
		labelKey      = flag.String("label-key", "dns.jb.io/leader", "Label key to set on leader pod")
		labelValue    = flag.String("label-value", "true", "Label value to set on leader pod")
	)
	flag.Parse()

	podName := mustEnv("POD_NAME")
	ns := mustEnv("POD_NAMESPACE")

	cfg, err := rest.InClusterConfig()
	if err != nil {
		log.Fatalf("in-cluster config: %v", err)
	}
	coordClient, err := coordination.NewForConfig(cfg)
	if err != nil {
		log.Fatalf("coord client: %v", err)
	}
	coreClient, err := corev1.NewForConfig(cfg)
	if err != nil {
		log.Fatalf("core client: %v", err)
	}

	lock, err := resourcelock.New(
		resourcelock.LeasesResourceLock,
		ns,
		*electionID,
		coreClient,
		coordClient,
		resourcelock.ResourceLockConfig{Identity: podName},
	)
	if err != nil {
		log.Fatalf("lock: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	leaderelection.RunOrDie(ctx, leaderelection.LeaderElectionConfig{
		Lock:            lock,
		LeaseDuration:   *leaseDur,
		RenewDeadline:   *renewDeadline,
		RetryPeriod:     *retryPeriod,
		ReleaseOnCancel: true,
		Callbacks: leaderelection.LeaderCallbacks{
			OnStartedLeading: func(c context.Context) {
				log.Printf("%s: became leader", podName)
				patch := []byte(`{"metadata":{"labels":{"` + *labelKey + `":"` + *labelValue + `"}}}`)
				_, err := coreClient.Pods(ns).Patch(c, podName, types.MergePatchType, patch, metav1.PatchOptions{})
				if err != nil {
					log.Printf("add label: %v", err)
				}
			},
			OnStoppedLeading: func() {
				log.Printf("%s: lost leadership", podName)
				patch := []byte(`{"metadata":{"labels":{"` + *labelKey + `":null}}}`)
				_, err := coreClient.Pods(ns).Patch(context.Background(), podName, types.MergePatchType, patch, metav1.PatchOptions{})
				if err != nil {
					log.Printf("remove label: %v", err)
				}
			},
		},
	})
}
