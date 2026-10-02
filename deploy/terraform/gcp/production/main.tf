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
# One environment of the notification service on Google Cloud (ADR-030): a VPC with Cloud NAT and private services
# access, a regional GKE cluster for the services and Pulsar, and the datastores module for Cloud SQL, Memorystore and
# the backups.

locals {
  name = "notification-${var.environment}"

  service_account_prefix = "notif-${var.environment}"

  labels = {
    application = "notification-service"
    environment = var.environment
    managed-by  = "terraform"
  }

  required_services = [
    "compute.googleapis.com", "container.googleapis.com", "sqladmin.googleapis.com", "redis.googleapis.com",
    "secretmanager.googleapis.com", "servicenetworking.googleapis.com", "monitoring.googleapis.com",
    "logging.googleapis.com",
  ]
}

resource "google_project_service" "required" {
  for_each = toset(local.required_services)

  service            = each.value
  disable_on_destroy = false
}

resource "google_compute_network" "environment" {
  name                    = local.name
  auto_create_subnetworks = false
  routing_mode            = "REGIONAL"

  depends_on = [google_project_service.required]
}

resource "google_compute_subnetwork" "nodes" {
  name                     = "${local.name}-nodes"
  network                  = google_compute_network.environment.id
  region                   = var.region
  ip_cidr_range            = var.subnet_cidr
  private_ip_google_access = true

  secondary_ip_range {
    range_name    = "pods"
    ip_cidr_range = var.pods_cidr
  }

  secondary_ip_range {
    range_name    = "services"
    ip_cidr_range = var.services_cidr
  }

  log_config {
    aggregation_interval = "INTERVAL_5_SEC"
    flow_sampling        = 0.5
    metadata             = "INCLUDE_ALL_METADATA"
  }
}

resource "google_compute_router" "egress" {
  name    = "${local.name}-egress"
  network = google_compute_network.environment.id
  region  = var.region
}

resource "google_compute_router_nat" "egress" {
  name                               = "${local.name}-egress"
  router                             = google_compute_router.egress.name
  region                             = var.region
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"

  log_config {
    enable = true
    filter = "ERRORS_ONLY"
  }
}

resource "google_compute_global_address" "private_services" {
  name          = "${local.name}-private-services"
  network       = google_compute_network.environment.id
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 20
}

resource "google_service_networking_connection" "private_services" {
  network                 = google_compute_network.environment.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.private_services.name]
}

resource "google_service_account" "nodes" {
  account_id   = "${local.service_account_prefix}-nodes"
  display_name = "GKE nodes of ${local.name}: logs and metrics only"
}

resource "google_project_iam_member" "nodes" {
  for_each = toset([
    "roles/logging.logWriter", "roles/monitoring.metricWriter", "roles/monitoring.viewer",
    "roles/stackdriver.resourceMetadata.writer",
  ])

  project = var.project_id
  role    = each.value
  member  = "serviceAccount:${google_service_account.nodes.email}"
}

resource "google_container_cluster" "cluster" {
  name                = local.name
  location            = var.region
  network             = google_compute_network.environment.id
  subnetwork          = google_compute_subnetwork.nodes.id
  deletion_protection = true

  remove_default_node_pool = true
  initial_node_count       = 1

  release_channel {
    channel = "REGULAR"
  }

  networking_mode   = "VPC_NATIVE"
  datapath_provider = "ADVANCED_DATAPATH"
  ip_allocation_policy {
    cluster_secondary_range_name  = "pods"
    services_secondary_range_name = "services"
  }

  private_cluster_config {
    enable_private_nodes    = true
    enable_private_endpoint = length(var.control_plane_authorized_cidrs) == 0
    master_ipv4_cidr_block  = var.control_plane_cidr
  }

  master_authorized_networks_config {
    gcp_public_cidrs_access_enabled = false

    dynamic "cidr_blocks" {
      for_each = var.control_plane_authorized_cidrs

      content {
        cidr_block   = cidr_blocks.value
        display_name = "operators"
      }
    }
  }

  workload_identity_config {
    workload_pool = "${var.project_id}.svc.id.goog"
  }

  enable_shielded_nodes = true
  enable_legacy_abac    = false

  master_auth {
    client_certificate_config {
      issue_client_certificate = false
    }
  }

  binary_authorization {
    evaluation_mode = "PROJECT_SINGLETON_POLICY_ENFORCE"
  }

  security_posture_config {
    mode               = "BASIC"
    vulnerability_mode = "VULNERABILITY_BASIC"
  }

  node_config {
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }

  resource_labels = local.labels

  depends_on = [google_project_service.required]
}

resource "google_container_node_pool" "general" {
  name     = "general"
  cluster  = google_container_cluster.cluster.id
  location = var.region

  autoscaling {
    total_min_node_count = var.node_count.minimum
    total_max_node_count = var.node_count.maximum
  }

  management {
    auto_repair  = true
    auto_upgrade = true
  }

  upgrade_settings {
    max_surge       = 1
    max_unavailable = 0
  }

  node_config {
    machine_type    = var.node_machine_type
    disk_type       = "pd-balanced"
    disk_size_gb    = 100
    image_type      = "COS_CONTAINERD"
    service_account = google_service_account.nodes.email
    oauth_scopes    = ["https://www.googleapis.com/auth/cloud-platform"]
    metadata        = { disable-legacy-endpoints = "true" }
    resource_labels = local.labels

    workload_metadata_config {
      mode = "GKE_METADATA"
    }

    shielded_instance_config {
      enable_secure_boot          = true
      enable_integrity_monitoring = true
    }
  }
}

module "datastores" {
  source = "../modules/datastores"

  name                               = local.name
  project_id                         = var.project_id
  region                             = var.region
  disaster_recovery_region           = var.disaster_recovery_region
  network_id                         = google_compute_network.environment.id
  private_services_access_dependency = google_service_networking_connection.private_services.id
  alert_email_addresses              = var.alert_email_addresses
  labels                             = local.labels
}
