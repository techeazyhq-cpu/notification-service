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
# Logical dumps (deploy/backup/postgres-backup.sh) go to a bucket in the recovery region, so a copy independent of RDS
# survives the loss of the primary region and of the RDS account-level backups.

resource "aws_s3_bucket" "backups" {
  provider = aws.disaster_recovery

  bucket_prefix = "${var.name}-backups-"
  tags          = var.tags
}

resource "aws_s3_bucket_public_access_block" "backups" {
  provider = aws.disaster_recovery

  bucket                  = aws_s3_bucket.backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backups" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backups.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "backups" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backups.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backups.id

  rule {
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.disaster_recovery.arn
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backups.id

  rule {
    id     = "expire-logical-dumps"
    status = "Enabled"

    filter {}

    expiration {
      days = var.logical_backup_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.backups]
}

resource "aws_s3_bucket_policy" "backups" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backups.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyRequestsWithoutTls"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.backups.arn, "${aws_s3_bucket.backups.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })

  depends_on = [aws_s3_bucket_public_access_block.backups]
}

resource "aws_s3_bucket" "backup_access_logs" { # NOSONAR: the access-log target itself; logging it would log its own log writes
  provider = aws.disaster_recovery

  bucket_prefix = "${var.name}-backup-logs-"
  tags          = var.tags
}

resource "aws_s3_bucket_public_access_block" "backup_access_logs" {
  provider = aws.disaster_recovery

  bucket                  = aws_s3_bucket.backup_access_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backup_access_logs" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backup_access_logs.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_versioning" "backup_access_logs" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backup_access_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

# S3 server access logging cannot deliver to a bucket encrypted with a customer managed KMS key, so the log target
# uses SSE-S3. The backups it records stay encrypted with the disaster-recovery region's KMS key.
#trivy:ignore:AVD-AWS-0132
resource "aws_s3_bucket_server_side_encryption_configuration" "backup_access_logs" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backup_access_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backup_access_logs" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backup_access_logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = 365
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.backup_access_logs]
}

resource "aws_s3_bucket_policy" "backup_access_logs" {
  provider = aws.disaster_recovery

  bucket = aws_s3_bucket.backup_access_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowServerAccessLogsFromTheBackupBucket"
        Effect    = "Allow"
        Principal = { Service = "logging.s3.amazonaws.com" }
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.backup_access_logs.arn}/*"
        Condition = {
          ArnLike      = { "aws:SourceArn" = aws_s3_bucket.backups.arn }
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
        }
      },
      {
        Sid       = "DenyRequestsWithoutTls"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.backup_access_logs.arn, "${aws_s3_bucket.backup_access_logs.arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
    ]
  })

  depends_on = [aws_s3_bucket_public_access_block.backup_access_logs]
}

resource "aws_s3_bucket_logging" "backups" {
  provider = aws.disaster_recovery

  bucket        = aws_s3_bucket.backups.id
  target_bucket = aws_s3_bucket.backup_access_logs.id
  target_prefix = "backups/"

  depends_on = [aws_s3_bucket_policy.backup_access_logs]
}
