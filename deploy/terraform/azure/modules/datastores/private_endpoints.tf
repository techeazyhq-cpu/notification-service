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
# Redis, the vault and the backup account are reached only through private endpoints in the virtual network, each
# with the private DNS zone that makes its usual host name resolve to the endpoint. PostgreSQL is injected into its
# own delegated subnet instead (postgres.tf).

locals {
  private_endpoints = {
    redis = {
      resource_id = azurerm_managed_redis.redis.id
      subresource = "redisEnterprise"
      dns_zone    = "privatelink.redis.azure.net"
    }
    vault = {
      resource_id = azurerm_key_vault.secrets.id
      subresource = "vault"
      dns_zone    = "privatelink.vaultcore.azure.net"
    }
    backups = {
      resource_id = azurerm_storage_account.backups.id
      subresource = "blob"
      dns_zone    = "privatelink.blob.core.windows.net"
    }
  }
}

resource "azurerm_private_dns_zone" "endpoints" {
  for_each = local.private_endpoints

  name                = each.value.dns_zone
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "endpoints" {
  for_each = local.private_endpoints

  name                = "${var.name}-${each.key}"
  private_dns_zone_id = azurerm_private_dns_zone.endpoints[each.key].id
  virtual_network_id  = var.virtual_network_id
  tags                = var.tags
}

resource "azurerm_private_endpoint" "datastores" {
  for_each = local.private_endpoints

  name                = "${var.name}-${each.key}"
  resource_group_name = var.resource_group_name
  location            = var.location
  subnet_id           = var.private_endpoint_subnet_id

  private_service_connection {
    name                           = "${var.name}-${each.key}"
    private_connection_resource_id = each.value.resource_id
    subresource_names              = [each.value.subresource]
    is_manual_connection           = false
  }

  private_dns_zone_group {
    name                 = each.key
    private_dns_zone_ids = [azurerm_private_dns_zone.endpoints[each.key].id]
  }

  tags = var.tags
}
