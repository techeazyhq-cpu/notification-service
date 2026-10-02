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
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}[a-z0-9]$", var.name))
    error_message = "Use 4 to 21 lower-case letters, digits and hyphens (the vault name adds -kv and is limited to 24)."
  }
}

variable "resource_group_name" {
  description = "Resource group that holds the datastores."
  type        = string
}

variable "location" {
  description = "Primary region. Choose it for data residency: recipients and message content are stored here."
  type        = string
}

variable "disaster_recovery_location" {
  description = <<-EOT
    Region for the logical dumps. Use the primary region's paired region: geo-redundant PostgreSQL backups always go
    there, so both copies then survive the same regional loss.
  EOT
  type        = string

  validation {
    condition     = var.disaster_recovery_location != var.location
    error_message = "The recovery region must differ from the primary region."
  }
}

variable "virtual_network_id" {
  description = "Virtual network the private DNS zones are linked to."
  type        = string
}

variable "postgres_subnet_id" {
  description = "Subnet delegated to Microsoft.DBforPostgreSQL/flexibleServers."
  type        = string
}

variable "private_endpoint_subnet_id" {
  description = "Subnet for the private endpoints of Redis, the vault and the backup account."
  type        = string
}

variable "operator_ip_ranges" {
  description = "Public ranges allowed to reach the vault's data plane, i.e. where Terraform and operators run."
  type        = list(string)
  default     = []
}

variable "alert_email_addresses" {
  description = "Addresses that receive database and cache alerts."
  type        = list(string)
  default     = []
}

variable "postgres_sku_name" {
  description = "Flexible Server SKU (General Purpose is the smallest tier with zone-redundant high availability)."
  type        = string
  default     = "GP_Standard_D4ds_v5"

  validation {
    condition     = !startswith(var.postgres_sku_name, "B_")
    error_message = "Burstable SKUs do not support high availability."
  }
}

variable "postgres_storage_mb" {
  description = "Initial storage; it grows automatically."
  type        = number
  default     = 131072
}

variable "postgres_primary_zone" {
  description = "Availability zone of the primary; the standby goes to postgres_standby_zone."
  type        = string
  default     = "1"
}

variable "postgres_standby_zone" {
  description = "Availability zone of the synchronous standby."
  type        = string
  default     = "2"
}

variable "backup_retention_days" {
  description = "Days of point-in-time recovery."
  type        = number
  default     = 35

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "Flexible Server keeps backups for 7 to 35 days."
  }
}

variable "redis_sku_name" {
  description = "Azure Managed Redis SKU."
  type        = string
  default     = "Balanced_B5"
}

variable "logical_backup_retention_days" {
  description = "Days the logical dumps of deploy/backup/postgres-backup.sh are kept."
  type        = number
  default     = 30
}

variable "tags" {
  description = "Tags added to every resource."
  type        = map(string)
  default     = {}
}
