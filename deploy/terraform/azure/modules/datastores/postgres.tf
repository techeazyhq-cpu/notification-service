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

ephemeral "random_password" "postgres_owner" {
  length  = 40
  special = false
}

resource "azurerm_private_dns_zone" "postgres" {
  name                = "${var.name}.private.postgres.database.azure.com"
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

resource "azurerm_private_dns_zone_virtual_network_link" "postgres" {
  name                = "${var.name}-postgres"
  private_dns_zone_id = azurerm_private_dns_zone.postgres.id
  virtual_network_id  = var.virtual_network_id
  tags                = var.tags
}

resource "azurerm_postgresql_flexible_server" "postgres" {
  name                = "${var.name}-postgres"
  resource_group_name = var.resource_group_name
  location            = var.location
  version             = "16"
  sku_name            = var.postgres_sku_name

  administrator_login               = "notification"
  administrator_password_wo         = ephemeral.random_password.postgres_owner.result
  administrator_password_wo_version = 1

  authentication {
    password_auth_enabled         = true
    active_directory_auth_enabled = false
  }

  delegated_subnet_id           = var.postgres_subnet_id
  private_dns_zone_id           = azurerm_private_dns_zone.postgres.id
  public_network_access_enabled = false

  zone = var.postgres_primary_zone
  high_availability {
    mode                      = "ZoneRedundant"
    standby_availability_zone = var.postgres_standby_zone
  }

  storage_mb        = var.postgres_storage_mb
  auto_grow_enabled = true

  backup_retention_days        = var.backup_retention_days
  geo_redundant_backup_enabled = true

  maintenance_window {
    day_of_week  = 0
    start_hour   = 3
    start_minute = 0
  }

  tags = var.tags

  depends_on = [azurerm_private_dns_zone_virtual_network_link.postgres]

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_postgresql_flexible_server_configuration" "settings" {
  for_each = {
    require_secure_transport            = "ON"
    ssl_min_protocol_version            = "TLSv1.2"
    log_min_duration_statement          = "1000"
    idle_in_transaction_session_timeout = "60000"
    log_checkpoints                     = "on"
    log_connections                     = "on"
  }

  name      = each.key
  server_id = azurerm_postgresql_flexible_server.postgres.id
  value     = each.value
}

resource "azurerm_postgresql_flexible_server_database" "notification" {
  name      = "notification"
  server_id = azurerm_postgresql_flexible_server.postgres.id
  charset   = "UTF8"
  collation = "en_US.utf8"

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_management_lock" "postgres" {
  name       = "${var.name}-postgres-cannot-delete"
  scope      = azurerm_postgresql_flexible_server.postgres.id
  lock_level = "CanNotDelete"
  notes      = "The system of record. Remove this lock deliberately before deleting the server."
}
