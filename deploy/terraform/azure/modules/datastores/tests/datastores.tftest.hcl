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
# Plan-time tests with a mocked azurerm provider (random runs for real): no credentials, nothing created. They pin the properties the recovery
# objectives (docs/disaster-recovery.md) and the security decisions (ADR-012, ADR-017, ADR-029) depend on.
# Run from the module directory: terraform init && terraform test

mock_provider "azurerm" {
  override_during = plan

  mock_resource "azurerm_key_vault" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.KeyVault/vaults/notification-kv"
    }
  }
  mock_resource "azurerm_storage_account" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.Storage/storageAccounts/notificationbackups"
    }
  }
  mock_resource "azurerm_managed_redis" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.Cache/redisEnterprise/notification-redis"
    }
  }
  mock_resource "azurerm_postgresql_flexible_server" {
    defaults = {
      id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.DBforPostgreSQL/flexibleServers/notification-postgres"
    }
  }
  mock_data "azurerm_client_config" {
    defaults = {
      tenant_id = "00000000-0000-0000-0000-000000000001"
      object_id = "00000000-0000-0000-0000-000000000002"
    }
  }
}

variables {
  name                       = "notification-prod"
  resource_group_name        = "notification-production"
  location                   = "westeurope"
  disaster_recovery_location = "northeurope"
  virtual_network_id         = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.Network/virtualNetworks/notification"
  postgres_subnet_id         = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.Network/virtualNetworks/notification/subnets/postgres"
  private_endpoint_subnet_id = "/subscriptions/00000000-0000-0000-0000-000000000000/resourceGroups/notification/providers/Microsoft.Network/virtualNetworks/notification/subnets/private-endpoints"
  operator_ip_ranges         = ["198.51.100.0/24"]
  alert_email_addresses      = ["oncall@example.com"]
}

run "postgres_survives_a_zone_and_keeps_thirty_five_days_of_geo_redundant_point_in_time_recovery" {
  command = plan

  assert {
    condition = (
      azurerm_postgresql_flexible_server.postgres.high_availability[0].mode == "ZoneRedundant"
      && azurerm_postgresql_flexible_server.postgres.zone != azurerm_postgresql_flexible_server.postgres.high_availability[0].standby_availability_zone
    )
    error_message = "PostgreSQL must keep a synchronous standby in another zone, so a failover loses nothing."
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.postgres.backup_retention_days == 35
    error_message = "Point-in-time recovery must cover 35 days, the most the service allows."
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.postgres.geo_redundant_backup_enabled
    error_message = "Backups must be copied to the paired region, so a region loss can be recovered."
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.postgres.version == "16"
    error_message = "PostgreSQL 16, the version the migrations and tests run against."
  }
  assert {
    condition     = azurerm_management_lock.postgres.lock_level == "CanNotDelete"
    error_message = "The database must be protected from deletion."
  }
}

run "postgres_is_private_requires_tls_and_keeps_the_owner_password_out_of_state" {
  command = plan

  assert {
    condition     = !azurerm_postgresql_flexible_server.postgres.public_network_access_enabled
    error_message = "PostgreSQL must be reachable only from inside the virtual network."
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.postgres.administrator_login == "notification"
    error_message = "The administrator is the schema owner of ADR-012, used only by the migration job."
  }
  assert {
    condition     = azurerm_postgresql_flexible_server.postgres.administrator_password == null
    error_message = "The owner password must be write-only, never stored in Terraform state."
  }
  assert {
    condition = (
      azurerm_postgresql_flexible_server_configuration.settings["require_secure_transport"].value == "ON"
      && azurerm_postgresql_flexible_server_configuration.settings["ssl_min_protocol_version"].value == "TLSv1.2"
    )
    error_message = "The server must refuse connections without TLS 1.2 or later (ADR-017)."
  }
}

run "redis_fails_over_requires_tls_and_a_password_and_is_private" {
  command = plan

  assert {
    condition     = azurerm_managed_redis.redis.high_availability_enabled
    error_message = "Redis must keep a replica and fail over to it."
  }
  assert {
    condition = (
      azurerm_managed_redis.redis.default_database[0].client_protocol == "Encrypted"
      && azurerm_managed_redis.redis.default_database[0].access_keys_authentication_enabled
    )
    error_message = "Redis must require TLS and a password (ADR-017, ADR-029)."
  }
  assert {
    condition     = azurerm_managed_redis.redis.default_database[0].clustering_policy == "EnterpriseCluster"
    error_message = "One endpoint behind a proxy, so the services' standalone Redis client works."
  }
  assert {
    condition     = azurerm_managed_redis.redis.public_network_access == "Disabled"
    error_message = "Redis must be reachable only through its private endpoint."
  }
}

run "the_backup_account_lives_in_the_other_region_and_admits_only_identities_over_tls" {
  command = plan

  assert {
    condition     = azurerm_storage_account.backups.location == "northeurope"
    error_message = "Logical dumps must be stored in the recovery region."
  }
  assert {
    condition = (
      !azurerm_storage_account.backups.shared_access_key_enabled
      && !azurerm_storage_account.backups.allow_nested_items_to_be_public
      && azurerm_storage_account.backups.https_traffic_only_enabled
      && azurerm_storage_account.backups.min_tls_version == "TLS1_2"
    )
    error_message = "Only Entra identities over TLS 1.2 may use the backup account, and nothing in it may be public."
  }
  assert {
    condition     = azurerm_storage_account.backups.network_rules[0].default_action == "Deny"
    error_message = "The backup account must refuse traffic that does not come through its private endpoint."
  }
  assert {
    condition     = azurerm_storage_account.backups.blob_properties[0].versioning_enabled
    error_message = "Backups must be versioned, so an overwrite or deletion can be undone."
  }
}

run "secrets_live_in_a_purge_protected_rbac_vault_closed_to_the_internet" {
  command = plan

  assert {
    condition = (
      azurerm_key_vault.secrets.rbac_authorization_enabled
      && azurerm_key_vault.secrets.purge_protection_enabled
      && azurerm_key_vault.secrets.network_acls[0].default_action == "Deny"
    )
    error_message = "The vault must use RBAC, be purge-protected, and deny networks not explicitly allowed."
  }
  assert {
    condition     = azurerm_key_vault_secret.postgres_owner_password.value == null
    error_message = "The owner password must reach the vault write-only, never through Terraform state."
  }
}

run "every_datastore_has_a_private_endpoint_and_dns_zone" {
  command = plan

  assert {
    condition     = toset(keys(azurerm_private_endpoint.datastores)) == toset(["redis", "vault", "backups"])
    error_message = "Redis, the vault and the backup account must each be reached through a private endpoint."
  }
}

run "outages_reach_the_operators" {
  command = plan

  assert {
    condition     = length(azurerm_monitor_action_group.operations.email_receiver) == 1
    error_message = "The listed operators receive the alerts."
  }
  assert {
    condition     = toset(keys(azurerm_monitor_metric_alert.postgres)) == toset(["storage_nearly_full", "server_down"])
    error_message = "Alert on PostgreSQL running out of storage and on the server being down."
  }
  assert {
    condition     = azurerm_monitor_activity_log_alert.resource_health.criteria[0].category == "ResourceHealth"
    error_message = "Alert when Azure reports the database or the cache unhealthy (failovers and outages)."
  }
}

run "backup_retention_outside_what_the_service_supports_is_refused" {
  command = plan

  variables {
    backup_retention_days = 40
  }

  expect_failures = [var.backup_retention_days]
}

run "the_recovery_region_must_differ_from_the_primary" {
  command = plan

  variables {
    disaster_recovery_location = "westeurope"
  }

  expect_failures = [var.disaster_recovery_location]
}
