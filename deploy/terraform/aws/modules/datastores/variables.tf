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

variable "name" {
  description = "Prefix for every resource, e.g. notification-production. Also names the Secrets Manager entries."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,40}[a-z0-9]$", var.name))
    error_message = "Use 4 to 42 lower-case letters, digits and hyphens, starting with a letter."
  }
}

variable "vpc_id" {
  description = "VPC that holds the cluster and the datastores."
  type        = string
}

variable "database_subnet_ids" {
  description = "Private subnets for PostgreSQL, one per availability zone."
  type        = list(string)

  validation {
    condition     = length(var.database_subnet_ids) >= 2
    error_message = "Multi-AZ PostgreSQL needs subnets in at least two availability zones."
  }
}

variable "cache_subnet_ids" {
  description = "Private subnets for Redis, one per availability zone."
  type        = list(string)

  validation {
    condition     = length(var.cache_subnet_ids) >= 2
    error_message = "Redis failover needs subnets in at least two availability zones."
  }
}

variable "client_security_group_ids" {
  description = "Security groups allowed to reach PostgreSQL and Redis: the EKS nodes or pods running the services."
  type        = list(string)

  validation {
    condition     = length(var.client_security_group_ids) > 0
    error_message = "List at least one client security group, or nothing can reach the datastores."
  }
}

variable "postgres_engine_version" {
  description = "PostgreSQL version. Major 16 is what the migrations and integration tests run against."
  type        = string
  default     = "16.10"

  validation {
    condition     = startswith(var.postgres_engine_version, "16")
    error_message = "Only PostgreSQL 16 is tested; change the parameter group family together with the major version."
  }
}

variable "postgres_instance_class" {
  description = "RDS instance class for the primary and its standby."
  type        = string
  default     = "db.m7g.large"
}

variable "postgres_allocated_storage_gb" {
  description = "Initial storage. Storage autoscaling grows it up to postgres_max_allocated_storage_gb."
  type        = number
  default     = 100
}

variable "postgres_max_allocated_storage_gb" {
  description = "Upper bound for storage autoscaling."
  type        = number
  default     = 1000
}

variable "postgres_max_connections" {
  description = "max_connections. Each service pod opens a pool of up to 20 (DB_POOL_SIZE)."
  type        = number
  default     = 400
}

variable "backup_retention_days" {
  description = "Days of automated backups and point-in-time recovery, in this region and in the other one."
  type        = number
  default     = 30

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "RDS keeps automated backups for 1 to 35 days; below 7 leaves too little time to notice damage."
  }
}

variable "redis_engine_version" {
  description = "Valkey version (Redis-compatible; the services use only Redis commands)."
  type        = string
  default     = "8.1"
}

variable "redis_node_type" {
  description = "ElastiCache node type."
  type        = string
  default     = "cache.m7g.large"
}

variable "redis_node_count" {
  description = "Primary plus replicas, spread across availability zones."
  type        = number
  default     = 2

  validation {
    condition     = var.redis_node_count >= 2 && var.redis_node_count <= 6
    error_message = "Automatic failover needs at least one replica (2 to 6 nodes)."
  }
}

variable "logical_backup_retention_days" {
  description = "Days the logical dumps of deploy/backup/postgres-backup.sh are kept in the backup bucket."
  type        = number
  default     = 30
}

variable "kms_deletion_window_days" {
  description = "Waiting period before a scheduled KMS key deletion takes effect."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags added to every resource."
  type        = map(string)
  default     = {}
}
