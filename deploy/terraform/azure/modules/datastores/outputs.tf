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

output "postgres_fqdn" {
  description = "Host name of PostgreSQL; it follows the primary across a zone-redundant failover."
  value       = azurerm_postgresql_flexible_server.postgres.fqdn
}

output "postgres_jdbc_url" {
  description = "config.database.url for the Helm chart; mount the CA bundle at the sslrootcert path."
  value = format(
    "jdbc:postgresql://%s:5432/notification?sslmode=verify-full&sslrootcert=/etc/db-ca/ca-bundle.pem",
    azurerm_postgresql_flexible_server.postgres.fqdn,
  )
}

output "redis_host" {
  description = "config.redis.host for the Helm chart (port 10000, TLS, password from the vault)."
  value       = azurerm_managed_redis.redis.hostname
}

output "key_vault_id" {
  description = "Vault holding the chart's secret values."
  value       = azurerm_key_vault.secrets.id
}

output "key_vault_uri" {
  description = "Vault URI for the External Secrets SecretStore."
  value       = azurerm_key_vault.secrets.vault_uri
}

output "backup_storage_account_name" {
  description = "Storage account in the recovery region for logical dumps."
  value       = azurerm_storage_account.backups.name
}

output "logical_dumps_container_id" {
  description = "Resource Manager ID of the dump container, the scope of the backup job's role."
  value       = azurerm_storage_container.logical_dumps.id
}

output "operations_action_group_id" {
  description = "Action group receiving the datastore alerts; add more receivers to it as needed."
  value       = azurerm_monitor_action_group.operations.id
}
