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
# Workload identities, each federated with one Kubernetes service account in the release namespace and allowed only
# what that workload does. The services themselves get no Azure identity: they read their secrets from the Kubernetes
# Secret that External Secrets writes.

locals {
  workload_identities = {
    external-secrets = {
      service_account = "notification-external-secrets"
      role            = "Key Vault Secrets User"
      scope           = module.datastores.key_vault_id
    }
    logical-backup = {
      service_account = "notification-logical-backup"
      role            = "Storage Blob Data Contributor"
      scope           = module.datastores.logical_dumps_container_id
    }
  }
}

resource "azurerm_user_assigned_identity" "workloads" {
  for_each = local.workload_identities

  name                = "${local.name}-${each.key}"
  resource_group_name = azurerm_resource_group.environment.name
  location            = var.location
  tags                = local.tags
}

resource "azurerm_federated_identity_credential" "workloads" {
  for_each = local.workload_identities

  name                      = "${local.name}-${each.key}"
  user_assigned_identity_id = azurerm_user_assigned_identity.workloads[each.key].id
  audience                  = ["api://AzureADTokenExchange"]
  issuer                    = azurerm_kubernetes_cluster.cluster.oidc_issuer_url
  subject                   = "system:serviceaccount:${var.namespace}:${each.value.service_account}"
}

resource "azurerm_role_assignment" "workloads" {
  for_each = local.workload_identities

  scope                = each.value.scope
  role_definition_name = each.value.role
  principal_id         = azurerm_user_assigned_identity.workloads[each.key].principal_id
}
