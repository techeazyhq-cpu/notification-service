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

ephemeral "random_password" "postgres_owner" {
  length  = 40
  special = false
}

resource "google_sql_database_instance" "postgres" {
  name                = "${var.name}-postgres"
  project             = var.project_id
  region              = var.region
  database_version    = "POSTGRES_16"
  deletion_protection = true

  settings {
    tier                        = var.postgres_tier
    edition                     = "ENTERPRISE"
    availability_type           = "REGIONAL"
    disk_type                   = "PD_SSD"
    disk_size                   = var.postgres_disk_size_gb
    disk_autoresize             = true
    deletion_protection_enabled = true
    retain_backups_on_delete    = true
    user_labels                 = var.labels

    ip_configuration {
      ipv4_enabled    = false
      private_network = var.network_id
      ssl_mode        = "ENCRYPTED_ONLY"
      server_ca_mode  = "GOOGLE_MANAGED_INTERNAL_CA"
    }

    backup_configuration {
      enabled                        = true
      start_time                     = "01:00"
      location                       = var.disaster_recovery_region
      point_in_time_recovery_enabled = true
      transaction_log_retention_days = 7

      backup_retention_settings {
        retained_backups = 30
        retention_unit   = "COUNT"
      }
    }

    maintenance_window {
      day          = 7
      hour         = 3
      update_track = "stable"
    }

    insights_config {
      query_insights_enabled = true
    }

    database_flags {
      name  = "log_min_duration_statement"
      value = "1000"
    }

    database_flags {
      name  = "idle_in_transaction_session_timeout"
      value = "60000"
    }

    database_flags {
      name  = "log_checkpoints"
      value = "on"
    }

    database_flags {
      name  = "log_connections"
      value = "on"
    }

    database_flags {
      name  = "log_disconnections"
      value = "on"
    }

    database_flags {
      name  = "log_lock_waits"
      value = "on"
    }

    database_flags {
      name  = "log_temp_files"
      value = "0"
    }

    database_flags {
      name  = "cloudsql.enable_pgaudit"
      value = "off"
    }
  }

  depends_on = [terraform_data.private_services_access]
}

resource "terraform_data" "private_services_access" {
  input = var.private_services_access_dependency
}

resource "google_sql_database" "notification" {
  name     = "notification"
  project  = var.project_id
  instance = google_sql_database_instance.postgres.name

  lifecycle {
    prevent_destroy = true
  }
}

resource "google_sql_user" "owner" {
  name                = "notification"
  project             = var.project_id
  instance            = google_sql_database_instance.postgres.name
  password_wo         = ephemeral.random_password.postgres_owner.result
  password_wo_version = 1
}
