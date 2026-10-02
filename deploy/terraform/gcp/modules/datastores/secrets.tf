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
#
# One Secret Manager entry per value of the chart's notification-secrets Secret, synced by External Secrets, each kept
# in the primary and recovery regions only. Terraform writes the two it creates (the schema owner's password,
# write-only; the Redis AUTH string); an operator adds versions for the other five.

locals {
  chart_secret_names = [
    "db-password", "secrets-encryption-key", "data-encryption-key", "admin-two-factor-key", "admin-password",
    "migration-db-password", "redis-password",
  ]
}

resource "google_secret_manager_secret" "chart" {
  for_each = toset(local.chart_secret_names)

  secret_id = "${var.name}-${each.key}"
  project   = var.project_id
  labels    = var.labels

  replication {
    user_managed {
      replicas {
        location = var.region
      }
      replicas {
        location = var.disaster_recovery_region
      }
    }
  }

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_secret_manager_secret_version" "owner_password" {
  secret                 = google_secret_manager_secret.chart["migration-db-password"].id
  secret_data_wo         = ephemeral.random_password.postgres_owner.result
  secret_data_wo_version = 1
}

resource "google_secret_manager_secret_version" "redis_password" {
  secret      = google_secret_manager_secret.chart["redis-password"].id
  secret_data = google_redis_instance.redis.auth_string
}
