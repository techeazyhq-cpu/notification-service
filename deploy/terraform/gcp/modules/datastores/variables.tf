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
  description = "Prefix for every resource, e.g. notification-prod."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,24}[a-z0-9]$", var.name))
    error_message = "Use 4 to 26 lower-case letters, digits and hyphens, starting with a letter."
  }
}

variable "project_id" {
  description = "Project that holds the environment."
  type        = string
}

variable "region" {
  description = "Primary region. Choose it for data residency: recipients and message content are stored here."
  type        = string
}

variable "disaster_recovery_region" {
  description = "Region that stores the database backups, the logical dumps and a replica of every secret."
  type        = string

  validation {
    condition     = var.disaster_recovery_region != var.region
    error_message = "The recovery region must differ from the primary region."
  }
}

variable "network_id" {
  description = "VPC with private services access set up; the database and the cache get addresses in it."
  type        = string
}

variable "private_services_access_dependency" {
  description = "Pass the service networking connection's ID, so the datastores wait for private services access."
  type        = string
  default     = ""
}

variable "alert_email_addresses" {
  description = "Addresses that receive database and cache alerts."
  type        = list(string)
  default     = []
}

variable "postgres_tier" {
  description = "Cloud SQL machine type."
  type        = string
  default     = "db-custom-4-16384"
}

variable "postgres_disk_size_gb" {
  description = "Initial storage; it grows automatically."
  type        = number
  default     = 100
}

variable "redis_memory_size_gb" {
  description = "Memorystore capacity."
  type        = number
  default     = 5
}

variable "logical_backup_retention_days" {
  description = "Days the logical dumps of deploy/backup/postgres-backup.sh are kept."
  type        = number
  default     = 30
}

variable "labels" {
  description = "Labels added to every resource that supports them."
  type        = map(string)
  default     = {}
}
