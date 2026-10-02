# Copyright 2026 Vasantha Kumar
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# @author Vasantha Kumar <vasantha.kumar@hotmail.com>

locals {
  private_services_cidr = format(
    "%s/%d",
    google_compute_global_address.private_services.address,
    google_compute_global_address.private_services.prefix_length,
  )
}

output "cluster_name" {
  description = "GKE cluster: gcloud container clusters get-credentials <this> --region <region>."
  value       = google_container_cluster.cluster.name
}

output "helm_values" {
  description = "Environment-specific values for deploy/helm/notification-service, merged over values-gcp.yaml."
  value = {
    config = {
      database = { url = module.datastores.postgres_jdbc_url }
      redis    = { host = module.datastores.redis_host, port = module.datastores.redis_port }
    }
    networkPolicy = {
      datastoreEgress = [
        {
          to    = [{ ipBlock = { cidr = local.private_services_cidr } }]
          ports = [{ protocol = "TCP", port = 5432 }, { protocol = "TCP", port = module.datastores.redis_port }]
        },
        {
          to = [{
            namespaceSelector = { matchLabels = { "kubernetes.io/metadata.name" = "pulsar" } }
            podSelector       = { matchLabels = { component = "broker" } }
          }]
          ports = [{ protocol = "TCP", port = 6651 }]
        },
      ]
    }
  }
}

output "manifest_values" {
  description = "Values for the placeholders of deploy/k8s/gcp/*.yaml."
  value = {
    PROJECT_ID                       = var.project_id
    CLUSTER_LOCATION                 = var.region
    CLUSTER_NAME                     = google_container_cluster.cluster.name
    SECRET_PREFIX                    = module.datastores.secret_name_prefix
    EXTERNAL_SECRETS_SERVICE_ACCOUNT = google_service_account.workloads["secrets-sync"].email
    LOGICAL_BACKUP_SERVICE_ACCOUNT   = google_service_account.workloads["db-dump"].email
    POSTGRES_PRIVATE_IP              = module.datastores.postgres_private_ip
    BACKUP_BUCKET                    = module.datastores.backup_bucket_name
    PRIVATE_SERVICES_CIDR            = local.private_services_cidr
  }
}

output "postgres_server_ca_certificate" {
  description = "PEM for the db-ca-bundle ConfigMap (terraform output -raw postgres_server_ca_certificate)."
  value       = module.datastores.postgres_server_ca_certificate
}

output "redis_server_ca_certificate" {
  description = "PEM for the redis-ca ConfigMap (terraform output -raw redis_server_ca_certificate)."
  value       = module.datastores.redis_server_ca_certificate
}
