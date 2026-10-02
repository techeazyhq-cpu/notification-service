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

output "postgres_address" {
  description = "Host name for config.database.url; it follows the primary across a Multi-AZ failover."
  value       = aws_db_instance.postgres.address
}

output "postgres_port" {
  description = "PostgreSQL port."
  value       = aws_db_instance.postgres.port
}

output "postgres_jdbc_url" {
  description = "config.database.url for the Helm chart; mount the RDS CA bundle at the sslrootcert path."
  value = format(
    "jdbc:postgresql://%s:%d/notification?sslmode=verify-full&sslrootcert=/etc/rds/global-bundle.pem",
    aws_db_instance.postgres.address,
    aws_db_instance.postgres.port,
  )
}

output "postgres_owner_secret_arn" {
  description = "RDS-managed secret holding the schema owner's password (MIGRATION_DB_PASSWORD)."
  value       = aws_db_instance.postgres.master_user_secret[0].secret_arn
}

output "postgres_security_group_id" {
  description = "Security group attached to PostgreSQL."
  value       = aws_security_group.postgres.id
}

output "redis_primary_endpoint" {
  description = "config.redis.host for the Helm chart; TLS is required (SPRING_DATA_REDIS_SSL_ENABLED=true)."
  value       = aws_elasticache_replication_group.redis.primary_endpoint_address
}

output "redis_security_group_id" {
  description = "Security group attached to Redis."
  value       = aws_security_group.redis.id
}

output "application_secret_arn" {
  description = "Secrets Manager entry for the application's secret values."
  value       = aws_secretsmanager_secret.application.arn
}

output "operations_topic_arn" {
  description = "SNS topic receiving RDS backup, failover, failure and storage events; subscribe the on-call channel."
  value       = aws_sns_topic.operations.arn
}

output "primary_kms_key_arn" {
  description = "KMS key encrypting the datastores and secrets in the primary region."
  value       = aws_kms_key.primary.arn
}

output "backup_bucket_name" {
  description = "Bucket in the recovery region for logical dumps."
  value       = aws_s3_bucket.backups.bucket
}

output "backup_bucket_arn" {
  description = "ARN of the logical dump bucket, for the backup job's IAM policy."
  value       = aws_s3_bucket.backups.arn
}

output "disaster_recovery_kms_key_arn" {
  description = "KMS key encrypting the replicated backups and logical dumps in the recovery region."
  value       = aws_kms_key.disaster_recovery.arn
}
