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
# Redis holds only rate-limit counters and caches, rebuilt on their own after a loss (ADR-026), so it has no
# persistence. Memorystore signs its TLS certificate with a CA of its own; the chart trusts exactly that CA.

resource "google_redis_instance" "redis" {
  name           = "${var.name}-redis"
  project        = var.project_id
  region         = var.region
  tier           = "STANDARD_HA"
  replica_count  = 1
  memory_size_gb = var.redis_memory_size_gb
  redis_version  = "REDIS_7_2"

  connect_mode       = "PRIVATE_SERVICE_ACCESS"
  authorized_network = var.network_id

  auth_enabled            = true
  transit_encryption_mode = "SERVER_AUTHENTICATION"

  persistence_config {
    persistence_mode = "DISABLED"
  }

  maintenance_policy {
    weekly_maintenance_window {
      day = "SUNDAY"

      start_time {
        hours   = 4
        minutes = 0
      }
    }
  }

  labels = var.labels

  depends_on = [terraform_data.private_services_access]
}
