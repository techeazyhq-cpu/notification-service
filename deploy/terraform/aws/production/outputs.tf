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

output "cluster_name" {
  description = "EKS cluster: aws eks update-kubeconfig --name <this> --region <region>."
  value       = module.eks.cluster_name
}

output "helm_values" {
  description = "Environment-specific values for deploy/helm/notification-service, merged over values-aws.yaml."
  value = {
    config = {
      database = { url = module.datastores.postgres_jdbc_url }
      redis    = { host = module.datastores.redis_primary_endpoint }
    }
    networkPolicy = {
      datastoreEgress = [
        {
          to    = [for cidr in module.vpc.database_subnets_cidr_blocks : { ipBlock = { cidr = cidr } }]
          ports = [{ protocol = "TCP", port = 5432 }]
        },
        {
          to    = [for cidr in module.vpc.elasticache_subnets_cidr_blocks : { ipBlock = { cidr = cidr } }]
          ports = [{ protocol = "TCP", port = 6379 }]
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

output "application_secret_arn" {
  description = "Secrets Manager entry to fill with the application's secret values before the first install."
  value       = module.datastores.application_secret_arn
}

output "postgres_owner_secret_arn" {
  description = "RDS-managed secret holding the schema owner's password."
  value       = module.datastores.postgres_owner_secret_arn
}

output "operations_topic_arn" {
  description = "SNS topic for RDS backup, failover, failure and storage events; subscribe the on-call channel."
  value       = module.datastores.operations_topic_arn
}

output "backup_bucket_name" {
  description = "Bucket in the recovery region for logical dumps."
  value       = module.datastores.backup_bucket_name
}
