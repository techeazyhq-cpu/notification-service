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
# One environment of the notification service on Azure (ADR-029): a virtual network with a NAT gateway, AKS for the
# services and Pulsar, and the datastores module for PostgreSQL Flexible Server, Managed Redis and the backups.

locals {
  name = "notification-${var.environment}"

  subnets = {
    nodes             = cidrsubnet(var.address_space, 4, 0)
    postgres          = cidrsubnet(var.address_space, 8, 64)
    private_endpoints = cidrsubnet(var.address_space, 8, 80)
  }

  tags = {
    application = "notification-service"
    environment = var.environment
    managed-by  = "terraform"
  }
}

resource "azurerm_resource_group" "environment" {
  name     = local.name
  location = var.location
  tags     = local.tags
}

resource "azurerm_virtual_network" "environment" {
  name                = local.name
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  address_space       = [var.address_space]
  tags                = local.tags
}

resource "azurerm_subnet" "nodes" {
  name                 = "nodes"
  resource_group_name  = azurerm_resource_group.environment.name
  virtual_network_name = azurerm_virtual_network.environment.name
  address_prefixes     = [local.subnets.nodes]
}

resource "azurerm_subnet" "postgres" {
  name                 = "postgres"
  resource_group_name  = azurerm_resource_group.environment.name
  virtual_network_name = azurerm_virtual_network.environment.name
  address_prefixes     = [local.subnets.postgres]

  delegation {
    name = "postgres-flexible-server"

    service_delegation {
      name    = "Microsoft.DBforPostgreSQL/flexibleServers"
      actions = ["Microsoft.Network/virtualNetworks/subnets/join/action"]
    }
  }
}

resource "azurerm_subnet" "private_endpoints" {
  name                 = "private-endpoints"
  resource_group_name  = azurerm_resource_group.environment.name
  virtual_network_name = azurerm_virtual_network.environment.name
  address_prefixes     = [local.subnets.private_endpoints]
}

resource "azurerm_public_ip" "egress" {
  name                = "${local.name}-egress"
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  allocation_method   = "Static"
  sku                 = "Standard"
  zones               = ["1", "2", "3"]
  tags                = local.tags
}

resource "azurerm_nat_gateway" "egress" {
  name                = "${local.name}-egress"
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  sku_name            = "Standard"
  tags                = local.tags
}

resource "azurerm_nat_gateway_public_ip_association" "egress" {
  nat_gateway_id       = azurerm_nat_gateway.egress.id
  public_ip_address_id = azurerm_public_ip.egress.id
}

resource "azurerm_subnet_nat_gateway_association" "nodes" {
  subnet_id      = azurerm_subnet.nodes.id
  nat_gateway_id = azurerm_nat_gateway.egress.id
}

resource "azurerm_log_analytics_workspace" "cluster" {
  name                = local.name
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  sku                 = "PerGB2018"
  retention_in_days   = 90
  tags                = local.tags
}

resource "azurerm_user_assigned_identity" "cluster" {
  name                = "${local.name}-aks"
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  tags                = local.tags
}

resource "azurerm_role_assignment" "cluster_manages_its_network" {
  scope                = azurerm_virtual_network.environment.id
  role_definition_name = "Network Contributor"
  principal_id         = azurerm_user_assigned_identity.cluster.principal_id
}

resource "azurerm_kubernetes_cluster" "cluster" {
  name                = local.name
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  dns_prefix          = local.name
  kubernetes_version  = var.kubernetes_version
  sku_tier            = "Standard"

  private_cluster_enabled = length(var.api_server_authorized_ip_ranges) == 0
  dynamic "api_server_access_profile" {
    for_each = length(var.api_server_authorized_ip_ranges) > 0 ? [1] : []

    content {
      authorized_ip_ranges = var.api_server_authorized_ip_ranges
    }
  }

  local_account_disabled            = true
  role_based_access_control_enabled = true
  azure_active_directory_role_based_access_control {
    azure_rbac_enabled     = true
    admin_group_object_ids = var.cluster_admin_group_object_ids
  }

  oidc_issuer_enabled       = true
  workload_identity_enabled = true
  azure_policy_enabled      = true
  automatic_upgrade_channel = "patch"
  node_os_upgrade_channel   = "NodeImage"

  identity {
    type         = "UserAssigned"
    identity_ids = [azurerm_user_assigned_identity.cluster.id]
  }

  node_provisioning_profile {
    mode = "Manual"
  }

  default_node_pool {
    name                        = "general"
    vm_size                     = var.node_vm_size
    vnet_subnet_id              = azurerm_subnet.nodes.id
    zones                       = ["1", "2", "3"]
    auto_scaling_enabled        = true
    min_count                   = var.node_count.minimum
    max_count                   = var.node_count.maximum
    os_sku                      = "AzureLinux"
    temporary_name_for_rotation = "rotation"

    upgrade_settings {
      max_surge = "33%"
    }
  }

  network_profile {
    network_plugin      = "azure"
    network_plugin_mode = "overlay"
    network_data_plane  = "cilium"
    network_policy      = "cilium"
    outbound_type       = "userAssignedNATGateway"
    service_cidr        = "172.20.0.0/16"
    dns_service_ip      = "172.20.0.10"
  }

  oms_agent {
    log_analytics_workspace_id      = azurerm_log_analytics_workspace.cluster.id
    msi_auth_for_monitoring_enabled = true
  }

  tags = local.tags

  depends_on = [
    azurerm_role_assignment.cluster_manages_its_network,
    azurerm_subnet_nat_gateway_association.nodes,
  ]
}

module "datastores" {
  source = "../modules/datastores"

  name                       = local.name
  resource_group_name        = azurerm_resource_group.environment.name
  location                   = var.location
  disaster_recovery_location = var.disaster_recovery_location
  virtual_network_id         = azurerm_virtual_network.environment.id
  postgres_subnet_id         = azurerm_subnet.postgres.id
  private_endpoint_subnet_id = azurerm_subnet.private_endpoints.id
  operator_ip_ranges         = var.operator_ip_ranges
  alert_email_addresses      = var.alert_email_addresses
  tags                       = local.tags
}
