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

variable "project_id" {
  description = "Project the environment is built in."
  type        = string
}

variable "region" {
  description = "Primary region. Choose it for data residency: recipients and message content are stored here."
  type        = string
}

variable "disaster_recovery_region" {
  description = "Region for the database backups, the logical dumps and the secret replicas."
  type        = string
}

variable "environment" {
  description = "Short environment name, used in resource names (service account IDs limit it to 10 characters)."
  type        = string
  default     = "prod"

  validation {
    condition     = can(regex("^[a-z][a-z0-9]{1,9}$", var.environment))
    error_message = "Use 2 to 10 lower-case letters and digits, starting with a letter."
  }
}

variable "subnet_cidr" {
  description = "Node subnet; pods and services use the ranges below. None may overlap networks you peer with."
  type        = string
  default     = "10.60.0.0/20"
}

variable "pods_cidr" {
  description = "Secondary range for pods (VPC-native)."
  type        = string
  default     = "10.64.0.0/14"
}

variable "services_cidr" {
  description = "Secondary range for services."
  type        = string
  default     = "10.68.0.0/20"
}

variable "control_plane_cidr" {
  description = "Range for the private control plane endpoint."
  type        = string
  default     = "172.16.0.0/28"
}

variable "control_plane_authorized_cidrs" {
  description = "Public ranges allowed to reach the Kubernetes API. Empty keeps the endpoint private."
  type        = list(string)
  default     = []
}

variable "alert_email_addresses" {
  description = "Addresses that receive database and cache alerts."
  type        = list(string)
  default     = []
}

variable "node_machine_type" {
  description = "Machine type of the node pool (services, Pulsar, ingress)."
  type        = string
  default     = "n2-standard-4"
}

variable "node_count" {
  description = "Minimum and maximum nodes in the regional pool, across three zones."
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
