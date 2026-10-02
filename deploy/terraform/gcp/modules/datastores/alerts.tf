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
# The managed-service counterpart of postgres-recovery.rules.yml: PostgreSQL down or running out of storage, and Redis
# running out of memory (which would start evicting rate-limit counters).

resource "google_monitoring_notification_channel" "email" {
  for_each = toset(var.alert_email_addresses)

  project      = var.project_id
  display_name = "${var.name} operations: ${each.value}"
  type         = "email"
  labels       = { email_address = each.value }
}

locals {
  cloud_sql   = "resource.type = \"cloudsql_database\""
  memorystore = "resource.type = \"redis_instance\""

  alert_conditions = {
    postgres_down = {
      title     = "PostgreSQL is down"
      filter    = "${local.cloud_sql} AND metric.type = \"cloudsql.googleapis.com/database/up\""
      reducer   = "ALIGN_MIN"
      threshold = 1
      operator  = "COMPARISON_LT"
    }
    postgres_storage_nearly_full = {
      title     = "PostgreSQL storage above 85%"
      filter    = "${local.cloud_sql} AND metric.type = \"cloudsql.googleapis.com/database/disk/utilization\""
      reducer   = "ALIGN_MEAN"
      threshold = 0.85
      operator  = "COMPARISON_GT"
    }
    redis_memory_nearly_full = {
      title     = "Redis memory above 85%"
      filter    = "${local.memorystore} AND metric.type = \"redis.googleapis.com/stats/memory/usage_ratio\""
      reducer   = "ALIGN_MEAN"
      threshold = 0.85
      operator  = "COMPARISON_GT"
    }
  }
}

resource "google_monitoring_alert_policy" "datastores" {
  for_each = local.alert_conditions

  project               = var.project_id
  display_name          = "${var.name}: ${each.value.title}"
  combiner              = "OR"
  notification_channels = [for channel in google_monitoring_notification_channel.email : channel.id]
  user_labels           = var.labels

  conditions {
    display_name = each.value.title

    condition_threshold {
      filter          = each.value.filter
      comparison      = each.value.operator
      threshold_value = each.value.threshold
      duration        = "300s"

      aggregations {
        alignment_period   = "60s"
        per_series_aligner = each.value.reducer
      }
    }
  }
}
