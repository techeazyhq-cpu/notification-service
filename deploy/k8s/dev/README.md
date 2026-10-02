# Trying the Helm chart on a local k3s

Development only, and excluded from the repository's security scan for that reason (ADR-025). This runs the
whole platform in a single-node k3s inside Docker, with throwaway datastores, to see the chart work: migration hook,
probes, ingress, network policies. It is how the chart was verified for ADR-025. Production uses real datastores
(managed services or operators) and images from a registry.

Requirements: Docker. Helm and kubectl run from containers, so nothing else needs installing.

## 1. Start k3s

```bash
docker run -d --name k3s-notification --privileged -p 127.0.0.1:8880:80 rancher/k3s:v1.36.5-k3s1 server --disable=metrics-server --kubelet-arg=fail-cgroupv1=false
```

`fail-cgroupv1=false` is needed only where the Docker host still uses cgroup v1, as Docker Desktop on WSL 2 does;
current Kubernetes refuses cgroup v1 by default. Wait until `docker exec k3s-notification kubectl get nodes` shows
`Ready`.

## 2. Build the images and load them into the cluster

```bash
for m in client-api admin-api dispatcher db-migration; do docker build --build-arg MODULE=$m -t registry.local/notification-$m:test .; done
```

```bash
docker build -t registry.local/notification-admin-ui:test admin-ui && docker build -t registry.local/notification-client-ui:test client-ui && docker build -t registry.local/notification-catcher:test tools/catcher
```

```bash
docker save $(docker images --format '{{.Repository}}:{{.Tag}}' | grep '^registry.local/notification-') -o images.tar && docker cp images.tar k3s-notification:/images.tar && docker exec k3s-notification ctr -n k8s.io images import /images.tar && rm images.tar
```

## 3. Datastores and secrets

Generate your own values; the services refuse the project's development defaults (ADR-013). The PostgreSQL
password is the schema owner's, and the migration job uses it.

```bash
POSTGRES_PASSWORD=$(openssl rand -hex 16) DB_APP_PASSWORD=$(openssl rand -hex 16)
```

```bash
docker exec -i k3s-notification kubectl apply -f - < deploy/k8s/dev/datastores.yaml
```

```bash
docker exec k3s-notification kubectl -n datastores create secret generic datastore-credentials --from-literal=POSTGRES_PASSWORD=$POSTGRES_PASSWORD --from-literal=DB_APP_PASSWORD=$DB_APP_PASSWORD
```

```bash
docker exec -i k3s-notification kubectl -n datastores create configmap postgres-init --from-file=01-create-app-role.sh=/dev/stdin < scripts/postgres-init/01-create-app-role.sh
```

```bash
docker exec k3s-notification kubectl -n datastores rollout restart deployment postgres
```

```bash
docker exec k3s-notification kubectl create namespace notification
```

```bash
docker exec k3s-notification kubectl -n notification create secret generic notification-secrets --from-literal=DB_PASSWORD=$DB_APP_PASSWORD --from-literal=MIGRATION_DB_PASSWORD=$POSTGRES_PASSWORD --from-literal=SECRETS_ENCRYPTION_KEY=$(openssl rand -hex 32) --from-literal=DATA_ENCRYPTION_KEY=$(openssl rand -hex 32) --from-literal=ADMIN_TWO_FACTOR_KEY=$(openssl rand -hex 32) --from-literal=ADMIN_PASSWORD=Initial-$(openssl rand -hex 8)
```

## 4. Install

```bash
docker cp k3s-notification:/etc/rancher/k3s/k3s.yaml kubeconfig
```

```bash
docker run --rm --network container:k3s-notification -v "$PWD/kubeconfig:/root/.kube/config:ro" -v "$PWD/deploy:/deploy" -w /deploy alpine/helm:4.3.0 install notification helm/notification-service -n notification -f k8s/dev/values-k3s.yaml --wait --timeout 10m
```

The admin UI is at http://admin.notification.localhost:8880, the client UI at http://app.notification.localhost:8880
and the API at http://api.notification.localhost:8880 (`*.localhost` resolves to your machine in browsers; for curl
add `--resolve api.notification.localhost:8880:127.0.0.1`). Sign in as `admin` with the `ADMIN_PASSWORD` you set;
the first sign-in requires a new password and two-factor authentication (ADR-020). Add an SMS provider with the URL
`http://catcher.datastores:9000/sms`; `catcher.datastores` is a trusted provider host in these values.

## 5. Remove it

```bash
docker rm -f k3s-notification
```
