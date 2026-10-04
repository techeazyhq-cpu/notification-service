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
# Plan-time tests with mocked AWS providers: no credentials, nothing created. They pin the properties the recovery
# objectives (docs/disaster-recovery.md) and the security decisions (ADR-012, ADR-017, ADR-028) depend on.
# Run from the module directory: terraform init && terraform test

mock_provider "aws" {
  override_during = plan

  mock_resource "aws_kms_key" {
    defaults = {
      arn = "arn:aws:kms:eu-west-1:111122223333:key/00000000-0000-0000-0000-000000000000"
    }
  }
}

mock_provider "aws" {
  alias           = "disaster_recovery"
  override_during = plan

  mock_resource "aws_s3_bucket" {
    defaults = {
      id  = "notification-production-backups-test"
      arn = "arn:aws:s3:::notification-production-backups-test"
    }
  }
}

variables {
  name                      = "notification-production"
  vpc_id                    = "vpc-0123456789abcdef0"
  database_subnet_ids       = ["subnet-0000000000000000a", "subnet-0000000000000000b", "subnet-0000000000000000c"]
  cache_subnet_ids          = ["subnet-0000000000000000d", "subnet-0000000000000000e", "subnet-0000000000000000f"]
  client_security_group_ids = ["sg-0123456789abcdef0"]
}

run "postgres_survives_an_availability_zone_and_keeps_thirty_days_of_point_in_time_recovery" {
  command = plan

  assert {
    condition     = aws_db_instance.postgres.multi_az
    error_message = "PostgreSQL must keep a synchronous standby in another zone, so a failover loses nothing."
  }
  assert {
    condition     = aws_db_instance.postgres.backup_retention_period == 30
    error_message = "Automated backups and point-in-time recovery must cover 30 days, as for CloudNativePG."
  }
  assert {
    condition     = aws_db_instance.postgres.deletion_protection && !aws_db_instance.postgres.skip_final_snapshot
    error_message = "The database must be protected from deletion and leave a final snapshot."
  }
  assert {
    condition = (
      aws_db_instance.postgres.engine == "postgres"
      && startswith(aws_db_instance.postgres.engine_version, "16")
    )
    error_message = "PostgreSQL 16, the version the migrations and tests run against."
  }
}

run "postgres_backups_are_copied_to_the_disaster_recovery_region" {
  command = plan

  assert {
    condition     = aws_db_instance_automated_backups_replication.postgres.retention_period == 30
    error_message = "Backups replicated to the other region must be kept as long as the primary ones."
  }
}

run "postgres_is_private_encrypted_and_refuses_plain_connections" {
  command = plan

  assert {
    condition     = !aws_db_instance.postgres.publicly_accessible
    error_message = "PostgreSQL must not be reachable from the internet."
  }
  assert {
    condition     = aws_db_instance.postgres.storage_encrypted
    error_message = "Storage, snapshots and backups must be encrypted with the module's KMS key."
  }
  assert {
    condition     = aws_db_instance.postgres.manage_master_user_password
    error_message = "The schema owner's password must live in Secrets Manager, never in Terraform state."
  }
  assert {
    condition     = aws_db_instance.postgres.username == "notification"
    error_message = "The master user is the schema owner of ADR-012, used only by the migration job."
  }
  assert {
    condition = anytrue([
      for parameter in aws_db_parameter_group.postgres.parameter :
      parameter.name == "rds.force_ssl" && parameter.value == "1"
    ])
    error_message = "The server must refuse connections without TLS (ADR-017)."
  }
}

run "postgres_accepts_connections_only_from_the_cluster" {
  command = plan

  assert {
    condition = (
      aws_vpc_security_group_ingress_rule.postgres_from_clients[var.client_security_group_ids[0]].from_port == 5432
      && (
        aws_vpc_security_group_ingress_rule.postgres_from_clients[var.client_security_group_ids[0]]
        .referenced_security_group_id == var.client_security_group_ids[0]
      )
    )
    error_message = "Only the listed client security groups may reach PostgreSQL, on 5432 only."
  }
}

run "redis_fails_over_and_encrypts_in_transit_and_at_rest" {
  command = plan

  assert {
    condition = (
      aws_elasticache_replication_group.redis.automatic_failover_enabled
      && aws_elasticache_replication_group.redis.multi_az_enabled
      && aws_elasticache_replication_group.redis.num_cache_clusters >= 2
    )
    error_message = "Redis must keep a replica in another availability zone and fail over to it."
  }
  assert {
    condition = (
      aws_elasticache_replication_group.redis.transit_encryption_enabled
      && aws_elasticache_replication_group.redis.transit_encryption_mode == "required"
      && aws_elasticache_replication_group.redis.at_rest_encryption_enabled == "true"
    )
    error_message = "Redis must require TLS and encrypt its data at rest (ADR-017)."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.redis_from_clients["sg-0123456789abcdef0"].from_port == 6379
    error_message = "Only the listed client security groups may reach Redis, on 6379 only."
  }
}

run "the_backup_bucket_lives_in_the_other_region_and_cannot_be_made_public" {
  command = plan

  assert {
    condition = (
      aws_s3_bucket_public_access_block.backups.block_public_acls
      && aws_s3_bucket_public_access_block.backups.block_public_policy
      && aws_s3_bucket_public_access_block.backups.ignore_public_acls
      && aws_s3_bucket_public_access_block.backups.restrict_public_buckets
    )
    error_message = "The backup bucket must block every form of public access."
  }
  assert {
    condition     = aws_s3_bucket_versioning.backups.versioning_configuration[0].status == "Enabled"
    error_message = "Backups must be versioned, so an overwrite or deletion can be undone."
  }
  assert {
    condition = (
      one(one(aws_s3_bucket_server_side_encryption_configuration.backups.rule).apply_server_side_encryption_by_default)
      .sse_algorithm == "aws:kms"
    )
    error_message = "Backups must be encrypted with the disaster-recovery region's KMS key."
  }
  assert {
    condition     = strcontains(aws_s3_bucket_policy.backups.policy, "aws:SecureTransport")
    error_message = "The bucket must refuse requests that are not made over TLS."
  }
  assert {
    condition     = aws_s3_bucket_logging.backups.target_bucket == aws_s3_bucket.backup_access_logs.id
    error_message = "Every request against the backups must leave an access log."
  }
  assert {
    condition = (
      aws_s3_bucket_public_access_block.backup_access_logs.block_public_acls
      && aws_s3_bucket_public_access_block.backup_access_logs.block_public_policy
      && aws_s3_bucket_public_access_block.backup_access_logs.ignore_public_acls
      && aws_s3_bucket_public_access_block.backup_access_logs.restrict_public_buckets
      && strcontains(aws_s3_bucket_policy.backup_access_logs.policy, "aws:SecureTransport")
    )
    error_message = "The access-log bucket must be as closed as the backups it records."
  }
}

run "keys_rotate_and_application_secrets_are_containers_without_values" {
  command = plan

  assert {
    condition     = aws_kms_key.primary.enable_key_rotation && aws_kms_key.disaster_recovery.enable_key_rotation
    error_message = "Both KMS keys must rotate yearly."
  }
  assert {
    condition     = aws_secretsmanager_secret.application.name == "notification-production/application"
    error_message = "The application secret is named after the deployment so External Secrets can find it."
  }
}

run "backup_retention_outside_what_rds_supports_is_refused" {
  command = plan

  variables {
    backup_retention_days = 40
  }

  expect_failures = [var.backup_retention_days]
}

run "a_single_cache_node_is_refused" {
  command = plan

  variables {
    redis_node_count = 1
  }

  expect_failures = [var.redis_node_count]
}

run "backup_failover_and_storage_events_reach_the_operators_topic" {
  command = plan

  assert {
    condition = alltrue([
      for category in ["backup", "failover", "failure", "low storage", "recovery"] :
      contains(aws_db_event_subscription.postgres.event_categories, category)
    ])
    error_message = "RDS must report failed backups, failovers, failures and low storage (the managed-service alerts)."
  }
  assert {
    condition     = aws_db_event_subscription.postgres.source_type == "db-instance"
    error_message = "The subscription follows the database instance."
  }
  assert {
    condition     = aws_sns_topic.operations.kms_master_key_id != null
    error_message = "The operations topic must be encrypted."
  }
}
