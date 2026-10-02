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

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  account_root_arn = format(
    "arn:%s:iam::%s:root", data.aws_partition.current.partition, data.aws_caller_identity.current.account_id
  )
}

resource "aws_kms_key" "primary" {
  description             = "${var.name}: PostgreSQL, Redis, Secrets Manager and the operations topic"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountAdministersTheKeyThroughIam"
        Effect    = "Allow"
        Principal = { AWS = local.account_root_arn }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "RdsEventsMayWriteToTheEncryptedTopic"
        Effect    = "Allow"
        Principal = { Service = "events.rds.amazonaws.com" }
        Action    = ["kms:GenerateDataKey*", "kms:Decrypt"]
        Resource  = "*"
        Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
      },
    ]
  })
  tags = var.tags
}

resource "aws_kms_alias" "primary" {
  name          = "alias/${var.name}"
  target_key_id = aws_kms_key.primary.key_id
}

resource "aws_kms_key" "disaster_recovery" {
  provider = aws.disaster_recovery

  description             = "${var.name}: replicated PostgreSQL backups and logical dumps in the recovery region"
  enable_key_rotation     = true
  deletion_window_in_days = var.kms_deletion_window_days
  tags                    = var.tags
}

resource "aws_kms_alias" "disaster_recovery" {
  provider = aws.disaster_recovery

  name          = "alias/${var.name}-disaster-recovery"
  target_key_id = aws_kms_key.disaster_recovery.key_id
}

resource "aws_secretsmanager_secret" "application" {
  name        = "${var.name}/application"
  description = <<-EOT
    Values for the chart's notification-secrets Secret, set by an operator and synced by External Secrets:
    DB_PASSWORD, SECRETS_ENCRYPTION_KEY, DATA_ENCRYPTION_KEY, ADMIN_TWO_FACTOR_KEY and ADMIN_PASSWORD.
    MIGRATION_DB_PASSWORD comes from the RDS-managed master user secret.
  EOT
  kms_key_id  = aws_kms_key.primary.arn
  tags        = var.tags
}
