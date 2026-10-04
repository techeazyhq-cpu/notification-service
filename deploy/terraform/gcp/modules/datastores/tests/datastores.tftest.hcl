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
# Plan-time tests with a mocked google provider (random runs for real): no credentials, nothing created. They pin the
# properties the recovery objectives (docs/disaster-recovery.md) and the security decisions (ADR-012, ADR-017,
# ADR-030) depend on. Run from the module directory: terraform init && terraform test

mock_provider "google" {}

variables {
  name                     = "notification-prod"
  project_id               = "example-project"
  region                   = "europe-west1"
  disaster_recovery_region = "europe-west4"
  network_id               = "projects/example-project/global/networks/notification-prod"
  alert_email_addresses    = ["oncall@example.com"]
}

run "postgres_survives_a_zone_and_keeps_point_in_time_recovery_with_backups_in_the_other_region" {
  command = plan

  assert {
    condition     = google_sql_database_instance.postgres.settings[0].availability_type == "REGIONAL"
    error_message = "PostgreSQL must keep a synchronous standby in another zone, so a failover loses nothing."
  }
  assert {
    condition = (
      google_sql_database_instance.postgres.settings[0].backup_configuration[0].point_in_time_recovery_enabled
      && google_sql_database_instance.postgres.settings[0].backup_configuration[0].transaction_log_retention_days == 7
    )
    error_message = "Point-in-time recovery must be on, with the 7 days of transaction logs Cloud SQL allows."
  }
  assert {
    condition = (
      google_sql_database_instance.postgres.settings[0].backup_configuration[0].location == "europe-west4"
      && one(google_sql_database_instance.postgres.settings[0].backup_configuration[0].backup_retention_settings)
      .retained_backups == 30
    )
    error_message = "Thirty daily backups must be stored in the recovery region."
  }
  assert {
    condition     = google_sql_database_instance.postgres.database_version == "POSTGRES_16"
    error_message = "PostgreSQL 16, the version the migrations and tests run against."
  }
  assert {
    condition = (
      google_sql_database_instance.postgres.deletion_protection
      && google_sql_database_instance.postgres.settings[0].deletion_protection_enabled
      && google_sql_database_instance.postgres.settings[0].retain_backups_on_delete
    )
    error_message = "The instance must be protected from deletion, and its backups kept even if it is deleted."
  }
}

run "postgres_is_private_requires_tls_with_its_own_ca_and_keeps_the_owner_password_out_of_state" {
  command = plan

  assert {
    condition = (
      !google_sql_database_instance.postgres.settings[0].ip_configuration[0].ipv4_enabled
      && google_sql_database_instance.postgres.settings[0].ip_configuration[0].private_network == var.network_id
    )
    error_message = "PostgreSQL must have only a private address in the environment's network."
  }
  assert {
    condition = (
      google_sql_database_instance.postgres.settings[0].ip_configuration[0].ssl_mode == "ENCRYPTED_ONLY"
      && (
        google_sql_database_instance.postgres.settings[0].ip_configuration[0].server_ca_mode
        == "GOOGLE_MANAGED_INTERNAL_CA"
      )
    )
    error_message = "Connections must use TLS, verified against a CA that signs only this instance (ADR-030)."
  }
  assert {
    condition     = google_sql_user.owner.name == "notification" && google_sql_user.owner.password == null
    error_message = "The schema owner of ADR-012 gets a write-only password, never stored in Terraform state."
  }
}

run "redis_fails_over_requires_auth_and_tls_and_is_private" {
  command = plan

  assert {
    condition     = google_redis_instance.redis.tier == "STANDARD_HA" && google_redis_instance.redis.replica_count >= 1
    error_message = "Redis must keep a replica in another zone and fail over to it."
  }
  assert {
    condition = (
      google_redis_instance.redis.auth_enabled
      && google_redis_instance.redis.transit_encryption_mode == "SERVER_AUTHENTICATION"
    )
    error_message = "Redis must require a password and TLS (ADR-017, ADR-030)."
  }
  assert {
    condition = (
      google_redis_instance.redis.connect_mode == "PRIVATE_SERVICE_ACCESS"
      && google_redis_instance.redis.authorized_network == var.network_id
    )
    error_message = "Redis must be reachable only from the environment's network."
  }
}

run "the_backup_bucket_lives_in_the_other_region_and_cannot_be_made_public" {
  command = plan

  assert {
    condition     = google_storage_bucket.backups.location == "EUROPE-WEST4"
    error_message = "Logical dumps must be stored in the recovery region."
  }
  assert {
    condition = (
      google_storage_bucket.backups.uniform_bucket_level_access
      && google_storage_bucket.backups.public_access_prevention == "enforced"
    )
    error_message = "The backup bucket must use IAM only and refuse every form of public access."
  }
  assert {
    condition     = google_storage_bucket.backups.versioning[0].enabled
    error_message = "Backups must be versioned, so an overwrite or deletion can be undone."
  }
  assert {
    condition     = one(google_storage_bucket.backups.lifecycle_rule[0].condition).age == 30
    error_message = "Dumps expire after the retention period."
  }
  assert {
    condition     = length(google_storage_bucket.backups.logging) == 1
    error_message = "Every request against the backups must leave an access log."
  }
  assert {
    condition = (
      google_storage_bucket.backup_access_logs.uniform_bucket_level_access
      && google_storage_bucket.backup_access_logs.public_access_prevention == "enforced"
    )
    error_message = "The access-log bucket must be as closed as the backups it records."
  }
}

run "secrets_are_replicated_to_both_regions_and_hold_no_plain_terraform_values" {
  command = plan

  assert {
    condition = alltrue([
      for secret in google_secret_manager_secret.chart : toset([
        for replica in secret.replication[0].user_managed[0].replicas : replica.location
      ]) == toset(["europe-west1", "europe-west4"])
    ])
    error_message = "Secrets stay in the primary and recovery regions only (data residency)."
  }
  assert {
    condition = toset(keys(google_secret_manager_secret.chart)) == toset([
      "db-password", "secrets-encryption-key", "data-encryption-key", "admin-two-factor-key", "admin-password",
      "migration-db-password", "redis-password",
    ])
    error_message = "Every value of the chart's Secret has a Secret Manager entry."
  }
  assert {
    condition     = google_secret_manager_secret_version.owner_password.secret_data == null
    error_message = "The owner password must reach Secret Manager write-only, never through Terraform state."
  }
}

run "outages_reach_the_operators" {
  command = plan

  assert {
    condition     = length(google_monitoring_notification_channel.email) == 1
    error_message = "The listed operators receive the alerts."
  }
  assert {
    condition = toset(keys(google_monitoring_alert_policy.datastores)) == toset([
      "postgres_down", "postgres_storage_nearly_full", "redis_memory_nearly_full",
    ])
    error_message = "Alert on PostgreSQL down or running out of storage, and on Redis running out of memory."
  }
}

run "the_recovery_region_must_differ_from_the_primary" {
  command = plan

  variables {
    disaster_recovery_region = "europe-west1"
  }

  expect_failures = [var.disaster_recovery_region]
}
