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

variable "region" {
  description = "Primary region. Choose it for data residency: recipients and message content are stored here."
  type        = string
}

variable "disaster_recovery_region" {
  description = "Region holding replicated backups and logical dumps; recovery after a region loss starts here."
  type        = string

  validation {
    condition     = var.disaster_recovery_region != var.region
    error_message = "The recovery region must differ from the primary region."
  }
}

variable "environment" {
  description = "Environment name, used in resource names and tags."
  type        = string
  default     = "production"
}

variable "vpc_cidr" {
  description = "VPC address range. Must not overlap networks you peer with."
  type        = string
  default     = "10.40.0.0/16"
}

variable "kubernetes_version" {
  description = "EKS Kubernetes version; the chart needs 1.30 or later."
  type        = string
  default     = "1.33"
}

variable "cluster_endpoint_public_access_cidrs" {
  description = "Networks allowed to reach the Kubernetes API from the internet. Empty keeps the endpoint private."
  type        = list(string)
  default     = []
}

variable "node_instance_types" {
  description = "Instance types for the general node group (services, Pulsar, ingress)."
  type        = list(string)
  default     = ["m7i.xlarge"]
}

variable "node_count" {
  description = "Minimum, desired and maximum nodes in the general node group, spread across three zones."
  type = object({
    minimum = number
    desired = number
    maximum = number
  })
  default = { minimum = 3, desired = 6, maximum = 12 }

  validation {
    condition = (
      var.node_count.minimum >= 3
      && var.node_count.minimum <= var.node_count.desired
      && var.node_count.desired <= var.node_count.maximum
    )
    error_message = "Keep at least 3 nodes (one per zone, for Pulsar's quorum), with minimum <= desired <= maximum."
  }
}

variable "namespace" {
  description = "Kubernetes namespace of the Helm release; the pod identities are bound to it."
  type        = string
  default     = "notification"
}
