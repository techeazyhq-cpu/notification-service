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
# Logical dumps (deploy/backup/postgres-backup.sh) go to an account in the recovery region, an independent copy next to
# the geo-redundant server backups. Only Entra identities may use it (no account keys), through its private endpoint.

resource "random_string" "backup_account_suffix" {
  length  = 6
  special = false
  upper   = false
}

resource "azurerm_storage_account" "backups" {
  name                = substr("${replace(var.name, "-", "")}bk${random_string.backup_account_suffix.result}", 0, 24)
  resource_group_name = var.resource_group_name
  location            = var.disaster_recovery_location

  account_kind             = "StorageV2"
  account_tier             = "Standard"
  account_replication_type = "ZRS"

  https_traffic_only_enabled        = true
  min_tls_version                   = "TLS1_2"
  shared_access_key_enabled         = false
  default_to_oauth_authentication   = true
  allow_nested_items_to_be_public   = false
  public_network_access             = "Disabled"
  local_user_enabled                = false
  infrastructure_encryption_enabled = true

  identity {
    type = "SystemAssigned"
  }

  network_rules {
    default_action = "Deny"
    bypass         = ["AzureServices"]
  }

  blob_properties {
    versioning_enabled = true

    delete_retention_policy {
      days = 7
    }

    container_delete_retention_policy {
      days = 7
    }
  }

  tags = var.tags

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_storage_container" "logical_dumps" {
  name                  = "logical-dumps"
  storage_account_id    = azurerm_storage_account.backups.id
  container_access_type = "private"

  lifecycle {
    prevent_destroy = true
  }
}

resource "azurerm_storage_management_policy" "backups" {
  storage_account_id = azurerm_storage_account.backups.id

  rule {
    name    = "expire-logical-dumps"
    enabled = true

    filters {
      blob_types   = ["blockBlob"]
      prefix_match = ["${azurerm_storage_container.logical_dumps.name}/"]
    }

    actions {
      base_blob {
        delete_after_days_since_creation_greater_than = var.logical_backup_retention_days
      }

      version {
        delete_after_days_since_creation = 7
      }
    }
  }
}
