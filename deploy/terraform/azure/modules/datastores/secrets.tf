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
# The vault holds every value of the chart's notification-secrets Secret, synced by External Secrets. Terraform
# writes the two it creates (the schema owner's password, write-only; the Redis access key); an operator adds the
# application's own: db-password, secrets-encryption-key, data-encryption-key, admin-two-factor-key, admin-password.

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "secrets" {
  name                = "${var.name}-kv"
  resource_group_name = var.resource_group_name
  location            = var.location
  tenant_id           = data.azurerm_client_config.current.tenant_id
  sku_name            = "standard"

  rbac_authorization_enabled = true
  purge_protection_enabled   = true
  soft_delete_retention_days = 90

  public_network_access_enabled = length(var.operator_ip_ranges) > 0
  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.operator_ip_ranges
  }

  tags = var.tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_role_assignment" "terraform_writes_secrets" {
  scope                = azurerm_key_vault.secrets.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = data.azurerm_client_config.current.object_id
}

resource "azurerm_key_vault_secret" "postgres_owner_password" {
  name             = "migration-db-password"
  key_vault_id     = azurerm_key_vault.secrets.id
  value_wo         = ephemeral.random_password.postgres_owner.result
  value_wo_version = 1
  content_type     = "PostgreSQL schema owner password (ADR-012)"

  depends_on = [azurerm_role_assignment.terraform_writes_secrets]

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_key_vault_secret" "redis_password" {
  name         = "redis-password"
  key_vault_id = azurerm_key_vault.secrets.id
  value        = azurerm_managed_redis.redis.default_database[0].primary_access_key
  content_type = "Azure Managed Redis access key"

  depends_on = [azurerm_role_assignment.terraform_writes_secrets]

  lifecycle {
    prevent_destroy = true
  }
}
