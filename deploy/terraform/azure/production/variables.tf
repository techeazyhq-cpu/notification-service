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

variable "subscription_id" {
  description = "Subscription the environment is built in."
  type        = string
}

variable "location" {
  description = "Primary region, with availability zones. Choose it for data residency."
  type        = string
}

variable "disaster_recovery_location" {
  description = "The primary region's paired region: geo-redundant database backups and the logical dumps go there."
  type        = string
}

variable "environment" {
  description = "Short environment name, used in resource names (the vault name limits the total length)."
  type        = string
  default     = "prod"
}

variable "address_space" {
  description = "Virtual network range. Must not overlap networks you peer with."
  type        = string
  default     = "10.50.0.0/16"
}

variable "kubernetes_version" {
  description = "AKS Kubernetes version; the chart needs 1.30 or later."
  type        = string
  default     = "1.33"
}

variable "api_server_authorized_ip_ranges" {
  description = "Public ranges allowed to reach the Kubernetes API. Empty makes the cluster private."
  type        = list(string)
  default     = []
}

variable "cluster_admin_group_object_ids" {
  description = "Entra ID groups that administer the cluster (local accounts are disabled)."
  type        = list(string)
}

variable "operator_ip_ranges" {
  description = "Public ranges allowed to reach the vault's data plane: where Terraform and operators run."
  type        = list(string)
}

variable "alert_email_addresses" {
  description = "Addresses that receive database and cache alerts."
  type        = list(string)
  default     = []
}

variable "node_vm_size" {
  description = "VM size of the node pool (services, Pulsar, ingress)."
  type        = string
  default     = "Standard_D4ds_v5"
}

variable "node_count" {
  description = "Minimum and maximum nodes, spread across three zones."
  type = object({
    minimum = number
    maximum = number
  })
  default = { minimum = 3, maximum = 12 }

  validation {
    condition     = var.node_count.minimum >= 3 && var.node_count.minimum <= var.node_count.maximum
    error_message = "Keep at least 3 nodes (one per zone, for Pulsar's quorum), with minimum <= maximum."
  }
}

variable "namespace" {
  description = "Kubernetes namespace of the Helm release; the workload identities are bound to it."
  type        = string
  default     = "notification"
}
